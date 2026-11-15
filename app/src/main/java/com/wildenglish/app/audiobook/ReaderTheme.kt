package com.wildenglish.app.audiobook

import androidx.compose.ui.graphics.Color

/**
 * Reading "skins" for the document/文稿 screen. Each skin is a self-contained
 * palette so the reader never depends on the app's light/dark Material theme.
 */
enum class ReaderTheme(
    val label: String,
    val background: Color,
    val ink: Color,
    val dim: Color,
    val highlight: Color,
    val divider: Color,
) {
    PAPER(
        "纸张",
        Color(0xFFFBF8F2), Color(0xFF2B2620), Color(0xFF8C857A),
        Color(0xFFF2E2B8), Color(0xFFE6DFD1),
    ),
    SEPIA(
        "羊皮纸",
        Color(0xFFF3E5C8), Color(0xFF4A3826), Color(0xFF9A8464),
        Color(0xFFE6CD93), Color(0xFFDDC8A2),
    ),
    GREEN(
        "护眼",
        Color(0xFFE4EED8), Color(0xFF2C3A22), Color(0xFF71836A),
        Color(0xFFC8DFAE), Color(0xFFCBDCBE),
    ),
    NIGHT(
        "夜间",
        Color(0xFF1C1C1E), Color(0xFFE6E2DA), Color(0xFF928D85),
        Color(0xFF4A4230), Color(0xFF3A3A3C),
    ),
    ;

    companion object {
        fun fromName(name: String?): ReaderTheme =
            values().firstOrNull { it.name == name } ?: PAPER
    }
}
