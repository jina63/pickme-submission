package com.pickme.car.core.model

/**
 * 가짜(제휴) 매장. Firebase 경로: stores/{id}
 * 형식은 docs/data-contract.md 를 따른다.
 */
data class Store(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 지금 주문하면 준비까지 걸리는 시간(분). 대기 주문이 반영된 값 */
    val prepMin: Int = 0,
)
