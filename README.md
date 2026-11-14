# Pickme — 픽미 & 딱픽업

Pickme는 Pleos 차량 환경에서 동승자 픽업과 이동 중 음료 픽업을 지원하는 Android 애플리케이션입니다. **픽미**는 초대·탑승 장소·차량 도착 정보를 연결하고, **딱픽업**은 경로와 준비 시간을 기준으로 매장과 주문 전송 시점을 조율합니다.

## 한눈에 보기

| 항목 | 내용 |
| --- | --- |
| 픽미 | 링크·이메일·QR 초대, 탑승 장소 확정, 경유지 연결, 차량 정보·준비 상태 공유 |
| 딱픽업 | 음료 선택, 매장 추천, 경유지 요청, 주문 예약·전송·상태 표시 |
| 구성 | 차량 앱, 동승자 웹, 중계 서버, Firebase Realtime Database |
| 구현 범위 | Pleos 에뮬레이터·HTTPS 배포 중계 서버를 사용하는 시연용 프로토타입 |

## 제출 APK와 실행 환경

| 항목 | 내용 |
| --- | --- |
| 제출 APK | [apk/345_Pickme_release.apk](apk/345_Pickme_release.apk) |
| 테스트 환경 | Pleos Connect v2.1.0 · Android 14 / API 34 · arm64 |
| 화면 규격 | 2560 × 1440 · 가로 · 12.8인치 · mdpi |
| 실행 준비 | APK 설치·인터넷 연결·차량 내비 목적지 설정. 로컬 서버·`.env` 준비 불필요 |

## 사용 흐름

1. 차량 내비에 목적지를 설정하고 홈에서 픽미 또는 딱픽업을 선택합니다.
2. 픽미에서는 초대를 받은 동승자가 웹에서 탑승 장소를 확정하고 운전자가 픽업을 수락합니다.
3. 딱픽업에서는 음료·추천 매장을 확인하고 경유지 요청과 주문 예약을 진행합니다.
4. 차량 앱과 동승자 웹은 HTTPS 배포 서버와 Firebase를 통해 차량 위치·도착 예상 시간·준비 상태를 공유합니다.

설치·실행 명령은 [제출 APK 설치 안내](apk/README.md)에서 확인합니다. 제공된 서명 APK는 소스 빌드나 `google-services.json` 준비 없이 설치할 수 있으며, [배포 중계 서버](https://pickme-relay.onrender.com)에 접속합니다. 초대 링크·QR로 휴대폰에서 동승자 웹을 열 수 있고, 이메일은 서버의 비공개 발송 설정에 따라 동작합니다. [제출 환경 안내](server/pickme/README.md#제출-환경과-시연-영상)를 참고합니다.

## 문서와 프로젝트 구성

| 위치 | 상세 설명 |
| --- | --- |
| [제출 APK](apk/README.md) | APK 다운로드·설치·실행, 파일 정보·서명 확인 범위 |
| [차량 앱](car-app/README.md) | 개발 환경, 소스 빌드·서명, 앱 폴더 구성 |
| [공통 내비](car-app/app/src/main/java/com/pickme/car/core/navi/README.md) | 경유지 순서·반영 확인·SDK 제약 |
| [픽미](car-app/app/src/main/java/com/pickme/car/pickme/README.md) | 초대부터 위치·준비 상태 공유까지의 기능 구현 |
| [딱픽업](car-app/app/src/main/java/com/pickme/car/pickup/README.md) | 매장 추천 공식·주문 시점·주문 진행 |
| [Firebase](firebase/README.md) | 데이터 구조·권한·시드·규칙 검증·배포 |
| [중계 서버](server/pickme/README.md) | 서버 실행·환경 설정·API·메일 처리 |
| [동승자 웹](web/friend/README.md) | 장소 선택·지도·상태 표시·Hosting 배포 |
| [매장 웹](web/store/README.md) | 현재 구현 범위와 연동 참조 |
| `docs/` | [데이터 약속](docs/data-contract.md), [결정 기록](docs/decisions.md), 기존 검증 기록 |

## 한계와 보완점

현재는 실제 결제·상용 매장 주문 시스템이 연결되지 않은 프로토타입입니다. 향후에는 예외 상황의 사용자 안내·재시도·복구 흐름과 앱 프로세스 종료 후 초대·주문 예약 복원을 보완할 예정입니다.
