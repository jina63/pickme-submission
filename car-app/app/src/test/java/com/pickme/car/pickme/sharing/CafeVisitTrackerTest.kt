package com.pickme.car.pickme.sharing

import org.junit.Assert.*
import org.junit.Test

class CafeVisitTrackerTest {
    @Test fun onlyConfirmedCafeBeforeFriendIsShared() {
        val tracker=CafeVisitTracker()
        tracker.update("카페",1,listOf("친구"),"친구")
        assertNull(tracker.phase)
        tracker.update("카페",1,listOf("친구","카페"),"친구")
        assertNull(tracker.phase)
        tracker.update("카페",1,listOf("카페","친구"),"친구")
        assertEquals("before_pickup",tracker.phase)
        tracker.update("카페",1,listOf("친구"),"친구")
        assertNull(tracker.phase) // Removal alone does not prove arrival.
    }
    @Test fun arrivalAndRemainingRouteConfirmPassingCafe() {
        val tracker=CafeVisitTracker()
        tracker.update("카페",1,listOf("카페","친구"),"친구")
        tracker.onArrival("다른 곳")
        assertEquals("before_pickup",tracker.phase)
        tracker.onArrival("카페")
        assertEquals("at_cafe",tracker.phase)
        tracker.update("카페",1,listOf("친구"),"친구")
        assertEquals("passed",tracker.phase)
        tracker.update("새 카페",2,listOf("친구"),"친구")
        assertNull(tracker.phase)
    }
    @Test fun cancelledRouteMissingPickupAndAmbiguousNamesClearCafe() {
        val tracker=CafeVisitTracker()
        tracker.update("카페",1,listOf("카페","친구"),"친구")
        tracker.reset()
        assertNull(tracker.phase)
        tracker.update("카페",1,listOf("카페"),"친구")
        assertNull(tracker.phase)
        tracker.update("카페",1,listOf("카페","카페","친구"),"친구")
        assertNull(tracker.phase)
    }
}
