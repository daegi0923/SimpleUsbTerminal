# SimpleUsbTerminal 개발 준비

작성일: 2026-10-12. 현재 checkout의 소스 및 빌드 설정 기준이다.

## 앱 기술 구성

Android 네이티브 앱이다. 앱 코드는 Java, 화면은 XML 기반 Android Views이며 AppCompat와 Fragment를 사용한다. Kotlin, Jetpack Compose, Flutter, React Native 기반 프로젝트가 아니다.

| 항목 | 현재 설정 |
|---|---|
| 앱 코드 | Java, 소스/타깃 호환성 Java 8 |
| 화면 | `app/src/main/res/layout/`의 XML |
| 빌드 설정 | Groovy Gradle + TOML 버전 카탈로그 |
| Gradle Wrapper | 9.6.1 |
| Android Gradle Plugin | 9.2.1 |
| Android compile / target SDK | 36 / 36 |
| 최소 Android SDK | 21 (Android 5.0) |
| USB 시리얼 라이브러리 | usb-serial-for-android 3.11.0 |
| UI 라이브러리 | AppCompat 1.7.1, Material 1.13.0 |
| 앱 ID | `de.kai_morich.simple_usb_terminal` |

Java 8 소스 호환성과 빌드 도구 실행용 JDK 버전은 서로 다르다.
Gradle 9 실행에는 JDK 17 이상이 필요하며, AGP 9.2 공식 호환성 표의 JDK 기준도 17이다.
Android Studio의 Gradle JDK에 호환되는 내장 JDK 또는 별도 JDK를 지정한다.

## 소스 구조

| 파일 | 역할 |
|---|---|
| `MainActivity.java` | 앱 진입, 화면 전환, USB 연결 Intent 처리 |
| `DevicesFragment.java` | USB 장치 목록, 장치/포트 및 baud rate 선택 |
| `TerminalFragment.java` | 연결 절차, 송수신 표시, HEX 및 흐름 제어 UI |
| `SerialSocket.java` | USB 포트 읽기/쓰기와 통신 콜백 |
| `SerialService.java` | 백그라운드 통신 유지 및 화면 분리 중 데이터 버퍼링 |
| `SerialListener.java` | 수신·연결·오류 콜백 인터페이스 |
| `CustomProber.java` | 추가 USB 장치와 드라이버 매핑 |
| `TextUtil.java` | 텍스트와 HEX 표현 보조 |

수신 경로의 개요:

```text
USB 장치 → usb-serial-for-android → SerialSocket
         → SerialService → TerminalFragment → 화면
```

PLC 기능을 추가한다면 기존 USB 통신을 활용하고, 메시지 조립·해석과 상태 표시를 추가하는 방식으로 시작할 수 있다.
현재 레포는 data bits 8 / stop bits 1 / parity none으로 고정되어 있으므로 현장 설정과 먼저 대조해야 한다.

## 현재 Mac 확인 결과

- `/usr/libexec/java_home -V`에서 확인된 JDK는 Temurin 8, x86_64이다.
- 기본 경로 `/Applications/Android Studio.app`, `~/Library/Android/sdk`에는 설치가 확인되지 않았다. 다른 위치의 설치 여부는 미확인이다.
- 이 문서 작성 과정에서 설치, Gradle Sync, APK 빌드는 수행하지 않았다.

## 개발 환경 준비 순서

1. AGP 9.2를 지원하는 Android Studio를 설치한다. 버전 호환성은 공식 표로 확인한다.
2. Android Studio에서 이 레포 루트를 Open한다.
3. SDK Manager에서 Android SDK Platform 36, SDK Platform-Tools 및 빌드에 필요한 SDK Build-Tools를 준비한다.
4. Android Studio 설정의 Build, Execution, Deployment → Build Tools → Gradle에서 호환 JDK를 선택한다. JDK 8을 선택하지 않는다.
5. Gradle Sync를 실행한다. 최초 실행은 Gradle, Google Maven, Maven Central, JitPack 다운로드를 위한 네트워크가 필요하다.
6. 원본 상태에서 Debug APK를 빌드한다. 성공 후 기능 개발을 시작한다.
7. Android 실기기에서 USB 디버깅을 켜고 설치·실행한다. 현장 USB 연결은 USB Host/OTG 지원 기기, 적합한 어댑터와 케이블, USB 접근 권한이 필요하다.

에뮬레이터는 화면 확인에 활용하고, 실제 USB 시리얼 송수신은 실기기와 장비로 검증한다.

## 터미널 빌드와 설치

아래 명령은 호환 JDK와 SDK 설정을 마친 뒤 레포 루트에서 실행한다.
Android Studio에서 지정한 Gradle JDK와 터미널의 JAVA_HOME은 다를 수 있으므로 먼저 확인한다.

```sh
java -version
./gradlew --version
./gradlew assembleDebug
```

기본 출력 경로:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Platform-Tools의 adb가 PATH에 있고 실기기가 연결된 경우:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

SDK 경로가 자동 인식되지 않으면 로컬 전용 `local.properties`에 실제 SDK 경로를 지정한다.
이 파일은 현재 `.gitignore`에 포함되어 있다.

```properties
sdk.dir=/Users/daegi/Library/Android/sdk
```

위 경로는 기본 설치 위치의 예시이며, 실제 설치 경로로 맞춘다.

## 현장 APK와 개발 APK

레포 루트의 `serial-usb-terminal.apk` 내부 manifest는 Serial USB Terminal 1.60 / versionCode 105 / `de.kai_morich.serial_usb_terminal`이다.
이 레포는 SimpleUsbTerminal 1.0 / versionCode 1이며, README에서도 간소화 버전임을 명시한다.
패키지 ID가 달라 기존 현장 앱과 개발 앱을 함께 설치할 수 있다.

현장 배포 전에는 필요한 기존 기능을 확인하고 실제 장비로 검증한다.
정식 배포용 Release APK는 서명 키와 버전 관리가 필요하며, 같은 앱의 후속 업데이트에는 동일한 서명 키를 유지해야 한다.
현재 문서의 명령은 개발용 Debug APK 기준이다.

## 서명된 배포 APK

다른 휴대폰에 APK 파일을 직접 전달하려면 배포 키로 서명한 Release APK를 사용한다. 이 프로젝트는 Java 기반 Android 앱이며 Gradle이 빌드와 서명을 함께 처리한다. Release APK는 개발 서버 없이 단독 실행된다.

`keystore.properties.example`을 참고해 레포 루트에 `keystore.properties`를 준비한다. `storeFile`에는 기존 배포 키의 절대 경로를 지정한다. 실제 설정 파일과 `.jks`/`.keystore`/`.p12` 파일은 Git에서 제외한다. 서명 설정이 없으면 Release 빌드는 실패하며 Debug 빌드는 계속 사용할 수 있다.

```sh
./gradlew assembleRelease lintRelease
```

출력은 `app/build/outputs/apk/release/app-release.apk`이다. 제어값 변경 이력 분리·순번 기록 버전은 SC Log Miner 1.7, versionCode 8, 앱 ID `de.kai_morich.simple_usb_terminal`, 최소 Android 5.0(API 21)이다. USB 수신에는 휴대폰의 USB Host/OTG 지원과 호환 어댑터가 별도로 필요하다.

2026-10-19 최초 로컬 배포 키는 `~/.android/bct-sc-plc-release/sc-log-miner-release.jks`, 별도 보관용 서명 설정은 같은 폴더의 `keystore.properties`에 생성했다. 이 폴더를 접근 권한을 제한한 별도 저장소에 백업한다. APK 수신자에게는 APK만 전달하고 키와 비밀번호는 전달하지 않는다.

후속 업데이트는 같은 앱 ID와 같은 배포 키를 유지하고 `app/build.gradle`의 versionCode를 올려 빌드한다. 키를 잃으면 기존 앱 위에 업데이트할 수 없다. 기존 Debug APK처럼 서명이 다른 앱이 이미 설치돼 있으면 설치가 거부되므로, 필요한 로그를 먼저 보관한 뒤 기존 앱을 삭제하고 설치해야 한다. 앱 삭제 시 내부 로그와 설정도 삭제된다.

휴대폰에서 APK를 열고 해당 브라우저나 파일 앱의 '알 수 없는 앱 설치'를 허용한다. 회사 기기 관리 정책이 설치를 제한하는 경우에는 관리자 설정이 필요하다. APK 빌드·서명 검증과 실제 PLC 연결 검증은 별개다.

참고: [Android 앱 서명](https://developer.android.com/studio/publish/app-signing), [APK 직접 배포](https://developer.android.com/distribute/marketing-tools/alternative-distribution).

## 다음 작업과 완료 기준

1. 환경 구성 → 원본 Debug APK 빌드 및 실기기 실행 성공.
2. 현장 연결 확인 → 기존 앱과 동일 통신 설정에서 수신 원문 확보.
3. PLC 규칙 확정 → `plc-protocol-notes.md`의 미확정 항목을 실제 로그와 대조.
4. 메시지 해석 및 화면 추가 → 분할·연속 수신 검증과 실제 픽업/하차 상태 대조.

## 공식 참고 자료

- [Android Studio 다운로드](https://developer.android.com/studio)
- [Android Studio / AGP 호환성](https://developer.android.com/build/releases/about-agp)
- [AGP 9.2 호환성](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html)
