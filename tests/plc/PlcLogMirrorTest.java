package de.kai_morich.simple_usb_terminal.plc;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PlcLogMirrorTest {
    public static void main(String[] args) throws Exception {
        copiesLogsAndSurvivesRepeatedSyncAndRestart();
        appendsOnlyNewSourceBytes();
        resumesAfterPartialPublicWrite();
        preservesLongerPublicFiles();
        captureContinuesOutsideTheSnapshotBoundary();
        rejectsSourceTruncation();
        System.out.println("PASS: public CSV/LOG mirrors, simulation folders, restart, append, partial-write recovery, conflict preservation, live capture, truncation");
    }

    private static void copiesLogsAndSurvivesRepeatedSyncAndRestart() throws Exception {
        File source = temporary("plc-mirror-source");
        File destination = temporary("plc-mirror-public");
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("usb-plc.csv", bytes("timestamp,message\r\n1,\"B012343000\"\r\n"));
        expected.put("usb-state.log", bytes("한글 상태\n"));
        expected.put("simulation/replay.csv", bytes("timestamp,message\r\n2,\"A012343000\"\r\n"));
        expected.put("simulation/replay.log", bytes("시뮬레이션 상태\n"));
        expected.put("empty.log", new byte[0]);
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) write(source, entry.getKey(), entry.getValue());
        write(source, "settings.xml", bytes("do not publish"));
        FileTarget target = new FileTarget(destination);
        check(PlcLogMirror.sync(source, target) == 5, "initial sync includes CSV/LOG files, including an empty log");
        equalFiles(destination, expected);
        check(!new File(destination, "settings.xml").exists(), "exclude unrelated files");
        int opened = target.appends;
        PlcLogMirror.sync(source, target);
        check(target.appends == opened + 1, "only the empty file may reopen; populated files must not append again");
        equalFiles(destination, expected);
        FileTarget restarted = new FileTarget(destination);
        PlcLogMirror.sync(source, restarted);
        check(restarted.appends == 1, "a restarted target only opens the empty file");
        equalFiles(destination, expected);
        equalFiles(source, expected);
        check(new File(source, "settings.xml").isFile(), "keep unrelated private file");
    }

    private static void appendsOnlyNewSourceBytes() throws Exception {
        File source = temporary("plc-mirror-tail");
        FileTarget target = new FileTarget(temporary("plc-mirror-tail-public"));
        byte[] first = bytes("첫 번째 완전한 기록\r\n");
        byte[] tail = bytes("두 번째 기록\r\n세 번째 기록\r\n");
        write(source, "usb.csv", first);
        PlcLogMirror.sync(source, target);
        Files.write(new File(source, "usb.csv").toPath(), tail, StandardOpenOption.APPEND);
        PlcLogMirror.sync(source, target);
        byte[] expected = Files.readAllBytes(new File(source, "usb.csv").toPath());
        check(Arrays.equals(expected, target.read("usb.csv")), "append exactly the new UTF-8 bytes");
        PlcLogMirror.sync(source, new FileTarget(target.root));
        check(Arrays.equals(expected, target.read("usb.csv")), "a further sync must not duplicate the tail");
    }

    private static void resumesAfterPartialPublicWrite() throws Exception {
        File source = temporary("plc-mirror-retry");
        FileTarget target = new FileTarget(temporary("plc-mirror-retry-public"));
        byte[] expected = new byte[100_003];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        write(source, "usb.csv", expected);
        target.failAfter = 12_345;
        try {
            PlcLogMirror.sync(source, target);
            throw new AssertionError("a partial destination failure must be reported");
        } catch (IOException expectedFailure) { }
        check(target.lastOutputClosed, "close output even after a partial write failure");
        check(Arrays.equals(Arrays.copyOf(expected, 12_345), target.read("usb.csv")),
                "failed write leaves an exact persisted prefix");
        PlcLogMirror.sync(source, new FileTarget(target.root));
        check(Arrays.equals(expected, target.read("usb.csv")), "retry resumes at the persisted byte offset");
        check(Arrays.equals(expected, Files.readAllBytes(new File(source, "usb.csv").toPath())),
                "a public write failure must not change private data");
    }

    private static void preservesLongerPublicFiles() throws Exception {
        File source = temporary("plc-mirror-conflict");
        FileTarget target = new FileTarget(temporary("plc-mirror-conflict-public"));
        byte[] originalPublic = bytes("longer public file edited outside the app\n");
        byte[] privateData = bytes("private\n");
        write(source, "simulation/replay.log", privateData);
        write(target.root, "simulation/replay.log", originalPublic);
        PlcLogMirror.sync(source, target);
        check(target.archived.size() == 1, "a longer public file requires a separate new copy");
        check(Arrays.equals(originalPublic, target.archived.get(0)), "keep the conflicting public bytes untouched");
        check(Arrays.equals(privateData, target.read("simulation/replay.log")), "new copy contains exact private data");
        PlcLogMirror.sync(source, new FileTarget(target.root));
        check(target.archived.size() == 1, "subsequent sync must not create another conflict copy");
    }

    private static void captureContinuesOutsideTheSnapshotBoundary() throws Exception {
        File source = temporary("plc-mirror-live");
        PlcCaptureLog capture = new PlcCaptureLog(source, 5 * 1024 * 1024, "USB", message -> { },
                message -> { throw new AssertionError(message); });
        try {
            capture.receive(bytes("B012343000"), 1000, "301");
            flush(capture);
            Map<String, byte[]> before = new LinkedHashMap<>();
            PlcLogExport.Snapshot snapshot = PlcLogExport.snapshot(source);
            for (int i = 0; i < snapshot.size(); i++) {
                String path = snapshot.path(i);
                before.put(path, Files.readAllBytes(new File(source, path).toPath()));
            }
            FileTarget target = new FileTarget(temporary("plc-mirror-live-public"));
            target.beforeAppend = () -> {
                capture.receive(bytes("A012343000"), 2000, "301");
                flush(capture);
                write(source, "later.log", bytes("created during copy\n"));
            };
            PlcLogMirror.sync(source, target);
            equalFiles(target.root, before);
            check(!new File(target.root, "later.log").exists(), "new files wait until the next snapshot");
            String csv = before.keySet().stream().filter(path -> path.endsWith(".csv")).findFirst().get();
            check(PlcCsv.read(new java.io.StringReader(new String(target.read(csv), StandardCharsets.UTF_8))).size() == 1,
                    "public snapshot ends on the prior complete CSV record");
            check(new String(Files.readAllBytes(new File(source, csv).toPath()), StandardCharsets.UTF_8)
                    .contains("A012343000"), "capture writes while public output is open");
            PlcLogMirror.sync(source, target);
            check(Arrays.equals(Files.readAllBytes(new File(source, csv).toPath()), target.read(csv)),
                    "next sync includes the later capture exactly once");
            check(new File(target.root, "later.log").isFile(), "next snapshot includes a newly created log");
        } finally { capture.close(); }
    }

    private static void rejectsSourceTruncation() throws Exception {
        File source = temporary("plc-mirror-truncated");
        write(source, "usb.log", bytes("original source before snapshot\n"));
        FileTarget target = new FileTarget(temporary("plc-mirror-truncated-public"));
        target.beforeAppend = () -> write(source, "usb.log", bytes("x"));
        try {
            PlcLogMirror.sync(source, target);
            throw new AssertionError("truncated source must not be reported as a completed sync");
        } catch (EOFException expected) { }
        check(target.lastOutputClosed, "close public output when source reaches EOF early");
    }

    private interface BeforeAppend { void run() throws IOException; }

    private static final class FileTarget implements PlcLogMirror.Target {
        final File root;
        final List<byte[]> archived = new ArrayList<>();
        int appends;
        int failAfter = -1;
        boolean lastOutputClosed;
        BeforeAppend beforeAppend;

        FileTarget(File root) { this.root = root; }
        public long length(String path) { return new File(root, path).length(); }

        public OutputStream append(String path) throws IOException {
            if (beforeAppend != null) {
                BeforeAppend once = beforeAppend;
                beforeAppend = null;
                once.run();
            }
            File file = new File(root, path);
            mkdir(file.getParentFile());
            FileOutputStream output = new FileOutputStream(file, true);
            appends++;
            lastOutputClosed = false;
            final int limit = failAfter;
            failAfter = -1;
            return new OutputStream() {
                private int written;
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
                @Override public void write(byte[] data, int offset, int count) throws IOException {
                    int accepted = limit < 0 ? count : Math.min(count, Math.max(0, limit - written));
                    output.write(data, offset, accepted);
                    written += accepted;
                    if (accepted != count) throw new IOException("simulated disk full after a partial write");
                }
                @Override public void close() throws IOException { lastOutputClosed = true; output.close(); }
            };
        }

        public void newFile(String path) throws IOException {
            File file = new File(root, path);
            archived.add(Files.readAllBytes(file.toPath()));
            File preserved = new File(file.getParentFile(), file.getName() + ".preserved-" + archived.size());
            Files.move(file.toPath(), preserved.toPath());
        }

        byte[] read(String path) throws IOException { return Files.readAllBytes(new File(root, path).toPath()); }
    }

    private static void flush(PlcCaptureLog capture) throws IOException {
        CountDownLatch done = new CountDownLatch(1);
        String[] error = {null};
        capture.flush(value -> { error[0] = value; done.countDown(); });
        try { check(done.await(3, TimeUnit.SECONDS), "public copying must not hold the capture FILE_LOCK"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        check(error[0] == null, "capture queue failure: " + error[0]);
    }

    private static File temporary(String prefix) throws IOException { return Files.createTempDirectory(prefix).toFile(); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void write(File root, String path, byte[] content) throws IOException {
        File file = new File(root, path);
        mkdir(file.getParentFile());
        Files.write(file.toPath(), content);
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("mkdir failed: " + directory);
    }

    private static void equalFiles(File root, Map<String, byte[]> expected) throws IOException {
        for (Map.Entry<String, byte[]> entry : expected.entrySet())
            check(Arrays.equals(entry.getValue(), Files.readAllBytes(new File(root, entry.getKey()).toPath())),
                    "exact bytes for " + entry.getKey());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
