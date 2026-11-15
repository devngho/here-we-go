# 가보자고

차의 위치, 시간, 일정을 고려하여 지금 갈 만한 곳을 골라 내비 경로까지 바로 이어 주는 Pleos Connect 차량용 앱입니다.

- 팀: 이유동지
- 패키지: `io.github.devngho.herewego`

## 주요 기능

| 기능      | 설명                                                                                       |
|-----------|--------------------------------------------------------------------------------------------|
| 지금 여기 | 차가 있는 곳 주변에서 다음에 갈 만한 장소를 추천                                           |
| 가는 길에 | 목적지로 가는 길에 들를 곳을 목적(장보기 등, 자유 입력 가능)에 맞춰 추천하고 경유지로 추가 |
| 빈 시간   | 두 일정 사이 남는 시간에 다녀올 수 있는 곳을 추천                                          |

추천 후보는 서버(`server/`, Kotlin + Ktor)가 카카오 로컬에서 가져와 거리·시간으로 거르고, Gemini가 순서를 정해 한 줄 이유를 붙입니다. 앱은 상위 결과의 주행 시간을 Pleos NaviHelper `requestTrip`으로 다시 계산하고, 안내는 Pleos TextToSpeech로 읽어 줍니다. 서버에 닿지 않으면 내장 샘플 데이터(양재–판교 일대)로 동작합니다.

## APK

- 위치: [`apk/이유동지_가보자고_release.apk`](apk/)
- release 빌드이며 데모 서버 `https://pleos-demo.ngho.dev` 를 사용합니다. 별도 키나 설정 없이 설치해 바로 실행할 수 있습니다.

## 실행 방법

### APK로 실행

1. Pleos Connect 에뮬레이터를 켭니다.
2. APK를 설치합니다.
   ```sh
   adb install -r apk/이유동지_가보자고_release.apk
   ```
3. 앱 목록에서 **가보자고**를 실행합니다. 처음 실행할 때 알림 권한을 허용해 주세요(도착 알림에 사용).

### 소스에서 빌드

```sh
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug        # 디버그 빌드를 바로 설치
./gradlew testDebugUnitTest   # 단위 테스트
```

- JDK 25가 필요합니다(`gradle/gradle-daemon-jvm.properties`). Android Studio에 포함된 JBR을 쓰면 됩니다.
- 기본 서버 주소는 데모 서버입니다. 다른 서버를 쓰려면 `local.properties` 에 적거나 `-P` 옵션으로 넘깁니다.
  ```properties
  herewego.serverUrl=http://10.0.2.2:8080   # 에뮬레이터에서 호스트 PC의 로컬 서버
  herewego.appKey=...                         # 서버에 APP_KEY 를 설정했을 때만
  ```
- release 서명 키를 `local.properties` 의 `herewego.release.storeFile`, `storePassword`, `keyAlias`, `keyPassword` 로 지정할 수 있습니다. 지정하지 않으면 디버그 키로 서명합니다.

## 테스트 환경과 필요한 설정

| 항목        | 값                                                                                                 |
|-------------|----------------------------------------------------------------------------------------------------|
| 에뮬레이터  | Pleos Connect Emulator **v2.1.0** (AVD `Pleos_Connect_v2.1.0`, Android Automotive, API 34, x86_64) |
| 화면        | 2560 × 1440, 160 dpi (가로)                                                                        |
| 앱 SDK      | minSdk 28, targetSdk / compileSdk 37                                                               |
| Pleos SDK   | NaviHelper, TextToSpeech 3.3.1                                                                     |
| 개발 도구   | Android Studio, Gradle 9.6, JDK 25                                                                 |

필요한 설정:

- **네트워크**: 추천에 데모 서버를 사용하므로 에뮬레이터가 인터넷에 연결되어 있어야 합니다. 연결이 안 되면 내장 샘플 데이터를 대신 사용합니다.
- **권한**: Pleos 권한(`NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `TTS_SERVICE`)은 자동으로 허용되며, 처음 실행 시 알림 권한을 요청합니다.

## 데모 영상

[`demo-videos/`](demo-videos/)에 기능별 영상이 있습니다.

- `demo1_now_here.mp4`: 지금 여기
- `demo2_on_the_way.mp4`: 가는 길에
- `demo3_free_time.mp4`: 빈 시간

## 폴더 구조

| 경로                   | 내용                                   |
|------------------------|----------------------------------------|
| `app/`                 | 앱 소스 (Kotlin, Jetpack Compose)      |
| `apk/`                 | 제출용 release APK                     |
| `demo-videos/`         | 데모 영상과 녹화 스크립트              |
