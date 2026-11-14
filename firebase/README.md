# Firebase 데이터와 규칙

Realtime Database의 구조·접근 규칙·시연 매장 시드를 관리합니다. 서버 연결 환경은 [중계 서버](../server/pickme/README.md), 동승자 웹 Hosting은 [동승자 웹](../web/friend/README.md)에서 설명합니다. 매장 주문 관리 사이트의 접속·사용과 연동 확인은 [매장 웹 안내](../web/store/README.md)에서 관리합니다.

## 데이터 구성

| 경로 | 용도·주요 필드 |
| --- | --- |
| `stores/{id}` | 매장 `name`·`lat`·`lng`·`prepMin` |
| `orders/{id}` | 주문 `storeId`·`item`·`qty`·`status`·`etaAt`·`createdAt` |
| `pickmeInvites/{key}` | 초대·탑승 장소 응답·추천 요청·유효기간·공유 소유 UID |
| `pickmeReady/{key}` | 동승자의 준비 완료 시각 |
| `pickmeLive/{key}` | 소유 서버가 쓰는 차량 정보와 동승자용 `public` 데이터 |

주문 상태는 `ordered` → `accepted` → `making` → `ready` → `picked_up`입니다. `etaAt`·`createdAt`은 ISO 시각 문자열이며, 초대·공유의 시각 필드는 각 규칙의 숫자 시각 형식을 따릅니다. 상세 필드는 [데이터 약속](../docs/data-contract.md)과 `database.rules.json`을 기준으로 확인합니다.

픽미 제출 앱은 HTTPS 배포 중계 서버에 연결합니다. 아래 초대·준비·공유 경로는 서버의 Firebase 연결 설정으로 사용하며, 비밀 환경 변수는 제출 컴퓨터가 아닌 배포 서버에 보관합니다. 별도 로컬 개발에서 Firebase 연결을 생략한 경우에만 응답을 서버 메모리로 처리합니다. [환경 구분](../server/pickme/README.md#제출-환경과-시연-영상)을 참고합니다. 딱픽업의 `stores`·`orders` 연결은 별도입니다.

## 매장 웹 연결

매장 대시보드는 [딱픽업 매장 주문 관리](https://pickme-ca1cd-store.web.app/)에서 접속합니다. 매장 선택·화면 사용·주문 연동 확인 절차는 [매장 웹 안내](../web/store/README.md#데이터-연동-확인)를 참고합니다.

차량 앱의 주문 데이터 기준은 위 `stores`·`orders` 구조입니다. 웹과 차량 앱은 같은 DB·매장 식별자를 사용하는지 확인해야 합니다. 현재 저장소에는 배포된 매장 웹의 구현·배포 설정이 없어, 사이트의 실제 DB 설정은 별도 소스 확인이 필요합니다. `firebase.friend.json`은 동승자 사이트용이므로 매장 웹 배포 설정으로 사용하지 않습니다.

## 현재 접근 규칙

- 기본 루트 읽기·쓰기는 차단합니다.
- `stores`는 읽기 허용·클라이언트 쓰기 차단입니다.
- `orders`는 전체 읽기와 주문별 쓰기·삭제를 허용합니다. 새 주문은 `ordered` 상태와 필수 필드를 요구하고 매장 존재·수량·상태값을 검증하며, 기존 주문의 매장·메뉴·수량·생성 시각 변경을 제한합니다. 삭제에는 필드 검증이 적용되지 않으므로 매장 웹의 삭제가 허용됩니다.
- 주문별 쓰기·삭제에 사용자 인증 조건은 아직 없으므로, 필드 검증을 운영용 주문 권한 분리로 간주하지 않습니다.
- 초대는 토큰·유효기간·필드 제약으로 접근을 제한하고 동승자 응답의 변경을 제한합니다.
- 차량 공유 쓰기는 서버용 익명 인증과 초대의 소유 UID를 확인합니다. 동승자는 해당 초대의 공개 공유 데이터만 조회합니다.
- 초대·준비·공유 데이터의 전체 목록 조회와 개별 토큰 경로 접근은 구분됩니다. 만료 접근 차단이 실제 데이터 자동 삭제를 의미하지는 않습니다.

## 시연 매장 시드

`seed/stores.json`은 수원 인계동·성남 판교·고양 일산의 3개 매장을 정의합니다. 준비 시간은 각각 4분·9분·15분이며 좌표·주소·시연용 시간도 포함합니다.

시드 파일을 수정해도 실제 DB는 변경되지 않습니다. 필요한 경우 기존 데이터를 확인하고 **`stores` 하위에만** 가져옵니다. 앱은 추천 시간을 자체 계산하며 시드의 시간 필드를 그대로 모두 사용하는 것은 아닙니다. 앱 기본 후보는 픽업카페 이름을 사용하지만 시드에는 이전 이름이 남아 있어 일관성 정리가 필요합니다.

## 규칙 검증과 적용

Firebase CLI와 에뮬레이터 실행 환경, 서버 의존성을 준비한 뒤 저장소 루트에서 실행합니다.

```bash
firebase emulators:exec --only database,auth --project demo-pickme-invites --config firebase.emulators.json "node --test server/pickme/firebase-rules.test.mjs server/pickme/live-rules.test.mjs server/pickme/live-tracking.test.mjs server/pickme/live-api.test.mjs"
```

실제 규칙 적용은 대상 프로젝트·DB와 기존 규칙을 확인한 뒤 수행합니다. 파일 저장만으로 배포되지는 않습니다.

```bash
firebase deploy --only database --project pickme-ca1cd --config firebase.friend.json
```

Hosting과 규칙의 데이터 형식이 함께 바뀌면 [웹 배포 안내](../web/friend/README.md#hosting-배포)에 따라 호환되는 버전을 적용합니다. `google-services.json`은 이 폴더가 아닌 `car-app/app/`에 두며 커밋하지 않습니다.

## 한계와 보완점

향후에는 주문 인증·매장별 권한 분리, 만료 데이터 자동 삭제, 시드·앱·실제 DB의 일관성을 보완할 예정입니다. 필드 검증과 주문 삭제 허용 범위는 현재 저장소 규칙을 기준으로 설명합니다.
