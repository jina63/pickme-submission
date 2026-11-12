package com.pickme.car.pickme.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pickme.car.core.ui.theme.PickMeTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.pickme.car.pickme.sharing.PickmeTripReader
import com.pickme.car.pickme.sharing.VehicleSharingService
import com.pickme.car.pickme.sharing.VehicleSharingState

/**
 * 픽미 메인 화면 (운전자용).
 * 내비 목적지 가져오기 → 연락처 선택 → 초대(이메일·링크) → 친구 응답 확인 → 수락·위치 공유 시작까지 담당한다.
 * 시연 연락처만 사용하며, 기기 전화번호부를 읽거나 문자를 자동으로 보내지 않는다.
 */
class InvitationActivity : ComponentActivity() {
    private lateinit var model: InvitationViewModel
    private var friendNavigator: com.pickme.car.core.navi.FriendWaypointController? = null
    private var tripReader: PickmeTripReader? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[InvitationViewModel::class.java]
        try {
            tripReader = PickmeTripReader(this, onTrip = { if(model.state.id.isBlank())model.receiveTrip(it) }, onMessage = { if(model.state.id.isBlank())model.tripNotice(it) })
        } catch (_: Exception) { model.tripNotice("Pleos 내비 연결을 준비하지 못했어요. 내비 실행 후 다시 열어 주세요.") }
        try {
            friendNavigator = com.pickme.car.core.navi.FriendWaypointController(this) { busy, applied, message -> model.navigationState(busy, applied, message); if(applied)VehicleSharingState.routeConfirmedFor=model.state.id }
        } catch (_: Exception) { model.navigationState(false, false, "Pleos 내비 연결을 준비하지 못했어요.") }
        setContent {
            PickMeTheme {
                var query by rememberSaveable { mutableStateOf("") }
                val driverName = "김픽미"
                var showMailConfirmation by rememberSaveable { mutableStateOf(false) }
                var showDestinationSearch by rememberSaveable { mutableStateOf(false) }
                var showInvitationLink by rememberSaveable { mutableStateOf(false) }
                var showInvitationQr by rememberSaveable { mutableStateOf(false) }
                var showSharingOptions by rememberSaveable { mutableStateOf(false) }
                var returnToContacts by rememberSaveable { mutableStateOf(false) }
                var destinationQuery by rememberSaveable { mutableStateOf("") }
                var selectedId by rememberSaveable { mutableStateOf("") }
                val state = model.state
                val selected = model.contacts.find { it.id == selectedId }
                val beginNewPickup = {
                    model.beginNewPickup()
                    query="";selectedId="";showMailConfirmation=false
                    showSharingOptions=false;showInvitationLink=false;showInvitationQr=false
                }
                LaunchedEffect(state.id) { if(state.id.isNotBlank())tripReader?.stopAutomatic() else tripReader?.startAutomatic() }
                LaunchedEffect(returnToContacts,VehicleSharingState.running) {
                    if(returnToContacts && !VehicleSharingState.running) {
                        beginNewPickup()
                        returnToContacts=false
                    }
                }
                if(showMailConfirmation && selected!=null) {
                    InvitationMailDialog(
                        passengerName=selected.name, driverName=driverName,
                        destinationAvailable=model.trip!=null,
                        destinationLabel=model.trip?.optJSONObject("destination")?.optString("label").orEmpty(),
                        sending=state.busy, sharingActive=VehicleSharingState.running,
                        onDismiss={showMailConfirmation=false},
                        onSend={showMailConfirmation=false;model.create(selected,driverName)}
                    )
                }
                Scaffold(containerColor=MaterialTheme.colorScheme.background) { contentPadding ->
                    Box(Modifier.fillMaxSize().padding(contentPadding),contentAlignment=Alignment.TopCenter) {
                    Column(Modifier.fillMaxHeight().widthIn(max=1120.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TextButton(onClick = { finish() }) { Text("← 돌아가기") }
                        Text(if(state.id.isBlank()) "친구 초대" else "픽업", fontSize=32.sp, fontWeight=FontWeight.Bold)
                        if(state.id.isBlank() && VehicleSharingState.running) {
                            Text("진행 중인 픽업이 있어요. 친구의 준비 상태를 확인할 수 있어요.")
                            Button(onClick={model.resumeSavedPickup()}) {Text("진행 중인 픽업 보기")}
                            OutlinedButton(onClick={startService(Intent(this@InvitationActivity,VehicleSharingService::class.java).setAction("STOP"))}) {Text("기존 차량 위치 공유 중지")}
                        }
                        if (state.id.isBlank()) {
                            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(100.dp)) {Text("운전자  김픽미",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(horizontal=16.dp,vertical=9.dp))}
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(26.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text("함께 갈 목적지",fontSize=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    model.trip?.let { trip ->Text(trip.getJSONObject("destination").getString("label"),fontSize=30.sp,fontWeight=FontWeight.Bold) }
                                    if(model.trip==null)Text(model.tripMessage,fontSize=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        TextButton(onClick={showDestinationSearch=!showDestinationSearch}){Text(if(showDestinationSearch) "검색 닫기" else "목적지 바꾸기")}
                                        if(model.trip==null) TextButton(onClick={tripReader?.read()}){Text("다시 연결")}
                                    }
                                    if(showDestinationSearch || model.trip==null) {
                                        OutlinedTextField(destinationQuery,{destinationQuery=it.take(120)},label={Text("내비에서 찾을 목적지")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
                                        OutlinedButton(onClick={tripReader?.search(destinationQuery)},enabled=destinationQuery.isNotBlank(),shape=RoundedCornerShape(16.dp)){Text("내비에서 검색")}
                                    }
                                }
                            }
                            Text("초대할 친구",fontSize=24.sp,fontWeight=FontWeight.Bold)
                            OutlinedTextField(query, { query = it.take(120) }, label = { Text("친구 이름 검색") }, placeholder = { Text("예: 친구 1, 친구 2") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (model.contactsLoading) Text("연락처를 불러오고 있어요.")
                            if (!model.contactsLoading && model.contacts.isEmpty()) {
                                OutlinedButton(onClick = { model.loadContacts() }) { Text("연락처 다시 불러오기") }
                            }
                            val filtered = model.contacts.filter { it.name.contains(query.trim(), ignoreCase = true) }
                            if (model.contacts.isNotEmpty() && filtered.isEmpty()) Text("검색 결과가 없어요. 다른 이름으로 찾아보세요.")
                            for (contact in filtered) {
                                Card(onClick = { selectedId = contact.id; showMailConfirmation=true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,if(contact.id==selectedId)MaterialTheme.colorScheme.primary.copy(alpha=.4f) else MaterialTheme.colorScheme.outlineVariant),colors=CardDefaults.cardColors(containerColor=if(contact.id==selectedId)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                    Row(Modifier.fillMaxWidth().heightIn(min=86.dp).padding(18.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(17.dp)),contentAlignment=Alignment.Center){Text(contact.name.take(1),fontSize=23.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
                                        Column(Modifier.weight(1f)) {
                                            Text(contact.name,fontSize=23.sp,fontWeight=FontWeight.SemiBold)
                                        }
                                        Text("초대하기  ›",fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        } else {
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Text("${state.passengerName}님 픽업",fontSize=28.sp,fontWeight=FontWeight.Bold)
                                    if(state.status=="declined")Text("초대가 거절됐어요.")
                                    if(state.arrivalMailState.isNotBlank()) Text(when(state.arrivalMailState){"submitted"->"도착 5분 이내 안내 메일이 접수됐어요";"sending"->"도착 안내 메일 요청 중…";else->"도착 안내 메일 결과를 확인하지 못했어요. 친구에게 직접 연락해 주세요."})
                                    if(state.passengerReady) Text("탑승 준비 완료",fontSize=22.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                                    Text(if(state.pickupLabel.isNotBlank()) "탑승 장소  ·  ${state.pickupLabel}" else "친구가 탑승 장소를 고르고 있어요",fontSize=22.sp,fontWeight=FontWeight.SemiBold)

                                    if (state.status == "accepted" && state.pickupLabel.isNotBlank()) {
                                        Row {
                                            Text("수락하면 차량 위치, 카페 경유와 도착 시간을 공유해요.",fontSize=15.sp)
                                        }
                                        if(!model.navigationApplied) Button(onClick = {
                                            model.startVehicleSharing(this@InvitationActivity)
                                            val lat=state.pickupLatitude.toDoubleOrNull()
                                            val lng=state.pickupLongitude.toDoubleOrNull()
                                            if(lat!=null && lng!=null) friendNavigator?.apply(lat,lng,state.pickupLabel)
                                            else model.navigationState(false,false,"탑승 좌표를 확인하지 못했어요. 새 초대를 만들어 주세요.")
                                        }, enabled = !model.navigationBusy && !model.navigationApplied, modifier=Modifier.fillMaxWidth().heightIn(min=68.dp),shape=RoundedCornerShape(16.dp)) {
                                            Text(if(model.navigationBusy) "픽업 경로 준비 중…" else "픽업 수락 · 안내 시작", fontSize=20.sp)
                                        }
                                        if(model.navigationApplied)Text("픽업 경로가 연결됐어요. 내비에서 탑승 위치를 확인해 주세요.",fontSize=15.sp)
                                        else if(!model.navigationBusy && model.navigationMessage.isNotBlank())Text("픽업 경로를 확인하지 못했어요. 내비의 목적지를 확인하고 다시 시도해 주세요.",color=MaterialTheme.colorScheme.error)
                                        if(VehicleSharingState.running) {
                                            OutlinedButton(onClick={
                                                returnToContacts=true
                                                startService(Intent(this@InvitationActivity,VehicleSharingService::class.java).setAction("STOP"))
                                            },enabled=!model.mailBusy && !model.navigationBusy && !returnToContacts) {Text(if(returnToContacts) "공유를 종료하고 있어요…" else "공유 중지 · 친구 선택으로")}
                                        } else {
                                            OutlinedButton(onClick={model.startVehicleSharing(this@InvitationActivity)}) {Text("차량 위치 공유 시작 / 다시 연결")}
                                        }
                                        if(VehicleSharingState.running)Text(
                                            if(VehicleSharingState.message.contains("공유 중")) "차량 위치·도착 시간 공유 중"
                                            else if(VehicleSharingState.message.contains("못") || VehicleSharingState.message.contains("끊")) "차량 정보 연결이 원활하지 않아요. 다시 연결 중이에요."
                                            else "차량 정보 연결 중…",fontSize=15.sp,color=MaterialTheme.colorScheme.primary)
                                        else if(VehicleSharingState.message.isNotBlank())Text(VehicleSharingState.message,fontSize=15.sp)
                                    }
                                    if(!VehicleSharingState.running) Button(onClick={beginNewPickup()},enabled=!state.busy && !model.mailBusy && !model.navigationBusy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("다른 친구 선택하기")}
                                    if(state.status!="pending")TextButton(onClick={showSharingOptions=!showSharingOptions}){Text(if(showSharingOptions) "초대 공유 닫기" else "초대 링크 공유")}
                                    if(state.status=="pending" || showSharingOptions) {
                                    OutlinedButton(onClick={showInvitationQr=!showInvitationQr},modifier=Modifier.heightIn(min=56.dp)) {
                                        Text(if(showInvitationQr) "QR 닫기" else "휴대폰으로 초대 열기 · QR")
                                    }
                                    if(showInvitationQr) InvitationQr(state.link)
                                    OutlinedButton(onClick = {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("픽미 초대 링크", state.link))
                                        model.notice("링크를 복사했어요. 친구에게 직접 전달해 주세요.")
                                    }, modifier = Modifier.heightIn(min = 56.dp)) { Text("초대 링크 복사") }
                                    OutlinedButton(onClick = {
                                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "픽미 픽업 초대예요. 링크에서 수락해 주세요.\n${state.link}")
                                        }, "초대 링크 공유"))
                                    }) { Text("공유 앱 선택") }
                                    if(!model.mailRequested) Button(onClick={model.sendInvitationMail()}, enabled=!model.mailBusy && state.status=="pending") {Text(if(model.mailBusy) "메일 보내는 중…" else "${state.passengerName}님에게 초대 메일 다시 요청")}
                                    if(model.mailMessage.isNotBlank())Text(if(model.mailRequested) "초대 메일 전송을 요청했어요." else model.mailMessage,fontSize=15.sp)
                                    TextButton(onClick={showInvitationLink=!showInvitationLink}){Text(if(showInvitationLink) "링크 숨기기" else "초대 링크 보기")}
                                    if(showInvitationLink)Text(state.link,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if(state.error && state.message.isNotBlank())Text(state.message,color=MaterialTheme.colorScheme.error)
                    }
                    }
                }
            }
        }
    }
    override fun onDestroy() { friendNavigator?.release(); tripReader?.release(); super.onDestroy() }
    override fun onStart() { super.onStart(); if (::model.isInitialized) { model.loadContacts(); model.startPolling(); if(model.state.id.isBlank())tripReader?.startAutomatic() } }
    override fun onStop() { tripReader?.stopAutomatic(); if (::model.isInitialized) model.stopPolling(); super.onStop() }
}

/** 화면에 보여 줄 초대 상태: 초대 링크, 친구 응답(대기·수락·거절), 친구가 고른 탑승 장소, 준비 완료 여부 등. */
data class InvitationState(
    val id: String = "", val link: String = "", val status: String = "",
    val busy: Boolean = false, val message: String = "", val error: Boolean = false,
    val passengerName: String = "", val passengerReady:Boolean=false, val arrivalMailState:String="",
    val pickupLabel: String = "", val pickupLatitude: String = "", val pickupLongitude: String = "", val pickupIsDemo: Boolean = false
)

/** 시연용 연락처 한 명 (실제 전화번호 없음). */
data class DemoContact(val id: String, val name: String, val subtitle: String)

/**
 * 로컬 중계 서버(server/pickme)와 통신하는 부분.
 * 초대 생성, 이메일 발송 요청, 2초마다 친구 응답 확인, 운전자 수락 전송, 위치 공유 서비스 시작을 맡는다.
 */
class InvitationViewModel : ViewModel() {
    private val restored = invitationSessions.restore()
    var navigationBusy by mutableStateOf(false)
        private set
    var navigationApplied by mutableStateOf(restored?.navigationApplied == true && VehicleSharingState.running && VehicleSharingState.invitationId == restored?.state?.id)
        private set
    var navigationMessage by mutableStateOf("")
        private set
    fun navigationState(busy:Boolean,applied:Boolean,message:String) { navigationBusy=busy; navigationApplied=applied; navigationMessage=message; rememberSession() }

    var trip by mutableStateOf<JSONObject?>(null)
        private set
    var tripMessage by mutableStateOf("내비에서 목적지를 설정한 뒤 현재 정보를 가져와 주세요.")
        private set
    fun receiveTrip(value: JSONObject) { trip=value; tripMessage="Pleos 목적지를 초대에 연결할 준비가 됐어요." }
    fun tripNotice(value: String) { trip=null; tripMessage=value }

    var contacts by mutableStateOf<List<DemoContact>>(emptyList())
        private set
    var contactsLoading by mutableStateOf(false)
        private set
    fun loadContacts() {
        if (contactsLoading || contacts.isNotEmpty()) return
        contactsLoading = true
        worker.execute {
            try {
                val data = request("/api/demo/contacts").getJSONArray("contacts")
                val list = (0 until data.length()).map { i ->
                    val contact = data.getJSONObject(i)
                    DemoContact(contact.getString("id"), contact.getString("name"), contact.getString("subtitle"))
                }
                update { contacts = list; contactsLoading = false; state = state.copy(message = "", error = false) }
            } catch (e: Exception) {
                update { contactsLoading = false; state = state.copy(message = friendlyError(e), error = true) }
            }
        }
    }
    var state by mutableStateOf(restored?.state?.copy(busy=false,passengerReady=false,error=false,message="진행 중인 픽업을 확인하고 있어요.") ?: InvitationState())
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cookie = restored?.cookie.orEmpty()
    private var active = false
    private var fetching = false
    @Volatile private var closed = false
    private val base = "http://10.0.2.2:8787"
    private val poll = object : Runnable {
        override fun run() {
            if (!active || closed) return
            if (state.id.isNotBlank() && state.status != "declined" && !fetching) refresh()
            main.postDelayed(this, 2000)
        }
    }
    fun startVehicleSharing(context:Context) {
        if(state.id.isBlank() || cookie.isBlank() || state.status!="accepted")return
        val intent=Intent(context,VehicleSharingService::class.java).putExtra("id",state.id).putExtra("cookie",cookie).putExtra("pickupName",state.pickupLabel).putExtra("passengerName",state.passengerName)
        try {androidx.core.content.ContextCompat.startForegroundService(context,intent)}catch(_:Exception){VehicleSharingState.message="차량 위치 공유를 시작하지 못했어요. 앱을 화면에 연 상태에서 다시 시도해 주세요."}
    }
    fun acceptWithoutLocation() {
        val current=state.id
        if(current.isBlank())return
        worker.execute {
            try {request("/api/pickups/$current/driver-accept",JSONObject().put("isEmulator",android.os.Build.HARDWARE.contains("ranchu")).put("sharingAllowed",false));update{VehicleSharingState.message="픽업을 수락했어요. 차량 위치는 공유하지 않아요."}}
            catch(e:Exception){update{VehicleSharingState.message=friendlyError(e)}}
        }
    }
    var mailBusy by mutableStateOf(false)
        private set
    var mailRequested by mutableStateOf(restored?.mailRequested ?: false)
        private set
    var mailMessage by mutableStateOf("")
        private set
    fun sendInvitationMail() {
        if(mailBusy || mailRequested || state.id.isBlank()) return
        mailBusy=true
        mailMessage=""
        val id=state.id
        worker.execute {
            try {
                val data=request("/api/pickups/$id/email",JSONObject())
                update {mailBusy=false;mailRequested=true;mailMessage=data.optString("message")}
            } catch(e:Exception) {
                update {mailBusy=false;mailMessage=friendlyError(e)+" 메일함을 먼저 확인해 주세요."}
            }
        }
    }
    private fun rememberSession() { invitationSessions.save(cookie,state,mailRequested,navigationApplied) }
    fun resumeSavedPickup() {
        val saved=invitationSessions.restore()
        if(saved==null) {
            state=state.copy(error=true,message="이 앱 실행에서 복원할 픽업 정보가 없어요. 기존 공유를 중지한 뒤 새 초대를 만들어 주세요.")
            return
        }
        cookie=saved.cookie
        state=saved.state.copy(busy=false,passengerReady=false,error=false,message="진행 중인 픽업을 확인하고 있어요.")
        mailRequested=saved.mailRequested
        navigationApplied=saved.navigationApplied && VehicleSharingState.running && VehicleSharingState.invitationId==state.id
        startPolling()
    }
    fun beginNewPickup() {
        if(VehicleSharingState.running || state.busy || mailBusy || navigationBusy)return
        invitationSessions.clear();cookie="";state=InvitationState();navigationApplied=false
        navigationMessage="";mailRequested=false;mailMessage="";trip=null
    }
    fun notice(message: String) { state = state.copy(message = message, error = false); rememberSession() }
    fun startPolling() { active = true; main.removeCallbacks(poll); main.post(poll) }
    fun stopPolling() { active = false; main.removeCallbacks(poll) }
    fun create(contact: DemoContact, driverName:String) {
        if (state.busy || state.id.isNotBlank()) return
        state = state.copy(busy = true, message = "", error = false)
        val tripSnapshot = trip?.let { JSONObject(it.toString()) }
        if(tripSnapshot==null){state=state.copy(busy=false,error=true,message="Pleos 내비 목적지를 먼저 가져와 주세요.");return}
        worker.execute {
            try {
                if (cookie.isBlank()) request("/api/driver/session", JSONObject())
                val body = JSONObject().put("contactId", contact.id).put("driverName",driverName.trim())
                body.put("trip", tripSnapshot)
                val data = request("/api/pickups", body)
                val next = InvitationState(id = data.getString("id"), link = data.getString("inviteUrl"), status = data.getString("status"), message = "초대 링크를 친구에게 직접 전달해 주세요. 아래에서 이메일로 보내거나 링크를 공유해 주세요.", passengerName = data.getString("passengerName"))
                update { state = next; sendInvitationMail() }
            } catch (e: Exception) { update { state = state.copy(busy = false, message = friendlyError(e), error = true) } }
        }
    }
    private fun refresh() {
        fetching = true
        val id = state.id
        worker.execute {
            try {
                val data = request("/api/pickups/$id")
                update {
                    fetching = false
                    val point = data.optJSONObject("pickupPoint")
                    if(state.id!=id)return@update
                    state = state.copy(arrivalMailState=if(data.isNull("arrivalMailState")) "" else data.optString("arrivalMailState", ""), passengerReady=data.optLong("passengerReadyAt",0)>0, pickupLabel = point?.optString("label").orEmpty(), pickupLatitude = point?.optString("latitude").orEmpty(), pickupLongitude = point?.optString("longitude").orEmpty(), pickupIsDemo = point?.optString("locationSource") == "demo", status = data.getString("status"), error = false, message = if (data.getString("status") == "pending") "친구 응답을 확인하고 있어요." else "친구의 응답을 받았어요.")
                }
            } catch (e: Exception) { update {
                fetching=false
                if(state.id!=id)return@update
                state=state.copy(error=true,message=friendlyError(e))
            } }
        }
    }
    private fun update(block: () -> Unit) { main.post { if (!closed) { block(); rememberSession() } } }
    private fun friendlyError(e: Exception) = if (e is ApiException) e.message.orEmpty() else "로컬 서버에 연결할 수 없어요. 컴퓨터에서 server/pickme 서버를 실행해 주세요."
    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            connection.getHeaderField("Set-Cookie")?.let { cookie = it.substringBefore(';') }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val data = JSONObject(stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}")
            if (code !in 200..299) throw ApiException(data.optString("error", "요청 실패 ($code)"))
            return data
        } finally { connection.disconnect() }
    }
    override fun onCleared() { rememberSession(); closed = true; stopPolling(); worker.shutdownNow(); super.onCleared() }
    private class ApiException(message: String) : Exception(message)
}

/** 초대 메일을 보내기 전에 받는 사람·목적지를 한 번 더 확인하는 창. */
@Composable
internal fun InvitationMailDialog(
    passengerName:String, driverName:String,
    destinationAvailable:Boolean, destinationLabel:String, sending:Boolean,
    sharingActive:Boolean, onDismiss:()->Unit, onSend:()->Unit
) {
    AlertDialog(
        onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(28.dp),
        title={Text("${passengerName}님에게 초대 메일을 보내시겠습니까?")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("운전자: ${driverName}")
            Text(if(destinationAvailable) "목적지: ${destinationLabel.ifBlank { "연결된 내비 목적지" }}" else "내비 목적지가 연결되지 않았어요. 내비에서 목적지를 설정하면 자동으로 가져와요.")
            if(sharingActive)Text("기존 차량 위치 공유를 먼저 중지해 주세요.")
            Text("등록된 이메일로 초대 링크를 보내고, 도착 전 안내 메일도 보내요.")
        }},
        confirmButton={Button(onClick=onSend,shape=RoundedCornerShape(16.dp),enabled=destinationAvailable && driverName.isNotBlank() && !sending && !sharingActive,modifier=Modifier.heightIn(min=56.dp)){Text(if(sending) "보내는 중…" else "메일 보내기")}},
        dismissButton={TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=56.dp)){Text("취소")}}
    )
}
