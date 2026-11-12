# firebase
Firebase 프로젝트 `pickme` 설정을 코드처럼 관리하는 폴더. (담당: 친구 2)

- `database.rules.json`: Realtime Database 규칙. 파일 수정만으로는 적용되지 않으며, 검증 후 Firebase CLI로 배포합니다. 친구 웹·규칙 배포 명령은 [서버 안내](../server/pickme/README.md)를 참고하세요.
- `seed/stores.json`: 2026-11-10 현재 Firebase의 수원·판교·일산 시연 매장 3곳을 읽어 맞춘 예시입니다. 좌표·주소·준비 시간·시연용 이동 시간을 포함합니다. 기존 운영 데이터를 자동으로 덮어쓰지 않습니다. 필요할 때만 `stores` 하위에 가져오세요.
- `google-services.json`은 여기에 두지 않는다. 각자 Firebase 콘솔에서 받아 `car-app/app/`에 넣는다 (커밋 금지)
