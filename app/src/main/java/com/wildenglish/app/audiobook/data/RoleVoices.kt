package com.wildenglish.app.audiobook.data

/**
 * 分角色配音：把一章的句子分配给「旁白」或某个角色，每个角色用一个固定的音色。
 *
 * 判定规则：
 *  - 句子不带成对引号 → 旁白；
 *  - 带成对引号（“” 「」 『』 "" ‘’）→ 台词，引号前后的提示语（`张三说：` / `… said Tom`）
 *    用来识别说话人；认不出时归为「对白」，由两个音色交替朗读，避免整章一个声音。
 *
 * 分配是确定的：同一角色名在同一章里始终用同一个音色；撞车时顺延到下一个空闲音色，
 * 保证同一章内不同角色不会共用声音。背景预生成、实时合成、就绪检查三条路径都调用本
 * 方法，因此缓存与播放永远一致。
 */
object RoleVoices {

    const val NARRATOR = "旁白"
    const val DIALOGUE = "对白"

    /** 一句话的角色与音色。 */
    data class Line(val role: String, val voice: VoiceConfig)

    fun assign(sentences: List<String>): List<Line> {
        if (sentences.isEmpty()) return emptyList()
        val lang = VoiceConfig.detectLang(sentences)
        val cast = VoiceConfig.cast(lang)
        val narrator = cast.first()
        val actors = cast.drop(1).ifEmpty { listOf(narrator) }

        // 本章已占用的音色：actor index -> 角色名（碰撞时线性探测）。
        val taken = HashMap<Int, String>()
        val voiceOfRole = HashMap<String, Int>()
        var unknownCursor = -1

        return sentences.map { text ->
            if (!isDialogue(text)) {
                Line(NARRATOR, narrator)
            } else {
                val name = speakerOf(text)
                if (name == null) {
                    // 不知道谁在说：交替换音色，听感上像两个人对话。
                    unknownCursor = (unknownCursor + 1) % actors.size
                    Line(DIALOGUE, actors[unknownCursor])
                } else {
                    val index = voiceOfRole.getOrPut(name) { claim(name, actors.size, taken) }
                    Line(name, actors[index])
                }
            }
        }
    }

    /** 取一个空闲音色；同名角色复用同一个，不同名角色必不撞车。 */
    private fun claim(name: String, size: Int, taken: MutableMap<Int, String>): Int {
        if (size <= 0) return 0
        var index = ((name.lowercase().hashCode().toLong() and 0x7FFFFFFFL) % size).toInt()
        var guard = 0
        while (guard < size) {
            val owner = taken[index]
            if (owner == null || owner == name) {
                taken[index] = name
                return index
            }
            index = (index + 1) % size
            guard++
        }
        return 0
    }

    // ---- 说话人识别 ----

    private const val QUOTE_CHARS = "\"“”「」『』‘’"

    /**
     * 是否为台词。要求成对引号：英文的 ’ 也可能只是撇号（don’t / it’s），
     * 单独出现不能当作台词，否则整本书的对白判定都会错。
     */
    private fun isDialogue(text: String): Boolean {
        if (text.count { it == '"' } >= 2) return true
        return ('“' in text && '”' in text) ||
                ('「' in text && '」' in text) ||
                ('『' in text && '』' in text) ||
                ('‘' in text && '’' in text)
    }

    // 张三：“…
    private val CN_COLON = Regex("^(.{1,12}?)\\s*[：:]\\s*[\"“「『]")

    // 张三说：/ 张三说道，“ / 他低声道：“
    private val CN_SAY = Regex(
        "^(.{1,12}?)\\s*(?:说道|问道|答道|嚷道|喊道|笑道|低声道|大声说|小声说|回答|回应|补充|嘟囔|说|道|问|答|喊|叫|笑)" +
                "\\s*[，,：:]?\\s*[\"“「『]"
    )

    // …。”张三说 / 。”他笑了笑
    private val CN_AFTER = Regex(
        "[”」』\"]\\s*(.{1,12}?)\\s*(?:说道|问道|答道|喊道|笑道|说|道|问|答|喊|叫|笑)"
    )

    // Tom said, "…
    private val EN_BEFORE = Regex(
        "^([A-Z][A-Za-z.'’-]*(?:\\s+[A-Z][A-Za-z.'’-]*){0,2})\\s+" +
                "(?:said|asked|replied|answered|whispered|shouted|muttered|cried|added|began|continued)\\b" +
                "[^\"“]{0,3}[\"“]"
    )

    // "…" said Tom
    private val EN_AFTER_VERB = Regex(
        "[\"”]\\s*,?\\s*(?:said|asked|replied|answered|whispered|shouted|muttered|cried|added)\\s+" +
                "([A-Z][A-Za-z.'’-]*(?:\\s+[A-Z][A-Za-z.'’-]*){0,2})"
    )

    // "…" Tom said
    private val EN_AFTER_NAME = Regex(
        "[\"”]\\s*,?\\s*([A-Z][A-Za-z.'’-]*(?:\\s+[A-Z][A-Za-z.'’-]*){0,2})\\s+" +
                "(?:said|asked|replied|answered|whispered|shouted|muttered|cried|added)\\b"
    )

    private val NAME_NOISE =
        Regex("^[\\s，。、！？!?、“”\"'「」『』：:.…—–-]+|[\\s，。、！？!?、“”\"'「」『』：:.…—–-]+$")

    /** 提示语里的修饰词，去掉后角色名才稳定（“他轻声” -> “他”）。 */
    private val CJK_MODIFIERS = listOf(
        "轻声", "低声", "大声", "高声", "冷冷", "淡淡", "缓缓", "慢慢", "急忙", "连忙",
        "笑着", "哭着", "喃喃", "自语", "点头", "摇头", "沉默", "开口", "接着", "又",
    )

    private val CJK_PRONOUNS = setOf('他', '她', '我', '你', '它', '咱')

    private fun speakerOf(text: String): String? {
        CN_SAY.find(text)?.let { return cjkName(it.groupValues[1], fromStart = true) }
        CN_COLON.find(text)?.let { return cjkName(it.groupValues[1], fromStart = true) }
        CN_AFTER.find(text)?.let { return cjkName(it.groupValues[1], fromStart = false) }
        EN_BEFORE.find(text)?.let { return enName(it.groupValues[1]) }
        EN_AFTER_VERB.find(text)?.let { return enName(it.groupValues[1]) }
        EN_AFTER_NAME.find(text)?.let { return enName(it.groupValues[1]) }
        return null
    }

    /**
     * 把中文提示语收敛成角色名：提示语里常带从句和修饰（“母亲抬起头，轻声说”），
     * 只取靠近说话人的那一小段；仍然太长就当作“认不出”，避免把半句话当成角色。
     */
    private fun cjkName(raw: String, fromStart: Boolean): String? {
        // 句首的提示语不该包含引号，否则说明匹配跨到了台词里。
        if (fromStart && raw.any { it in QUOTE_CHARS }) return null
        var name = raw.replace(NAME_NOISE, "").trim()
        val segments = name.split(Regex("[，,。；;：:、\\s]+")).filter { it.isNotBlank() }
        if (segments.isNotEmpty()) name = if (fromStart) segments.first() else segments.last()
        name = name.replace(NAME_NOISE, "").trim()
        var changed = true
        while (changed) {
            changed = false
            for (word in CJK_MODIFIERS) {
                if (name.length > word.length && name.endsWith(word)) {
                    name = name.dropLast(word.length)
                    changed = true
                }
            }
        }
        val first = name.firstOrNull()
        if (first != null && first in CJK_PRONOUNS) return first.toString()
        if (name.isEmpty() || name.length > 4) return null
        return name
    }

    private fun enName(raw: String): String? {
        val name = raw.replace(NAME_NOISE, "").trim()
        if (name.isEmpty() || name.length > 24) return null
        return name
    }
}
