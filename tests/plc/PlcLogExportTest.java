package de.kai_morich.simple_usb_terminal.plc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PlcLogExportTest {
    public static void main(String[] args) throws Exception {
        copiesLogsAndSimulationWithoutModifyingSources();
        copiesOnlySelectedFiles();
        exportsOnlyTheRecordedSnapshot();
        destinationFailureDoesNotReportSuccess();
        handlesMissingAndEmptyLogs();
        captureCanContinueWhileDestinationIsSlow();
        System.out.println("PASS: CSV/LOG selection, simulation folders, snapshot boundaries, source preservation, failures, live capture");
    }

    private static void copiesOnlySelectedFiles() throws Exception {
        File root = Files.createTempDirectory("plc-export-selected").toFile();
        write(root, "usb.csv", "usb raw");
        write(root, "usb.log", "usb state");
        write(root, "simulation/replay.csv", "simulation raw");
        PlcLogExport.Snapshot all = PlcLogExport.snapshot(root);
        check(all.size() == 3, "show all saved logs");
        check(all.path(0).equals("simulation/replay.csv") && all.length(0) == 14, "list relative path and size");
        PlcLogExport.Snapshot selected = all.select(new java.util.HashSet<>(java.util.Arrays.asList("usb.log", "simulation/replay.csv")));
        Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
        PlcLogExport.Result result = PlcLogExport.copy(selected, path -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            outputs.put(path, output);
            return output;
        }, (count, total) -> check(total == 2, "selection progress total"));
        check(result.files == 2 && !outputs.containsKey("usb.csv"), "only selected files copied");
        check(all.select(java.util.Collections.emptySet()).size() == 0, "empty selection");
        check(new File(root, "usb.csv").isFile(), "unselected original remains");
    }

    private static void copiesLogsAndSimulationWithoutModifyingSources() throws Exception {
        File root = Files.createTempDirectory("plc-export").toFile();
        write(root, "usb-plc-2026-10-21-000000.csv", "timestamp,message\r\n1,\"B012343000\"\r\n");
        write(root, "usb-state-2026-10-21-000000.log", "한글 상태\n");
        write(root, "simulation/simulation-plc-2026-10-21-000000.csv", "test\n");
        write(root, "simulation/simulation-state-2026-10-21-000000.log", "");
        write(root, "settings.xml", "do not export");
        Map<String, ByteArrayOutputStream> copied = new LinkedHashMap<>();
        int[] progress = {0};
        PlcLogExport.Result result = PlcLogExport.copy(PlcLogExport.snapshot(root), path -> {
            check(!copied.containsKey(path), "duplicate output name");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copied.put(path, output);
            return output;
        }, (count, total) -> { check(total == 4, "progress total"); progress[0] = count; });
        check(result.files == 4 && progress[0] == 4, "must copy four CSV/LOG files");
        long bytes = 0;
        for (Map.Entry<String, ByteArrayOutputStream> entry : copied.entrySet()) {
            byte[] original = Files.readAllBytes(new File(root, entry.getKey()).toPath());
            check(java.util.Arrays.equals(original, entry.getValue().toByteArray()), "source bytes preserved");
            bytes += original.length;
        }
        check(result.bytes == bytes, "copied byte count");
        check(!copied.containsKey("settings.xml"), "do not export settings");
        check(new File(root, "settings.xml").isFile(), "keep unrelated source");
    }

    private static void exportsOnlyTheRecordedSnapshot() throws Exception {
        File root = Files.createTempDirectory("plc-snapshot").toFile();
        write(root, "usb.csv", "complete-record\r\n");
        PlcLogExport.Snapshot snapshot = PlcLogExport.snapshot(root);
        Files.write(new File(root, "usb.csv").toPath(), "later-record\r\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        write(root, "later.log", "created later");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PlcLogExport.Result result = PlcLogExport.copy(snapshot, path -> output, (count, total) -> { });
        check(result.files == 1, "new files belong to next export");
        check(output.toString("UTF-8").equals("complete-record\r\n"), "do not follow a growing source");
    }

    private static void destinationFailureDoesNotReportSuccess() throws Exception {
        File root = Files.createTempDirectory("plc-export-failure").toFile();
        write(root, "usb.csv", "original");
        boolean failed = false;
        try {
            PlcLogExport.copy(PlcLogExport.snapshot(root), path -> {
                throw new IOException("destination unavailable");
            }, (count, total) -> { throw new AssertionError("failed file must not be counted"); });
        } catch (IOException e) { failed = true; }
        check(failed, "surface destination failure");
        check(new String(Files.readAllBytes(new File(root, "usb.csv").toPath()), StandardCharsets.UTF_8).equals("original"),
                "failed export must preserve source");
        boolean[] closed = {false};
        try {
            PlcLogExport.copy(PlcLogExport.snapshot(root), path -> new java.io.OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("disk full"); }
                @Override public void close() { closed[0] = true; }
            }, (count, total) -> { throw new AssertionError("incomplete file counted"); });
            throw new AssertionError("write failure was reported as success");
        } catch (IOException expected) { }
        check(closed[0], "close output on write failure");
        PlcLogExport.Snapshot beforeTruncation = PlcLogExport.snapshot(root);
        write(root, "usb.csv", "x");
        try {
            PlcLogExport.copy(beforeTruncation, path -> new ByteArrayOutputStream(), (count, total) -> { });
            throw new AssertionError("truncated source reported as complete");
        } catch (java.io.EOFException expected) { }
    }

    private static void handlesMissingAndEmptyLogs() throws Exception {
        File root = Files.createTempDirectory("plc-export-empty").toFile();
        check(PlcLogExport.snapshot(new File(root, "missing")).size() == 0, "no logs yet");
        check(PlcLogExport.snapshot(root).size() == 0, "empty directory");
        File notDirectory = new File(root, "file");
        write(root, "file", "not a directory");
        try { PlcLogExport.snapshot(notDirectory); throw new AssertionError("invalid directory accepted"); }
        catch (IOException expected) { }
    }

    private static void captureCanContinueWhileDestinationIsSlow() throws Exception {
        File root = Files.createTempDirectory("plc-export-live").toFile();
        PlcCaptureLog capture = new PlcCaptureLog(root, 5242880, "USB", message -> { }, message -> {
            throw new AssertionError(message);
        });
        try {
            capture.receive("B012343000".getBytes(StandardCharsets.US_ASCII), 1000, "301");
            flush(capture);
            PlcLogExport.Snapshot snapshot = PlcLogExport.snapshot(root);
            boolean[] injected = {false};
            Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();
            PlcLogExport.copy(snapshot, path -> {
                if (!injected[0]) {
                    injected[0] = true;
                    capture.receive("A012343000".getBytes(StandardCharsets.US_ASCII), 2000, "301");
                    flush(capture);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                outputs.put(path, output);
                return output;
            }, (count, total) -> { });
            String csv = outputs.entrySet().stream().filter(e -> e.getKey().endsWith(".csv"))
                    .findFirst().get().getValue().toString("UTF-8");
            check(PlcCsv.read(new java.io.StringReader(csv)).size() == 1, "snapshot ends on a complete CSV record");
            check(!csv.contains("A012343000"), "later receive is excluded from snapshot");
            String current = new String(Files.readAllBytes(new File(root, outputs.keySet().stream()
                    .filter(p -> p.endsWith(".csv")).findFirst().get()).toPath()), StandardCharsets.UTF_8);
            check(current.contains("A012343000"), "live capture continues during export");
        } finally { capture.close(); }
    }

    private static void flush(PlcCaptureLog capture) throws IOException {
        CountDownLatch done = new CountDownLatch(1);
        capture.flush(error -> { check(error == null, "capture queue failure"); done.countDown(); });
        try {
            check(done.await(3, TimeUnit.SECONDS), "export must not block live capture");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static void write(File root, String relative, String text) throws IOException {
        File file = new File(root, relative);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir failed");
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
