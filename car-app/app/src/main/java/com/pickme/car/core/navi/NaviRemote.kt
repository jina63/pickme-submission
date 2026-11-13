package com.pickme.car.core.navi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** Coordinate-only pickup: no fabricated POI ID, no changes to FIRST or final destination. */
internal fun friendWaypoint(lat: Double, lng: Double, name: String): RequestWaypointInfo {
    require(lat.isFinite() && lat in -85.0..85.0 && lng.isFinite() && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)) { "탑승 위치 좌표를 다시 확인해 주세요." }
    require(name.isNotBlank() && name.length <= 120) { "탑승 장소 이름을 확인해 주세요." }
    return RequestWaypointInfo(latitude=lat, longitude=lng, poiName=name.trim(), waypointIndex=WaypointIndex.SECOND, poiId="", address="", poiSubId="", poiProvider="")
}

/** Navigation may change the snapped entrance coordinate while keeping the same POI. */
internal fun sameNavigationDestination(before: DestinationInfo, after: DestinationInfo): Boolean {
    if(before.poiId.isNotBlank() && after.poiId.isNotBlank()) {
        return before.poiId==after.poiId &&
                (before.poiProvider.isNullOrBlank() || after.poiProvider.isNullOrBlank() || before.poiProvider==after.poiProvider)
    }
    return before.name==after.name && kotlin.math.abs(before.latitude-after.latitude)<0.00002 && kotlin.math.abs(before.longitude-after.longitude)<0.00002
}

internal enum class ReportedWaypointMatch { COORDINATE, NAME_ONLY, NONE }

internal fun reportedWaypointMatch(point: WaypointInfo, lat: Double, lng: Double, name: String): ReportedWaypointMatch {
    val coordinates=mutableListOf(point.latitude to point.longitude)
    if(point.displayLatitude!=null && point.displayLongitude!=null) coordinates.add(point.displayLatitude!! to point.displayLongitude!!)
    val usable=coordinates.filter { (a,b)->a.isFinite() && b.isFinite() && a in -85.0..85.0 && b in -180.0..180.0 && (a!=0.0 || b!=0.0) }
    if(usable.any{(a,b)->kotlin.math.abs(a-lat)<0.00002 && kotlin.math.abs(b-lng)<0.00002})return ReportedWaypointMatch.COORDINATE
    if(usable.isEmpty() && point.name==name)return ReportedWaypointMatch.NAME_ONLY
    return ReportedWaypointMatch.NONE
}

/**
 * Pleos fills the first empty slot: a friend added as SECOND moves up to FIRST, and a store
 * added later lands after the friend. Returns the friend's slot to clear first (null = just add).
 */
internal fun friendSlotToReorder(waypointNames: List<String>, friendName: String?, storeName: String): WaypointIndex? {
    if (friendName.isNullOrBlank()) return null
    val friend = waypointNames.indexOfLast { it == friendName }
    if (friend < 0) return null
    val store = waypointNames.indexOf(storeName)
    if (store in 0 until friend) return null
    return when (friend) { 0 -> WaypointIndex.FIRST; 1 -> WaypointIndex.SECOND; else -> null }
}

internal fun storeRouteConfirmed(storeIndex: Int, friendIndex: Int, expectsFriend: Boolean): Boolean =
    storeIndex == 0 && (!expectsFriend || friendIndex == 1)

enum class StopType { STORE, FRIEND }

/** 경로 시간을 물어볼 지점 하나. key로 결과를 찾는다. */
class TripPoint(
    val key: String,
    val lat: Double,
    val lng: Double,
    val name: String,
    val poiId: String = "pickme-store",   // 가짜 매장이라 임시 값
    val poiSubId: String? = null,
)

/** 내비가 알려 준 실제 경로 시간. 시간은 초, 거리는 미터. */
class TripSnapshot(
    val carLat: Double,
    val carLng: Double,
    val destLat: Double,
    val destLng: Double,
    val destEtaSec: Int,
    val destDistM: Int,
    val storeEtaSec: Map<String, Int>,
)

object NaviRemote {
    /** Last requested store; consumers must verify it against current Pleos waypoints. */
    data class SelectedStore(val name: String, val latitude: Double, val longitude: Double, val requestedAt: Long)
    @Volatile var selectedStore: SelectedStore? = null
        private set

    private const val TAG = "NaviRemote"
    private const val DEST_KEY = "__dest__"

    private class StoreStop(val lat: Double, val lng: Double, val name: String, val onResult: (String?) -> Unit) {
        private var finished = false
        fun finish(error: String?) { if (!finished) { finished = true; onResult(error) } }
    }
    private class StoreConfirmation(val store: StoreStop, val readback: StoreRouteConfirmation)
    private var storeConfirmation: StoreConfirmation? = null
    private val storeConfirmationTimeout = Runnable {
        val pending = storeConfirmation ?: return@Runnable
        Log.w(TAG, "카페 경유지 확인 시간 초과: ${pending.readback.lastSeen}")
        pending.readback.onTimeout()
    }
    private val readStoreConfirmation: Runnable = object : Runnable {
        override fun run() {
            if (storeConfirmation == null) return
            try {
                helper?.getWaypointInfo()
                main.postDelayed(this, 1500)   // 응답이 안 와도 1.5초 뒤 다시 묻는다 (응답이 오면 onWayPointInfo가 이 예약을 지우고 0.5초로 다시 건다)
            } catch (e: Exception) {
                Log.e(TAG, "경유지 목록 다시 읽기 실패: ${e.message}")
                storeConfirmation?.readback?.fail("카페 경유지를 확인하지 못했어요. 주문은 예약되지 않았어요.")
            }
        }
    }

    /** Only a matching waypoint-list readback may release the scheduled order. */
    private fun completeStoreConfirmation(c: StoreConfirmation, error: String?) {
        if (storeConfirmation !== c) return
        storeConfirmation = null
        main.removeCallbacks(storeConfirmationTimeout)
        main.removeCallbacks(readStoreConfirmation)
        val stop = c.store
        if (error == null) {
            Log.d(TAG, "카페 경유지와 방문 순서를 목록에서 확인했어요.")
            selectedStore = SelectedStore(stop.name, stop.lat, stop.lng, android.os.SystemClock.elapsedRealtime())
        }
        stop.finish(error)
    }
    /** Last friend stop this app added, so a later cafe can be put in front of it. */
    private class FriendStop(val lat: Double, val lng: Double, val name: String)
    @Volatile private var friendStop: FriendStop? = null
    /** Waiting for the current waypoint list before inserting the store. */
    private var storeAwaitingWaypoints: Pair<DestinationInfo, StoreStop>? = null
    private val waypointWaitTimeout = Runnable {
        val (_, store) = storeAwaitingWaypoints ?: return@Runnable
        storeAwaitingWaypoints = null
        store.finish("경유지 순서를 확인하지 못했어요. 다시 시도해 주세요.")
    }

    /** 경로 시간 계산 한 번의 작업 상태. */
    private class TripJob(val points: List<TripPoint>, val callback: (TripSnapshot?) -> Unit) {
        var car: CurrentLocationInfo? = null
        var dest: DestinationInfo? = null
        var started = false
        val queue = ArrayDeque<TripPoint>()
        var current: TripPoint? = null
        val results = mutableMapOf<String, TripInfo>()
    }

    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var connected = false
    private var pendingStore: StoreStop? = null
    private var tripJob: TripJob? = null
    private var routeChangedListener: (() -> Unit)? = null
    private var lastTripFinishedAt = 0L

    private val giveUp = Runnable {
        val store = pendingStore ?: return@Runnable
        pendingStore = null
        store.finish("목적지를 확인하지 못했어요. 내비에서 목적지를 먼저 설정해 주세요.")
    }

    // 지점 하나의 응답이 4초 안에 안 오면 건너뛴다
    private val tripStepTimeout = Runnable {
        val job = tripJob ?: return@Runnable
        Log.w(TAG, "경로 시간 응답이 없어 건너뜀: ${job.current?.name}")
        job.current = null
        nextTrip(job)
    }

    // 전체 계산이 15초를 넘기면 포기하고 기본값을 쓰게 한다
    private val tripOverallTimeout = Runnable {
        if (tripJob != null) {
            Log.w(TAG, "경로 시간 계산 시간 초과")
            finishTrip(null)
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            main.post {
                val job = tripJob
                if (job != null && job.dest == null) {
                    job.dest = destinationInfo
                    startTripIfReady(job)
                    return@post
                }
                val store = pendingStore ?: return@post
                main.removeCallbacks(giveUp)
                pendingStore = null
                sendRoute(destinationInfo, store)
            }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            main.post {
                val job = tripJob ?: return@post
                if (job.car == null) {
                    job.car = currentLocationInfo
                    startTripIfReady(job)
                }
            }
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            main.post {
                val job = tripJob ?: return@post
                val cur = job.current ?: return@post
                main.removeCallbacks(tripStepTimeout)
                job.results[cur.key] = tripInfo
                job.current = null
                Log.d(TAG, "경로 시간: ${cur.name} ${tripInfo.eta}초, ${tripInfo.distance}m")
                nextTrip(job)
            }
        }

        override fun onWayPointInfo(waypoints: List<WaypointInfo>) {
            main.post {
                val confirmation = storeConfirmation
                if (confirmation != null) {
                    confirmation.readback.onWaypoints(waypoints)
                    if (storeConfirmation === confirmation) {
                        main.removeCallbacks(readStoreConfirmation)
                        main.postDelayed(readStoreConfirmation, 500)
                    }
                    return@post
                }
                val (dest, store) = storeAwaitingWaypoints ?: return@post
                storeAwaitingWaypoints = null
                main.removeCallbacks(waypointWaitTimeout)
                val names = waypoints.map { it.name }
                Log.d(TAG, "매장 추가 전 경유지 순서: $names")
                insertStore(dest, store, friendSlotToReorder(names, friendStop?.name, store.name), friendStop?.takeIf { it.name in names })
            }
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            main.post {
                // An ADD event only acknowledges a request; it does not prove identity/order.
                if (storeConfirmation != null) {
                    main.removeCallbacks(readStoreConfirmation)
                    main.postDelayed(readStoreConfirmation, 500)
                }
            }
        }

        override fun onRoutingCompleted() {
            Log.d(TAG, "경로 요청 처리 완료")
            main.post { notifyRouteChanged("onRoutingCompleted") }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            main.post { notifyRouteChanged("onRouteStarted") }
        }

        override fun onRouteCancelled() {
            main.post { notifyRouteChanged("onRouteCancelled") }
        }

        override fun onDestinationChanged() {
            main.post { notifyRouteChanged("onDestinationChanged") }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.e(TAG, "NaviHelper 오류: $errorCode")
            main.post {
                if (pendingStore != null || storeAwaitingWaypoints != null || storeConfirmation != null) cancelStoreRequest()
            }
        }
    }

    private var screenOwner = false
    private var sharingOwner = false
    private var sharingReady: ((NaviHelper) -> Unit)? = null
    private var sharingFailed: (() -> Unit)? = null

    /** Order screen and live sharing reuse one SDK connection (callbacks are package-scoped). */
    fun init(context: Context) {
        screenOwner = true
        ensureConnected(context)
    }

    fun acquireForSharing(context: Context, onReady: (NaviHelper) -> Unit, onFailed: () -> Unit) {
        sharingOwner = true
        sharingReady = onReady
        sharingFailed = onFailed
        if (connected) {
            sharingReady = null
            sharingFailed = null
            helper?.let(onReady)
        } else ensureConnected(context)
    }

    private fun ensureConnected(context: Context) {
        if (helper != null) return
        val h = NaviHelper(context.applicationContext)
        helper = h
        h.initialize(
            onConnected = {
                main.post {
                    if (helper !== h) return@post
                    connected = true
                    h.addListener(listener)
                    Log.d(TAG, "Pleos 내비 연결됨")
                    val ready = sharingReady
                    sharingReady = null
                    sharingFailed = null
                    if (sharingOwner) ready?.invoke(h)
                    if (pendingStore != null) {
                        try { h.getDestinationInfo() } catch (e: Exception) { Log.e(TAG, "목적지 요청 실패: ${e.message}") }
                    }
                }
            },
            onFailed = { error -> main.post {
                if (helper !== h) return@post
                Log.e(TAG, "Pleos 내비 연결 실패: ${error.message}")
                helper = null
                connected = false
                try { h.release() } catch (_: Exception) {}
                val failed = sharingFailed
                sharingReady = null
                sharingFailed = null
                failed?.invoke()
            } }
        )
    }

    /** Closing the order screen must not stop the foreground sharing service. */
    fun release() {
        screenOwner = false
        routeChangedListener = null
        cancelStoreRequest()
        tripJob = null
        main.removeCallbacks(giveUp)
        main.removeCallbacks(tripStepTimeout)
        main.removeCallbacks(tripOverallTimeout)
        if (!sharingOwner) disposeConnection()
    }

    fun releaseForSharing() {
        sharingOwner = false
        sharingReady = null
        sharingFailed = null
        if (!screenOwner) disposeConnection()
    }

    private fun disposeConnection() {
        helper?.let {
            if (connected) it.removeListener(listener)
            it.release()
        }
        helper = null
        connected = false
        selectedStore = null
        cancelStoreRequest()
        friendStop = null
        tripJob = null
        routeChangedListener = null
        main.removeCallbacks(giveUp)
        main.removeCallbacks(tripStepTimeout)
        main.removeCallbacks(tripOverallTimeout)
    }

    // ───────── 내비 경로 변경 알림 ─────────

    /** 내비 목적지·경로가 바뀌면 불린다(메인 스레드). 우리가 경로 시간을 묻는 중에는 불리지 않는다. */
    fun setRouteChangedListener(l: (() -> Unit)?) {
        routeChangedListener = l
    }

    private fun notifyRouteChanged(why: String) {
        if (tripJob != null) return                                          // 우리가 계산하는 중이면 무시
        if (System.currentTimeMillis() - lastTripFinishedAt < 2000) return   // 계산 직후의 이벤트는 무시
        Log.d(TAG, "내비 경로 변경 감지: $why")
        routeChangedListener?.invoke()
    }

    // ───────── 경로 시간 계산 (딱픽업 추천용) ─────────

    /**
     * 내비에게 "차량 현재 위치 → 각 지점" 예상 시간을 차례로 물어본다.
     * 차량 위치와 현재 목적지도 함께 읽는다. 결과는 callback(메인 스레드)으로 오고,
     * 내비가 응답하지 않거나 목적지가 없으면 null이 온다.
     */
    fun estimateTrips(points: List<TripPoint>, callback: (TripSnapshot?) -> Unit) {
        val h = helper
        if (h == null || !connected) {
            Log.w(TAG, "estimateTrips: 아직 내비 연결 전")
            callback(null)
            return
        }
        if (tripJob != null) {
            Log.w(TAG, "estimateTrips: 이미 계산 중")
            callback(null)
            return
        }
        tripJob = TripJob(points, callback)
        main.removeCallbacks(tripOverallTimeout)
        main.postDelayed(tripOverallTimeout, 15000)
        try {
            h.getCurrentLocationInfo()
            h.getDestinationInfo()
        } catch (e: Exception) {
            Log.e(TAG, "차량 위치·목적지 요청 실패: ${e.message}")
            finishTrip(null)
        }
    }

    private fun startTripIfReady(job: TripJob) {
        val car = job.car ?: return
        val dest = job.dest ?: return
        if (job.started) return
        job.started = true
        Log.d(TAG, "계산 시작: 차량(${car.latitude}, ${car.longitude}) 목적지 '${dest.name}'(${dest.latitude}, ${dest.longitude}) poiId='${dest.poiId}'")
        if (dest.name.isBlank() || dest.poiId.isBlank() ||
            (dest.latitude == 0.0 && dest.longitude == 0.0)) {
            Log.w(TAG, "내비에 목적지가 없어 경로 시간을 계산하지 않았어요.")
            finishTrip(null)
            return
        }
        job.queue.add(TripPoint(DEST_KEY, dest.latitude, dest.longitude, dest.name, dest.poiId, dest.poiSubId))
        job.queue.addAll(job.points)
        nextTrip(job)
    }

    private fun nextTrip(job: TripJob) {
        val next = job.queue.removeFirstOrNull()
        if (next == null) {
            finishTrip(buildSnapshot(job))
            return
        }
        val h = helper
        if (h == null) {
            finishTrip(null)
            return
        }
        job.current = next
        main.removeCallbacks(tripStepTimeout)
        main.postDelayed(tripStepTimeout, 4000)
        try {
            Log.d(TAG, "requestTrip 요청: ${next.name} (${next.lat}, ${next.lng})")
            h.requestTrip(TripRequest(next.lat, next.lng, next.poiId, next.name, next.poiSubId))
        } catch (e: Exception) {
            Log.e(TAG, "requestTrip 실패(${next.name}): ${e.message}")
            main.removeCallbacks(tripStepTimeout)
            job.current = null
            nextTrip(job)
        }
    }

    private fun buildSnapshot(job: TripJob): TripSnapshot? {
        val car = job.car ?: return null
        val dest = job.dest ?: return null
        val destTrip = job.results[DEST_KEY] ?: return null
        if (destTrip.eta <= 0 || destTrip.distance <= 0) return null
        val etas = job.results.filterKeys { it != DEST_KEY }.mapValues { it.value.eta }
        return TripSnapshot(
            carLat = car.latitude,
            carLng = car.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            destEtaSec = destTrip.eta,
            destDistM = destTrip.distance,
            storeEtaSec = etas,
        )
    }

    private fun finishTrip(snapshot: TripSnapshot?) {
        val job = tripJob ?: return
        tripJob = null
        lastTripFinishedAt = System.currentTimeMillis()
        main.removeCallbacks(tripStepTimeout)
        main.removeCallbacks(tripOverallTimeout)
        job.callback(snapshot)
    }

    // ───────── 매장 경유지 추가 ─────────

    private fun cancelStoreRequest() {
        val stores = listOfNotNull(pendingStore, storeAwaitingWaypoints?.second, storeConfirmation?.store)
        pendingStore = null
        storeAwaitingWaypoints = null
        storeConfirmation = null
        main.removeCallbacks(giveUp)
        main.removeCallbacks(waypointWaitTimeout)
        main.removeCallbacks(storeConfirmationTimeout)
        main.removeCallbacks(readStoreConfirmation)
        stores.forEach { it.finish("카페 경유지 확인이 취소됐어요. 주문은 예약되지 않았어요.") }
    }

    /** Calls onResult on the main thread: null only after waypoint coordinates/order are read back. */
    fun addStoreStop(lat: Double, lng: Double, name: String, onResult: (String?) -> Unit) {
        Log.d(TAG, "addStoreStop: $name ($lat, $lng)")
        if (!lat.isFinite() || !lng.isFinite() || lat !in -85.0..85.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0) || name.isBlank()) {
            onResult("카페 위치를 확인해 주세요.")
            return
        }
        if (pendingStore != null || storeAwaitingWaypoints != null || storeConfirmation != null) {
            onResult("이전 카페 경유지를 확인 중이에요. 잠시 후 다시 시도해 주세요.")
            return
        }
        pendingStore = StoreStop(lat, lng, name, onResult)
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, 8000)
        val h = helper
        if (h == null || !connected) {
            Log.w(TAG, "아직 연결 전. 연결되면 자동으로 이어서 보냅니다.")
            return
        }
        try {
            h.getDestinationInfo()   // 현재 목적지를 먼저 받아 와야 poiProvider를 복사할 수 있다
        } catch (e: Exception) {
            Log.e(TAG, "목적지 요청 실패: ${e.message}")
            val store = pendingStore
            pendingStore = null
            main.removeCallbacks(giveUp)
            store?.finish("내비 목적지를 요청하지 못했어요. 다시 시도해 주세요.")
        }
    }

    private fun sendRoute(dest: DestinationInfo, store: StoreStop) {
        Log.d(TAG, "현재 목적지: name='${dest.name}', lat=${dest.latitude}, lng=${dest.longitude}, poiId='${dest.poiId}', address='${dest.address}', provider=${dest.poiProvider}")
        if (dest.name.isBlank() || dest.poiId.isBlank() ||
            (dest.latitude == 0.0 && dest.longitude == 0.0)) {
            store.finish("내비에서 목적지를 먼저 설정해 주세요.")
            return
        }
        val h = helper ?: run { store.finish("내비 연결을 확인해 주세요."); return }

        // 매장은 친구보다 먼저 들러야 한다. 지금 경유지 순서를 먼저 받아 본 뒤 넣는다.
        storeAwaitingWaypoints = dest to store
        main.removeCallbacks(waypointWaitTimeout)
        main.postDelayed(waypointWaitTimeout, 3000)
        try { h.getWaypointInfo() } catch (e: Exception) {
            Log.e(TAG, "경유지 목록 요청 실패: ${e.message}")
            main.removeCallbacks(waypointWaitTimeout)
            storeAwaitingWaypoints = null
            store.finish("경유지 순서를 확인하지 못했어요. 다시 시도해 주세요.")
        }
    }

    /**
     * friendSlot이 있으면: 목적지 + [매장 FIRST, 친구 SECOND]로 경로를 한 번에 다시 요청한다(카페 → 친구 순서).
     * 없으면: 매장만 FIRST로 넣는다. 경로 전체를 다시 요청하지 않으므로 목적지는 그대로다.
     */
    private fun insertStore(dest: DestinationInfo, store: StoreStop, friendSlot: WaypointIndex?, expectedFriend: FriendStop?) {
        val h = helper ?: run { store.finish("내비 연결을 확인해 주세요."); return }
        val friend = friendStop
        val storeRequest = RequestWaypointInfo(
            latitude = store.lat,
            longitude = store.lng,
            poiName = store.name,
            waypointIndex = WaypointIndex.FIRST,   // FIRST = 매장
            poiId = "pickme-store",                // 임시 값 (가짜 매장)
            address = store.name,                  // 임시 값
            poiSubId = "",
            poiProvider = dest.poiProvider         // 현재 목적지 것을 복사
        )
        selectedStore = null
        // Keep the currently reported friend when verifying the resulting cafe → friend order.
        lateinit var confirmation: StoreConfirmation
        val readback = StoreRouteConfirmation(
            store = ExpectedStoreWaypoint(store.lat, store.lng, store.name),
            friend = expectedFriend?.let { ExpectedStoreWaypoint(it.lat, it.lng, it.name) },
            onResult = { error -> completeStoreConfirmation(confirmation, error) },
        )
        confirmation = StoreConfirmation(store, readback)
        storeConfirmation = confirmation
        main.removeCallbacks(storeConfirmationTimeout)
        main.postDelayed(storeConfirmationTimeout, 8000)
        try {
            if (friendSlot != null && friend != null) {
                // removeWaypoint는 Pleos에서 경로 자체를 취소해 버린다(2026-11-11 확인).
                // 목적지 + [매장 FIRST, 친구 SECOND]를 한 번에 다시 요청해 순서를 확정한다.
                Log.d(TAG, "친구 경유지가 매장보다 앞에 있어 순서를 바꿔요: 목적지 + [매장, 친구]로 경로 다시 요청")
                h.requestRouteWithWaypoints(
                    RouteWithWaypointsRequest(
                        destination = RouteInfo(
                            latitude = dest.latitude,
                            longitude = dest.longitude,
                            poiName = dest.name,
                            poiId = dest.poiId,
                            address = dest.address,
                            poiSubId = dest.poiSubId ?: "",
                            poiProvider = dest.poiProvider ?: ""
                        ),
                        waypoints = listOf(storeRequest, friendWaypoint(friend.lat, friend.lng, friend.name))
                    )
                )
            } else {
                h.addWaypoint(storeRequest)
            }
            main.postDelayed(readStoreConfirmation, 500)
            Log.d(TAG, "매장 경유지(FIRST) 추가 요청 보냄: ${store.name}")
        } catch (e: Exception) {
            Log.e(TAG, "경로 요청 실패: ${e.message}")
            readback.fail("카페 경유지를 추가하지 못했어요. 주문은 예약되지 않았어요.")
        }
    }

    /** Uses the caller's connected helper. Acknowledgement must be verified through callbacks. */
    fun addFriendStop(helper: NaviHelper, lat: Double, lng: Double, name: String) {
        helper.addWaypoint(friendWaypoint(lat, lng, name))
        friendStop = FriendStop(lat, lng, name.trim())
    }

    // ↓ 아래 함수들은 기존 뼈대 그대로 둔다
    fun addFriendStop(lat: Double, lng: Double, name: String) { /* TODO SECOND */ }
    fun removeStoreStop() { /* TODO */ }
    fun removeFriendStop() { /* TODO */ }
    fun remainingMinutes(onResult: (Int) -> Unit) { /* TODO */ }
    fun onStopArrived(listener: (which: StopType) -> Unit) { /* TODO */ }
}