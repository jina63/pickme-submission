# 픽미 차량 앱

Pleos Connect에서 실행하는 Kotlin / Jetpack Compose 앱입니다. 첫 화면에서 **픽미(친구 초대)** 또는 **딱픽업(매장 주문)**을 선택합니다.

## Android Studio 실행

1. 저장소의 `car-app` 폴더를 엽니다.
2. JDK 21, Android SDK Platform 35를 준비하고 Gradle Sync를 완료합니다.
3. 팀 Firebase 프로젝트의 `google-services.json`을 `app/`에 둡니다. 이 파일과 `local.properties`는 커밋하지 않습니다.
4. Pleos Connect 에뮬레이터에서 실행합니다. (debug·release 모두 같은 화면 구성)
5. 픽미는 [중계 서버 안내](../server/pickme/README.md)에 따라 서버를 켜고, Pleos 내비에 목적지를 설정한 뒤 시작합니다.

## 픽미 흐름

홈 → 픽미 → 등록 연락처 4명 중 선택 → 초대 발송 확인 → 친구 웹에서 탑승 장소 확정 → 차량에서 픽업 수락 순서입니다. 수락하면 친구 장소를 SECOND 경유지로 추가하고 차량 위치·픽업 예상 시간을 공유합니다. 딱픽업의 카페 FIRST 경유지가 확인되면 친구 웹에도 카페 경유 정보를 표시합니다.

픽미 화면과 위치 공유 서비스는 debug·release 모두 같습니다(2026-11-12부터). 다만 친구 초대·응답·위치 공유는 컴퓨터에서 실행한 중계 서버(`10.0.2.2:8787`)가 있어야 동작합니다. 실제 전화번호부·문자 발송은 후속 구현 대상입니다.

## 친구 준비 완료 확인

친구가 웹에서 준비 완료를 누르면 차량 앱의 픽업 화면에 **탑승 준비 완료**가 표시됩니다. 내비 위 팝업은 사용하지 않으며, 별도의 표시 권한 설정이 필요하지 않습니다.

## 명령줄 빌드·실행

`car-app`에서 실행합니다. JAVA_HOME은 JDK 21, ANDROID_HOME은 각자 설치한 Android SDK 경로로 설정합니다.

```bash
bash ./gradlew assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.pickme.car/.home.HomeActivity
```

패키지는 `com.pickme.car`이며 `home/`(첫 화면), `pickme/`(픽미: `ui`·`sharing`), `pickup/`(딱픽업: `ui`·`domain`), `core/`(공용: `navi`·`firebase`·`model`·`ui/theme`)으로 나눕니다. 내비 경유지 변경은 `core/navi/NaviRemote`를 사용합니다. **FIRST=매장, SECOND=친구** 순서는 유지합니다. 딱픽업 세부 동작은 [프로젝트 안내](../README.md)를 참고하세요.


## 에뮬레이터에서 내비 서버 주소를 찾지 못할 때

컴퓨터에서는 접속되는데 내비 로그에 `UnknownHostException`이 반복된다면 에뮬레이터 DNS를 확인합니다. 2026-11-10에는 컴퓨터의 DNS가 IPv6 링크 로컬 주소였고, 에뮬레이터에서 기본 DNS 조회가 실패했습니다. 실행 중인 에뮬레이터를 종료한 뒤 아래처럼 **이번 실행에만** 공용 DNS를 지정해 복구했습니다.

```bash
"$ANDROID_HOME/emulator/emulator" -avd Pleos_Connect_v2.1.0 -no-snapshot -no-metrics -dns-server 8.8.8.8,1.1.1.1 -gpu host -feature -Vulkan -crash-report-mode never
```

기본 그래픽 설정에서는 에뮬레이터 창이 종료되고, 소프트웨어 그래픽 모드에서는 차량 기본 화면이 충돌하는 현상이 있었습니다. 위 예시는 기본 GPU를 사용하면서 Vulkan만 끕니다. 이 명령은 앱 데이터 삭제나 컴퓨터 전체 DNS 변경을 하지 않습니다. 기관 네트워크에서 공용 DNS를 제한하면 해당 네트워크에서 허용한 DNS를 사용해야 합니다.

## 친구 초대 QR

초대 생성 후 **휴대폰으로 초대 열기 · QR**을 누르면 이메일과 같은 친구 링크를 QR로 표시합니다. 휴대폰 카메라로 열며, 앱에 카메라 권한을 요구하지 않습니다. 공개 HTTPS 친구 링크와 연결된 서버가 필요합니다. QR 생성은 ZXing Core 3.5.3(Apache-2.0)으로 기기 안에서 처리하며, 라이선스와 고지는 APK의 `assets/licenses/ZXing-*`에 포함합니다. 문자 발송은 후속 개발 대상입니다.

## 요청은 보내졌는데 목적지가 계속 연결되지 않을 때

2026-11-12에는 내비 권한이 허용되고 `GetCurrentLocationInfo`·`GetDestinationInfo` 요청이 순정 내비까지 전달됐지만 조회 응답이 돌아오지 않는 상태를 확인했습니다. 픽미 목적지 코드 변경 없이 **순정 내비만 재실행**하자 응답이 복구됐습니다. 재실행하면 진행 중인 목적지는 다시 설정해야 할 수 있습니다. 앱 데이터 삭제는 필요하지 않았습니다.

복구 확인 순서: 순정 내비 재실행 → 목적지 검색 → **안내 시작** → 픽미로 돌아오기 → **함께 갈 목적지**에 이름이 표시되는지 확인합니다. 2026-11-12에는 CGV 판교를 다시 설정한 뒤 픽미 자동 연결까지 확인했습니다. 같은 증상이 반복되면 앱 요청 로그·내비 응답 로그를 함께 확인하고, 연결 성공 표시만으로 목적지 조회 성공으로 판단하지 않습니다.

## 진행 중인 픽업으로 돌아오기

내비를 보다가 픽미를 다시 열면 같은 초대의 픽업 화면을 복원하고 친구 준비 완료 상태를 서버에서 다시 조회합니다. 위치 공유 중에는 홈의 **진행 중인 픽업 보기**도 사용할 수 있습니다. 새 친구를 초대하려면 기존 위치 공유를 중지하고 **다른 친구 선택하기**을 누릅니다. 초대와 소유 세션은 앱 프로세스 메모리에 유지하므로, 앱 프로세스 종료·APK 교체·서버 재시작 후에는 새 초대가 필요합니다.
