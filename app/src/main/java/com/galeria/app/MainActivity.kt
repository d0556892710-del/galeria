package com.galeria.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.MainViewModel
import com.galeria.app.ui.collections.CollectionsScreen
import com.galeria.app.ui.components.GaleriaBottomBar
import com.galeria.app.ui.hidden.HiddenScreen
import com.galeria.app.ui.library.LibraryScreen
import com.galeria.app.ui.search.SearchScreen
import com.galeria.app.ui.theme.GaleriaTheme
import com.galeria.app.ui.theme.GlassTokens
import com.galeria.app.ui.viewer.PhotoViewerScreen
import com.galeria.app.ui.viewer.VideoPlayerScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GaleriaTheme {
                GaleriaApp(vm)
            }
        }
    }
}

sealed class Screen {
    object Library : Screen()
    object Collections : Screen()
    object Search : Screen()
    data class PhotoViewer(val index: Int) : Screen()
    data class VideoPlayer(val item: MediaItem) : Screen()
    object Hidden : Screen()
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GaleriaApp(vm: MainViewModel) {
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
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Library) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Collect state
    val library    by vm.libraryMedia.collectAsStateWithLifecycle()
    val hidden     by vm.hiddenMedia.collectAsStateWithLifecycle()
    val favorites  by vm.favoriteMedia.collectAsStateWithLifecycle()
    val videos     by vm.videosMedia.collectAsStateWithLifecycle()
    val screenshots by vm.screenshotsMedia.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()

    // Visible library (non-hidden)
    val visibleLibrary = remember(library) { library.filter { !it.isHidden } }

    Box(modifier = Modifier.fillMaxSize().background(GlassTokens.BgPrimary)) {

        when (val screen = currentScreen) {
            is Screen.Library -> {
                LibraryScreen(
                    items = visibleLibrary,
                    selectedIds = selectedIds,
                    totalCount = visibleLibrary.size,
                    onItemClick = { item ->
                        val idx = visibleLibrary.indexOf(item)
                        if (item.isVideo) currentScreen = Screen.VideoPlayer(item)
                        else currentScreen = Screen.PhotoViewer(idx)
                    },
                    onItemLongPress = { vm.toggleSelect(it.id) },
                    onSelectToggle = { vm.toggleSelect(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is Screen.Collections -> {
                CollectionsScreen(
                    allMedia = visibleLibrary,
                    favorites = favorites,
                    videos = videos,
                    screenshots = screenshots,
                    hiddenCount = hidden.size,
                    recentlyDeletedCount = 0,
                    onAlbumClick = { key ->
                        if (key == "hidden") currentScreen = Screen.Hidden
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is Screen.Search -> {
                SearchScreen(
                    query = searchQuery,
                    results = searchResults,
                    onQueryChange = vm::setQuery,
                    onItemClick = { item ->
                        val idx = visibleLibrary.indexOf(item)
                        if (item.isVideo) currentScreen = Screen.VideoPlayer(item)
                        else currentScreen = Screen.PhotoViewer(idx)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is Screen.PhotoViewer -> {
                PhotoViewerScreen(
                    items = visibleLibrary,
                    startIndex = screen.index,
                    onClose = { currentScreen = if (selectedTab == 0) Screen.Library else Screen.Collections },
                    onToggleFavorite = vm::toggleFavorite,
                    onShare = { /* TODO */ },
                    onDelete = { /* TODO */ },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is Screen.VideoPlayer -> {
                VideoPlayerScreen(
                    videoUri = screen.item.uri,
                    onClose = { currentScreen = if (selectedTab == 0) Screen.Library else Screen.Collections },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is Screen.Hidden -> {
                HiddenScreen(
                    hiddenItems = hidden,
                    onItemClick = { /* TODO: open viewer */ },
                    onUnhide = { ids -> vm.unhideItems(ids) },
                    onBack = { currentScreen = Screen.Collections },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom nav — hidden inside viewer / video / hidden screens
        val showNav = currentScreen is Screen.Library ||
                      currentScreen is Screen.Collections ||
                      currentScreen is Screen.Search

        AnimatedVisibility(
            visible = showNav,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            GaleriaBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    currentScreen = if (tab == 0) Screen.Library else Screen.Collections
                },
                onSearchClick = {
                    currentScreen = Screen.Search
                }
            )
        }
    }
}
