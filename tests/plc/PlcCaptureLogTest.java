package de.kai_morich.simple_usb_terminal.plc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 원문과 상태의 자동 파일 저장을 검증합니다. */
public final class PlcCaptureLogTest {
    /** 날짜별 분리, 이어쓰기 및 기록 누락을 장비 없이 검사합니다. */
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("plc-files-test").toFile();
        File selected = Files.createTempDirectory("plc-selected-test").toFile();
        List<String> errors = new ArrayList<>();
        PlcCaptureLog capture = new PlcCaptureLog(directory, 600, "USB", message -> {}, errors::add);
        capture.setDestination((name, line) -> {
            File file = new File(selected, name);
            boolean header = name.endsWith(".csv") && file.length() == 0;
            try (OutputStream out = new FileOutputStream(file, true)) {
                if (header) out.write(PlcCsv.HEADER.getBytes(StandardCharsets.UTF_8));
                out.write(line);
            }
        });
        long midnight = java.time.Instant.parse("2026-10-13T15:00:00Z").toEpochMilli();
        capture.receive("B012".getBytes(StandardCharsets.US_ASCII), midnight - 1, "301");
        capture.receive("343000junkF012343000".getBytes(StandardCharsets.US_ASCII), midnight, "301");
        String text = snapshot(capture, directory);
        check(text.contains("\"B012\""), "raw bytes");
        check(text.contains("DISCARD bytes=4"), "noise diagnostics");
        check(text.contains("[상태 변경]"), "readable transition");
        check(new File(directory, "usb-plc-2026-10-13-000000.csv").exists(), "date before midnight");
        check(new File(directory, "usb-plc-2026-10-14-000000.csv").exists(), "date after midnight");
        for (File file : directory.listFiles()) {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (file.getName().contains("-plc-")) {
                check(!content.contains("[상태 변경]") && !content.contains("FRAME"), "raw log separation");
                check(PlcCsv.read(new java.io.StringReader(content)).size() == 1, "capture CSV can replay");
            }
            if (file.getName().contains("-state-")) check(!content.contains("RX_HEX"), "state log separation");
            check(Arrays.equals(Files.readAllBytes(file.toPath()), Files.readAllBytes(new File(selected, file.getName()).toPath())), "automatic selected-folder write");
        }
        for (int i = 0; i < 30; i++) capture.receive("A012343000".getBytes(StandardCharsets.US_ASCII), midnight + i, "301");
        snapshot(capture, directory);
        for (File file : directory.listFiles()) check(file.length() <= 600, "file split limit");
        capture.close();
        PlcCaptureLog reopened = new PlcCaptureLog(directory, 600, "USB", message -> {}, errors::add);
        reopened.receive("E012343000".getBytes(StandardCharsets.US_ASCII), midnight + 100, "312");
        String afterRestart = snapshot(reopened, directory);
        check(afterRestart.contains("F012343000") && afterRestart.contains("E012343000"), "append and old file preservation");
        reopened.close();
        check(errors.isEmpty(), "unexpected errors");

        File busyDir = Files.createTempDirectory("plc-overflow-test").toFile();
        PlcCaptureLog busy = new PlcCaptureLog(busyDir, 5 * 1024 * 1024, "SIMULATION", message -> {}, errors::add);
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        busy.setDestination((name, line) -> {
            opened.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        });
        busy.receive(new byte[] {0}, 0, "");
        check(opened.await(10, TimeUnit.SECONDS), "writer blocked");
        for (int i = 0; i < 300; i++) busy.receive(new byte[] {0}, i, "");
        check(errors.size() == 1, "overflow warning once");
        release.countDown();
        snapshot(busy, busyDir);
        busy.receive("B012343000".getBytes(StandardCharsets.US_ASCII), 1000, "301");
        check(snapshot(busy, busyDir).contains("droppedEvents=44"), "gap position and count");
        busy.close();
        System.out.println("PASS: separate raw/state files, automatic folder writes, midnight, splitting, restart, overflow");
    }

    /** 대기 중인 파일 기록을 완료한 뒤 저장된 텍스트를 읽습니다. */
    private static String snapshot(PlcCaptureLog capture, File directory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            CountDownLatch done = new CountDownLatch(1);
            String[] error = new String[1];
            capture.flush(value -> { error[0] = value; done.countDown(); });
            check(done.await(10, TimeUnit.SECONDS), "flush timeout");
            if (error[0] == null) break;
            check(System.nanoTime() < deadline, "queue did not drain");
            Thread.sleep(5);
        }
        StringBuilder text = new StringBuilder();
        for (File file : directory.listFiles()) text.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        return text.toString();
    }

    /** 검증 조건을 충족하지 못하면 테스트를 실패 처리합니다. */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
