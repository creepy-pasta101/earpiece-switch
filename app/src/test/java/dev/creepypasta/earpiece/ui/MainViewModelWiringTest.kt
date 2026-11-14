package dev.creepypasta.earpiece.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.creepypasta.earpiece.data.RoutePreferences
import dev.creepypasta.earpiece.model.AudioRoute
import dev.creepypasta.earpiece.model.RouteCapabilities
import dev.creepypasta.earpiece.service.AudioRouteService
import dev.creepypasta.earpiece.state.RouteFailure
import dev.creepypasta.earpiece.state.RouteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression cover for the wiring between the route cards and the audio switch.
 *
 * The reported bug is that tapping a route card does nothing at all: the UI renders
 * and no audio changes. Rendering is covered elsewhere, so this test asserts the only
 * thing that distinguishes "wired" from "not wired" — that a selection actually hands
 * an intent to [AudioRouteService], with the route it was asked for.
 *
 * [MainViewModel.state] is a `stateIn(WhileSubscribed)` flow and therefore cold, so it
 * is deliberately not asserted on here: the source of truth is [RouteState] plus the
 * recorded started service, which is exactly the wiring under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelWiringTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // The preference file outlives a single test in the same JVM, so start clean.
        clearPreferences()
        // A fresh view model observes the platform audio devices; nothing else needs
        // to be torn down between tests because the singleton is reset in tearDown.
    }

    @After
    fun tearDown() {
        // RouteState is a process-wide singleton shared with the service, so it has to
        // be put back to its defaults or one test's capabilities leak into the next.
        RouteState.publishRequested(AudioRoute.SPEAKER)
        RouteState.publishActive(AudioRoute.SPEAKER)
        RouteState.publishServiceRunning(false)
        RouteState.publishCapabilities(RouteCapabilities.UNKNOWN)
        RouteState.consumeOutcome()
        clearPreferences()
    }

    @Test
    fun `selecting an available route starts the foreground service with the right action and extra`() {
        val viewModel = MainViewModel(app)
        // The view model seeds capabilities from the platform in init, so publish the
        // forced set afterwards: everything must look available for this test.
        RouteState.publishCapabilities(
            RouteCapabilities(
                hasEarpiece = true,
                hasSpeaker = true,
                hasBluetooth = true,
                hasWired = true,
                sdkInt = 34,
            ),
        )

        viewModel.select(AudioRoute.EARPIECE)

        val started = shadowOf(app).nextStartedService
        assertNotNull("Selecting a route must start AudioRouteService", started)
        val intent = checkNotNull(started)

        assertEquals(
            AudioRouteService::class.java.name,
            intent.component?.className,
        )
        assertEquals(AudioRouteService.ACTION_APPLY, intent.action)
        assertEquals("earpiece", intent.getStringExtra(AudioRouteService.EXTRA_ROUTE))
    }

    @Test
    fun `selecting an unavailable route reports it instead of starting the service`() {
        val viewModel = MainViewModel(app)
        // Only the speaker exists: a Bluetooth request cannot be honoured.
        RouteState.publishCapabilities(
            RouteCapabilities(
                hasEarpiece = false,
                hasSpeaker = true,
                hasBluetooth = false,
                hasWired = false,
                sdkInt = 34,
            ),
        )

        viewModel.select(AudioRoute.BLUETOOTH)

        assertNull(
            "An unavailable route must not start the service",
            shadowOf(app).nextStartedService,
        )

        val outcome = RouteState.outcome.value
        assertNotNull("An unavailable route must publish an outcome", outcome)
        val reported = checkNotNull(outcome)
        assertEquals(AudioRoute.BLUETOOTH, reported.requested)
        assertNull("Nothing was applied, so applied must be null", reported.applied)
        assertEquals(RouteFailure.NOT_AVAILABLE, reported.failure)

        viewModel.consumeOutcome()
        assertNull("Consuming the outcome must clear it", RouteState.outcome.value)
    }

    @Test
    fun `requested route is persisted and published`() {
        val viewModel = MainViewModel(app)
        RouteState.publishCapabilities(
            RouteCapabilities(
                hasEarpiece = true,
                hasSpeaker = true,
                hasBluetooth = true,
                hasWired = true,
                sdkInt = 34,
            ),
        )

        viewModel.select(AudioRoute.SPEAKER)

        assertEquals(AudioRoute.SPEAKER, RoutePreferences(app).requestedRoute)
        assertEquals(AudioRoute.SPEAKER, RouteState.requested.value)
    }

    private fun clearPreferences() {
        app.getSharedPreferences("route_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
