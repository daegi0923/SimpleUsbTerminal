package de.kai_morich.simple_usb_terminal.plc;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PlcStateJournalTest {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("plc-state-journal").toFile();
        List<String> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        PlcCaptureLog capture = new PlcCaptureLog(directory, 1024 * 1024, "USB", events::add, errors::add);
        int[] input = {2, 2, 6, 5, 1, 4, 3, 2, 'B', 0, 0, 1};
        int[] expected = {2, 6, 5, 1, 4, 3, 2, 'B', 0, 1};
        try {
            capture.session("TEST_CONNECTED");
            long start = System.currentTimeMillis();
            for (int i = 0; i < input.length; i++)
                capture.receive(PlcBinaryProtocolTest.packet(input[i], "03305", "4027"), start + i, "");
            capture.diagnostic("TEST_BACKGROUND_STATUS");
            flush(capture);
            check(events.size() == expected.length, "record each raw control change, including unknown controls");
            for (int i = 0; i < expected.length; i++) {
                check(events.get(i).contains("상태 순번: " + (i + 1) + " (세션 내)"), "monotonic transition order " + i);
                String current = String.format("0x%02X", expected[i]);
                int last = events.get(i).lastIndexOf("0x");
                check(last >= 0 && events.get(i).substring(last, last + 4).equals(current), "exact current control " + i);
                check(events.get(i).contains("SC 미설정"), "SC number must not gate recording");
            }
            check(events.get(5).contains("상태 의미 미확정"), "unknown 03 must not become a defined state");
            check(events.get(7).contains("표시 상태 동일"), "binary B and ASCII B are different raw codes");
            String state = contents(directory, "-state-changes-");
            String diagnostic = contents(directory, "-diagnostic-");
            check(!state.contains("DISCARD") && !state.contains("TEST_BACKGROUND_STATUS"), "state history stays free of diagnostic noise");
            check(diagnostic.contains("DISCARD") && diagnostic.contains("TEST_BACKGROUND_STATUS"), "diagnostics remain available separately");
            check(state.contains("^B(0x02) → ^F(0x06)"), "explicit previous-to-current byte codes");
            capture.session("TEST_RECONNECT");
            capture.receive(PlcBinaryProtocolTest.packet(2, "03305", "2000"), start + input.length, "");
            flush(capture);
            String baseline = events.get(events.size() - 1);
            check(baseline.contains("[초기 상태]") && baseline.contains("상태 순번: 1 (세션 내)"), "new connection has an explicit baseline");
            check(errors.isEmpty(), "capture errors");
        } finally { capture.close(); }
        System.out.println("PASS: ordered raw control transitions, unknown codes, duplicate suppression, clean state journal, reconnect baseline");
    }
    private static void flush(PlcCaptureLog capture) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        capture.flush(error -> done.countDown());
        check(done.await(5, TimeUnit.SECONDS), "flush timeout");
    }
    private static String contents(File directory, String marker) throws Exception {
        StringBuilder text = new StringBuilder();
        File[] files = directory.listFiles((parent, name) -> name.contains(marker) && name.endsWith(".log"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) text.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        return text.toString();
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
