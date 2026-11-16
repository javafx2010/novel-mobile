@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.Screen
import com.wildenglish.app.audiobook.playback.VoicePrepCenter
import com.wildenglish.app.ui.theme.GlassBarShape
import com.wildenglish.app.ui.theme.GlassPanel
import com.wildenglish.app.ui.theme.LiquidGlassBackdrop

@Composable
fun AudioBookApp(vm: AudioBookViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importPackage(uri)
    }

    LaunchedEffect(state.error) {
        val message = state.error
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            vm.dismissError()
        }
    }

    BackHandler(enabled = state.screen != Screen.LIBRARY) { vm.goBack() }

    // On the library screen, "back" minimizes to the tray instead of finishing:
    // the resident foreground service keeps dubbing and its notification stays.
    val activity = LocalContext.current.findActivity()
    BackHandler(enabled = state.screen == Screen.LIBRARY) {
        VoicePrepCenter.ensureService()
        activity?.moveTaskToBack(true)
    }

    val darkChrome = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize()) {
        if (state.screen != Screen.PLAYER) {
            LiquidGlassBackdrop(Modifier.fillMaxSize(), dark = darkChrome)
        } else {
            Box(Modifier
                .fillMaxSize()
                .background(Color.Black))
        }
        Column(Modifier
            .fillMaxSize()
            .navigationBarsPadding()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = state.screen,
                    modifier = Modifier.fillMaxSize(),
                    label = "ScreenTransition",
                    transitionSpec = { screenTransition(initialState, targetState) },
                ) { screen ->
                    when (screen) {
                        Screen.LIBRARY -> LibraryScreen(
                            state = state,
                            vm = vm,
                            onImport = { importLauncher.launch(arrayOf("*/*")) },
                        )
                        Screen.DETAIL -> BookDetailScreen(state, vm)
                        Screen.PLAYER -> PlayerScreen(state, vm)
                        Screen.READER -> ReaderScreen(state, vm)
                        Screen.MARKET -> MarketScreen(state, vm)
                        Screen.SETTINGS -> SettingsScreen(state, vm)
                        Screen.VOICE -> VoiceScreen(state, vm)
                        Screen.STORAGE -> StorageScreen(state, vm)
                        Screen.PLAYBACK -> PlaybackSettingsScreen(state, vm)
                    }
                }
            }
            AnimatedVisibility(
                visible = (state.screen == Screen.LIBRARY || state.screen == Screen.DETAIL) && state.hasActivePlayer,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(
                    state = state,
                    onOpen = vm::openPlayer,
                    onToggle = { vm.togglePlayPause() },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

/**
 * Navigation transitions that follow the iOS hierarchy:
 * - entering the player presents upward like a sheet,
 * - leaving it dismisses downward,
 * - deeper screens push in from the right and pop back to the left.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun screenTransition(from: Screen, to: Screen): ContentTransform {
    val slideSpec = tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)
    val fadeInSpec = tween<Float>(durationMillis = 260)
    val fadeOutSpec = tween<Float>(durationMillis = 220)
    return when {
        // Presenting / dismissing the audiobook player (cover view).
        to == Screen.PLAYER && from != Screen.READER ->
            (slideInVertically(animationSpec = slideSpec) { it } + fadeIn(animationSpec = fadeInSpec))
                .togetherWith(
                    slideOutVertically(animationSpec = slideSpec) { -it / 6 } +
                        fadeOut(animationSpec = fadeOutSpec)
                )

        from == Screen.PLAYER && to != Screen.READER ->
            (fadeIn(animationSpec = fadeInSpec) +
                slideInVertically(animationSpec = slideSpec) { -it / 6 })
                .togetherWith(
                    slideOutVertically(animationSpec = slideSpec) { it } +
                        fadeOut(animationSpec = fadeOutSpec)
                )

        to.ordinal > from.ordinal ->
            (slideInHorizontally(animationSpec = slideSpec) { it } + fadeIn(animationSpec = fadeInSpec))
                .togetherWith(
                    slideOutHorizontally(animationSpec = slideSpec) { -it / 3 } +
                        fadeOut(animationSpec = fadeOutSpec)
                )

        to.ordinal < from.ordinal ->
            (slideInHorizontally(animationSpec = slideSpec) { -it / 3 } + fadeIn(animationSpec = fadeInSpec))
                .togetherWith(
                    slideOutHorizontally(animationSpec = slideSpec) { it } +
                        fadeOut(animationSpec = fadeOutSpec)
                )

        else -> fadeIn(animationSpec = fadeInSpec).togetherWith(fadeOut(animationSpec = fadeOutSpec))
    }
}

@Composable
private fun MiniPlayer(
    state: AudioBookUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val book = state.currentBook ?: return
    GlassPanel(
        shape = GlassBarShape,
        dark = isSystemInDarkTheme(),
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                coverFile = book.coverFile,
                title = book.title,
                modifier = Modifier
                    .width(40.dp)
                    .aspectRatio(0.72f),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.currentChapter?.title ?: "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
