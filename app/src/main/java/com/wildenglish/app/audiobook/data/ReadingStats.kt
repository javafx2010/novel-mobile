package com.wildenglish.app.audiobook.data

import android.content.Context
import android.content.SharedPreferences
import com.wildenglish.app.audiobook.data.ReadingStats.FLUSH_INTERVAL_MS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate

/** One day's listening time, used by the "最近 7 天" chart. */
data class DayStat(val date: LocalDate, val seconds: Long, val isToday: Boolean)

/** One achievement badge (读书成就). */
data class Achievement(
    val id: String,
    val title: String,
    val desc: String,
    val current: Long,
    val target: Long,
) {
    val unlocked: Boolean get() = current >= target
    val progress: Float
        get() = if (target <= 0L) 1f else (current.toFloat() / target).coerceIn(0f, 1f)
}

data class ReadingStatsSnapshot(
    val todaySeconds: Long = 0L,
    val last7Days: List<DayStat> = emptyList(),
    val streakDays: Int = 0,
    val totalSeconds: Long = 0L,
    val totalDays: Int = 0,
    val finishedIds: Set<String> = emptySet(),
    val finishedThisYear: Int = 0,
    val year: Int = LocalDate.now().year,
) {
    val todayMinutes: Int get() = (todaySeconds / 60).toInt()
    val totalHours: Int get() = (totalSeconds / 3600).toInt()
}

/**
 * 收听时长的本地统计（每日阅读时长 / 连续天数 / 读完的书）。
 *
 * 由 [com.wildenglish.app.audiobook.playback.PlaybackCenter] 在播放时每秒上报（[addSeconds]），
 * 数据按「天」累计到 SharedPreferences：`daily_seconds` = {epochDay: 秒}，`finished_books` =
 * {bookId: 读完那天的 epochDay}。写入做了节流（默认 5 秒一次），避免每秒写盘。
 */
object ReadingStats {

    private const val PREFS = "reading_stats"
    private const val KEY_DAILY = "daily_seconds"
    private const val KEY_FINISHED = "finished_books"
    private const val FLUSH_INTERVAL_MS = 5_000L

    private var prefs: SharedPreferences? = null
    private val daily = HashMap<Long, Long>()
    private val finished = HashMap<String, Long>()
    private var lastFlush = 0L
    private var lastPublish = 0L

    private val _state = MutableStateFlow(ReadingStatsSnapshot())
    val state: StateFlow<ReadingStatsSnapshot> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            JSONObject(p.getString(KEY_DAILY, "{}") ?: "{}").let { obj ->
                obj.keys().forEach { key ->
                    key.toLongOrNull()?.let { day -> daily[day] = obj.optLong(key) }
                }
            }
        }
        runCatching {
            JSONObject(p.getString(KEY_FINISHED, "{}") ?: "{}").let { obj ->
                obj.keys().forEach { id -> finished[id] = obj.optLong(id) }
            }
        }
        publish()
    }

    /** 播放中每秒上报一次；内存累计，按 [FLUSH_INTERVAL_MS] 节流落盘与刷新界面。 */
    fun addSeconds(seconds: Long) {
        if (seconds <= 0L) return
        val today = LocalDate.now().toEpochDay()
        daily[today] = (daily[today] ?: 0L) + seconds
        val now = System.currentTimeMillis()
        if (now - lastFlush >= FLUSH_INTERVAL_MS) flush()
        if (now - lastPublish >= FLUSH_INTERVAL_MS) publish()
    }

    /** 一本书听完（播完最后一章）时记一次。 */
    fun markFinished(bookId: String) {
        if (bookId.isBlank() || finished.containsKey(bookId)) return
        finished[bookId] = LocalDate.now().toEpochDay()
        flush()
        publish()
    }

    fun isFinished(bookId: String): Boolean = finished.containsKey(bookId)

    fun todaySeconds(): Long = daily[LocalDate.now().toEpochDay()] ?: 0L

    /** Writes pending seconds; call when playback pauses or the UI goes away. */
    fun flush() {
        val p = prefs ?: return
        val dailyJson = JSONObject().apply {
            daily.forEach { (day, seconds) -> put(day.toString(), seconds) }
        }
        val finishedJson = JSONObject().apply {
            finished.forEach { (id, day) -> put(id, day) }
        }
        p.edit()
            .putString(KEY_DAILY, dailyJson.toString())
            .putString(KEY_FINISHED, finishedJson.toString())
            .apply()
        lastFlush = System.currentTimeMillis()
    }

    /** 成就列表：数值来自累计时长 / 连续天数 / 读完本数 / 年度计划。 */
    fun achievements(dailyGoalMinutes: Int, yearPlanBooks: Int): List<Achievement> {
        val s = _state.value
        val hours = s.totalSeconds / 3600
        val books = s.finishedIds.size.toLong()
        return listOf(
            Achievement(
                "first",
                "初次开卷",
                "累计收听 1 分钟",
                s.totalSeconds.coerceAtMost(60L),
                60L
            ),
            Achievement("streak3", "三日不辍", "连续收听 3 天", s.streakDays.toLong(), 3L),
            Achievement("streak7", "一周坚持", "连续收听 7 天", s.streakDays.toLong(), 7L),
            Achievement("streak30", "月度读者", "连续收听 30 天", s.streakDays.toLong(), 30L),
            Achievement("hours10", "十小时", "累计收听 10 小时", hours, 10L),
            Achievement("hours50", "五十小时", "累计收听 50 小时", hours, 50L),
            Achievement("hours100", "百小时", "累计收听 100 小时", hours, 100L),
            Achievement("book1", "读完第一本", "完整听完 1 本书", books, 1L),
            Achievement("book5", "五本读者", "完整听完 5 本书", books, 5L),
            Achievement("book10", "十本读者", "完整听完 10 本书", books, 10L),
            Achievement(
                "year",
                "年度达成",
                "完成 $yearPlanBooks 本的年度计划",
                s.finishedThisYear.toLong(),
                yearPlanBooks.toLong(),
            ),
            Achievement(
                "goal",
                "今日达标",
                "当天收听满 $dailyGoalMinutes 分钟",
                (s.todaySeconds / 60),
                dailyGoalMinutes.toLong(),
            ),
        )
    }

    private fun publish() {
        lastPublish = System.currentTimeMillis()
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val days = (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            DayStat(date, daily[date.toEpochDay()] ?: 0L, date == today)
        }
        var streak = 0
        // 今天还没听不算断档：从昨天开始往回数，仍保留昨天的连续记录。
        var cursor = if ((daily[todayEpoch] ?: 0L) > 0L) todayEpoch else todayEpoch - 1
        while ((daily[cursor] ?: 0L) > 0L) {
            streak++
            cursor -= 1
        }
        _state.value = ReadingStatsSnapshot(
            todaySeconds = daily[todayEpoch] ?: 0L,
            last7Days = days,
            streakDays = streak,
            totalSeconds = daily.values.sum(),
            totalDays = daily.count { it.value > 0L },
            finishedIds = finished.keys.toHashSet(),
            finishedThisYear = finished.values.count { epochDay ->
                LocalDate.ofEpochDay(epochDay).year == today.year
            },
            year = today.year,
        )
    }
}
