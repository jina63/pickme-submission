package com.pickme.car.pickup.domain

import com.pickme.car.core.model.Store
import com.pickme.car.core.navi.TripSnapshot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 추천 계산에 쓰는 후보 매장 한 곳. 시간 단위는 모두 '분'. */
data class Candidate(
    val storeId: String,
    val name: String,
    val detourMin: Int,   // 경로가 얼마나 늘어나는가 (0이면 경로 위)
    val travelMin: Int,   // 지금 위치에서 매장까지 걸리는 시간
    val prepMin: Int,     // 음료 준비 시간
    val lat: Double = 0.0,   // 매장 위도
    val lng: Double = 0.0,   // 매장 경도
    val totalMin: Int = 0,   // 차 → 목적지 직행 시간. 모르면 0 (우회 상한 계산에만 쓴다)
) {
    /** 도착했는데 음료가 아직 안 나와서 기다려야 하는 시간 */
    val waitMin: Int get() = maxOf(0, prepMin - travelMin)

    /** 이동시간과 준비시간의 차이(절댓값). 0에 가까울수록 막 만든 음료를 받는다. */
    val gapMin: Int get() = abs(travelMin - prepMin)

    /** 도착 전에 음료가 준비되는가 (기다림 없음) */
    val noWait: Boolean get() = travelMin >= prepMin
}

data class Recommendation(val pick: Candidate, val reason: String)

object StoreRecommender {

    /** 우회 1분은 대기 1.5분만큼 손해로 본다. (우회는 실제로 더 달리는 시간이라 더 크게 친다) */
    private const val DETOUR_WEIGHT = 1.5

    /** 이 시간(분)까지의 대기는 수령·확인에 걸리는 시간으로 보고 0으로 계산한다. */
    private const val WAIT_FREE_MIN = 2

    /** 우회 상한: max(이 값, 직행 시간의 비율) 분을 넘으면 후보에서 뺀다. */
    private const val MAX_DETOUR_MIN = 10.0   // 5.0 -> 10.0
    private const val MAX_DETOUR_RATIO = 0.35  //0.2 -> 0.35

    /**
     * 점수 = 1.5 × 우회(분) + 대기(분, 2분까지는 0)  → 낮을수록 좋다.
     * - 우회가 상한(max(10분, 직행 시간의 35%))을 넘는 매장은 제외한다.
     *   모두 넘으면 그중 점수가 가장 낮은 매장을 고르고 "많이 돌아가요"를 표시한다.
     * - 점수가 같으면 실제 대기가 짧은 매장, 그래도 같으면 더 빨리 도착하는 매장.
     */
    fun recommend(candidates: List<Candidate>): Recommendation? {
        val usable = candidates.filter { hasLocation(it) }      // 좌표 없는 매장은 경유지로 못 넣으니 뺀다
        if (usable.isEmpty()) return null

        val tripMin = usable.maxOf { it.totalMin }
        val maxDetour = max(MAX_DETOUR_MIN, tripMin * MAX_DETOUR_RATIO)

        val withinLimit = usable.filter { it.detourMin <= maxDetour }
        val pool = withinLimit.ifEmpty { usable }
        val tooFar = withinLimit.isEmpty()

        val best = pool.minWithOrNull(
            compareBy<Candidate> { score(it) }
                .thenBy { it.waitMin }
                .thenBy { it.travelMin }
        ) ?: return null

        val waitNote = if (best.waitMin > 0) " · 도착 후 ${best.waitMin}분 대기" else " · 기다림 없음"
        val farNote = if (tooFar) " · 많이 돌아가요" else ""
        val reason = "${best.name} 추천 · 우회 ${best.detourMin}분 · 이동 ${best.travelMin}분 · 준비 ${best.prepMin}분$waitNote$farNote"
        return Recommendation(best, reason)
    }

    /** 좌표가 있는 매장인가. (0,0)이나 비어 있는 값은 제외한다. Firebase에 lat/lng가 없으면 0.0이 들어온다. */
    private fun hasLocation(c: Candidate): Boolean =
        c.lat.isFinite() && c.lng.isFinite() && !(c.lat == 0.0 && c.lng == 0.0)

    /** 낮을수록 좋은 점수. */
    private fun score(c: Candidate): Double =
        DETOUR_WEIGHT * c.detourMin + max(0, c.waitMin - WAIT_FREE_MIN)

    /** Firebase를 못 읽을 때 쓰는 기본 후보. Firebase stores 값과 같게 맞춰 둔다. */
    fun sampleCandidates(): List<Candidate> = listOf(
        Candidate("store-a", "픽업카페 A (수원 인계동)", detourMin = 0, travelMin = 12, prepMin = 4, lat = 37.2616466, lng = 127.0318618),
        Candidate("store-b", "픽업카페 B (성남 판교)", detourMin = 0, travelMin = 7, prepMin = 9, lat = 37.3947237, lng = 127.1115312),
        Candidate("store-c", "픽업카페 C (고양 일산)", detourMin = 4, travelMin = 5, prepMin = 15, lat = 37.659711, lng = 126.7732651),
    )

    /**
     * Firebase 매장(이름·좌표·준비시간)을 후보로 바꾼다.
     * 우회·이동 시간은 같은 id의 기존 고정값을 쓴다. (내비 계산값은 withTrips로 덮어쓴다)
     */
    fun fromStores(stores: List<Store>): List<Candidate> {
        val base = sampleCandidates().associateBy { it.storeId }
        return stores.map { s ->
            val b = base[s.id]
            Candidate(
                storeId = s.id,
                name = s.name.ifBlank { b?.name ?: s.id },
                detourMin = b?.detourMin ?: 0,
                travelMin = b?.travelMin ?: 10,
                prepMin = s.prepMin,
                lat = s.lat,
                lng = s.lng,
            )
        }
    }

    /**
     * 내비가 알려 준 실제 경로 시간으로 이동·우회시간을 다시 계산한다.
     * - 이동시간 = 차 → 매장 (내비 값. 응답이 없으면 직선거리로 근사)
     * - 매장 → 목적지는 내비로 못 구하니, 차 → 목적지의 실제 거리/시간으로 구한
     *   속도와 도로 보정 비율을 직선거리에 적용해 근사한다.
     * - 우회시간 = 차 → 매장 + 매장 → 목적지 − 차 → 목적지
     * - totalMin = 차 → 목적지 직행 시간 (우회 상한 계산용)
     */
    fun withTrips(base: List<Candidate>, t: TripSnapshot): List<Candidate> {
        val straightCarDest = haversineM(t.carLat, t.carLng, t.destLat, t.destLng)
        val roadFactor = if (straightCarDest > 500) {
            (t.destDistM / straightCarDest).coerceIn(1.0, 2.5)
        } else {
            1.3
        }
        val speedMps = t.destDistM.toDouble() / t.destEtaSec   // 미터/초
        val totalMin = (t.destEtaSec / 60.0).roundToInt()

        return base.map { c ->
            if (c.lat == 0.0 && c.lng == 0.0) return@map c
            val toStoreSec = t.storeEtaSec[c.storeId]?.takeIf { it > 0 }?.toDouble()
                ?: (haversineM(t.carLat, t.carLng, c.lat, c.lng) * roadFactor / speedMps)
            val storeToDestSec = haversineM(c.lat, c.lng, t.destLat, t.destLng) * roadFactor / speedMps
            val detourSec = toStoreSec + storeToDestSec - t.destEtaSec
            c.copy(
                travelMin = max(1, (toStoreSec / 60.0).roundToInt()),
                detourMin = max(0, (detourSec / 60.0).roundToInt()),
                totalMin = totalMin,
            )
        }
    }

    /** 두 좌표 사이 직선거리(미터). */
    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}