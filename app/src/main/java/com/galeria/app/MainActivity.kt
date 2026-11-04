package com.galeria.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.MainViewModel
import com.galeria.app.ui.albums.AlbumsScreen
import com.galeria.app.ui.components.GaleriaBottomBar
import com.galeria.app.ui.deleted.RecentlyDeletedScreen
import com.galeria.app.ui.details.PhotoDetailsScreen
import com.galeria.app.ui.explore.ExploreScreen
import com.galeria.app.ui.hidden.HiddenScreen
import com.galeria.app.ui.library.LibraryScreen
import com.galeria.app.ui.search.SearchScreen
import com.galeria.app.ui.settings.SettingsScreen
import com.galeria.app.ui.theme.GaleriaTheme
import com.galeria.app.ui.theme.GlassTokens
import com.galeria.app.ui.viewer.PhotoViewerScreen
import com.galeria.app.ui.viewer.VideoPlayerScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File
import java.util.concurrent.Executor

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GaleriaTheme {
                LumaApp(
                    vm = vm,
                    activity = this
                )
            }
        }
    }
}

sealed class Screen {
    object Photos : Screen()
    object Albums : Screen()
    object Explore : Screen()
    object Settings : Screen()
    object Search : Screen()
    data class PhotoViewer(val index: Int, val list: List<MediaItem> = emptyList()) : Screen()
    data class VideoPlayer(val item: MediaItem) : Screen()
    object Hidden : Screen()
    object RecentlyDeleted : Screen()
    data class PhotoDetails(val item: MediaItem) : Screen()
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LumaApp(vm: MainViewModel, activity: MainActivity) {
    // Runtime permissions
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permState.allPermissionsGranted) {
        if (permState.allPermissionsGranted) vm.load()
        else permState.launchMultiplePermissionRequest()
    }

    // Navigation state
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Photos) }
    var selectedTab   by remember { mutableIntStateOf(0) }

    // Collect state
    val library          by vm.libraryMedia.collectAsStateWithLifecycle()
    val hidden           by vm.hiddenMedia.collectAsStateWithLifecycle()
    val favorites        by vm.favoriteMedia.collectAsStateWithLifecycle()
    val videos           by vm.videosMedia.collectAsStateWithLifecycle()
    val screenshots      by vm.screenshotsMedia.collectAsStateWithLifecycle()
    val downloads        by vm.downloadedMedia.collectAsStateWithLifecycle()
    val whatsapp         by vm.whatsappMedia.collectAsStateWithLifecycle()
    val recentlyAdded    by vm.recentlyAddedMedia.collectAsStateWithLifecycle()
    val deletedItems     by vm.deletedMedia.collectAsStateWithLifecycle()
    val selectedIds      by vm.selectedIds.collectAsStateWithLifecycle()
    val searchQuery      by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults    by vm.searchResults.collectAsStateWithLifecycle()
    val settings         by vm.settings.collectAsStateWithLifecycle()

    val visibleLibrary = remember(library) { vm.visibleLibrary() }

    // Tab screens
    val tabScreens = listOf(Screen.Photos, Screen.Albums, Screen.Explore, Screen.Settings)

    Box(modifier = Modifier.fillMaxSize().background(GlassTokens.BgPrimary)) {

        when (val screen = currentScreen) {

            // ── PHOTOS ───────────────────────────────────────────
            is Screen.Photos -> {
                LibraryScreen(
                    items = visibleLibrary,
                    selectedIds = selectedIds,
                    totalCount = visibleLibrary.size,
                    groupByDate = settings.groupByDate,
                    gridColumns = settings.gridColumns,
                    onItemClick = { item ->
                        val idx = visibleLibrary.indexOf(item)
                        if (item.isVideo) currentScreen = Screen.VideoPlayer(item)
                        else currentScreen = Screen.PhotoViewer(idx, visibleLibrary)
                    },
                    onItemLongPress = { vm.toggleSelect(it.id) },
                    onSelectToggle = { vm.toggleSelect(it) },
                    onSearchClick = { currentScreen = Screen.Search },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── ALBUMS ───────────────────────────────────────────
            is Screen.Albums -> {
                AlbumsScreen(
                    allMedia = visibleLibrary,
                    favorites = favorites,
                    videos = videos,
                    screenshots = screenshots,
                    downloads = downloads,
                    whatsapp = whatsapp,
                    recentlyAdded = recentlyAdded,
                    hiddenCount = hidden.size,
                    showHiddenAlbum = settings.showHiddenAlbum,
                    recentlyDeletedCount = deletedItems.size,
                    onAlbumClick = { key ->
                        when (key) {
                            "hidden" -> showBiometricPrompt(
                                activity = activity,
                                title = "Hidden Album",
                                subtitle = "Authenticate to view hidden photos",
                                onSuccess = { currentScreen = Screen.Hidden }
                            )
                            "deleted" -> {
                                if (settings.deletedProtection) {
                                    showBiometricPrompt(
                                        activity = activity,
                                        title = "Recently Deleted",
                                        subtitle = "Authenticate to view deleted photos",
                                        onSuccess = { currentScreen = Screen.RecentlyDeleted }
                                    )
                                } else {
                                    currentScreen = Screen.RecentlyDeleted
                                }
                            }
                            "all" -> {
                                selectedTab = 0
                                currentScreen = Screen.Photos
                            }
                            "favorites" -> {
                                // Open photos filtered to favorites — open photo viewer at first fav
                                if (favorites.isNotEmpty()) {
                                    currentScreen = Screen.PhotoViewer(0, favorites)
                                }
                            }
                            else -> { /* other albums: show photos filtered by album */ }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── EXPLORE ──────────────────────────────────────────
            is Screen.Explore -> {
                ExploreScreen(
                    allMedia = visibleLibrary,
                    favorites = favorites,
                    videos = videos,
                    recentlyAdded = recentlyAdded,
                    onItemClick = { item ->
                        val idx = visibleLibrary.indexOf(item)
                        if (item.isVideo) currentScreen = Screen.VideoPlayer(item)
                        else currentScreen = Screen.PhotoViewer(idx, visibleLibrary)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── SETTINGS ─────────────────────────────────────────
            is Screen.Settings -> {
                SettingsScreen(
                    settings = settings,
                    onGridColumns    = vm::setGridColumns,
                    onGroupByDate    = vm::setGroupByDate,
                    onAnimations     = vm::setAnimations,
                    onGlassOpacity   = vm::setGlassOpacity,
                    onAppLock        = vm::setAppLock,
                    onHiddenAlbumLock= vm::setHiddenAlbumLock,
                    onAutoLockMins   = vm::setAutoLockMins,
                    onShowHiddenAlbum= vm::setShowHiddenAlbum,
                    onDeletedProtection = vm::setDeletedProtection,
                    onShowFileInfo   = vm::setShowFileInfo,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── SEARCH ───────────────────────────────────────────
            is Screen.Search -> {
                SearchScreen(
                    query = searchQuery,
                    results = searchResults,
                    onQueryChange = vm::setQuery,
                    onItemClick = { item ->
                        val idx = visibleLibrary.indexOf(item)
                        if (item.isVideo) currentScreen = Screen.VideoPlayer(item)
                        else currentScreen = Screen.PhotoViewer(idx, visibleLibrary)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── PHOTO VIEWER ─────────────────────────────────────
            is Screen.PhotoViewer -> {
                val displayList = screen.list.ifEmpty { visibleLibrary }
                PhotoViewerScreen(
                    items = displayList,
                    startIndex = screen.index.coerceIn(0, (displayList.size - 1).coerceAtLeast(0)),
                    onClose = { currentScreen = tabScreens[selectedTab] },
                    onToggleFavorite = vm::toggleFavorite,
                    onShare = { item -> shareMedia(activity, item) },
                    onDelete = { item -> vm.softDelete(item.id) },
                    onInfo = { item -> currentScreen = Screen.PhotoDetails(item) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── VIDEO PLAYER ─────────────────────────────────────
            is Screen.VideoPlayer -> {
                VideoPlayerScreen(
                    videoUri = screen.item.uri,
                    onClose = { currentScreen = tabScreens[selectedTab] },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── HIDDEN ALBUM ─────────────────────────────────────
            is Screen.Hidden -> {
                HiddenScreen(
                    hiddenItems = hidden,
                    onItemClick = { /* open in viewer */ },
                    onUnhide = { ids -> vm.unhideItems(ids) },
                    onBack = { currentScreen = Screen.Albums },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── RECENTLY DELETED ─────────────────────────────────
            is Screen.RecentlyDeleted -> {
                RecentlyDeletedScreen(
                    items = deletedItems,
                    onBack = { currentScreen = Screen.Albums },
                    onRestore = { ids -> vm.restoreDeleted(ids) },
                    onDeletePermanent = { ids -> vm.permanentDelete(ids) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── PHOTO DETAILS ─────────────────────────────────────
            is Screen.PhotoDetails -> {
                PhotoDetailsScreen(
                    item = screen.item,
                    onBack = { currentScreen = tabScreens[selectedTab] },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom nav — visible only on main 4 tabs
        val showNav = currentScreen in tabScreens
        AnimatedVisibility(
            visible = showNav,
            enter = slideInVertically { it } + fadeIn(),
            exit  = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            GaleriaBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    currentScreen = tabScreens[tab]
                }
            )
        }
    }
}

private fun showBiometricPrompt(
    activity: MainActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit
) {
    val executor: Executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
    val bio = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    bio.authenticate(info)
}

private fun shareMedia(activity: MainActivity, item: MediaItem) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType.ifEmpty { "image/*" }
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
        // Share failed silently
    }
}
