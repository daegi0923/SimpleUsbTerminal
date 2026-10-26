# 프로젝트 구조 안내

기존 SimpleUsbTerminal 기반 코드와 추가한 PLC 기능을 Java 패키지로 구분한다.

```text
SimpleUsbTerminal/
├── app/                              Android 앱
│   ├── build.gradle                  앱 ID·SDK·빌드 설정
│   └── src/main/
│       ├── AndroidManifest.xml       앱 진입점·권한·서비스 선언
│       ├── java/de/kai_morich/simple_usb_terminal/
│       │   ├── MainActivity.java     기존 앱 진입점 + PLC 메뉴 연결
│       │   ├── DevicesFragment.java  기존 USB 장치 선택 화면
│       │   ├── TerminalFragment.java 기존 USB 터미널 화면
│       │   ├── SerialSocket.java     기존 USB 포트 읽기·쓰기
│       │   ├── SerialService.java    USB 자동 연결·재연결·백그라운드 수집
│       │   ├── SerialListener.java  통신·연결 상태 콜백
│       │   ├── UsbAutoConnectPolicy.java  장치 선택·권한·재시도 판단
│       │   ├── SerialUiBuffer.java   화면 대기열 용량 제한
│       │   ├── Constants.java        기존 앱 상수
│       │   ├── CustomProber.java     기존 USB 드라이버 매핑
│       │   ├── TextUtil.java         기존 문자·HEX 표시 도우미
│       │   └── plc/                  추가한 PLC 기능 전체
│       │       ├── PlcStateMonitor.java  메시지 조립·상태 변경·작업 추정
│       │       ├── PlcCaptureLog.java    분석 연결·기록 대기열·날짜별 로그
│       │       ├── PlcLogExport.java    수신 중 로그의 기록 경계 확정·원본 복사
│       │       ├── PlcLogExportFolder.java  선택한 공용 폴더에 새 내보내기 생성
│       │       ├── PlcLogDownloadsFolder.java  선택창 사용 불가 시 공용 Download 내보내기
│       │       ├── PlcLogExportDialog.java  선택·진행·결과와 화면/프로세스 복원
│       │       ├── PlcLogMirror.java     완성된 내부 기록의 누락분 복사
│       │       ├── PlcPublicLogs.java    기본 공용 복사 예약·재시도·화면 상태
│       │       ├── PlcPublicLogStorage.java  설치별 MediaStore Download 기록
│       │       ├── PlcLogFolder.java     Android 선택 폴더 자동 저장
│       │       ├── PlcBytes.java         원본 바이트 HEX·제어값 표시
│       │       ├── PlcCsv.java           시간·원문 CSV 형식과 검증
│       │       ├── CsvReplay.java        CSV 시간 간격 재생·중지
│       │       ├── PlcTestFragment.java  CSV 파일 선택·재생 화면
│       │       ├── ScNumber.java         SC 번호 형식 검증
│       │       └── ScSettings.java       SC 번호 입력·저장
│       └── res/
│           ├── layout/fragment_plc_test.xml  추가한 CSV 재생 화면 배치
│           ├── menu/menu_plc.xml            추가한 PLC 공통 메뉴
│           └── ...                          기존 화면·메뉴·아이콘
├── tests/plc/                       추가한 PLC 기능의 장비 없는 테스트
├── examples/                        재생용 CSV와 데이터 설명
├── docs/                            개발 준비·PLC 규칙·사용법
├── gradle/                          Gradle 빌드 도구 설정
├── build.gradle                     프로젝트 공통 빌드 설정
├── settings.gradle                  앱 모듈 등록
└── serial-usb-terminal.apk          비교용 기존 앱 APK, 개발 빌드 출력 아님
```

## 어디를 수정하면 되는가

| 바꿀 내용 | 우선 볼 곳 |
|---|---|
| A/B/E/F 해석, 상태 변경, 픽업·하차 판단 | plc/PlcStateMonitor.java |
| PLC 원문 CSV 형식 | plc/PlcCsv.java |
| 상태 로그·날짜별 파일 기록 | plc/PlcCaptureLog.java |
| 저장 폴더 접근 | plc/PlcLogFolder.java |
| CSV 재생 시간 간격·중지 | plc/CsvReplay.java |
| CSV 화면의 동작 / 배치 | plc/PlcTestFragment.java / res/layout/fragment_plc_test.xml |
| SC 번호 입력·저장 / 검증 | plc/ScSettings.java / plc/ScNumber.java |
| USB 장치 탐색 / 실제 포트 통신 | DevicesFragment.java / SerialSocket.java |
| 앱 공통 메뉴와 화면 이동 | MainActivity.java / res/menu/menu_plc.xml |

## 기존 코드와 추가 기능의 연결

```text
SerialService → USB 검색·권한 확인·자동 재연결
실제 USB → SerialSocket → SerialService ──────┐
                                           ├→ PlcCaptureLog → PlcStateMonitor
CSV 파일 → PlcCsv → CsvReplay ───────────────┘       └→ 일별 원문·상태 파일
```

기존 파일도 일부 수정되어 있다. MainActivity는 PLC 메뉴·번호 설정·폴더 선택을 연결하고, SerialService는 화면과 독립적으로 USB 검색·권한 요청·자동 재연결을 관리하고 수신 데이터를 PLC 수집기에 전달한다. TerminalFragment와 기존 메뉴에는 현재 용도에 맞춘 변경이 있다.
폴더 분리는 기능의 위치를 구분하는 것이며, 기존 기반 파일이 원본과 완전히 동일하다는 뜻은 아니다.

Android 리소스는 layout, menu 같은 정해진 res 폴더 구조를 따라야 하므로 임의의 res/plc 폴더를 만들지 않는다. 추가 리소스는 파일명의 plc로 구분한다.
앱 ID, 저장 폴더 이름, SC 설정 키는 변경하지 않는다. Java 코드 폴더를 옮겨도 다른 앱으로 설치되지는 않는다.

## 확인 방법

Android Studio에서 Run하거나 레포 루트에서 다음 명령을 실행한다. Gradle에는 호환 JDK·SDK 설정이 필요하다.

```sh
./gradlew assembleDebug lintDebug
```

JDK가 설정된 터미널에서 장비 없는 PLC 테스트를 실행할 수 있다.

```bash
mkdir -p /tmp/simpleusb-plc-tests
javac -d /tmp/simpleusb-plc-tests app/src/main/java/de/kai_morich/simple_usb_terminal/plc/{ScNumber,PlcBytes,PlcCsv,CsvReplay,PlcStateMonitor,PlcCaptureLog,PlcLogExport,PlcLogMirror}.java tests/plc/*.java
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcStateMonitorTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcCaptureLogTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcCsvTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcLogExportTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcLogMirrorTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcWireCaptureTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcBinaryProtocolTest
java -cp /tmp/simpleusb-plc-tests de.kai_morich.simple_usb_terminal.plc.PlcStateJournalTest
```

USB 자동 연결 정책·화면 버퍼 테스트:

```sh
mkdir -p /tmp/simpleusb-usb-tests
javac -d /tmp/simpleusb-usb-tests app/src/main/java/de/kai_morich/simple_usb_terminal/{UsbAutoConnectPolicy,SerialUiBuffer}.java tests/usb/*.java
java -cp /tmp/simpleusb-usb-tests de.kai_morich.simple_usb_terminal.UsbAutoConnectPolicyTest
java -cp /tmp/simpleusb-usb-tests de.kai_morich.simple_usb_terminal.SerialUiBufferTest
```

개발 APK 출력: app/build/outputs/apk/debug/app-debug.apk.

## 관련 문서

- [개발 환경 준비](docs/development-guide.md)
- [PLC 규칙 분석 메모](docs/plc-protocol-notes.md)
- [수신·CSV 재생·로그 사용법](docs/plc-mock-logging.md)
- [재생용 CSV 설명](examples/README.md)
