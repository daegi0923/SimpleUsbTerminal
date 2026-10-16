package de.kai_morich.simple_usb_terminal.plc;

/** SC 호기 번호의 업무 형식을 검증합니다. */
final class ScNumber {
    /** 3으로 시작하는 ASCII 숫자 세 자리만 허용합니다. */
    static boolean isValid(String value) {
        return value != null && value.matches("3[0-9]{2}");
    }
}
