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
            if (isFrame()) {
                processFrame(pending.toString());
                pending.setLength(0);
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
        discardedBytes = 0;
        history = "";
    }

    /** 현재 분석에서 정의한 상태 코드인지 확인합니다. */
    private boolean isPrefix(char ch) {
        return ch == 'A' || ch == 'B' || ch == 'E' || ch == 'F';
    }

    /** Prefix, 위치 숫자 5자리 및 임시 고정 후행 값 3000을 검증합니다. */
    private boolean isFrame() {
        for (int i = 1; i <= 5; i++) {
            char ch = pending.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return pending.substring(6).equals("3000");
    }

    /** 최초 상태를 기록하고 Prefix가 바뀔 때만 이전·현재 상태와 작업 패턴을 표시합니다. */
    private void processFrame(String frame) {
        char state = frame.charAt(0);
        if (state == previousState) return;
        history += state;
        if (history.length() > 4) history = history.substring(history.length() - 4);
        String context = PlcCsv.formatTimestamp(clock.now()) + " | SC "
                + (scNumber.isEmpty() ? "미설정" : scNumber) + "\n";
        if (previousState == 0) {
            logger.log(context + "[초기 상태]\n" + describe(state) + "\n");
        } else {
            String trail = history.substring(0, 1);
            for (int i = 1; i < history.length(); i++) trail += "→" + history.charAt(i);
            logger.log(context + "[상태 변경]\n" + describe(previousState) + "→ " + describe(state)
                    + "\n" + operation() + "\n관찰 순서: " + trail + "\n");
        }
        previousState = state;
    }

    /** 전달받은 Prefix 정의를 사람이 읽을 수 있는 상태명으로 표시합니다. */
    private String describe(char state) {
        switch (state) {
            case 'A': return "A(Laden·컨테이너 O·호이스트 동작)";
            case 'B': return "B(Idle·컨테이너 X·호이스트 동작)";
            case 'E': return "E(Twist Lock)";
            default: return "F(Twist Unlock)";
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
