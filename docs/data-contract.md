# 데이터 약속 (Firebase Realtime Database)

차량 앱(`car-app`)과 매장 웹(`web/store`)은 Firebase Realtime Database의 매장·주문 데이터를 공유합니다. 아래 표는 두 구성 요소가 사용하는 데이터 형식과 작성 주체를 정의합니다. 초대·차량 공유 경로와 접근 규칙은 [Firebase 안내](../firebase/README.md)에서 관리합니다.

| 경로 | 필드 | 쓰는 쪽 | 읽는 쪽 |
| --- | --- | --- | --- |
| `stores/{id}` | `name`, `lat`, `lng`, `prepMin`(준비 시간, 분) | 팀(시드 파일) | 차량 앱 |
| `orders/{id}` | `storeId`, `item`, `qty`, `status`, `etaAt`(도착 예정 시각, ISO), `createdAt` | 차량 앱 | 매장 웹 |
| `orders/{id}/status` | `ordered` → `accepted` → `making` → `ready` → `picked_up` | 주문 생성은 차량 앱, 접수·제조·준비 상태는 매장 웹 | 차량 앱·매장 웹 |

`picked_up`은 데이터 모델에 정의된 수령 완료 상태입니다. 현재 차량 앱은 해당 상태를 표시할 수 있지만 수령 완료를 기록하는 화면 동작은 연결되어 있지 않습니다. 수령 완료 입력·저장 흐름은 향후 보완 범위입니다.

## 주문 데이터 예시

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

내비 경유지 변경은 `car-app/.../core/navi/NaviRemote`에서 처리합니다. 방문 순서·반영 확인 기준은 [공통 내비 안내](../car-app/app/src/main/java/com/pickme/car/core/navi/README.md)에서 관리합니다.
