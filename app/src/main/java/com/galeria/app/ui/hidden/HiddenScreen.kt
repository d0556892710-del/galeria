package com.galeria.app.ui.hidden

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.components.GlassButton
import com.galeria.app.ui.library.LibraryScreen
import com.galeria.app.ui.theme.GlassTokens

enum class HiddenAuthState { LOCKED, UNLOCKED, ERROR }

@Composable
fun HiddenScreen(
    hiddenItems: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    onUnhide: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var authState by remember { mutableStateOf(HiddenAuthState.LOCKED) }
    val context = LocalContext.current

    when (authState) {
        HiddenAuthState.LOCKED -> {
            LockScreen(
                onAuthenticate = {
                    val activity = context as? FragmentActivity ?: run {
                        authState = HiddenAuthState.ERROR; return@LockScreen
                    }
                    val executor = ContextCompat.getMainExecutor(activity)
                    val callback = object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            authState = HiddenAuthState.UNLOCKED
                        }
                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                                code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                authState = HiddenAuthState.ERROR
                            }
                        }
                    }
                    val prompt = BiometricPrompt(activity, executor, callback)
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Hidden Album")
                        .setSubtitle("Authenticate to view hidden photos")
                        .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        .build()
                    prompt.authenticate(info)
                },
                onBack = onBack,
                modifier = modifier
            )
        }

        HiddenAuthState.UNLOCKED -> {
            var selected by remember { mutableStateOf(emptySet<Long>()) }
            Column(modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("← Back", fontSize = 17.sp, color = GlassTokens.AccentBlue,
                        modifier = androidx.compose.ui.Modifier.clickableNoRipple { onBack() })
                    Text(
                        "Hidden",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary
                    )
                    if (selected.isNotEmpty()) {
                        Text("Unhide", fontSize = 17.sp, color = GlassTokens.AccentBlue,
                            modifier = androidx.compose.ui.Modifier.clickableNoRipple { onUnhide(selected) })
                    } else {
                        Spacer(Modifier.width(60.dp))
                    }
                }
                LibraryScreen(
                    items = hiddenItems,
                    selectedIds = selected,
                    totalCount = hiddenItems.size,
                    onItemClick = onItemClick,
                    onItemLongPress = { selected = selected + it.id },
                    onSelectToggle = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    }
                )
            }
        }

        HiddenAuthState.ERROR -> {
            Box(modifier.fillMaxSize().background(GlassTokens.BgPrimary), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Authentication failed", color = GlassTokens.TextSecondary, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    GlassButton(text = "Retry", onClick = { authState = HiddenAuthState.LOCKED })
                }
            }
        }
    }
}

@Composable
private fun LockScreen(
    onAuthenticate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(GlassTokens.GlassStandard),
                contentAlignment = Alignment.Center
            ) {
                Text("🔒", fontSize = 36.sp)
            }
            Text("Hidden Album", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary)
            Text("Authenticate to view", fontSize = 15.sp, color = GlassTokens.TextSecondary)
            GlassButton(text = "View Album", onClick = onAuthenticate, tint = GlassTokens.AccentBlue)
        }
        // Back button top-left
        Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
            .clickableNoRipple { onBack() }
        ) {
            Text("← Back", fontSize = 17.sp, color = GlassTokens.AccentBlue)
        }
    }
}

// Helper extension
@Composable
private fun androidx.compose.ui.Modifier.clickableNoRipple(onClick: () -> Unit): androidx.compose.ui.Modifier {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}
