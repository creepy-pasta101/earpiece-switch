package dev.creepypasta.earpiece.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Hand-tuned fallback palette, used only when the platform cannot generate a
 * Material You scheme (API < 31) or when dynamic colour is disabled.
 *
 * The hues are a desaturated teal / indigo pair, picked so the app still reads
 * as "system generated" next to a real Material You scheme. Every value below is
 * a Material 3 tonal role, not an ad-hoc colour: the light set is roughly tone
 * 40 / 90 / 100 on the primary ramp, the dark set tone 80 / 30 / 10.
 */

// --- Light -------------------------------------------------------------

/** Primary, tone 40 of the teal ramp. */
val LightPrimary = Color(0xFF3E6374)

/** On primary, tone 100. */
val LightOnPrimary = Color(0xFFFFFFFF)

/** Primary container, tone 90. */
val LightPrimaryContainer = Color(0xFFC1E8FB)

/** On primary container, tone 10. */
val LightOnPrimaryContainer = Color(0xFF001F2A)

/** Secondary, tone 40 of the neutral-teal ramp. */
val LightSecondary = Color(0xFF4E616B)

/** On secondary, tone 100. */
val LightOnSecondary = Color(0xFFFFFFFF)

/** Secondary container, tone 90. Used for the selected route card. */
val LightSecondaryContainer = Color(0xFFD1E6F1)

/** On secondary container, tone 10. */
val LightOnSecondaryContainer = Color(0xFF0A1E27)

/** Surface, tone 98 with a cool cast. */
val LightSurface = Color(0xFFF6FAFD)

/** On surface, tone 10. */
val LightOnSurface = Color(0xFF171C1F)

/** Surface variant, tone 90. */
val LightSurfaceVariant = Color(0xFFDCE4E9)

/** On surface variant, tone 30. */
val LightOnSurfaceVariant = Color(0xFF40484C)

/** Outline, tone 50. */
val LightOutline = Color(0xFF70787D)

/** Error, tone 40 of the red ramp. */
val LightError = Color(0xFFBA1A1A)

/** Surface container family, tones 99 / 94 / 92 / 90 / 100. */
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF0F4F7)
val LightSurfaceContainer = Color(0xFFEAEFF2)
val LightSurfaceContainerHigh = Color(0xFFE5EAED)
val LightSurfaceContainerHighest = Color(0xFFDFE4E8)

// --- Dark --------------------------------------------------------------

/** Primary, tone 80. */
val DarkPrimary = Color(0xFFA5CCDF)

/** On primary, tone 20. */
val DarkOnPrimary = Color(0xFF073544)

/** Primary container, tone 30. */
val DarkPrimaryContainer = Color(0xFF254C5C)

/** On primary container, tone 90. */
val DarkOnPrimaryContainer = Color(0xFFC1E8FB)

/** Secondary, tone 80. */
val DarkSecondary = Color(0xFFB5C9D4)

/** On secondary, tone 20. */
val DarkOnSecondary = Color(0xFF1F333C)

/** Secondary container, tone 30. Used for the selected route card. */
val DarkSecondaryContainer = Color(0xFF354A54)

/** On secondary container, tone 90. */
val DarkOnSecondaryContainer = Color(0xFFD1E6F1)

/** Surface, tone 6. */
val DarkSurface = Color(0xFF0F1417)

/** On surface, tone 90. */
val DarkOnSurface = Color(0xFFDEE3E6)

/** Surface variant, tone 30. */
val DarkSurfaceVariant = Color(0xFF40484C)

/** On surface variant, tone 80. */
val DarkOnSurfaceVariant = Color(0xFFBFC8CD)

/** Outline, tone 60. */
val DarkOutline = Color(0xFF8A9297)

/** Error, tone 80. */
val DarkError = Color(0xFFFFB4AB)

/** Surface container family, tones 4 / 10 / 12 / 17 / 22. */
val DarkSurfaceContainerLowest = Color(0xFF0A0F12)
val DarkSurfaceContainerLow = Color(0xFF171C1F)
val DarkSurfaceContainer = Color(0xFF1B2023)
val DarkSurfaceContainerHigh = Color(0xFF252B2E)
val DarkSurfaceContainerHighest = Color(0xFF30363A)
