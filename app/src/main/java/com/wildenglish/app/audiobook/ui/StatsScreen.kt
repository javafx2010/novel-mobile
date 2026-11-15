@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.Achievement
import com.wildenglish.app.audiobook.data.DayStat
import com.wildenglish.app.audiobook.data.ReadingStats
import com.wildenglish.app.ui.theme.GlassCardShape
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.glass
import kotlin.math.max

/**
 * 阅读统计（iBooks 风格）：今日时长与目标、最近 7 天、年度读书计划、读书成就。
 */
@Composable
fun StatsScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val dark = isSystemInDarkTheme()
    var editingPlan by remember { mutableStateOf(false) }
    val achievements = remember(state.stats, state.dailyGoalMinutes, state.yearPlanBooks) {
        ReadingStats.achievements(state.dailyGoalMinutes, state.yearPlanBooks)
    }

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
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text(
                    "阅读统计",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "今年的阅读足迹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { TodayCard(state, dark) }
            item { WeekChart(state, dark) }
            item { YearPlanCard(state, dark, onEdit = { editingPlan = true }) }

            item {
                Text(
                    "读书成就",
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            items(achievements.chunked(3)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { achievement -> BadgeCell(achievement) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    if (editingPlan) {
        PlanDialog(
            current = state.yearPlanBooks,
            onDismiss = { editingPlan = false },
            onConfirm = { books ->
                vm.setYearPlanBooks(books)
                editingPlan = false
            },
        )
    }
}

@Composable
private fun TodayCard(state: AudioBookUiState, dark: Boolean) {
    val goal = state.dailyGoalMinutes
    val todayMinutes = state.stats.todaySeconds / 60
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape = GlassCardShape, dark = dark)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "今日阅读",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$todayMinutes",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        " 分钟",
                        modifier = Modifier.padding(bottom = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StreakPill(state.stats.streakDays)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { state.dailyGoalProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (todayMinutes >= goal) {
                "已达成今日目标（$goal 分钟）· 累计 ${state.stats.totalHours} 小时"
            } else {
                "目标 $goal 分钟 · 还差 ${(goal - todayMinutes).coerceAtLeast(0)} 分钟 · 累计 ${state.stats.totalHours} 小时"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StreakPill(days: Int) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "连续 ",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Text(
            "$days",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = color,
        )
        Text(
            " 天",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun WeekChart(state: AudioBookUiState, dark: Boolean) {
    val days: List<DayStat> = state.stats.last7Days
    val maxSeconds = max(days.maxOfOrNull { it.seconds } ?: 0L, 60L)
    val barColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape = GlassCardShape, dark = dark)
            .padding(18.dp),
    ) {
        Text(
            "最近 7 天",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                val fraction = (day.seconds.toFloat() / maxSeconds).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .width(18.dp)
                            .height((6 + 72 * fraction).dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    day.isToday -> barColor
                                    day.seconds > 0L -> barColor.copy(alpha = 0.55f)
                                    else -> idleColor
                                }
                            ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        weekdayLabel(day),
                        fontSize = 11.sp,
                        color = if (day.isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun weekdayLabel(day: DayStat): String = when (day.date.dayOfWeek.value) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

@Composable
private fun YearPlanCard(state: AudioBookUiState, dark: Boolean, onEdit: () -> Unit) {
    val done = state.stats.finishedThisYear
    val plan = state.yearPlanBooks
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape = GlassCardShape, dark = dark)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${state.stats.year} 年读书计划",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$done",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        " / $plan 本",
                        modifier = Modifier.padding(bottom = 5.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onEdit) { Text("调整计划") }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { state.yearPlanProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (done >= plan) "年度目标已达成 · 共读完 ${state.stats.finishedIds.size} 本"
            else "还差 ${plan - done} 本达成今年目标 · 共读完 ${state.stats.finishedIds.size} 本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowScope.BadgeCell(achievement: Achievement) {
    val unlocked = achievement.unlocked
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(74.dp)) {
            val badge = BADGE_RES[achievement.id]
            if (badge != null) {
                Image(
                    painter = painterResource(badge),
                    contentDescription = achievement.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .alpha(if (unlocked) 1f else 0.32f),
                )
            }
            if (!unlocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            achievement.title,
            fontSize = 12.sp,
            fontWeight = if (unlocked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (unlocked) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (unlocked) "已达成" else "${achievement.current}/${achievement.target}",
            fontSize = 10.sp,
            color = if (unlocked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 成就 id -> ComfyUI 生成的徽章图（无解锁状态，锁定时界面置灰）。 */
private val BADGE_RES: Map<String, Int> = mapOf(
    "first" to R.drawable.badge_first,
    "streak3" to R.drawable.badge_streak3,
    "streak7" to R.drawable.badge_streak7,
    "streak30" to R.drawable.badge_streak30,
    "hours10" to R.drawable.badge_hours10,
    "hours50" to R.drawable.badge_hours50,
    "hours100" to R.drawable.badge_hours100,
    "book1" to R.drawable.badge_book1,
    "book5" to R.drawable.badge_book5,
    "book10" to R.drawable.badge_book10,
    "year" to R.drawable.badge_year,
    "goal" to R.drawable.badge_goal,
)

@Composable
private fun PlanDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一年读书计划") },
        text = {
            Column {
                Text(
                    "设定今年计划读完的本数，完成后可在统计页查看进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = { if (value > 1) value-- }) { Text("－") }
                    Text(
                        "$value 本",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                    TextButton(onClick = { if (value < 200) value++ }) { Text("＋") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(12, 24, 50).forEach { preset ->
                        Text(
                            "$preset 本",
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { value = preset }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
