# 데이터 약속 (Firebase Realtime Database)

차 앱(`car-app`)과 가짜 매장(`web/store`)은 Firebase를 통해서만 만난다. 아래 형식만 맞으면 각자 따로 만들어도 붙는다.

| 경로 | 필드 | 쓰는 쪽 | 읽는 쪽 |
| --- | --- | --- | --- |
| `stores/{id}` | `name`, `lat`, `lng`, `prepMin`(지금 준비시간, 분) | 친구 2(시드) | 차 앱 |
| `orders/{id}` | `storeId`, `item`, `qty`, `status`, `etaAt`(도착 예정 시각, ISO), `createdAt` | 차 앱 | 가짜 매장 |
| `orders/{id}/status` | `ordered` → `accepted` → `making` → `ready` → `picked_up` | 처음은 차 앱, 이후 가짜 매장 (`picked_up`은 차 앱) | 양쪽 |

예시
```json
{
  "orders": {
    "o-0001": {
      "storeId": "store-a",
      "item": "돌체 라떼",
      "qty": 1,
      "status": "ordered",
      "etaAt": "2026-11-05T15:20:00+09:00",
      "createdAt": "2026-11-05T15:00:00+09:00"
    }
  }
}
```

## 순정 내비 경유지 규칙
- 내비 조작은 `car-app/.../core/navi/NaviRemote`에서만 한다.
- **FIRST = 매장(딱픽업), SECOND = 친구(픽미)**
