package dev.creepypasta.earpiece.model

/**
 * The outcome of asking for [requested]: what the app will actually hold ([effective]) and whether
 * that is a compromise ([downgraded]).
 *
 * The UI turns `downgraded` into the `downgrade_note` string, so it must be true whenever the user
 * did not get what they asked for — including the degenerate case where the requested route *is*
 * SPEAKER but no output is available at all.
 */
data class RouteResolution(
    val requested: AudioRoute,
    val effective: AudioRoute,
    val downgraded: Boolean,
)
