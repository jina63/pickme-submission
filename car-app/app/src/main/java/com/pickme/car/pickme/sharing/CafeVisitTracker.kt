package com.pickme.car.pickme.sharing

/**
 * 딱픽업 카페 경유 상태를 판단한다: 들르기 전 → 카페 도착 → 지나감.
 * 현재 경로에서 친구 탑승 장소보다 앞에 확인된 카페만 친구 웹에 알린다.
 */
internal class CafeVisitTracker {
    private var selection: Long? = null
    private var confirmed = false
    private var arrived = false
    private var name = ""
    var phase: String? = null
        private set
    fun update(storeName: String?, selectionId: Long?, waypoints: List<String>, pickupName: String) {
        if (selection != selectionId) { reset(); selection = selectionId }
        name = storeName.orEmpty()
        val friend = waypoints.indexOfLast { it == pickupName }
        val cafe = waypoints.indexOf(name)
        if (name.isBlank() || name.length > 120 || name == pickupName || friend < 0 || waypoints.count { it == name } > 1) { phase = null; confirmed = false; arrived = false; return }
        if (cafe in 0 until friend) {
            confirmed = true
            phase = if (arrived) "at_cafe" else "before_pickup"
        } else if (cafe < 0 && confirmed && arrived) {
            phase = "passed"
        } else { phase = null; confirmed = false; arrived = false }
    }
    fun onArrival(arrivedName: String) {
        if (confirmed && phase == "before_pickup" && arrivedName == name) { arrived = true; phase = "at_cafe" }
    }
    fun reset() { confirmed = false; arrived = false; phase = null }
}
