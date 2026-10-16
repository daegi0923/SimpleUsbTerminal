package de.kai_morich.simple_usb_terminal.plc;

/** PLC 수신 데이터를 분석하고 상태 변경 요청의 모의 로그를 제공합니다. */
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
    private long validFrames;
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
        validFrames = 0;
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

    /** 최초 상태를 기록하고 이후 Prefix 변경에만 모의 요청을 출력합니다. */
    private void processFrame(String frame) {
        validFrames++;
        char state = frame.charAt(0);
        logger.log("FRAME state=" + state + " position=" + frame.substring(1, 6) + " raw=" + frame);
        if (state == previousState) return;
        history += state;
        if (history.length() > 4) history = history.substring(history.length() - 4);
        long receivedAt = clock.now();
        String counts = " validFrames=" + validFrames + " discardedBytes=" + discardedBytes;
        if (previousState == 0) {
            logger.log("BASELINE receivedAtEpochMs=" + receivedAt + " state=" + state
                    + " position=" + frame.substring(1, 6) + " raw=" + frame + counts);
        } else if (scNumber.isEmpty()) {
            logger.log("REQUEST_SKIPPED SC 호기 미설정 from=" + previousState + " to=" + state);
        } else {
            String event = state == 'E' ? "TWIST_LOCK" : state == 'F' ? "TWIST_UNLOCK"
                    : state == 'A' ? "LADEN_HOISTING" : "IDLE_HOISTING";
            String operation = history.equals("BFEA") ? "PICKUP_INFERRED"
                    : history.equals("AEFB") ? "DROP_OFF_INFERRED" : "UNDETERMINED";
            logger.log("DRY_RUN POST /mock/plc/state body={\"receivedAtEpochMs\":" + receivedAt
                    + ",\"from\":\"" + previousState + "\",\"to\":\"" + state
                    + "\",\"position\":\"" + frame.substring(1, 6)
                    + "\",\"raw\":\"" + frame + "\",\"scNumber\":\"" + scNumber
                    + "\",\"event\":\"" + event + "\",\"operation\":\"" + operation
                    + "\",\"evidence\":\"" + history + "\"}" + counts);
        }
        previousState = state;
    }
}
