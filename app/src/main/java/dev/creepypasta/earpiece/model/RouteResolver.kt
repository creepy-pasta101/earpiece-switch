package dev.creepypasta.earpiece.model

/**
 * Turns a requested route plus a capability snapshot into a route that can actually be held.
 *
 * Pure and Android-free: no side effects, no platform calls, fully unit-testable on the JVM.
 */
object RouteResolver {

    /**
     * Ordered candidates to try when the requested route is unavailable.
     *
     * Kept as data rather than an if/else ladder so the policy is auditable in one place and can be
     * asserted directly in tests. Each list deliberately excludes its own key.
     */
    private val FALLBACKS: Map<AudioRoute, List<AudioRoute>> = mapOf(
        AudioRoute.EARPIECE to listOf(AudioRoute.SPEAKER, AudioRoute.WIRED, AudioRoute.BLUETOOTH),
        AudioRoute.SPEAKER to listOf(AudioRoute.EARPIECE, AudioRoute.WIRED, AudioRoute.BLUETOOTH),
        AudioRoute.BLUETOOTH to listOf(AudioRoute.WIRED, AudioRoute.SPEAKER, AudioRoute.EARPIECE),
        AudioRoute.WIRED to listOf(AudioRoute.BLUETOOTH, AudioRoute.SPEAKER, AudioRoute.EARPIECE),
    )

    /** Read-only view of the fallback policy, for tests and for the about screen. */
    val fallbacks: Map<AudioRoute, List<AudioRoute>> get() = FALLBACKS

    /**
     * Resolves [requested] against [caps].
     *
     * - requested available -> effective = requested, downgraded = false
     * - otherwise the first available entry of its fallback chain
     * - if the whole chain is unavailable -> SPEAKER, downgraded = true
     */
    fun resolve(requested: AudioRoute, caps: RouteCapabilities): RouteResolution {
        if (caps.supports(requested)) {
            return RouteResolution(
                requested = requested,
                effective = requested,
                downgraded = false,
            )
        }

        val fallback = FALLBACKS[requested]
            .orEmpty()
            .firstOrNull { caps.supports(it) }

        if (fallback != null) {
            return RouteResolution(
                requested = requested,
                effective = fallback,
                downgraded = fallback != requested,
            )
        }

        // Nothing at all is available. SPEAKER is the only route we can name, and it is still a
        // compromise the user must be told about — even when SPEAKER was what they asked for,
        // because the device reports no working speaker either.
        return RouteResolution(
            requested = requested,
            effective = AudioRoute.SPEAKER,
            downgraded = true,
        )
    }
}
