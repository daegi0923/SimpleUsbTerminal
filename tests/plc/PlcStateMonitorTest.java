package de.kai_morich.simple_usb_terminal.plc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** PLC 분석의 메시지 조립, 잡음 처리 및 상태 변경 로그를 검증합니다. */
public final class PlcStateMonitorTest {
    /** 장비 없이 재현 가능한 수신 시나리오를 실행합니다. */
    public static void main(String[] args) {
        List<String> logs = new ArrayList<>();
        PlcStateMonitor monitor = new PlcStateMonitor(message -> {
            if (message.contains("[초기 상태]") || message.contains("[상태 변경]")) logs.add(message);
        }, () -> 1234L);

        monitor.setScNumber("301");

        // 분할 수신과 픽업 순서를 검증합니다.
        feed(monitor, "B012");
        check(logs.isEmpty(), "incomplete frame");
        feed(monitor, "343000\r\nF012343000E012353000A012363000");
        check(logs.size() == 4, "pickup log count");
        check(logs.get(0).contains("[초기 상태]"), "initial state is not a request");
        check(logs.get(1).contains("[상태 변경]\nB(Idle·컨테이너 X·호이스트 동작)→ F(Twist Unlock)"), "B to F with readable line breaks");
        check(logs.get(2).contains("F(Twist Unlock)→ E(Twist Lock)"), "F to E");
        check(logs.get(3).contains("컨테이너 픽업 완료 패턴 추정"), "pickup operation");
        check(logs.get(3).contains("SC 301"), "SC request number");
        check(logs.get(3).contains("관찰 순서: B→F→E→A"), "pattern evidence");
        check(logs.get(3).contains("1970-01-01T09:00:01.234+09:00"), "observation time");

        // 같은 상태의 대량 수신은 요청 로그를 추가하지 않습니다.
        for (int i = 0; i < 100000; i++) feed(monitor, "A999993000");
        check(logs.size() == 4, "duplicate and position-only suppression");

        // Invalid prefixes and nonnumeric payloads must not change state.
        feed(monitor, "Z012343000B01x343000E0123499x9");
        monitor.accept(new byte[] {(byte) 0xff, 0, 1});
        check(logs.size() == 4, "invalid data must not change state");
        feed(monitor, "junkE000013000F000023000B000033000");
        check(logs.size() == 7, "drop-off sequence after noise");
        check(logs.get(4).contains("A(Laden·컨테이너 O·호이스트 동작)→ E(Twist Lock)"), "A to E");
        check(logs.get(6).contains("F(Twist Unlock)→ B(Idle·컨테이너 X·호이스트 동작)"), "F to B");

        check(logs.get(6).contains("컨테이너 하차 완료 패턴 추정"), "drop-off operation");

        // 연결이 끊기면 부분 메시지와 이전 상태를 이어 쓰지 않습니다.
        feed(monitor, "A123");
        monitor.reset();
        feed(monitor, "453000F000043000");
        check(logs.size() == 8 && logs.get(7).contains("[초기 상태]"), "reconnect baseline");

        // 메시지의 모든 분할 위치에서 동일한 결과가 나오는지 확인합니다.
        for (int split = 0; split <= 20; split++) {
            logs.clear();
            monitor.reset();
            String frames = "B012343000A543213000";
            feed(monitor, frames.substring(0, split));
            feed(monitor, frames.substring(split));
            check(logs.size() == 2 && logs.get(1).contains("[상태 변경]"), "split " + split);
        }
        logs.clear();
        monitor.reset();
        feed(monitor, "F012343000E012343000A012343000");
        check(logs.stream().noneMatch(line -> line.contains("픽업 완료")), "partial pickup must remain uncertain");
        check(logs.get(2).contains("패턴 미확정"), "partial evidence label");
        // 번호 미설정과 호기 변경 시 잘못된 장비로 요청을 만들지 않습니다.
        logs.clear();
        monitor.setScNumber("");
        feed(monitor, "B012343000F012343000");
        check(logs.size() == 2 && logs.get(1).contains("SC 미설정"), "missing equipment is visible");
        monitor.setScNumber("312");
        feed(monitor, "A012343000");
        check(logs.size() == 3 && logs.get(2).contains("[초기 상태]"), "equipment change resets state");
        for (String valid : new String[] {"300", "301", "312", "399"})
            check(ScNumber.isValid(valid), "valid SC " + valid);
        for (String invalid : new String[] {"", "30", "3000", "201", "4a1", "３１２", " 301"})
            check(!ScNumber.isValid(invalid), "invalid SC " + invalid);
        System.out.println("PASS: split/merged frames, pickup/drop-off, 100000 duplicates, noise, reconnect");
        System.out.println(logs.get(1));
    }

    /** 테스트 문자열을 실제 수신과 동일한 바이트 입력으로 변환합니다. */
    private static void feed(PlcStateMonitor monitor, String value) {
        monitor.accept(value.getBytes(StandardCharsets.US_ASCII));
    }

    /** 검증 조건을 충족하지 못하면 테스트를 실패 처리합니다. */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
