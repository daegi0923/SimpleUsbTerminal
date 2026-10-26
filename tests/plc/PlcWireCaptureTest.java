package de.kai_morich.simple_usb_terminal.plc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PlcWireCaptureTest {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("plc-wire-capture").toFile();
        byte[] input = new byte[256];
        for (int i = 0; i < input.length; i++) input[i] = (byte) i;
        PlcCaptureLog capture = new PlcCaptureLog(directory, 5 * 1024 * 1024, "USB", value -> {},
                error -> { throw new AssertionError(error); });
        try {
            capture.receive(input, 1000, "301");
            CountDownLatch done = new CountDownLatch(1);
            capture.flush(error -> done.countDown());
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("capture timeout");
            File csv = Arrays.stream(directory.listFiles()).filter(file -> file.getName().endsWith(".csv")).findFirst().get();
            PlcCsv.Entry entry;
            try (Reader reader = Files.newBufferedReader(csv.toPath(), StandardCharsets.UTF_8)) {
                entry = PlcCsv.read(reader).get(0);
            }
            if (!Arrays.equals(input, entry.bytes()) || !entry.hasRawBytes)
                throw new AssertionError("CSV must preserve all 256 original byte values");
            if (entry.message.indexOf('\ufffd') >= 0 || !entry.message.contains("\\xFF") || !entry.message.contains("^B"))
                throw new AssertionError("CSV display must distinguish high bytes and control notation without replacement");
        } finally { capture.close(); }
        preservesOldSchemaAndResumesNewFile();
        System.out.println("PASS: lossless 256-byte capture, readable display, legacy file preservation, new schema restart");
    }

    private static void preservesOldSchemaAndResumesNewFile() throws Exception {
        File directory = Files.createTempDirectory("plc-schema-upgrade").toFile();
        File old = new File(directory, "usb-plc-1970-01-01-000000.csv");
        byte[] oldBytes = (PlcCsv.HEADER + PlcCsv.row(1000, "old\ufffd\u0002record")).getBytes(StandardCharsets.UTF_8);
        Files.write(old.toPath(), oldBytes);
        byte[] packet = PlcBinaryProtocolTest.packet(2, "03305", "2000");
        for (int restart = 0; restart < 2; restart++) {
            PlcCaptureLog capture = new PlcCaptureLog(directory, 5 * 1024 * 1024, "USB", value -> {},
                    error -> { throw new AssertionError(error); });
            try {
                capture.receive(packet, 1001 + restart, "301");
                CountDownLatch done = new CountDownLatch(1);
                capture.flush(error -> done.countDown());
                if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("flush timeout");
            } finally { capture.close(); }
        }
        if (!Arrays.equals(oldBytes, Files.readAllBytes(old.toPath()))) throw new AssertionError("legacy CSV changed");
        File next = new File(directory, "usb-plc-1970-01-01-000001.csv");
        try (Reader reader = Files.newBufferedReader(next.toPath(), StandardCharsets.UTF_8)) {
            java.util.List<PlcCsv.Entry> entries = PlcCsv.read(reader);
            if (entries.size() != 2 || !entries.get(0).hasRawBytes || !Arrays.equals(entries.get(1).bytes(), packet))
                throw new AssertionError("new schema must resume without mixing with old CSV");
        }
        if (new File(directory, "usb-plc-1970-01-01-000002.csv").exists()) throw new AssertionError("unnecessary file after restart");
    }
}
