# 제출 APK

Pleos 차량 환경에서 픽미·딱픽업을 실행하는 서명된 release APK를 제공합니다. [APK 다운로드](https://github.com/jina63/pickme-submission/raw/refs/heads/main/apk/345_Pickme_release.apk) 또는 [저장소 파일](345_Pickme_release.apk)에서 받을 수 있습니다.

## 한눈에 보기

| 항목 | 내용 |
| --- | --- |
| 파일 | `345_Pickme_release.apk` |
| 크기 | 7,462,028바이트 (약 7.12 MiB) |
| 앱 식별자 | `com.pickme.car` |
| 버전 | `1.0.0-sdk35` · versionCode `35010000` |
| Android SDK | minSdk 23 · targetSdk 35 |
| 시작 화면 | `com.pickme.car.home.HomeActivity` |
| 서명 | `apksigner` 검증 통과 · v1·v2 서명 |

## 설치와 실행

1. Pleos Connect 에뮬레이터를 실행하고 인터넷에 연결합니다.
2. APK를 다운로드하거나 저장소를 받아 아래 명령으로 설치합니다.
3. 앱 홈에서 픽미·딱픽업을 선택합니다. 기능을 사용하기 전에 차량 내비의 목적지를 설정합니다.

다음 명령은 저장소 루트 기준입니다. `emulator-5554`는 `adb devices`에 표시되는 실제 장치 ID로 바꿉니다. 파일만 다운로드한 경우에는 APK 경로를 해당 파일 위치로 바꿉니다.

```bash
adb devices
adb -s emulator-5554 install -r "apk/345_Pickme_release.apk"
adb -s emulator-5554 shell am start -n com.pickme.car/.home.HomeActivity
```

기존 개발 앱과 제출 APK의 서명이 다른 환경에서는 덮어쓰기 설치가 실패합니다. 새 Pleos 에뮬레이터에서는 설치·실행이 확인되었으며, 기존 앱·데이터를 유지해야 하는 경우 새 에뮬레이터에 설치합니다.

제공된 APK를 설치할 때는 Android Studio·소스 빌드·별도의 `google-services.json` 파일이 필요하지 않습니다. 차량 앱은 HTTPS 배포 서버를 사용하므로 심사위원 컴퓨터에서 `.env`나 로컬 서버를 실행하지 않습니다. 초대·이메일·휴대폰 참여 조건은 [제출 환경 안내](../server/pickme/README.md#제출-환경과-시연-영상), 소스 수정 후 재빌드·서명은 [차량 앱 안내](../car-app/README.md#소스에서-release-빌드)를 참고합니다.

## 파일 확인과 검증 범위

제출 APK의 서명·설치·기본 연결과 픽미·딱픽업 정상 시나리오 연동을 확인했습니다.

| 구분 | 확인 결과 |
| --- | --- |
| 배포 파일 | 다운로드 파일과 저장소 원본 일치·APK 서명 정상 |
| 설치·기본 화면 | 새 Pleos 에뮬레이터 설치·실행, 홈 → 픽미·딱픽업 진입 정상 |
| 서버 연결 | 배포 중계 서버 연결·시연 연락처 4명 표시 정상 |
| 매장·추천 화면 | Firebase 매장 3곳 조회·메뉴 선택·추천 화면 진입 정상 |
| 픽미 정상 시나리오 | 목적지 설정·친구 초대·응답·운전자 수락·차량 정보 공유 연동 확인 |
| 딱픽업 정상 시나리오 | 카페 경유·주문·매장 상태의 차량 반영과 친구 웹의 위치·ETA·카페 경유 표시 연동 확인 |

관련 소스의 단위 테스트 26개 통과 결과를 함께 기록했습니다.

경로 기반 추천을 사용하려면 먼저 내비 목적지를 설정합니다. 목적지 미설정 상태에서는 기본값으로 추천이 계산되며, 정상 시나리오의 연동 확인은 목적지를 설정한 픽업·주문 흐름을 기준으로 합니다.

향후에는 경유지 확인 실패·시간 초과·통신 단절·중복 입력 등 예외 상황을 중심으로 사용자 안내·재시도·복구 흐름을 보완할 예정입니다.

파일의 SHA-256은 다음과 같습니다.

```text
6a8533f17d3034ded041f7b5f0eb8c6f418e89105e6ebee7c58f5736b79e7940
```

현재 파일을 확인하는 명령입니다.

```powershell
Get-FileHash -Algorithm SHA256 "apk/345_Pickme_release.apk"
```

APK를 교체하면 버전·파일 크기·해시·검증 범위를 함께 갱신합니다. 서명 키(`.jks`·`.keystore`)와 비밀 설정은 저장소에 포함하지 않습니다.
