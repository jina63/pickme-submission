package com.pickme.car.core.firebase

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.pickme.car.core.model.Order
import com.pickme.car.core.model.OrderStatus
import com.pickme.car.core.model.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 딱픽업 ↔ 가짜 매장 연결 창구. Firebase 는 이 파일에서만 직접 부른다.
 *
 * 사용 예:
 *   FirebaseRepo.loadStores { stores -> ... }
 *   FirebaseRepo.sendOrder(Order(storeId = "store-a", item = "돌체 라떼", etaAt = FirebaseRepo.isoTime(20))) { id -> ... }
 *   val stop = FirebaseRepo.watchOrder(id) { order -> /* 화면에 order.status 표시 */ }
 *   stop()  // 화면을 닫을 때 구독 해제
 */
object FirebaseRepo {

    // google-services.json 의 DB 주소를 자동으로 쓴다 (싱가포르 리전)
    private val db by lazy { FirebaseDatabase.getInstance() }
    private val stores get() = db.getReference("stores")
    private val orders get() = db.getReference("orders")

    /** 매장 목록을 한 번 읽는다. */
    fun loadStores(onError: (String) -> Unit = {}, onResult: (List<Store>) -> Unit) {
        stores.get()
            .addOnSuccessListener { snap ->
                val list = snap.children.mapNotNull { child ->
                    child.getValue(Store::class.java)?.copy(id = child.key.orEmpty())
                }
                onResult(list)
            }
            .addOnFailureListener { e -> onError(e.message ?: "stores 읽기 실패") }
    }

    /**
     * 주문을 보낸다. 새 주문 id 를 돌려준다.
     * status 는 항상 ordered 로, createdAt 은 지금 시각으로 채운다.
     */
    fun sendOrder(order: Order, onError: (String) -> Unit = {}, onDone: (String) -> Unit) {
        val ref = orders.push()
        val id = ref.key ?: return onError("주문 id 생성 실패")
        val data = order.copy(
            id = "",                       // id 는 경로(키)로만 쓴다
            status = OrderStatus.ORDERED,
            createdAt = order.createdAt.ifEmpty { isoTime() },
        )
        ref.setValue(data.toMap())
            .addOnSuccessListener { onDone(id) }
            .addOnFailureListener { e -> onError(e.message ?: "주문 전송 실패") }
    }

    /** 도착 예정 시각만 갱신한다 (차가 늦어질 때). */
    fun updateEta(orderId: String, etaAt: String) {
        orders.child(orderId).child("etaAt").setValue(etaAt)
    }

    /** 상태만 바꾼다 (차 앱은 픽업 완료 때 picked_up 으로). */
    fun updateStatus(orderId: String, status: String) {
        orders.child(orderId).child("status").setValue(status)
    }

    /**
     * 주문 하나를 실시간으로 지켜본다. 매장이 status 를 바꾸면 onChange 가 다시 불린다.
     * 반환값을 호출하면 구독이 끝난다.
     */
    fun watchOrder(orderId: String, onError: (String) -> Unit = {}, onChange: (Order) -> Unit): () -> Unit {
        val ref = orders.child(orderId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.getValue(Order::class.java)?.let { onChange(it.copy(id = orderId)) }
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        ref.addValueEventListener(listener)
        return { ref.removeEventListener(listener) }
    }

    /**
     * 지금(+plusMin 분) 시각을 ISO-8601 문자열로. 예: 2026-11-05T15:20:00+09:00
     * (minSdk 23 이라 java.time 대신 SimpleDateFormat 사용)
     */
    fun isoTime(plusMin: Int = 0): String {
        val t = System.currentTimeMillis() + plusMin * 60_000L
        val tz = TimeZone.getTimeZone("Asia/Seoul")   // 에뮬레이터 시간대와 상관없이 한국 시간
        val base = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = tz }.format(Date(t))
        val off = tz.getOffset(t) / 60_000
        val sign = if (off >= 0) "+" else "-"
        return base + sign + String.format(Locale.US, "%02d:%02d", Math.abs(off) / 60, Math.abs(off) % 60)
    }

    private fun Order.toMap() = mapOf(
        "storeId" to storeId,
        "item" to item,
        "qty" to qty,
        "status" to status,
        "etaAt" to etaAt,
        "createdAt" to createdAt,
    )
}
