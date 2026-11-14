package dev.creepypasta.earpiece.model

/**
 * The four audio outputs the app can hold.
 *
 * [key] is the stable, persisted identifier: it is written to preferences, sent through service
 * intents and read back from them, so it must never change once shipped.
 */
enum class AudioRoute(val key: String) {
    EARPIECE("earpiece"),
    SPEAKER("speaker"),
    BLUETOOTH("bluetooth"),
    WIRED("wired");

    companion object {

        /** Declaration order, which is also the order used by the UI picker and by [AudioRouteService]'s cycle. */
        val all: List<AudioRoute> = AudioRoute.entries

        /**
         * Maps a persisted/transport key back to a route.
         *
         * Returns null for an unknown or absent key so callers can fall back to their own default
         * instead of silently guessing a route the user never asked for.
         */
        fun fromKey(key: String?): AudioRoute? {
            if (key == null) return null
            return all.firstOrNull { it.key == key }
        }
    }
}
