package com.galeria.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.galeria.app.data.Media
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.Bridge
import com.galeria.app.ui.root.LocalBridge
import com.galeria.app.ui.root.PhotosRoot
import com.galeria.app.ui.root.PickerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : AppCompatActivity(), Bridge {
    private val vm: AppViewModel by viewModels()
    private var senderCallback: ((Boolean) -> Unit)? = null
    private var lastDark: Boolean? = null

    private val senderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val cb = senderCallback
        senderCallback = null
        cb?.invoke(r.resultCode == RESULT_OK)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checkPermission()
        if (vm.permissionGranted) vm.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        checkPermission()
        if (vm.permissionGranted) vm.start()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CompositionLocalProvider(LocalBridge provides this) { PhotosRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val before = vm.permissionGranted
        checkPermission()
        if (vm.permissionGranted) {
            if (!before) vm.start() else vm.refresh()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.lockAll()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun checkPermission() {
        val full: Boolean
        var partial = false
        if (Build.VERSION.SDK_INT >= 33) {
            full = granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) partial = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            full = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        vm.permissionGranted = full || partial
        vm.limitedAccess = !full && partial
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_VIEW, "android.provider.action.REVIEW", "android.provider.action.REVIEW_SECURE", "com.android.camera.action.REVIEW" -> {
                val uri = i.data ?: return
                lifecycleScope.launch {
                    if (vm.permissionGranted) withTimeoutOrNull(2500) { vm.loaded.first { it } }
                    val m = vm.describe(uri, i.type)
                    if (m != null) vm.openExternal(m)
                }
            }
            Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> {
                val type = i.type ?: ""
                vm.picker = PickerState(
                    multiple = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                    videoOnly = type.startsWith("video") || type.endsWith("/video"),
                    imageOnly = type.startsWith("image") || type.endsWith("/image"),
                )
                vm.selecting = true
            }
        }
    }

    // ------------------------------- Bridge -------------------------------

    override fun launch(sender: IntentSender?, onResult: (Boolean) -> Unit) {
        if (sender == null) {
            onResult(false); return
        }
        senderCallback = onResult
        runCatching { senderLauncher.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure {
            senderCallback = null; onResult(false)
        }
    }

    private fun shareUri(m: Media): Uri =
        if (m.hiddenFile != null) FileProvider.getUriForFile(this, "$packageName.files", File(vm.repo.hidden.dir, m.hiddenFile)) else m.uri

    override fun share(items: List<Media>) {
        if (items.isEmpty()) return
        val uris = ArrayList(items.map { shareUri(it) })
        val type = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(contentResolver, "media", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    override fun authenticate(title: String, subtitle: String, onOk: () -> Unit) {
        val auth = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No fingerprint and no screen lock on this phone: nothing to unlock with.
            onOk(); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        })
        runCatching {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(auth)
                    .build()
            )
        }
    }

    override fun setWallpaper(m: Media) {
        val uri = shareUri(m)
        val i = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, m.mime).putExtra("mimeType", m.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, null)) }
    }

    override fun copy(m: Media) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newUri(contentResolver, m.displayName.ifBlank { "Photo" }, shareUri(m)))
    }

    override fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Photos", text))
    }

    override fun shareText(text: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
    }

    override fun openMaps(lat: Double, lon: Double) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }
    }

    override fun requestPermissions() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        permLauncher.launch(perms)
    }

    override fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    override fun requestManageMedia() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                openAppSettings()
            }
        } else openAppSettings()
    }

    override fun openDefaultApps() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    override fun setLanguage(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isNullOrBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    override fun currentLanguage(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    override fun finishPick(items: List<Media>) {
        if (items.isEmpty()) return cancelPick()
        val data = Intent()
        data.data = items[0].uri
        val clip = ClipData.newUri(contentResolver, "media", items[0].uri)
        items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        data.clipData = clip
        data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun cancelPick() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun canManageMedia(): Boolean = vm.repo.canManageMedia()

    override fun setSystemBars(darkContent: Boolean) {
        if (lastDark == darkContent) return
        lastDark = darkContent
        val style = if (darkContent) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(if (darkContent) Color.BLACK else Color.WHITE))
    }

    override fun hasLocationAccess(): Boolean = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
}
