package com.pickme.car.core.navi

import ai.pleos.playground.navi.data.WaypointInfo

internal data class ExpectedStoreWaypoint(val lat: Double, val lng: Double, val name: String)

/** Releases an order only after its store and expected friend are read back in route order. */
internal class StoreRouteConfirmation(
    private val store: ExpectedStoreWaypoint,
    private val friend: ExpectedStoreWaypoint?,
    private val onResult: (String?) -> Unit,
) {
    private var finished = false
    var lastSeen: String = "응답 없음"
        private set

    fun onWaypoints(waypoints: List<WaypointInfo>) {
        if (finished) return
        lastSeen = waypoints.joinToString(prefix = "${waypoints.size}개 [", postfix = "]") { it.name }
        val storeIndex = waypoints.indexOfFirst { matches(it, store) }
        val friendIndex = friend?.let { expected -> waypoints.indexOfFirst { matches(it, expected) } } ?: -1
        if (storeRouteConfirmed(storeIndex, friendIndex, friend != null)) finish(null)
    }

    fun onTimeout() = fail("카페 경유지와 방문 순서를 확인하지 못했어요. 주문은 예약되지 않았어요. 내비 경로를 확인하고 다시 시도해 주세요.")

    fun fail(message: String) = finish(message)

    private fun matches(point: WaypointInfo, expected: ExpectedStoreWaypoint): Boolean =
        reportedWaypointMatch(point, expected.lat, expected.lng, expected.name) != ReportedWaypointMatch.NONE

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        onResult(error)
    }
}
