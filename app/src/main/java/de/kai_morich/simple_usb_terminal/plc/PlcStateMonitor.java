package de.kai_morich.simple_usb_terminal.plc;

/** PLC 수신 데이터를 분석하고 읽기 쉬운 상태 변화 로그를 제공합니다. */
final class PlcStateMonitor {
    interface Logger {
        /** 분석 로그를 출력합니다. */
        void log(String message);
    }

    interface Clock {
        /** 앱에서 관찰한 시각을 밀리초로 반환합니다. */
        long now();
    }

    private final StringBuilder pending = new StringBuilder();
    private final Logger logger;
    private final Clock clock;
    private char previousState;
    private char previousCode;
    private boolean hasPrevious;
    private long transitionSequence;
    private long discardedBytes;
    private String scNumber = "";
    private String history = "";

    /** 호기가 변경되면 이전 장비의 메시지와 상태를 이어 쓰지 않습니다. */
    void setScNumber(String number) {
        String next = ScNumber.isValid(number) ? number : "";
        if (!next.equals(scNumber)) {
            reset();
            scNumber = next;
        }
    }

    /** 로그 출력 대상과 앱 수신 시각 공급자를 설정합니다. */
    PlcStateMonitor(Logger logger, Clock clock) {
        this.logger = logger;
        this.clock = clock;
    }

    /** 분할·연속 수신을 조립하고 형식에 맞는 PLC 메시지만 처리합니다. */
    void accept(byte[] data) {
        long before = discardedBytes;
        for (byte value : data) {
            char ch = (char) (value & 0xff);
            if (pending.length() == 0 && !isPrefix(ch)) {
                if (ch != '\r' && ch != '\n') discardedBytes++;
                continue;
            }
            pending.append(ch);
            if (pending.length() < 10) continue;
            boolean payload = isFrame();
            boolean known = isKnownState(displayState(pending.charAt(0)));
            if (payload && (known || (pending.length() == 11 && pending.charAt(10) == '\r'))) {
                processFrame(pending.substring(0, 10));
                pending.setLength(0);
            } else if (payload && !known && pending.length() == 10) {
                // Unknown control values need the observed CR terminator, not just nine digits.
                continue;
            } else {
                pending.deleteCharAt(0);
                discardedBytes++;
                while (pending.length() > 0 && !isPrefix(pending.charAt(0))) {
                    pending.deleteCharAt(0);
                    discardedBytes++;
                }
            }
        }
        if (before != discardedBytes)
            logger.log("DISCARD bytes=" + (discardedBytes - before) + " total=" + discardedBytes
                    + " pendingBytes=" + pending.length());
    }

    /** 연결 종료 시 부분 메시지와 이전 세션의 상태를 초기화합니다. */
    void reset() {
        pending.setLength(0);
        previousState = 0;
        previousCode = 0;
        hasPrevious = false;
        transitionSequence = 0;
        discardedBytes = 0;
        history = "";
    }

    /** 현재 분석에서 정의한 상태 코드인지 확인합니다. */
    private boolean isPrefix(char ch) {
        return isKnownState(ch) || (ch < 32 && ch != '\r' && ch != '\n');
    }

    private boolean isKnownState(char state) { return state == 'A' || state == 'B' || state == 'E' || state == 'F'; }

    private char displayState(char code) { return code < 32 ? (char) (code + 64) : code; }

    private String controlLabel(char code) {
        return (code < 32 ? "^" + displayState(code) : Character.toString(code))
                + "(0x" + PlcBytes.hex(new byte[]{(byte) code}) + ")";
    }

    /** Both field captures and old samples carry five position digits and four variable digits. */
    private boolean isFrame() {
        for (int i = 1; i <= 9; i++) {
            char ch = pending.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /** 최초 상태를 기록하고 Prefix가 바뀔 때만 이전·현재 상태와 작업 패턴을 표시합니다. */
    private void processFrame(String frame) {
        char code = frame.charAt(0);
        char state = displayState(code);
        if (hasPrevious && code == previousCode) return;
        boolean sameDisplay = hasPrevious && state == previousState;
        if (!isKnownState(state)) history = "";
        else if (!sameDisplay) history += state;
        if (history.length() > 4) history = history.substring(history.length() - 4);
        String context = PlcCsv.formatTimestamp(clock.now()) + " | SC "
                + (scNumber.isEmpty() ? "미설정" : scNumber) + "\n";
        String values = "위치 원시값: " + frame.substring(1, 6) + " | 후행값: " + frame.substring(6)
                + " (단위·의미 미확정)\n";
        String change = "상태 순번: " + (++transitionSequence) + " (세션 내)\n제어값: "
                + (hasPrevious ? controlLabel(previousCode) + " → " : "") + controlLabel(code) + "\n";
        if (!hasPrevious) {
            logger.log(context + "[초기 상태]\n" + describe(state) + "\n" + change + values);
        } else {
            String trail = history.isEmpty() ? "미확정 코드로 순서 초기화" : history.substring(0, 1);
            for (int i = 1; i < history.length(); i++) trail += "→" + history.charAt(i);
            logger.log(context + "[상태 변경]\n" + describe(previousState) + "→ " + describe(state)
                    + "\n" + (sameDisplay ? "표시 상태 동일 · 작업 패턴 판단 갱신 없음" : operation())
                    + "\n관찰 순서: " + trail + "\n" + change + values);
        }
        previousState = state;
        previousCode = code;
        hasPrevious = true;
    }

    /** 전달받은 Prefix 정의를 사람이 읽을 수 있는 상태명으로 표시합니다. */
    private String describe(char state) {
        switch (state) {
            case 'A': return "A(Laden·컨테이너 O·호이스트 동작)";
            case 'B': return "B(Idle·컨테이너 X·호이스트 동작)";
            case 'E': return "E(Twist Lock)";
            case 'F': return "F(Twist Unlock)";
            default: return "^" + state + "(0x" + PlcBytes.hex(new byte[]{(byte) (state - 64)}) + "·상태 의미 미확정)";
        }
    }

    /** 실제로 관찰한 연속 Prefix를 근거로 픽업·하차 패턴의 진행을 추정합니다. */
    private String operation() {
        if (history.equals("BFEA")) return "컨테이너 픽업 완료 패턴 추정";
        if (history.equals("AEFB")) return "컨테이너 하차 완료 패턴 추정";
        if (history.endsWith("BFE")) return "컨테이너 픽업 진행 패턴 추정 (잠금 단계)";
        if (history.endsWith("BF")) return "컨테이너 픽업 진행 패턴 추정 (해제 단계)";
        if (history.endsWith("AEF")) return "컨테이너 하차 진행 패턴 추정 (해제 단계)";
        if (history.endsWith("AE")) return "컨테이너 하차 진행 패턴 추정 (잠금 단계)";
        return "픽업·하차 패턴 미확정";
    }
}
