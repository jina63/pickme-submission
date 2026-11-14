# Pickme 차량 앱

Pleos에서 픽미·딱픽업을 실행하는 Android 앱입니다. Kotlin을 기반으로 Jetpack Compose와 Android View를 함께 사용합니다. 기능 설명은 [픽미](app/src/main/java/com/pickme/car/pickme/README.md)·[딱픽업](app/src/main/java/com/pickme/car/pickup/README.md), 경유지 규칙은 [공통 내비](app/src/main/java/com/pickme/car/core/navi/README.md)에서 관리합니다.

## 한눈에 보기

| 항목 | 설정 |
| --- | --- |
| 앱 식별자·시작 화면 | `com.pickme.car` · `com.pickme.car.home.HomeActivity` |
| Gradle 실행 JDK / 컴파일 대상 | JDK 21 / Java·Kotlin JVM 17 |
| Android SDK | compileSdk·targetSdk 35, minSdk 23 |
| Gradle Wrapper / NaviHelper | 8.10.2 / 3.0.5 |
| 테스트 환경 | Pleos Connect v2.1.0, Android 14 / API 34, arm64 |
| 화면 규격 | 2560 × 1440, 가로, 12.8인치, mdpi |
| Firebase 설정 | `app/google-services.json` |

## 제출 APK 실행

서명된 `apk/345_Pickme_release.apk`가 저장소에 포함되어 있습니다. 다운로드·설치·실행 명령과 파일 정보는 [제출 APK 안내](../apk/README.md)에서 관리합니다. 제공된 APK를 설치할 때는 소스 빌드나 별도의 `google-services.json` 파일을 준비하지 않습니다.

## 소스에서 release 빌드

1. Android Studio에서 `car-app`을 열고 SDK Platform 35·Gradle JDK 21을 설정합니다.
2. Firebase 프로젝트에 맞는 `google-services.json`을 `app/`에 배치하고 Gradle Sync를 완료합니다.
3. release로 빌드하고 서명된 APK를 Pleos Connect 에뮬레이터에 설치합니다. 홈에서 최신 픽미·딱픽업 화면으로 진입합니다.
4. 픽미는 HTTPS 배포 서버에 접속합니다. [제출 환경 안내](../server/pickme/README.md#제출-환경과-시연-영상)에 따라 초대 링크·QR을 휴대폰에서 열어 확인하며, 개인 컴퓨터에서 `.env`나 서버 실행은 필요하지 않습니다.
5. 내비 목적지를 설정하고 안전하게 정차한 상태에서 기능을 실행합니다.

`car-app`에서 실행하는 release 빌드 명령입니다.

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleRelease
```

```bash
# macOS·Linux
bash ./gradlew :app:assembleRelease
```

현재 `app/build.gradle.kts`에는 release 서명 설정이 없으므로 빌드 명령만으로 제출·설치용 서명이 완료되지는 않습니다. 새 APK를 만드는 경우 Android Studio의 **Build → Generate Signed App Bundle or APK → APK**에서 별도 서명하거나 담당자가 관리하는 서명 절차를 사용합니다. 서명 키(`.jks`·`.keystore`)는 저장소 밖에서 보관하며 커밋하지 않습니다. 빌드 결과가 저장소의 제출 APK를 자동으로 교체하지는 않습니다. 새 파일을 설치할 때도 [APK 설치 안내](../apk/README.md#설치와-실행)를 따릅니다.

release에 초대 화면·위치 공유 서비스가 포함되며 두 통신 경로 모두 `https://pickme-relay.onrender.com`을 사용합니다. `google-services.json`·`local.properties`·비밀 설정은 커밋하지 않습니다. 서버 `.env` 미포함과 차량 앱의 Firebase 빌드 설정은 별개입니다.

## 친구 준비 상태와 초대 화면

준비 상태는 차량 앱의 픽업 화면에 표시하며, 별도의 오버레이 권한이나 ADB 설정이 필요하지 않습니다. 초대 QR·진행 중인 픽업 복원은 [픽미 안내](app/src/main/java/com/pickme/car/pickme/README.md)에서 관리합니다.

## 개발용 빌드와 테스트

아래 명령은 개발용 빌드와 단위 테스트에 사용합니다.

`car-app`에서 실행합니다.

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

```bash
# macOS·Linux
bash ./gradlew :app:assembleDebug
bash ./gradlew :app:testDebugUnitTest
```

개발용 APK 설치 예시입니다. 장치 ID는 실제 연결된 값으로 바꿉니다.

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

### Firebase 설정 없는 개발 빌드

`google-services.json` 없이 초대 관련 코드의 빌드·시험에 사용하는 별도 개발 옵션입니다. Google Services 설정 플러그인을 생략하므로 딱픽업 Firebase 기능 시험이나 제출용 빌드를 대체하지 않습니다. 옵션 이름과 관계없이 앱의 서버 주소는 HTTPS 배포 주소를 유지하며 로컬 서버로 자동 전환되지 않습니다.

```powershell
.\gradlew.bat :app:assembleDebug -PpickmeLocalInvites=true
```

macOS·Linux는 `bash ./gradlew :app:assembleDebug -PpickmeLocalInvites=true`를 사용합니다. 서버 연결은 [중계 서버 안내](../server/pickme/README.md#차량-연결과-로컬-시험)를 참고합니다.

## 파일 지도

기준 경로는 `app/src/main/java/com/pickme/car/`입니다.

| 위치 | 역할 |
| --- | --- |
| `home/` | 기능 선택 |
| `pickme/ui/`·`sharing/` | 초대·QR·프로세스 내 초대 복원·위치 공유 |
| `pickup/ui/`·`domain/` | 주문 화면·추천 및 예약 계산 |
| `core/navi/`·`firebase/`·`model/`·`ui/theme/` | 내비·데이터 연결·공용 모델·테마 |
| `app/src/main/AndroidManifest.xml` | 공통 화면·내비 권한·픽미 공유 서비스 |
| `app/src/main/res/xml/pickme_dev_network.xml` | 공통 로컬 개발 서버 HTTP 허용 |
| `app/build.gradle.kts` | 빌드·SDK·의존성 설정 |

## 한계와 보완점

| 항목 | 현재 상태·확인 사항 |
| --- | --- |
| release 제출 | 새 Pleos 에뮬레이터 설치·기본 연결과 픽미·딱픽업 정상 시나리오 연동 확인. 예외 상황의 안내·복구 절차는 향후 보완 예정 ([상세 안내](../apk/README.md#파일-확인과-검증-범위)) |
| 서버 연결 | HTTPS 배포 주소 사용. 인터넷 연결·서버 상태·초대 유효기간 확인 |
| Firebase 빌드 오류 | `app/google-services.json` 배치와 앱 설정 일치 여부 확인 |
| 준비 상태 미표시 | 진행 중인 픽업 화면으로 돌아가 서버 응답·초대 유효기간 확인 |
| 내비 `UnknownHostException` | 앱 서버 장애와 구분해 에뮬레이터 DNS·Pleos 도메인 접근 확인. 재실행 DNS는 해당 네트워크에서 허용한 값 사용 |

개발 시험 절차와 SDK·시연 관련 기록은 저장소 `docs/`에서 관리합니다.

QR 생성은 ZXing Core 3.5.3을 사용하며 라이선스·고지는 `app/src/main/assets/licenses/ZXing-*`에 포함되어 있습니다.
