package com.pickme.car.core.navi

import ai.pleos.playground.navi.data.WaypointInfo
import org.junit.Assert.*
import org.junit.Test

class StoreRouteConfirmationTest {
    private val store = ExpectedStoreWaypoint(37.4, 127.1, "픽업카페 B")
    private val friend = ExpectedStoreWaypoint(37.5, 127.2, "친구 탑승 장소")
    private fun point(expected: ExpectedStoreWaypoint, noCoordinates: Boolean = false) = WaypointInfo(
        name = expected.name, address = "", latitude = if (noCoordinates) 0.0 else expected.lat,
        longitude = if (noCoordinates) 0.0 else expected.lng, displayLatitude = null, displayLongitude = null,
        remainDistance = 0, remainTime = 0, poiId = "", poiSubId = "", poiProvider = "",
    )

    @Test fun timeoutMustNotReleaseOrderAndLateSuccessMustBeIgnored() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, friend) { results.add(it) }
        confirmation.onTimeout()
        confirmation.onWaypoints(listOf(point(store), point(friend)))
        confirmation.onTimeout()
        assertEquals(1, results.size)
        assertNotNull("Timeout must not schedule an order", results.single())
    }

    @Test fun emptyListOrSingleUnrelatedWaypointCannotConfirmStore() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, null) { results.add(it) }
        confirmation.onWaypoints(emptyList())
        confirmation.onWaypoints(listOf(point(ExpectedStoreWaypoint(37.6, 127.3, "다른 매장"))))
        assertTrue("A waypoint count is not an identity confirmation", results.isEmpty())
        confirmation.onTimeout()
        assertNotNull(results.single())
    }

    @Test fun wrongCoordinatesCannotBeAcceptedJustBecauseNameMatches() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, null) { results.add(it) }
        confirmation.onWaypoints(listOf(point(store.copy(lat = 37.9))))
        assertTrue(results.isEmpty())
    }

    @Test fun missingFriendOrReversedVisitOrderKeepsOrderBlocked() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, friend) { results.add(it) }
        confirmation.onWaypoints(listOf(point(store)))
        confirmation.onWaypoints(listOf(point(friend), point(store)))
        assertTrue(results.isEmpty())
        confirmation.onWaypoints(listOf(point(store), point(friend)))
        assertEquals(listOf<String?>(null), results)
    }

    @Test fun validStoreOnlyRouteCanConfirmWithoutFriend() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, null) { results.add(it) }
        confirmation.onWaypoints(listOf(point(store)))
        assertEquals(listOf<String?>(null), results)
    }

    @Test fun emulatorNameReadbackRequiresActualMatchingNamesInOrder() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, friend) { results.add(it) }
        confirmation.onWaypoints(listOf(point(friend, true), point(store, true)))
        assertTrue(results.isEmpty())
        confirmation.onWaypoints(listOf(point(store, true), point(friend, true)))
        assertEquals(listOf<String?>(null), results)
    }

    @Test fun sdkFailurePreventsLaterReadbackFromReleasingOrder() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, null) { results.add(it) }
        confirmation.fail("SDK error")
        confirmation.onWaypoints(listOf(point(store)))
        assertEquals(listOf("SDK error"), results)
    }

    @Test fun duplicateReadbacksAndTimeoutAfterSuccessCompleteOnlyOnce() {
        val results = mutableListOf<String?>()
        val confirmation = StoreRouteConfirmation(store, friend) { results.add(it) }
        repeat(2) { confirmation.onWaypoints(listOf(point(store), point(friend))) }
        confirmation.onTimeout()
        assertEquals(listOf<String?>(null), results)
    }
}
