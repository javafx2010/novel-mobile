package com.wildenglish.app.audiobook.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 本地统一数据库（原生 SQLite，零新依赖）。
 *
 * 把原先分散在多个 SharedPreferences / 文件里的信息集中落库：
 *  - [books]：图书元数据、章节数、封面路径、最近访问、上次读到的章/句
 *  - [settings]：全局设置（音色 / 存储 / 显示 / 目标）
 *  - [reading_stats]：每日收听秒数
 *  - [finished_books]：读完的书
 *  - [rss_subscriptions]：RSS 订阅
 *
 * 说明：这是一层**集中存储 / 查询入口**。原有的 SharedPreferences 读取路径暂时保留，
 * 各 store 在写入时同步写库（双写），后续可逐步把读取切到数据库而不丢数据。
 * 所有方法都是同步的，调用方需自行切到 Dispatchers.IO。
 */
object AppDatabase {

    const val DB_NAME = "wildenglish.db"
    const val DB_VERSION = 1

    /** 图书表。 */
    const val T_BOOKS = "books"
    const val C_BOOK_ID = "book_id"
    const val C_BOOK_TITLE = "title"
    const val C_BOOK_AUTHOR = "author"
    const val C_BOOK_NARRATOR = "narrator"
    const val C_BOOK_DESC = "description"
    const val C_BOOK_DIR = "dir_path"
    const val C_BOOK_COVER = "cover_path"
    const val C_BOOK_CHAPTERS = "chapter_count"
    const val C_BOOK_ADDED = "added_at"
    const val C_BOOK_ACCESS = "last_access_at"
    const val C_BOOK_PROGRESS_CHAPTER = "progress_chapter"
    const val C_BOOK_PROGRESS_SENTENCE = "progress_sentence"

    /** 设置表。 */
    const val T_SETTINGS = "settings"
    const val C_KEY = "key"
    const val C_VALUE = "value"

    /** 每日统计表。 */
    const val T_READING = "reading_stats"
    const val C_DAY = "day"
    const val C_SECONDS = "seconds"

    /** 读完记录表。 */
    const val T_FINISHED = "finished_books"
    const val C_FINISHED_BOOK = "book_id"
    const val C_FINISHED_DAY = "finished_day"

    /** RSS 订阅表。 */
    const val T_RSS = "rss_subscriptions"
    const val C_RSS_ID = "feed_id"

    /** 市场同步记录表。 */
    const val T_MARKET = "market_sync"
    const val C_MARKET_ID = "market_id"
    const val C_MARKET_UPDATED = "updated_at"
    const val C_MARKET_LOCAL = "local_id"

    private var helper: OpenHelper? = null

    /**
     * 所有写操作串行投递到单个 IO 线程：SQLite 写入是磁盘 IO，绝不能阻塞调用方主线程。
     * Dispatchers.IO.limitedParallelism(1) 保证写串行、避免锁竞争。
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun init(context: Context) {
        if (helper == null) helper = OpenHelper(context.applicationContext)
    }

    private fun db(): SQLiteDatabase =
        helper?.writableDatabase ?: error("AppDatabase 尚未 init")

    /** 异步投递写操作（主线程安全）。 */
    private fun write(block: (SQLiteDatabase) -> Unit) {
        writeScope.launch {
            runCatching { block(db()) }
        }
    }

    // ---- books ----

    /** 导入 / 扫描到一本书时 upsert（含元数据与进度）。 */
    fun upsertBook(book: Audiobook, addedAt: Long = System.currentTimeMillis()) {
        write { db ->
            val values = ContentValues().apply {
                put(C_BOOK_ID, book.id)
                put(C_BOOK_TITLE, book.title)
                put(C_BOOK_AUTHOR, book.author)
                put(C_BOOK_NARRATOR, book.narrator)
                put(C_BOOK_DESC, book.description)
                put(C_BOOK_DIR, book.dir.absolutePath)
                put(C_BOOK_COVER, book.coverFile?.absolutePath)
                put(C_BOOK_CHAPTERS, book.chapters.size)
                put(C_BOOK_ADDED, addedAt)
            }
            db.insertWithOnConflict(T_BOOKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * 更新最近访问时间（打开书时）。[bookIdOrDir] 传 bookId 或目录名均可，
     * 因为部分调用点只有目录名。
     */
    fun touchBook(bookIdOrDir: String, at: Long = System.currentTimeMillis()) {
        write { db ->
            val values = ContentValues().apply { put(C_BOOK_ACCESS, at) }
            db.update(
                T_BOOKS, values,
                "$C_BOOK_ID=? OR $C_BOOK_DIR LIKE ?",
                arrayOf(bookIdOrDir, "%/$bookIdOrDir"),
            )
        }
    }

    /** 更新阅读进度（章 / 句）。 */
    fun updateBookProgress(bookId: String, chapter: Int, sentence: Int) {
        write { db ->
            val values = ContentValues().apply {
                put(C_BOOK_PROGRESS_CHAPTER, chapter)
                put(C_BOOK_PROGRESS_SENTENCE, sentence)
            }
            db.update(T_BOOKS, values, "$C_BOOK_ID=?", arrayOf(bookId))
        }
    }

    fun deleteBook(bookId: String) {
        write { db -> db.delete(T_BOOKS, "$C_BOOK_ID=?", arrayOf(bookId)) }
    }

    /** 从 DB 读图书快照（标题/作者/目录/封面/进度），冷启动毫秒级，无需解析 manifest。 */
    fun loadBooks(): List<BookRow> {
        val out = ArrayList<BookRow>()
        db().rawQuery(
            "SELECT $C_BOOK_ID,$C_BOOK_TITLE,$C_BOOK_AUTHOR,$C_BOOK_NARRATOR,$C_BOOK_DESC," +
                    "$C_BOOK_DIR,$C_BOOK_COVER,$C_BOOK_CHAPTERS,$C_BOOK_ACCESS," +
                    "$C_BOOK_PROGRESS_CHAPTER,$C_BOOK_PROGRESS_SENTENCE FROM $T_BOOKS",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BookRow(
                        id = c.getString(0).orEmpty(),
                        title = c.getString(1).orEmpty(),
                        author = c.getString(2),
                        narrator = c.getString(3),
                        description = c.getString(4),
                        dirPath = c.getString(5).orEmpty(),
                        coverPath = c.getString(6),
                        chapterCount = c.getInt(7),
                        lastAccess = c.getLong(8),
                        progressChapter = c.getInt(9),
                        progressSentence = c.getInt(10),
                    )
                )
            }
        }
        return out
    }

    data class BookRow(
        val id: String,
        val title: String,
        val author: String?,
        val narrator: String?,
        val description: String?,
        val dirPath: String,
        val coverPath: String?,
        val chapterCount: Int,
        val lastAccess: Long,
        val progressChapter: Int,
        val progressSentence: Int,
    )

    /** 得到图书 id -> 最近访问时间的映射。同步读，**必须在 Dispatchers.IO 调用**。 */
    fun bookAccessTimes(): Map<String, Long> {
        val out = HashMap<String, Long>()
        db().rawQuery("SELECT $C_BOOK_ID, $C_BOOK_ACCESS FROM $T_BOOKS", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = c.getLong(1)
            }
        }
        return out
    }

    // ---- settings ----

    fun putSetting(key: String, value: String) {
        write { db ->
            val values = ContentValues().apply {
                put(C_KEY, key)
                put(C_VALUE, value)
            }
            db.insertWithOnConflict(T_SETTINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** 同步读，**必须在 Dispatchers.IO 调用**。 */
    fun setting(key: String): String? {
        db().rawQuery("SELECT $C_VALUE FROM $T_SETTINGS WHERE $C_KEY=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    // ---- reading stats ----

    /** 全量写入某天秒数（SP -> DB 初始迁移用，覆盖） */
    fun setReadingSeconds(day: Long, seconds: Long) {
        write { db ->
            val values = ContentValues().apply {
                put(C_DAY, day)
                put(C_SECONDS, seconds)
            }
            db.insertWithOnConflict(T_READING, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun addReadingSeconds(day: Long, seconds: Long) {
        write { db ->
            db.execSQL(
                "INSERT INTO $T_READING($C_DAY,$C_SECONDS) VALUES(?,?) " +
                        "ON CONFLICT($C_DAY) DO UPDATE SET $C_SECONDS=$C_SECONDS+excluded.$C_SECONDS",
                arrayOf<Any>(day, seconds),
            )
        }
    }

    fun markFinished(bookId: String, day: Long) {
        write { db ->
            val values = ContentValues().apply {
                put(C_FINISHED_BOOK, bookId)
                put(C_FINISHED_DAY, day)
            }
            db.insertWithOnConflict(T_FINISHED, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // ---- rss ----

    fun setRssSubscribed(feedId: String, subscribed: Boolean) {
        write { db ->
            if (subscribed) {
                val values = ContentValues().apply { put(C_RSS_ID, feedId) }
                db.insertWithOnConflict(T_RSS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            } else {
                db.delete(T_RSS, "$C_RSS_ID=?", arrayOf(feedId))
            }
        }
    }

    /** 市场同步记录 upsert。 */
    fun putMarketSync(marketId: Long, updatedAt: String, localId: String) {
        write { db ->
            val values = ContentValues().apply {
                put(C_MARKET_ID, marketId)
                put(C_MARKET_UPDATED, updatedAt)
                put(C_MARKET_LOCAL, localId)
            }
            db.insertWithOnConflict(T_MARKET, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun deleteMarketSync(marketId: Long) {
        write { db -> db.delete(T_MARKET, "$C_MARKET_ID=?", arrayOf(marketId.toString())) }
    }

    private class OpenHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $T_BOOKS (
                    $C_BOOK_ID TEXT PRIMARY KEY,
                    $C_BOOK_TITLE TEXT,
                    $C_BOOK_AUTHOR TEXT,
                    $C_BOOK_NARRATOR TEXT,
                    $C_BOOK_DESC TEXT,
                    $C_BOOK_DIR TEXT,
                    $C_BOOK_COVER TEXT,
                    $C_BOOK_CHAPTERS INTEGER,
                    $C_BOOK_ADDED INTEGER,
                    $C_BOOK_ACCESS INTEGER,
                    $C_BOOK_PROGRESS_CHAPTER INTEGER,
                    $C_BOOK_PROGRESS_SENTENCE INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_SETTINGS (" +
                        "$C_KEY TEXT PRIMARY KEY, $C_VALUE TEXT)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_READING (" +
                        "$C_DAY INTEGER PRIMARY KEY, $C_SECONDS INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_FINISHED (" +
                        "$C_FINISHED_BOOK TEXT PRIMARY KEY, $C_FINISHED_DAY INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_RSS ($C_RSS_ID TEXT PRIMARY KEY)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_MARKET (" +
                        "$C_MARKET_ID INTEGER PRIMARY KEY, $C_MARKET_UPDATED TEXT, $C_MARKET_LOCAL TEXT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有 v1；后续版本在此追加 ALTER TABLE。
        }
    }
}
