package com.pickme.car.core.navi

import ai.pleos.playground.navi.constants.WaypointIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StoreOrderTest {
    @Test fun friendAlreadyFirstIsMovedBehindTheCafe() {
        // 실제 에뮬레이터에서 본 상황: 친구가 FIRST 자리로 당겨져 있음
        assertEquals(WaypointIndex.FIRST, friendSlotToReorder(listOf("수서 주차장"), "수서 주차장", "픽업카페 B"))
        assertEquals(WaypointIndex.FIRST, friendSlotToReorder(listOf("수서 주차장", "픽업카페 B"), "수서 주차장", "픽업카페 B"))
    }
    @Test fun nothingToDoWhenCafeIsAlreadyFirstOrNoFriend() {
        assertNull(friendSlotToReorder(listOf("픽업카페 B", "수서 주차장"), "수서 주차장", "픽업카페 B"))
        assertNull(friendSlotToReorder(emptyList(), "수서 주차장", "픽업카페 B"))
        assertNull(friendSlotToReorder(listOf("다른 경유지"), "수서 주차장", "픽업카페 B"))
        assertNull(friendSlotToReorder(listOf("수서 주차장"), null, "픽업카페 B"))
    }
    @Test fun friendInSecondSlotBehindAnotherStopIsRemovedFromSecond() {
        assertEquals(WaypointIndex.SECOND, friendSlotToReorder(listOf("다른 경유지", "수서 주차장"), "수서 주차장", "픽업카페 B"))
    }
    @Test fun orderRequiresReadbackWithCafeFirstAndFriendSecond() {
        assertTrue(storeRouteConfirmed(0, 1, true))
        assertTrue(storeRouteConfirmed(0, -1, false))
        // Missing cafe, reversed order, or lost friend must never release the order.
        assertFalse(storeRouteConfirmed(-1, 0, true))
        assertFalse(storeRouteConfirmed(1, 0, true))
        assertFalse(storeRouteConfirmed(0, -1, true))
        assertFalse(storeRouteConfirmed(-1, -1, false))
        assertFalse(storeRouteConfirmed(1, -1, false))
    }
}
