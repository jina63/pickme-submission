# core/navi
순정 내비(NaviHelper) 조작은 이 폴더의 `NaviRemote` 한 곳에서만 한다.
- 경유지 규칙: **FIRST = 매장(딱픽업), SECOND = 친구(픽미)**
- 담당: 친구 4 (픽미·딱픽업 둘 다 사용하므로 PR 리뷰 필수)

## Pleos 경유지 순서에서 알아 둘 점 (2026-11-11 에뮬레이터 확인)
- Pleos는 **빈 앞자리를 먼저 채웁니다.** 친구를 SECOND로 넣어도 FIRST가 비어 있으면 친구가 FIRST로 당겨지고, 그 뒤에 넣은 매장은 친구 뒤에 붙습니다(친구 → 매장).
- `removeWaypoint()`는 경유지 하나만 빼는 게 아니라 **경로 자체를 취소**해 버립니다. 순서를 바꿀 때 쓰지 않습니다.
- 그래서 매장을 넣기 전에 `getWaypointInfo()`로 순서를 확인하고, 친구가 앞에 있으면 `requestRouteWithWaypoints(목적지, [매장 FIRST, 친구 SECOND])`로 경로를 한 번에 다시 요청합니다. 판단은 `friendSlotToReorder()`(단위 테스트 `StoreOrderTest`)가 합니다.
- 친구가 없으면 예전처럼 `addWaypoint(매장 FIRST)`만 합니다. 경유지 목록 응답이 3초 안에 없어도 이 방식으로 넘어갑니다.
- 에뮬레이터는 경유지 좌표를 0으로 돌려주므로 이름으로 경유지를 구분합니다.
