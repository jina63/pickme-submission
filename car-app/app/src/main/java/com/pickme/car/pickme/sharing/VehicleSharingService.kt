package com.pickme.car.pickme.sharing

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import com.pickme.car.core.navi.NaviRemote
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.pickme.car.pickme.ui.InvitationActivity

/** 위치 공유 서비스의 현재 상태(공유 중인지, 안내 문구)를 화면과 나눠 쓰는 곳. */
object VehicleSharingState {
    var running by mutableStateOf(false)
    var message by mutableStateOf("")
    var invitationId by mutableStateOf("")
    var routeConfirmedFor = ""
}

/**
 * 운전자가 수락하고 위치 공유에 동의하면 뒤에서 도는 서비스.
 * 약 5초마다 Pleos 내비에서 차 위치·친구 경유지까지 남은 시간을 읽어 서버로 올리고,
 * 카페 경유 상태를 함께 전한다. 친구 준비 완료는 초대 화면에서 확인한다.
 * 최신 값만 올리고 이동 경로 기록은 저장하지 않는다.
 */
class VehicleSharingService : Service() {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var helper:NaviHelper?=null
    private var listener:NaviHelperEventListener?=null
    private var id=""
    private var cookie=""
    private var pickupName=""
    private var destinationName=""
    private var naviConnected=false
    private var accepted=false
    @Volatile private var stopped=false
    private var uploading=false
    private var etaSeconds:Int?=null
    private val cafeTracker=CafeVisitTracker()
    private var cafeUpdated=0L
    private var cafeStore:NaviRemote.SelectedStore?=null
    private var etaUpdated=0L
    private var sampleGeneration=0
    private val emulator get()=Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk",true)
    private val poll=object:Runnable {
        override fun run() {
            if(stopped || !accepted)return
            try {helper?.getCurrentLocationInfo();helper?.getWaypointInfo();helper?.getDestinationInfo()}catch(_:Exception){VehicleSharingState.message="Pleos 차량 정보 응답을 기다리고 있어요."}
            main.postDelayed(this,5000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("pickme-live","픽미 차량 위치 공유",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {stopSharing("차량 위치 공유를 중지했어요.");return START_NOT_STICKY}
        if(VehicleSharingState.running && !stopped)return START_NOT_STICKY
        id=intent?.getStringExtra("id").orEmpty();cookie=intent?.getStringExtra("cookie").orEmpty();pickupName=intent?.getStringExtra("pickupName").orEmpty()
        if(id.isBlank() || cookie.isBlank() || pickupName.isBlank()){stopSelf();return START_NOT_STICKY}
        stopped=false;VehicleSharingState.running=true;VehicleSharingState.invitationId=id;VehicleSharingState.message="차량 위치 공유에 연결하고 있어요."
        val stopIntent=PendingIntent.getService(this,1,Intent(this,VehicleSharingService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,2,Intent(this,InvitationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,"pickme-live")else Notification.Builder(this)
        val notification=builder.setSmallIcon(com.pickme.car.R.drawable.ic_pickme).setContentTitle("픽미 차량 위치 공유").setContentText("친구에게 차량 위치와 픽업 예상 시간을 공유해요. 눌러 확인하세요.").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"공유 중지",stopIntent).build()).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(7101,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(7101,notification)
        val generation=++sampleGeneration
        worker.execute {
            try {
                val result=request("driver-accept",JSONObject().put("isEmulator",emulator).put("sharingAllowed",true))
                if(stopped) {try{request("live",JSONObject().put("state","paused"))}catch(_:Exception){};return@execute}
                main.post {
                    if(stopped || generation!=sampleGeneration)return@post
                    accepted=true
                    main.postDelayed({stopSharing("공유 시간이 끝났어요. 새 초대를 만들어 주세요.")},result.optLong("ttlMillis",3600000).coerceIn(1000,3600000))
                    waitForConfirmedRoute(generation)
                }
            } catch(e:Exception){main.post{stopSharing(e.message?:"차량 위치 공유를 시작하지 못했어요.")}}
        }
        return START_NOT_STICKY
    }
    private fun waitForConfirmedRoute(generation:Int) {
        if(stopped || generation!=sampleGeneration)return
        if(VehicleSharingState.routeConfirmedFor==id)connect(generation)
        else {
            VehicleSharingState.message="픽업은 수락했어요. 내비 경유지 반영을 확인한 뒤 차량 위치 공유를 시작해요."
            main.postDelayed({waitForConfirmedRoute(generation)},1000)
        }
    }
    private fun connect(generation:Int) {
        try {
            val events=object:NaviHelperEventListener {
                override fun onCurrentLocationInfo(currentLocationInfo:CurrentLocationInfo) {main.post {
                    if(stopped || generation!=sampleGeneration || uploading)return@post
                    val p=currentLocationInfo
                    if(!p.latitude.isFinite() || !p.longitude.isFinite() || p.latitude !in -85.0..85.0 || p.longitude !in -180.0..180.0 || (p.latitude==0.0 && p.longitude==0.0))return@post
                    val position=JSONObject().put("latitude",p.latitude).put("longitude",p.longitude)
                    if(p.heading.isFinite() && p.heading>=0 && p.heading<360)position.put("heading",p.heading)
                    val body=JSONObject().put("state","on_way").put("position",position)
                    if(destinationName.isNotBlank())body.put("destinationLabel",destinationName)
                    if(VehicleSharingState.routeConfirmedFor==id && SystemClock.elapsedRealtime()-etaUpdated<=15000 && etaUpdated >= (NaviRemote.selectedStore?.requestedAt ?: 0L))etaSeconds?.let{body.put("pickupEtaSeconds",it).put("pickupEtaCapturedAt",System.currentTimeMillis()-(SystemClock.elapsedRealtime()-etaUpdated))}
                    val cafeAge=SystemClock.elapsedRealtime()-cafeUpdated
                    if(VehicleSharingState.routeConfirmedFor==id && cafeAge in 0..15000 && cafeStore!=null && cafeStore==NaviRemote.selectedStore) cafeTracker.phase?.let { phase ->
                        val store=cafeStore!!
                        val cafe=JSONObject().put("name",store.name).put("phase",phase)
                        if(store.latitude.isFinite() && store.longitude.isFinite() && store.latitude in -85.0..85.0 && store.longitude in -180.0..180.0 && (store.latitude!=0.0 || store.longitude!=0.0))cafe.put("position",JSONObject().put("latitude",store.latitude).put("longitude",store.longitude))
                        body.put("cafeVisit",cafe)
                            .put("cafeVisitCapturedAt",System.currentTimeMillis()-cafeAge)
                    }
                    uploading=true
                    worker.execute {
                        try {request("live",body);main.post {if(!stopped)VehicleSharingState.message="차량 위치 공유 중 · 약 5초마다 갱신해요."}}
                        catch(e:Exception){main.post {
                            if(!stopped) {
                                if(e is SharingApiException && e.code in listOf(401,404,410)) stopSharing("픽업 연결이 끝나 차량 위치 공유를 종료했어요.")
                                else VehicleSharingState.message=e.message?:"차량 정보 연결이 끊겼어요. 다시 시도 중이에요."
                            }
                        }}
                        finally {main.post{uploading=false}}
                    }
                } }
                override fun onWayPointInfo(waypoints:List<WaypointInfo>) {main.post {
                    if(stopped || generation!=sampleGeneration)return@post
                    // remainTime is seconds: confirmed against native UI (540 -> 9 min).
                    val target=waypoints.lastOrNull{it.name==pickupName}
                    etaSeconds=target?.remainTime?.takeIf{it in 1..86400}
                    etaUpdated=SystemClock.elapsedRealtime()
                    val store=NaviRemote.selectedStore
                    cafeStore=store
                    cafeTracker.update(store?.name,store?.requestedAt,waypoints.map{it.name},pickupName)
                    cafeUpdated=etaUpdated
                } }
                override fun onWayPointArrived(waypointArrivedInfo:WaypointArrivedInfo) {main.post {
                    if(!stopped && generation==sampleGeneration) {
                        cafeTracker.onArrival(waypointArrivedInfo.name)
                    }
                } }
                override fun onRouteCancelled() {main.post {cafeTracker.reset();etaSeconds=null;cafeUpdated=0L} }
                override fun onDestinationInfo(destinationInfo:DestinationInfo) {main.post {if(!stopped && generation==sampleGeneration && destinationInfo.name.isNotBlank())destinationName=destinationInfo.name.take(120)} }
            }
            listener=events
            NaviRemote.acquireForSharing(applicationContext,onReady={navi -> if(!stopped && generation==sampleGeneration){helper=navi;naviConnected=true;navi.addListener(events);main.post(poll)}},onFailed={stopSharing("Pleos 차량 정보에 연결하지 못했어요. 다시 공유를 시작해 주세요.")})
        } catch(_:Exception){stopSharing("Pleos 차량 위치 공유를 준비하지 못했어요.")}
    }
    private class SharingApiException(val code:Int,message:String):IllegalStateException(message)
    private fun request(action:String,body:JSONObject?):JSONObject {
        val connection=URL("http://10.0.2.2:8787/api/pickups/$id"+if(action.isBlank())"" else "/$action").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=8000;connection.readTimeout=10000;connection.requestMethod=if(body==null)"GET" else "POST"
            connection.setRequestProperty("Cookie",cookie);connection.setRequestProperty("Content-Type","application/json")
            if(body!=null){connection.doOutput=true;connection.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val code=connection.responseCode
            val data=JSONObject((if(code in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}?:"{}")
            if(code !in 200..299)throw SharingApiException(code,data.optString("error","차량 정보 공유 서버를 확인해 주세요."))
            return data
        } finally {connection.disconnect()}
    }
    private fun stopSharing(message:String) {
        if(stopped)return
        stopped=true;sampleGeneration++;main.removeCallbacksAndMessages(null)
        if(accepted)worker.execute{try{request("live",JSONObject().put("state","paused"))}catch(_:Exception){}}
        VehicleSharingState.running=false;VehicleSharingState.message=message
        if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE)else stopForeground(true);stopSelf()
    }
    override fun onTaskRemoved(rootIntent:Intent?) {stopSharing("앱을 닫아 차량 위치 공유를 중지했어요.");super.onTaskRemoved(rootIntent)}
    override fun onTimeout(startId:Int,fgsType:Int){stopSharing("시스템 제한으로 차량 위치 공유를 중지했어요.")}
    override fun onDestroy() {
        if(!stopped)stopSharing("차량 위치 공유를 중지했어요.")
        try{if(naviConnected)listener?.let{helper?.removeListener(it)};NaviRemote.releaseForSharing()}catch(_:Exception){};main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()
    }
    override fun onBind(intent:Intent?)=null
}
