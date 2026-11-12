package com.pickme.car.pickme.sharing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.constants.NaviErrorCode
import org.json.JSONObject

/**
 * 초대를 만들기 전에 Pleos 내비에서 현재 위치와 목적지를 한 번 읽어 온다.
 * 읽기만 하고 경로나 매장·친구 경유지 칸은 바꾸지 않는다.
 */
class PickmeTripReader(context: Context, private val onTrip: (JSONObject) -> Unit, private val onMessage: (String) -> Unit) {
    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private var released = false
    private var pending: (() -> Unit)? = null
    private var origin: CurrentLocationInfo? = null
    private var destination: DestinationInfo? = null
    private var reading = false
    private var automatic = false
    private var lastSuccessAt = 0L
    private val automaticRefresh = object : Runnable {
        override fun run() {
            if(!automatic || released)return
            if(!reading && (connected || pending==null) && (lastSuccessAt==0L || SystemClock.elapsedRealtime()-lastSuccessAt>=60000))read()
            main.postDelayed(this,3000)
        }
    }
    fun startAutomatic() {
        if(released)return
        automatic=true;lastSuccessAt=0L
        main.removeCallbacks(automaticRefresh);main.post(automaticRefresh)
    }
    fun stopAutomatic() {
        automatic=false;pending=null;reading=false
        main.removeCallbacks(automaticRefresh);main.removeCallbacks(timeout)
    }
    private val timeout = Runnable {
        if (!released && reading) { reading = false; onMessage("내비 응답을 기다리고 있어요. 목적지 설정을 확인해 주세요. 자동으로 다시 시도해요.") }
    }
    private fun valid(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() && lat in -85.0..85.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { main.post { if (reading && !released) { origin = currentLocationInfo; complete() } } }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { main.post { if (reading && !released) { destination = destinationInfo; complete() } } }
        override fun onDestinationChanged() { main.post { if (!released) { lastSuccessAt=0L; if(automatic){if(!reading)read()} else onMessage("내비 목적지가 바뀌었어요. 화면을 다시 열면 갱신돼요.") } } }
        override fun onRouteCancelled() { main.post { if (!released) { reading=false;lastSuccessAt=0L;main.removeCallbacks(timeout);onMessage("내비 경로가 취소됐어요. 목적지를 설정하면 자동으로 다시 가져와요.") } } }
        override fun onError(errorCode: NaviErrorCode) { main.post { if (!released) { reading=false; onMessage("내비 정보 오류: $errorCode. 목적지 설정과 권한을 확인해 주세요.") } } }
    }
    init {
        helper.initialize(onConnected = { main.post { if (!released) { connected=true; helper.addListener(listener); pending?.invoke(); pending=null } } }, onFailed = { main.post { if (!released) onMessage("Pleos 내비에 연결하지 못했어요. 내비를 실행하고 다시 열어 주세요.") } })
    }
    private fun runWhenConnected(action: () -> Unit) {
        if (released) return
        if (connected) action() else { pending=action; onMessage("Pleos 내비 연결을 기다리고 있어요.") }
    }
    fun read() = runWhenConnected {
        origin=null; destination=null; reading=true
        onMessage("차량 현재 위치와 목적지를 요청했어요.")
        main.removeCallbacks(timeout); main.postDelayed(timeout, 8000)
        try { helper.getCurrentLocationInfo(); helper.getDestinationInfo() } catch (_: Exception) { reading=false; onMessage("내비 정보를 가져오지 못했어요. 다시 시도해 주세요.") }
    }
    fun search(keyword: String) = runWhenConnected {
        try { helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword.trim())); onMessage("내비에서 목적지를 정하고 돌아오면 자동으로 가져와요.") } catch (_: Exception) { onMessage("내비 검색을 열지 못했어요.") }
    }
    private fun complete() {
        val o=origin ?: return
        val d=destination ?: return
        if (!valid(o.latitude,o.longitude) || !valid(d.latitude,d.longitude) || d.name.isBlank()) {reading=false;onMessage("유효한 차량 위치와 목적지가 필요해요. 내비에서 먼저 목적지를 설정해 주세요.");return}
        reading=false;lastSuccessAt=SystemClock.elapsedRealtime();main.removeCallbacks(timeout)
        val trip=JSONObject().put("source","pleos").put("capturedAt",System.currentTimeMillis())
            .put("origin",JSONObject().put("latitude",o.latitude).put("longitude",o.longitude))
            .put("destination",JSONObject().put("latitude",d.latitude).put("longitude",d.longitude).put("label",d.name))
        if (o.heading.isFinite() && o.heading >= 0 && o.heading < 360) trip.put("heading",o.heading)
        onTrip(trip)
    }
    fun release() { stopAutomatic();released=true; reading=false;pending=null;main.removeCallbacksAndMessages(null);if(connected)helper.removeListener(listener);helper.release() }
}
