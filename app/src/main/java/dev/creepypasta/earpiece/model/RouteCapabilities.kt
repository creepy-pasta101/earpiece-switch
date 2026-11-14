package dev.creepypasta.earpiece.model

/**
 * A snapshot of which outputs the device currently reports as present.
 *
 * Deliberately a plain data class with no Android types so the resolver can be unit-tested on the
 * JVM. [sdkInt] is carried here (rather than read from `Build.VERSION` inside the model) so the
 * API-level gate is also testable.
 */
data class RouteCapabilities(
    val hasEarpiece: Boolean,
    val hasSpeaker: Boolean,
    val hasBluetooth: Boolean,
    val hasWired: Boolean,
    val sdkInt: Int,
) {

    /**
     * True when [route] can be selected right now.
     *
     * EARPIECE is special: the earpiece may exist but the audio-mode/communication-device APIs the
     * app relies on are only reliable from API 26, so the route additionally requires
     * `sdkInt >= MIN_SDK_FOR_EARPIECE`. The app's minSdk is 26, so in practice this gate only
     * bites on data that did not come from the platform — which is exactly what makes it worth
     * asserting in tests.
     */
    fun supports(route: AudioRoute): Boolean = when (route) {
        AudioRoute.EARPIECE -> hasEarpiece && sdkInt >= MIN_SDK_FOR_EARPIECE
        AudioRoute.SPEAKER -> hasSpeaker
        AudioRoute.BLUETOOTH -> hasBluetooth
        AudioRoute.WIRED -> hasWired
    }

    companion object {

        /** First API level on which the earpiece route is trusted. */
        const val MIN_SDK_FOR_EARPIECE: Int = 26

        /**
         * What we assume before the platform has been queried: the loudspeaker is the one output
         * that exists on every device, everything else is unknown and therefore unavailable.
         */
        val UNKNOWN: RouteCapabilities = RouteCapabilities(
            hasEarpiece = false,
            hasSpeaker = true,
            hasBluetooth = false,
            hasWired = false,
            sdkInt = 0,
        )
    }
}
