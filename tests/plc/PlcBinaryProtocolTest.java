package de.kai_morich.simple_usb_terminal.plc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PlcBinaryProtocolTest {
    public static void main(String[] args) throws Exception {
        byte[] pair = concat(packet(2, "03305", "2000"), packet(1, "04009", "4027"));
        for (int split = 0; split <= pair.length; split++) {
            List<String> logs = new ArrayList<>();
            PlcStateMonitor monitor = monitor(logs);
            monitor.accept(java.util.Arrays.copyOfRange(pair, 0, split));
            monitor.accept(java.util.Arrays.copyOfRange(pair, split, pair.length));
            check(logs.size() == 2, "binary split " + split);
            check(logs.get(0).contains("03305") && logs.get(0).contains("2000"), "position and variable tail");
            check(logs.get(1).contains("04009") && logs.get(1).contains("4027"), "variable tail must not require 3000");
        }
        List<String> logs = new ArrayList<>();
        PlcStateMonitor monitor = monitor(logs);
        for (int code : new int[]{2, 6, 5, 1}) monitor.accept(packet(code, "01049", "4000"));
        check(logs.size() == 4 && logs.get(3).contains("픽업 완료 패턴 추정"), "binary control display maps to existing tentative pattern");
        monitor.reset(); logs.clear();
        for (int code : new int[]{2, 4, 6, 5, 1}) monitor.accept(packet(code, "04014", "0000"));
        check(logs.size() == 5 && logs.get(1).contains("0x04·상태 의미 미확정"), "D is retained as unknown");
        check(logs.stream().noneMatch(log -> log.contains("픽업 완료")), "unknown D must break a pattern rather than become F");
        monitor.reset(); logs.clear();
        monitor.accept(packet(2, "03305", "2000"));
        for (int n = 0; n < 1000; n++) monitor.accept(packet(2, "02037", "4032"));
        check(logs.size() == 1, "same code numeric updates remain in raw CSV without repeating state-change messages");
        monitor.accept(new byte[]{2, '0', '1', 'x', '4', '5', '4', '0', '0', '0'});
        check(logs.size() == 1, "nonnumeric binary payload rejected");
        monitor.reset(); logs.clear();
        byte[] unknown = packet(3, "04014", "4000");
        monitor.accept(java.util.Arrays.copyOf(unknown, unknown.length - 1));
        check(logs.isEmpty(), "unknown code requires the complete terminated packet");
        monitor.accept(new byte[]{13});
        check(logs.size() == 1 && logs.get(0).contains("0x03·상태 의미 미확정"), "unknown complete code is journaled without a guessed meaning");
        System.out.println("PASS: all binary packet splits, variable numeric fields, unknown D preserved, pattern boundary, repeated states");
    }

    static byte[] packet(int code, String position, String tail) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{10, '1', '5', (byte) 0xff, (byte) code});
        bytes.write((position + tail).getBytes(StandardCharsets.US_ASCII));
        bytes.write(13);
        return bytes.toByteArray();
    }
    private static byte[] concat(byte[] a, byte[] b) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(a); bytes.write(b); return bytes.toByteArray();
    }
    private static PlcStateMonitor monitor(List<String> logs) {
        return new PlcStateMonitor(text -> { if (!text.startsWith("DISCARD")) logs.add(text); }, () -> 1000L);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
