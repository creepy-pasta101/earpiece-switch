package dev.creepypasta.earpiece.model

import dev.creepypasta.earpiece.state.RouteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Android-free domain model.
 *
 * No Robolectric, no android.jar, no coroutine dispatchers: everything here is a pure function call
 * or a `.value` read on a StateFlow.
 */
class RouteResolverTest {

    // ---------------------------------------------------------------- helpers

    /**
     * Builds a capability snapshot. Defaults describe a bare phone: a working loudspeaker, no
     * earpiece, no bluetooth, no wired output, on a modern API level.
     */
    private fun caps(
        earpiece: Boolean = false,
        speaker: Boolean = true,
        bluetooth: Boolean = false,
        wired: Boolean = false,
        sdkInt: Int = 36,
    ): RouteCapabilities = RouteCapabilities(
        hasEarpiece = earpiece,
        hasSpeaker = speaker,
        hasBluetooth = bluetooth,
        hasWired = wired,
        sdkInt = sdkInt,
    )

    /** Snapshot with every output present. */
    private fun allCaps(sdkInt: Int = 36): RouteCapabilities =
        caps(earpiece = true, speaker = true, bluetooth = true, wired = true, sdkInt = sdkInt)

    /** Snapshot with nothing present at all. */
    private fun nothingCaps(): RouteCapabilities =
        caps(earpiece = false, speaker = false, bluetooth = false, wired = false)

    private fun assertResolution(
        expectedRequested: AudioRoute,
        expectedEffective: AudioRoute,
        expectedDowngraded: Boolean,
        actual: RouteResolution,
        scenario: String,
    ) {
        assertEquals("$scenario: requested must be echoed back unchanged",
            expectedRequested, actual.requested)
        assertEquals("$scenario: wrong effective route",
            expectedEffective, actual.effective)
        assertEquals("$scenario: wrong downgraded flag",
            expectedDowngraded, actual.downgraded)
    }

    /** RouteState is a singleton, so anything a test publishes must be undone. */
    @After
    fun resetRouteState() {
        RouteState.publishRequested(AudioRoute.SPEAKER)
        RouteState.publishActive(AudioRoute.SPEAKER)
        RouteState.publishServiceRunning(false)
        RouteState.publishCapabilities(RouteCapabilities.UNKNOWN)
    }

    // ------------------------------------------- 1-4: everything available, no downgrade

    @Test
    fun earpieceRequested_whenEverythingAvailable_isNotDowngraded() {
        val result = RouteResolver.resolve(AudioRoute.EARPIECE, allCaps())
        assertResolution(AudioRoute.EARPIECE, AudioRoute.EARPIECE, false, result,
            "all routes available, EARPIECE requested")
    }

    @Test
    fun speakerRequested_whenEverythingAvailable_isNotDowngraded() {
        val result = RouteResolver.resolve(AudioRoute.SPEAKER, allCaps())
        assertResolution(AudioRoute.SPEAKER, AudioRoute.SPEAKER, false, result,
            "all routes available, SPEAKER requested")
    }

    @Test
    fun bluetoothRequested_whenEverythingAvailable_isNotDowngraded() {
        val result = RouteResolver.resolve(AudioRoute.BLUETOOTH, allCaps())
        assertResolution(AudioRoute.BLUETOOTH, AudioRoute.BLUETOOTH, false, result,
            "all routes available, BLUETOOTH requested")
    }

    @Test
    fun wiredRequested_whenEverythingAvailable_isNotDowngraded() {
        val result = RouteResolver.resolve(AudioRoute.WIRED, allCaps())
        assertResolution(AudioRoute.WIRED, AudioRoute.WIRED, false, result,
            "all routes available, WIRED requested")
    }

    // ------------------------------------------- 5-7: walking the fallback chain

    @Test
    fun earpieceUnavailable_fallsToFirstFallback_speaker() {
        // EARPIECE chain is SPEAKER, WIRED, BLUETOOTH; only SPEAKER exists.
        val result = RouteResolver.resolve(AudioRoute.EARPIECE, caps(speaker = true))
        assertResolution(AudioRoute.EARPIECE, AudioRoute.SPEAKER, true, result,
            "EARPIECE requested, only speaker present")
    }

    @Test
    fun earpieceAndSpeakerUnavailable_fallsToSecondFallback_wired() {
        // Both the requested route and the first fallback are missing; WIRED is second in the chain.
        // BLUETOOTH is also present and must NOT win, because WIRED comes first in the chain.
        val result = RouteResolver.resolve(
            AudioRoute.EARPIECE,
            caps(speaker = false, wired = true, bluetooth = true),
        )
        assertResolution(AudioRoute.EARPIECE, AudioRoute.WIRED, true, result,
            "EARPIECE requested, speaker and earpiece absent, wired and bluetooth present")
    }

    @Test
    fun entireChainUnavailable_fallsBackToSpeakerAndIsDowngraded() {
        val result = RouteResolver.resolve(AudioRoute.EARPIECE, nothingCaps())
        assertResolution(AudioRoute.EARPIECE, AudioRoute.SPEAKER, true, result,
            "EARPIECE requested, nothing available at all")
    }

    // ------------------------------------------- 8-9: the asymmetric bluetooth/wired chains

    @Test
    fun bluetoothRequested_withOnlyWiredPresent_resolvesToWired() {
        val result = RouteResolver.resolve(AudioRoute.BLUETOOTH, caps(speaker = false, wired = true))
        assertResolution(AudioRoute.BLUETOOTH, AudioRoute.WIRED, true, result,
            "BLUETOOTH requested, only wired present")
    }

    @Test
    fun wiredRequested_withOnlyBluetoothPresent_resolvesToBluetooth() {
        val result = RouteResolver.resolve(
            AudioRoute.WIRED,
            caps(speaker = false, bluetooth = true),
        )
        assertResolution(AudioRoute.WIRED, AudioRoute.BLUETOOTH, true, result,
            "WIRED requested, only bluetooth present")
    }

    // ------------------------------------------- 10-11: the API-level gate on EARPIECE

    @Test
    fun earpieceRequested_withEarpieceButSdk25_isDowngraded() {
        val result = RouteResolver.resolve(
            AudioRoute.EARPIECE,
            caps(earpiece = true, speaker = true, sdkInt = 25),
        )
        assertResolution(AudioRoute.EARPIECE, AudioRoute.SPEAKER, true, result,
            "EARPIECE requested with hasEarpiece=true but sdkInt=25")
    }

    @Test
    fun earpieceRequested_withEarpieceAndSdk26_isNotDowngraded() {
        val result = RouteResolver.resolve(
            AudioRoute.EARPIECE,
            caps(earpiece = true, speaker = true, sdkInt = 26),
        )
        assertResolution(AudioRoute.EARPIECE, AudioRoute.EARPIECE, false, result,
            "EARPIECE requested with hasEarpiece=true and sdkInt=26")
    }

    // ------------------------------------------- 12: SPEAKER requested with no speaker

    @Test
    fun speakerRequested_whenSpeakerUnavailableAndNothingElse_isStillSpeakerAndDowngraded() {
        // Degenerate case: the fallback chain bottoms out at SPEAKER, so effective == requested.
        // The contract still requires downgraded = true here, otherwise the UI would silently
        // claim success while the device has no working output.
        val result = RouteResolver.resolve(AudioRoute.SPEAKER, nothingCaps())
        assertResolution(AudioRoute.SPEAKER, AudioRoute.SPEAKER, true, result,
            "SPEAKER requested, no speaker and nothing else available")
    }

    @Test
    fun speakerRequested_whenSpeakerUnavailable_fallsToFirstChainEntry_earpiece() {
        val result = RouteResolver.resolve(
            AudioRoute.SPEAKER,
            caps(earpiece = true, speaker = false, sdkInt = 26),
        )
        assertResolution(AudioRoute.SPEAKER, AudioRoute.EARPIECE, true, result,
            "SPEAKER requested, only earpiece present")
    }

    // ------------------------------------------- fallback policy is data-driven

    @Test
    fun fallbackPolicy_matchesTheContractChains() {
        val policy = RouteResolver.fallbacks
        assertEquals("policy must cover all four routes", AudioRoute.all.toSet(), policy.keys)
        assertEquals("EARPIECE chain",
            listOf(AudioRoute.SPEAKER, AudioRoute.WIRED, AudioRoute.BLUETOOTH),
            policy[AudioRoute.EARPIECE])
        assertEquals("SPEAKER chain",
            listOf(AudioRoute.EARPIECE, AudioRoute.WIRED, AudioRoute.BLUETOOTH),
            policy[AudioRoute.SPEAKER])
        assertEquals("BLUETOOTH chain",
            listOf(AudioRoute.WIRED, AudioRoute.SPEAKER, AudioRoute.EARPIECE),
            policy[AudioRoute.BLUETOOTH])
        assertEquals("WIRED chain",
            listOf(AudioRoute.BLUETOOTH, AudioRoute.SPEAKER, AudioRoute.EARPIECE),
            policy[AudioRoute.WIRED])
        policy.forEach { (route, chain) ->
            assertFalse("chain for $route must not contain the requested route itself",
                chain.contains(route))
            assertEquals("chain for $route must have three candidates", 3, chain.size)
        }
    }

    // ------------------------------------------- AudioRoute keys

    @Test
    fun audioRoute_all_isTheFourRoutesInContractOrder() {
        assertEquals(
            "AudioRoute.all must be EARPIECE, SPEAKER, BLUETOOTH, WIRED in that order",
            listOf(
                AudioRoute.EARPIECE,
                AudioRoute.SPEAKER,
                AudioRoute.BLUETOOTH,
                AudioRoute.WIRED,
            ),
            AudioRoute.all,
        )
        assertEquals("AudioRoute.all must contain four entries", 4, AudioRoute.all.size)
    }

    @Test
    fun audioRoute_keysAreTheContractStrings() {
        assertEquals("earpiece", AudioRoute.EARPIECE.key)
        assertEquals("speaker", AudioRoute.SPEAKER.key)
        assertEquals("bluetooth", AudioRoute.BLUETOOTH.key)
        assertEquals("wired", AudioRoute.WIRED.key)
    }

    @Test
    fun audioRoute_fromKey_roundTripsForEveryRoute() {
        AudioRoute.all.forEach { route ->
            assertSame(
                "fromKey(${route.key}) must round-trip to $route",
                route,
                AudioRoute.fromKey(route.key),
            )
        }
    }

    @Test
    fun audioRoute_fromKey_returnsNullForUnknownKey() {
        assertNull("fromKey(\"nonsense\") must be null", AudioRoute.fromKey("nonsense"))
        assertNull("fromKey(\"\") must be null", AudioRoute.fromKey(""))
        assertNull("fromKey(\"EARPIECE\") must be null — keys are lower case",
            AudioRoute.fromKey("EARPIECE"))
    }

    @Test
    fun audioRoute_fromKey_returnsNullForNull() {
        assertNull("fromKey(null) must be null", AudioRoute.fromKey(null))
    }

    // ------------------------------------------- RouteCapabilities

    @Test
    fun capabilities_unknown_supportsSpeakerButNotEarpiece() {
        assertTrue("UNKNOWN must report the speaker as available",
            RouteCapabilities.UNKNOWN.supports(AudioRoute.SPEAKER))
        assertFalse("UNKNOWN must not report the earpiece as available",
            RouteCapabilities.UNKNOWN.supports(AudioRoute.EARPIECE))
    }

    @Test
    fun capabilities_unknown_matchesTheContractDefaults() {
        assertEquals(
            "UNKNOWN must be (false, true, false, false, sdkInt = 0)",
            RouteCapabilities(
                hasEarpiece = false,
                hasSpeaker = true,
                hasBluetooth = false,
                hasWired = false,
                sdkInt = 0,
            ),
            RouteCapabilities.UNKNOWN,
        )
        assertFalse("UNKNOWN must not report bluetooth",
            RouteCapabilities.UNKNOWN.supports(AudioRoute.BLUETOOTH))
        assertFalse("UNKNOWN must not report wired",
            RouteCapabilities.UNKNOWN.supports(AudioRoute.WIRED))
    }

    @Test
    fun capabilities_supportsEachRouteIndependently() {
        val onlyWired = caps(speaker = false, wired = true)
        assertTrue("only-wired snapshot must support WIRED",
            onlyWired.supports(AudioRoute.WIRED))
        assertFalse("only-wired snapshot must not support SPEAKER",
            onlyWired.supports(AudioRoute.SPEAKER))
        assertFalse("only-wired snapshot must not support BLUETOOTH",
            onlyWired.supports(AudioRoute.BLUETOOTH))
        assertFalse("only-wired snapshot must not support EARPIECE",
            onlyWired.supports(AudioRoute.EARPIECE))

        val onlyBluetooth = caps(speaker = false, bluetooth = true)
        assertTrue("only-bluetooth snapshot must support BLUETOOTH",
            onlyBluetooth.supports(AudioRoute.BLUETOOTH))
        assertFalse("only-bluetooth snapshot must not support WIRED",
            onlyBluetooth.supports(AudioRoute.WIRED))
    }

    @Test
    fun capabilities_earpieceRequiresBothHardwareAndApiLevel() {
        assertTrue("earpiece on sdk 26 must be supported",
            caps(earpiece = true, sdkInt = 26).supports(AudioRoute.EARPIECE))
        assertFalse("earpiece on sdk 25 must not be supported",
            caps(earpiece = true, sdkInt = 25).supports(AudioRoute.EARPIECE))
        assertFalse("sdk 36 without earpiece hardware must not be supported",
            caps(earpiece = false, sdkInt = 36).supports(AudioRoute.EARPIECE))
    }

    // ------------------------------------------- RouteResolution

    @Test
    fun routeResolution_isAValueType() {
        val a = RouteResolution(AudioRoute.EARPIECE, AudioRoute.SPEAKER, true)
        val b = RouteResolution(AudioRoute.EARPIECE, AudioRoute.SPEAKER, true)
        assertEquals("equal field values must compare equal", a, b)
        assertEquals("equal field values must share a hash code", a.hashCode(), b.hashCode())
        assertNotNull("toString must be implemented", a.toString())
    }

    // ------------------------------------------- RouteState

    @Test
    fun routeState_initialValuesMatchTheContractDefaults() {
        assertEquals("requested must start at SPEAKER",
            AudioRoute.SPEAKER, RouteState.requested.value)
        assertEquals("active must start at SPEAKER",
            AudioRoute.SPEAKER, RouteState.active.value)
        assertEquals("serviceRunning must start false",
            false, RouteState.serviceRunning.value)
        assertEquals("capabilities must start at UNKNOWN",
            RouteCapabilities.UNKNOWN, RouteState.capabilities.value)
    }

    @Test
    fun routeState_publishFunctionsUpdateTheFlows() {
        val published = caps(earpiece = true, bluetooth = true, sdkInt = 36)

        RouteState.publishRequested(AudioRoute.BLUETOOTH)
        RouteState.publishActive(AudioRoute.WIRED)
        RouteState.publishServiceRunning(true)
        RouteState.publishCapabilities(published)

        assertEquals("publishRequested must update the requested flow",
            AudioRoute.BLUETOOTH, RouteState.requested.value)
        assertEquals("publishActive must update the active flow",
            AudioRoute.WIRED, RouteState.active.value)
        assertEquals("publishServiceRunning must update the serviceRunning flow",
            true, RouteState.serviceRunning.value)
        assertEquals("publishCapabilities must update the capabilities flow",
            published, RouteState.capabilities.value)
    }
}
