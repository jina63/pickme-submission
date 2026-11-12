# CLAUDE.md

Claude Code가 이 저장소에서 작업할 때 참고하는 안내입니다.

## 프로젝트

pickme — 팀 공모전 프로젝트. 하나의 저장소에 세 부분이 함께 있습니다.

- `car-app/` — 차량용 Android 앱
- `web/` — 웹 프론트엔드
- `server/` — 백엔드 서버
- `docs/` — 문서. 중요한 결정은 `docs/decisions.md`에 기록

## 작업 원칙

- 저장소 관리자는 개발 초보입니다. 설명은 쉬운 말로, 한 단계씩 해 주세요. 사용자가 직접 해야 하는 일은 명확히 알려 주세요.
- 비밀키, 비밀번호, API 키, 토큰은 절대 파일에 쓰거나 커밋하지 마세요. 필요하면 `.env`(커밋 안 됨)를 쓰고, 이름만 담은 `.env.example`을 커밋합니다.
- `.gitignore`에서 막고 있는 파일(`local.properties`, `.env`, `node_modules`, `*.jks`, `*.keystore`, `.DS_Store`)을 강제로 추가(`git add -f`)하지 마세요.
- 커밋이나 push는 사용자가 요청할 때만 합니다.
- 구조나 기술 선택처럼 팀 전체에 영향을 주는 결정을 하면 `docs/decisions.md`에 한 항목을 추가하세요.
