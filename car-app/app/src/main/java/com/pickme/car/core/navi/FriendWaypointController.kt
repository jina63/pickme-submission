package com.pickme.car.core.navi

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.WaypointCmdType
import kotlin.math.abs

/** A single user-requested SECOND update, followed by read-back. Never recreates a route. */
class FriendWaypointController(context: Context, private val onState: (busy: Boolean, applied: Boolean, message: String) -> Unit) {
    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private enum class Phase { IDLE, CONNECTING, BEFORE, WAITING, VERIFYING }
    private var phase = Phase.IDLE
    private var connected = false
    private var released = false
    private var point: Triple<Double,Double,String>? = null
    private var originalDestination: DestinationInfo? = null
    private var originalWaypoints: List<WaypointInfo>? = null
    private var checkDestination: DestinationInfo? = null
    private var checkWaypoints: List<WaypointInfo>? = null
    private var secondAcknowledged = false
    private val poll = Runnable { if(!released && phase==Phase.WAITING)readBack() }
    private val timeout = Runnable {
        if (phase != Phase.IDLE && !released) finish(false,"내비 반영 확인 시간이 초과됐어요. 내비 경유지 목록을 확인한 뒤 다시 시도해 주세요.")
    }
    private fun same(aLat:Double,aLng:Double,bLat:Double,bLng:Double) = abs(aLat-bLat)<0.00002 && abs(aLng-bLng)<0.00002
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { main.post {
            if(released)return@post
            when(phase) {
                Phase.BEFORE -> {originalDestination=destinationInfo; maybeSend()}
                Phase.VERIFYING -> {checkDestination=destinationInfo; verify()}
                else -> Unit
            }
        } }
        override fun onWayPointInfo(waypoints: List<WaypointInfo>) { main.post {
            if(released)return@post
            when(phase) {
                Phase.BEFORE -> {originalWaypoints=waypoints; maybeSend()}
                Phase.VERIFYING -> {checkWaypoints=waypoints; verify()}
                else -> Unit
            }
        } }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { main.post {
            if(!released && phase == Phase.WAITING && waypointChangedInfo.index==WaypointIndex.SECOND && waypointChangedInfo.cmdType==WaypointCmdType.ADD) {
                secondAcknowledged=true; readBack()
            }
        } }
        override fun onRoutingCompleted() { main.post { if(!released && phase==Phase.WAITING) readBack() } }
        override fun onError(errorCode: NaviErrorCode) { main.post {
            if(!released && phase!=Phase.IDLE)finish(false,"Pleos 내비가 경유지를 반영하지 못했어요: $errorCode. 좌표만으로 추가할 수 있는지와 현재 경로를 확인해 주세요.")
        } }
        override fun onRouteCancelled() { main.post {
            if(released)return@post
            if(phase==Phase.BEFORE || phase==Phase.CONNECTING)finish(false,"내비 안내가 취소됐어요. 최종 목적지를 다시 설정해 주세요.")
            else if(phase==Phase.WAITING) {
                // Pleos cancels the previous guidance while rebuilding the route with a waypoint.
                // Read-back, rather than this intermediate event, determines success.
                onState(true,false,"내비가 경로를 갱신하고 있어요. 새 경유지와 최종 목적지를 확인할게요.")
                main.removeCallbacks(poll);main.postDelayed(poll,1500)
            }
        } }
    }
    init {
        helper.initialize(onConnected={main.post {
            if(!released) {
                connected=true;helper.addListener(listener)
                if(phase==Phase.CONNECTING)readBefore()
            }
        }},onFailed={main.post {if(!released && phase!=Phase.IDLE)finish(false,"Pleos 내비에 연결하지 못했어요. 내비를 실행한 뒤 다시 시도해 주세요.")}})
    }
    fun apply(lat:Double,lng:Double,name:String) {
        if(released || phase!=Phase.IDLE)return
        try {friendWaypoint(lat,lng,name)} catch(e:IllegalArgumentException){onState(false,false,e.message.orEmpty());return}
        point=Triple(lat,lng,name);originalDestination=null;originalWaypoints=null;secondAcknowledged=false
        phase=Phase.CONNECTING
        onState(true,false,"현재 목적지와 경유지를 확인하고 있어요.")
        main.removeCallbacks(timeout);main.postDelayed(timeout,60000)
        if(connected)readBefore()
    }
    private fun readBefore() {
        phase=Phase.BEFORE
        try {helper.getDestinationInfo();helper.getWaypointInfo()}catch(_:Exception){finish(false,"현재 경로를 가져오지 못했어요. 내비에서 목적지를 먼저 설정해 주세요.")}
    }
    private fun maybeSend() {
        val destination=originalDestination ?: return
        val stops=originalWaypoints ?: return
        val target=point ?: return
        if(!destination.latitude.isFinite() || !destination.longitude.isFinite() || destination.latitude !in -85.0..85.0 || destination.longitude !in -180.0..180.0 || (destination.latitude==0.0 && destination.longitude==0.0) || destination.name.isBlank()) {
            finish(false,"내비에 최종 목적지를 먼저 설정하고 안내를 시작해 주세요.");return
        }
        // Reuse an identical named stop even if the service compacts a lone SECOND into a one-item list.
        if(stops.any { reportedWaypointMatch(it,target.first,target.second,target.third)==ReportedWaypointMatch.COORDINATE && it.name==target.third }) {
            finish(true,"이미 같은 탑승 장소가 내비 경유지에 있어요. 최종 목적지: ${destination.name}");return
        }
        phase=Phase.WAITING
        onState(true,false,"친구 SECOND 추가를 요청했어요. Pleos 경로 확인 화면이 열리면 안내 시작을 눌러 주세요.")
        try {NaviRemote.addFriendStop(helper,target.first,target.second,target.third);main.postDelayed(poll,1500)}catch(_:Exception){finish(false,"경유지 요청을 보내지 못했어요. 다시 시도해 주세요.")}
    }
    private fun readBack() {
        main.removeCallbacks(poll)
        phase=Phase.VERIFYING;checkDestination=null;checkWaypoints=null
        try {helper.getDestinationInfo();helper.getWaypointInfo()}catch(_:Exception){finish(false,"추가 요청 후 경유지를 확인하지 못했어요. 내비 목록을 확인해 주세요.")}
    }
    private fun verify() {
        val destination=checkDestination ?: return
        val stops=checkWaypoints ?: return
        val previous=originalDestination ?: return
        val target=point ?: return
        if(destination.name.isBlank() || (destination.latitude==0.0 && destination.longitude==0.0)) {
            // During the native route preview there may temporarily be no active destination.
            phase=Phase.WAITING;main.postDelayed(poll,1500);return
        }
        if(!sameNavigationDestination(previous,destination)) {
            finish(false,"목적지 확인이 필요해요. 요청 전: ${previous.name}, 현재 내비: ${destination.name}. 내비에서 최종 목적지를 확인해 주세요.");return
        }
        val matches=stops.map{reportedWaypointMatch(it,target.first,target.second,target.third)}
        val exact=ReportedWaypointMatch.COORDINATE in matches
        val nameOnly=ReportedWaypointMatch.NAME_ONLY in matches
        // First slot must survive when the service provided a full two-stop list before the update.
        val first=originalWaypoints?.takeIf{it.size==2}?.firstOrNull()
        if(first!=null && stops.firstOrNull()?.let { it.name==first.name && (reportedWaypointMatch(it,first.latitude,first.longitude,first.name)!=ReportedWaypointMatch.NONE) } != true) {
            finish(false,"기존 매장 경유지 유지 여부를 확인하지 못했어요. 내비 목록을 확인해 주세요.");return
        }
        when {
            exact -> finish(true,"탑승 좌표가 내비 경유지에 반영됐어요. 최종 목적지 ${destination.name} 유지${if(secondAcknowledged) " · SECOND 응답 확인" else " · 목록 확인"}")
            nameOnly -> finish(true,"내비에서 '${target.third}' 경유지와 최종 목적지 '${destination.name}'를 확인했어요. 이 환경은 경유지 좌표를 다시 읽을 수 없어 내비 지도에서 탑승 위치를 확인해 주세요.")
            else -> { phase=Phase.WAITING;main.postDelayed(poll,1500) }
        }
    }
    private fun finish(applied:Boolean,message:String) {
        main.removeCallbacks(timeout);main.removeCallbacks(poll);phase=Phase.IDLE
        onState(false,applied,message)
    }
    fun release() {
        if(released)return
        val interrupted=phase!=Phase.IDLE
        released=true;phase=Phase.IDLE;main.removeCallbacksAndMessages(null)
        if(connected)helper.removeListener(listener)
        helper.release()
        if(interrupted)onState(false,false,"화면이 닫혀 내비 반영 확인을 중단했어요. 다시 열어 경유지 목록을 확인해 주세요.")
    }
}
