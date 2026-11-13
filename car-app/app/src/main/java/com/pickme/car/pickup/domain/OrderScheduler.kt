package com.pickme.car.pickup.domain

/** 주문을 언제 보낼지에 대한 계획 */
data class OrderPlan(
    val sendDelayMin: Int,     // 지금부터 몇 분 뒤에 주문을 보낼지
    val waitAtStoreMin: Int,   // 도착 후 음료를 기다려야 하는 시간
) {
    val message: String
        get() = when {
            sendDelayMin > 0 -> "${sendDelayMin}분 뒤 주문 전송"
            waitAtStoreMin > 0 -> "지금 바로 주문 전송 · 도착 후 약 ${waitAtStoreMin}분 기다려요"
            else -> "지금 바로 주문 전송"
        }
}

object OrderScheduler {

    /** 제출용 실제 시간: 계획의 1분은 실제 60초다. */
    const val SECONDS_PER_PLAN_MINUTE = 60L

    /**
     * 이동이 준비보다 길면: 그 차이만큼 기다렸다가 주문을 보낸다(도착 시 음료가 막 나옴).
     * 준비가 더 길면: 바로 보내고 도착 후 차이만큼 기다린다.
     */
    fun plan(travelMin: Int, prepMin: Int): OrderPlan =
        OrderPlan(
            sendDelayMin = maxOf(0, travelMin - prepMin),
            waitAtStoreMin = maxOf(0, prepMin - travelMin),
        )

    fun delayMillis(plan: OrderPlan): Long =
        plan.sendDelayMin * SECONDS_PER_PLAN_MINUTE * 1000L
}