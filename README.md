[![Codacy Badge](https://api.codacy.com/project/badge/Grade/83070da7805b4899820e285d2f7847b9)](https://www.codacy.com/manual/kai-morich/SimpleUsbTerminal?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=kai-morich/SimpleUsbTerminal&amp;utm_campaign=Badge_Grade)

# SimpleUsbTerminal

This Android app provides a line-oriented terminal / console for devices with a serial / UART interface connected with a USB-to-serial-converter.

It supports USB to serial converters based on
- FTDI FT232, FT2232, ...
- Prolific PL2303
- Silabs CP2102, CP2105, ...
- Qinheng CH340, CH341

and devices implementing the USB CDC protocol like
- Arduino using ATmega32U4
- Digispark using V-USB software USB
- BBC micro:bit using ARM mbed DAPLink firmware
- Pi Pico
- ...

## Features

- permission handling on device connection
- foreground service to buffer receive data while the app is rotating, in background, ...
- send BREAK
- show control lines
- RTS/CTS, DTR/DSR, XON/XOFF flow control

## Credits

The app uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library.

## Motivation

I got various requests asking for help with Android development or source code for my
[Serial USB Terminal](https://play.google.com/store/apps/details?id=de.kai_morich.serial_usb_terminal) app.
Here you find a simplified version of my app.

## PLC 현장 테스트 기능

추가한 PLC 기능은 `app/src/main/java/de/kai_morich/simple_usb_terminal/plc/`에 모아두었다.
폴더별 역할과 기존 코드의 연결 지점은 [프로젝트 구조 안내](PROJECT_STRUCTURE.md)를 참고한다.
사용법은 [PLC 수신·CSV 재생·로그](docs/plc-mock-logging.md)를 참고한다.
