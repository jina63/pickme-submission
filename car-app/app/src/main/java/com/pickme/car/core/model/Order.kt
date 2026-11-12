package com.pickme.car.core.model

/**
 * 매장 주문. Firebase 경로: orders/{id}
 * 형식은 docs/data-contract.md 를 따른다.
 */
data class Order(
    val id: String = "",
    val storeId: String = "",
    val item: String = "",
    val qty: Int = 1,
    /** ordered → accepted → making → ready → picked_up */
    val status: String = OrderStatus.ORDERED,
    /** 매장 도착 예정 시각 (ISO-8601, 예: 2026-11-05T15:20:00+09:00) */
    val etaAt: String = "",
    /** 주문 생성 시각 (ISO-8601) */
    val createdAt: String = "",
)

object OrderStatus {
    const val ORDERED = "ordered"
    const val ACCEPTED = "accepted"
    const val MAKING = "making"
    const val READY = "ready"
    const val PICKED_UP = "picked_up"
}
