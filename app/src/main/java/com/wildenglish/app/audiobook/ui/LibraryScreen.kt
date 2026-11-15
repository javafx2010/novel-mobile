package com.wildenglish.app.audiobook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.VoicePrepProgress
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.GlassPrimaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    state: AudioBookUiState,
    vm: AudioBookViewModel,
    onImport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "图书库",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "有声书 · ${state.books.size} 部",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val dark = isSystemInDarkTheme()
            GlassIconButton(onClick = vm::openSettings, dark = dark) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            GlassIconButton(onClick = vm::openMarket, dark = dark) {
                Icon(Icons.Filled.Storefront, contentDescription = "图书市场")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            if (state.isImporting) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                GlassIconButton(onClick = onImport, dark = dark) {
                    Icon(Icons.Filled.Add, contentDescription = "导入")
                }
            }
        }

        when {
            state.isLoading && state.books.isEmpty() ->
                Box(Modifier
                    .weight(1f)
                    .fillMaxWidth()) { CenteredLoading() }

            state.books.isEmpty() ->
                Box(Modifier
                    .weight(1f)
                    .fillMaxWidth()) {
                    EmptyLibrary(error = state.error, onImport = onImport)
                }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                itemsIndexed(state.books, key = { _, book -> book.id }) { index, book ->
                    BookTile(
                        book = book,
                        index = index,
                        prep = state.voicePrep[book.id],
                        offlineReady = book.id in state.readyVoiceBookIds,
                        onClick = { vm.openBook(book.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(error: String?, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "还没有有声书",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "导入 .epub 图书或 .jpd 有声书开始收听。\n导入后会自动预生成离线配音，朗读无需联网、逐句更流畅（详见 PACKAGE_FORMAT.md）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        GlassPrimaryButton(label = "导入 .epub / .jpd", onClick = onImport)
    }
}

@Composable
private fun BookTile(
    book: Audiobook,
    index: Int,
    prep: VoicePrepProgress?,
    offlineReady: Boolean,
    onClick: () -> Unit,
) {
    val alpha = remember(book.id) { Animatable(0f) }
    val translation = remember(book.id) { Animatable(28f) }
    LaunchedEffect(book.id) {
        delay((index % 8) * 40L)
        launch { alpha.animateTo(1f, tween(340, easing = FastOutSlowInEasing)) }
        launch { translation.animateTo(0f, tween(340, easing = FastOutSlowInEasing)) }
    }
    Column(
        Modifier
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = translation.value
            }
            .clickable(onClick = onClick),
    ) {
        BookCover(
            coverFile = book.coverFile,
            title = book.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author ?: "${book.chapterCount} 章",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when {
            prep != null && prep.total > 0 -> "配音中 ${prep.done}/${prep.total}"
            prep != null -> "准备配音…"
            offlineReady -> "已本地配音"
            else -> null
        }
        if (status != null) {
            Text(
                status,
                fontSize = 11.sp,
                color = if (offlineReady && prep == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
