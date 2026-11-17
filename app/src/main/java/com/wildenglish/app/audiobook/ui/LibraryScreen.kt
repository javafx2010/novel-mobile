package com.wildenglish.app.audiobook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.Audiobook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LibTop = Color(0xFF14110E)
private val LibBottom = Color(0xFF070605)
private val LibBrassLight = Color(0xFFF0D8A8)
private val LibBrass = Color(0xFFC79A56)
private val LibBrassDark = Color(0xFF6E5128)
private val LibCream = Color(0xFFF4E9D4)
private val LibInk = Color(0xFF17120C)

/**
 * 图书库：深色留声机配色，但用「唱片封套墙」而不是圆盘——
 * 选碟页才用圆形唱片，播放页用唱机，三屏各有自己的主角。
 */
@Composable
fun LibraryScreen(
    state: AudioBookUiState,
    vm: AudioBookViewModel,
    onImport: () -> Unit,
) {
    var bookToDelete by remember { mutableStateOf<Audiobook?>(null) }

    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.libraryActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LibTop, LibBottom)))
    ) {
        LibraryBackdrop(Modifier.fillMaxSize())

        Column(Modifier
            .fillMaxSize()
            .statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "图书库",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        color = LibCream,
                    )
                    Text(
                        "有声书 · ${state.books.size} 部",
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        color = LibBrass.copy(alpha = 0.85f),
                    )
                }
                BrassCircleButton(
                    onClick = vm::openSettings,
                    icon = Icons.Filled.Settings,
                    label = "设置"
                )
                Spacer(Modifier.size(8.dp))
                BrassCircleButton(
                    onClick = vm::openMarket,
                    icon = Icons.Filled.Storefront,
                    label = "图书市场"
                )
                Spacer(Modifier.size(8.dp))
                if (state.isImporting) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            color = LibBrass,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    BrassCircleButton(onClick = onImport, icon = Icons.Filled.Add, label = "导入")
                }
            }

            when {
                state.isLoading && state.books.isEmpty() ->
                    Box(Modifier
                        .weight(1f)
                        .fillMaxWidth()) { LibraryLoading() }

                state.books.isEmpty() ->
                    Box(Modifier
                        .weight(1f)
                        .fillMaxWidth()) {
                        LibraryEmpty(error = state.error, onImport = onImport)
                    }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 28.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    itemsIndexed(state.books, key = { _, book -> book.id }) { index, book ->
                        SleeveTile(
                            book = book,
                            index = index,
                            isCurrent = state.currentBookId == book.id && state.hasActivePlayer,
                            onClick = { vm.openBook(book.id) },
                            onLongClick = { bookToDelete = book },
                        )
                    }
                }
            }
        }
    }

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            containerColor = Color(0xFF1D1711),
            title = {
                Text(
                    "删除《${book.title}》？",
                    color = LibCream,
                    fontFamily = FontFamily.Serif,
                )
            },
            text = {
                Text(
                    "将从本地移除该书及其封面、文稿与离线配音。",
                    color = LibCream.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bookToDelete = null
                    vm.deleteBook(book)
                }) { Text("删除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text(
                        "取消",
                        color = LibBrassLight
                    )
                }
            },
        )
    }
}

/** Faint horn arcs + warm glow, keeping the gramophone mood without repeating the disc motif. */
@Composable
private fun LibraryBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val glow = Offset(w * 0.86f, h * 0.04f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(LibBrass.copy(alpha = 0.13f), Color.Transparent),
                center = glow,
                radius = w * 0.9f,
            ),
            radius = w * 0.9f,
            center = glow,
        )
        val bell = Offset(w * 1.02f, h * 0.02f)
        for (i in 0..4) {
            val r = w * 0.24f + i * w * 0.06f
            drawArc(
                color = LibBrass.copy(alpha = 0.09f - i * 0.014f),
                startAngle = 100f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(bell.x - r, bell.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun BrassCircleButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xFF1D1711).copy(alpha = 0.92f))
            .border(1.dp, LibBrass.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = LibBrassLight,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun LibraryLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = LibBrass, strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text("正在翻开唱片架…", fontSize = 13.sp, color = LibBrassLight.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun LibraryEmpty(error: String?, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "唱片架还是空的",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = LibCream,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "导入 .epub 图书、.jpd 有声书，或直接分享 MP3 文件，即可开始收听。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = LibCream.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        BrassPill(label = "导入图书 / 音频", onClick = onImport)
    }
}

@Composable
private fun BrassPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(LibBrassDark, LibBrass, LibBrassLight)))
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = LibInk,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SleeveTile(
    book: Audiobook,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // 唱片封套：竖版封面 + 黄铜细边 + 编号，区别于选碟页的圆盘。
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1D1711))
                .border(
                    width = if (isCurrent) 2.dp else 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            LibBrassLight.copy(alpha = if (isCurrent) 0.95f else 0.5f),
                            LibBrassDark.copy(alpha = 0.55f),
                        )
                    ),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            BookCover(
                coverFile = book.coverFile,
                title = book.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D0A08).copy(alpha = 0.78f))
                    .border(1.dp, LibBrass.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    (index + 1).toString().padStart(2, '0'),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = LibBrassLight,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            book.title,
            fontFamily = FontFamily.Serif,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 14.sp,
            color = LibCream,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author ?: "${book.chapterCount} 章",
            fontSize = 12.sp,
            color = LibBrass.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private tailrec fun Context.libraryActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.libraryActivity()
    else -> null
}
