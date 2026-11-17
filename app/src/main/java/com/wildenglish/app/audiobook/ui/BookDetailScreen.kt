package com.wildenglish.app.audiobook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel

private val ShelfTop = Color(0xFF14110E)
private val ShelfBottom = Color(0xFF070605)
private val ShelfBrassLight = Color(0xFFF0D8A8)
private val ShelfBrass = Color(0xFFC79A56)
private val ShelfBrassDark = Color(0xFF6E5128)
private val ShelfCream = Color(0xFFF4E9D4)
private val ShelfInk = Color(0xFF17120C)

/**
 * "选碟架": the book's chapters are presented as vinyl records, in the same dark
 * brass palette as the gramophone player. Picking a disc drops it onto the turntable
 * (navigates straight into the player). MP3 books never reach this screen —
 * [AudioBookViewModel.openBook] opens their player directly.
 */
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

    val cover = rememberCover(book.coverFile)

    // 选碟页是深色留声机风格，状态栏图标需要切成浅色。
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.shelfActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ShelfTop, ShelfBottom)))
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::goBack, modifier = Modifier.size(46.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = ShelfCream,
                )
            }
            Text(
                "选择碟片",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                letterSpacing = 3.sp,
                color = ShelfBrass.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(46.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 0.dp,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ShelfHero(
                    cover = cover,
                    title = book.title,
                    meta = listOfNotNull(book.author, book.narrator?.let { "演播 $it" })
                        .joinToString(" · ")
                        .ifBlank { "${book.chapterCount} 章" },
                    description = book.description,
                    progressLabel = progressLabel(state),
                    hasProgress = hasProgress(state),
                    onPlay = vm::playSaved,
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "碟片架 · 共 ${book.chapters.size} 张",
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    fontFamily = FontFamily.Serif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ShelfCream.copy(alpha = 0.85f),
                )
            }

            itemsIndexed(book.chapters, key = { _, chapter -> chapter.index }) { index, chapter ->
                DiscTile(
                    cover = cover,
                    number = index + 1,
                    title = chapter.title,
                    isCurrent = index == state.chapterIndex,
                    onClick = { vm.playChapter(book, index) },
                )
            }
        }
    }
}

private fun hasProgress(state: AudioBookUiState): Boolean =
    state.savedChapterIndex > 0 ||
            (if (state.currentBook?.audioMode == true) {
                state.savedPositionMs > 0
            } else {
                state.savedSentenceIndex > 0
            })

private fun progressLabel(state: AudioBookUiState): String? {
    val book = state.currentBook ?: return null
    val has = hasProgress(state)
    if (!has || book.chapters.isEmpty()) return null
    return if (book.audioMode) {
        "上次听到：第 ${state.savedChapterIndex + 1} 章 · ${formatTime(state.savedPositionMs)}"
    } else {
        "上次听到：第 ${state.savedChapterIndex + 1} 章 · 第 ${state.savedSentenceIndex + 1} 句"
    }
}

/** The featured disc (the book's cover), title, progress and the main play action. */
@Composable
private fun ShelfHero(
    cover: ImageBitmap?,
    title: String,
    meta: String,
    description: String?,
    progressLabel: String?,
    hasProgress: Boolean,
    onPlay: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VinylDisc(
            size = 208.dp,
            cover = cover,
            modifier = Modifier,
            highlight = true,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            color = ShelfCream,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            meta,
            fontSize = 13.sp,
            color = ShelfBrass.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        if (progressLabel != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                progressLabel,
                fontSize = 12.sp,
                color = ShelfCream.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ShelfCream.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(ShelfBrassDark, ShelfBrass, ShelfBrassLight)
                    )
                )
                .clickable(onClick = onPlay)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = ShelfInk,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasProgress) "继续朗读" else "开始朗读",
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = ShelfInk,
                )
            }
        }
    }
}

/** One selectable chapter = one record on the shelf. */
@Composable
private fun DiscTile(
    cover: ImageBitmap?,
    number: Int,
    title: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VinylDisc(
            size = 132.dp,
            cover = cover,
            highlight = isCurrent,
            modifier = Modifier,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "第 $number 张",
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = if (isCurrent) ShelfBrassLight else ShelfBrass.copy(alpha = 0.7f),
        )
        Text(
            title,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) ShelfCream else ShelfCream.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A black-vinyl disc with the cover as its centre label. */
@Composable
private fun VinylDisc(
    size: Dp,
    cover: ImageBitmap?,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF33291F), Color(0xFF17110D), Color(0xFF080706))
                )
            )
            .then(
                if (highlight) {
                    Modifier.border(
                        2.dp,
                        Brush.sweepGradient(
                            listOf(ShelfBrassDark, ShelfBrassLight, ShelfBrassDark)
                        ),
                        CircleShape,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
            val radius = size.toPx() / 2f
            for (i in 1..9) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.045f),
                    radius = radius * (0.44f + i * 0.052f),
                    center = center,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            drawCircle(
                color = ShelfBrass.copy(alpha = 0.45f),
                radius = radius - 1.dp.toPx(),
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent)
                ),
                startAngle = -35f,
                sweepAngle = 70f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
            )
        }
        Box(
            Modifier
                .size(size * 0.42f)
                .clip(CircleShape)
                .border(1.dp, ShelfBrassLight.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.cover_default),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color(0xFF090706)),
        )
    }
}

private tailrec fun Context.shelfActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.shelfActivity()
    else -> null
}
