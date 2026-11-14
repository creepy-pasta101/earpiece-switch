package dev.creepypasta.earpiece.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * The stock Material 3 type scale, with two deliberate tweaks:
 *  - bodyLarge carries a slightly looser line height, because the route
 *    descriptions and the about body are read as paragraphs, not labels;
 *  - titleLarge is semi-bold so the app bar reads as a title against the
 *    tonal surface behind it.
 *
 * No custom font family is bundled: the app ships zero third-party assets and
 * relies on the platform's default (Roboto / system font).
 */
val EarpieceTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.5.sp,
    ),
)
