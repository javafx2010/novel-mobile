package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.Chapter
import com.wildenglish.app.ui.theme.GlassCardShape
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.GlassPrimaryButton

@Composable
fun BookDetailScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val book = state.currentBook
    if (book == null) {
        CenteredMessage(
            title = "没有选择书籍",
            hint = "请返回书架重新选择。",
            actionLabel = "返回书架",
            onAction = vm::goBack,
        )
        return
    }

    val dark = isSystemInDarkTheme()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = vm::goBack, dark = dark) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BookCover(
                        coverFile = book.coverFile,
                        title = book.title,
                        modifier = Modifier
                            .width(150.dp)
                            .aspectRatio(0.7f),
                    )
                    Spacer(Modifier.height(22.dp))
                    Text(
                        book.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    val meta = listOfNotNull(book.author, book.narrator?.let { "演播 $it" })
                        .joinToString(" · ")
                        .ifBlank { "${book.chapterCount} 章" }
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                Column(Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)) {
                    // 有上次的进度就从那里接着读，没有才从第一章开始。
                    val hasProgress = state.savedChapterIndex > 0 || state.savedSentenceIndex > 0
                    if (hasProgress) {
                        Text(
                            "上次听到：第 ${state.savedChapterIndex + 1} 章 · 第 ${state.savedSentenceIndex + 1} 句",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    GlassPrimaryButton(
                        label = if (hasProgress) "继续朗读" else "开始朗读",
                        onClick = { vm.playSaved() },
                        modifier = Modifier.fillMaxWidth(),
                        leading = {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.width(20.dp)
                            )
                        },
                    )
                }
            }

            book.description?.takeIf { it.isNotBlank() }?.let { description ->
                item {
                    Text(
                        description,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionHeader("目录") }

            // 懒加载：只组合可见的章节。以前把所有章节塞进一个 item 里
            // forEachIndexed 一次性组合，几百章会在进入页面时卡住主线程。
            val cardFill = if (dark) Color.White.copy(alpha = 0.10f)
            else Color.White.copy(alpha = 0.46f)
            itemsIndexed(book.chapters, key = { _, chapter -> chapter.index }) { index, chapter ->
                val first = index == 0
                val last = index == book.chapters.lastIndex
                val shape = when {
                    first && last -> GlassCardShape
                    first -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    last -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(shape)
                        .background(cardFill, shape),
                ) {
                    ChapterRow(
                        chapter = chapter,
                        isCurrent = index == state.chapterIndex,
                        onClick = { vm.playChapter(book, index) },
                    )
                    if (!last) RowDivider(52.dp)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun RowDivider(startInset: Dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${chapter.index + 1}",
            modifier = Modifier.width(30.dp),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            chapter.title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
