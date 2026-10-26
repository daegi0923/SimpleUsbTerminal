package de.kai_morich.simple_usb_terminal.plc;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic bytes exercise the actual service writer and public store, never physical USB. */
@android.annotation.TargetApi(29)
public final class PlcWireRuntimeChecks {
    public static String run(Context context, PlcCaptureLog capture) throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        byte[] all = new byte[256];
        for (int index = 0; index < all.length; index++) all[index] = (byte) index;
        chunks.add(all);
        for (int code : new int[]{2, 6, 5, 1, 4}) {
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(new byte[]{10, '1', '5', (byte) 0x81, (byte) code});
            packet.write("033054027".getBytes(StandardCharsets.US_ASCII));
            packet.write(13);
            byte[] bytes = packet.toByteArray();
            chunks.add(Arrays.copyOfRange(bytes, 0, 7));
            chunks.add(Arrays.copyOfRange(bytes, 7, bytes.length));
        }
        capture.diagnostic("WIRE_TEST_SYNTHETIC_BYTES");
        long start = System.currentTimeMillis();
        for (int index = 0; index < chunks.size(); index++) capture.receive(chunks.get(index), start + index, "301");
        CountDownLatch flushed = new CountDownLatch(1);
        capture.flush(error -> flushed.countDown());
        check(flushed.await(10, TimeUnit.SECONDS), "wire flush");
        File directory = new File(context.getFilesDir(), "plc-logs");
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".csv"));
        check(files != null && files.length == 1, "one new raw CSV");
        File csv = files[0];
        List<PlcCsv.Entry> entries;
        try (InputStream stream = new FileInputStream(csv)) {
            entries = PlcCsv.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        check(entries.size() == chunks.size(), "all callback chunks recorded");
        for (int index = 0; index < chunks.size(); index++) {
            PlcCsv.Entry entry = entries.get(index);
            check(entry.hasRawBytes && Arrays.equals(entry.bytes(), chunks.get(index)), "HEX byte identity " + index);
            check(entry.message.indexOf('\ufffd') < 0, "no replacement text " + index);
        }
        StringBuilder state = new StringBuilder();
        for (File file : directory.listFiles((parent, name) -> name.endsWith(".log")))
            try (InputStream input = new FileInputStream(file)) { state.append(new String(read(input), StandardCharsets.UTF_8)); }
        for (String marker : new String[]{"B(Idle", "F(Twist Unlock)", "E(Twist Lock)", "A(Laden", "0x04·상태 의미 미확정", "후행값: 4027"})
            check(state.toString().contains(marker), "binary analysis " + marker);
        File[] journals = directory.listFiles((parent, name) -> name.contains("-state-changes-") && name.endsWith(".log"));
        check(journals != null && journals.length == 1, "one separate state journal");
        byte[] journalBytes;
        try (InputStream input = new FileInputStream(journals[0])) { journalBytes = read(input); }
        String journal = new String(journalBytes, StandardCharsets.UTF_8);
        check(!journal.contains("DISCARD") && !journal.contains("WIRE_TEST_SYNTHETIC_BYTES"), "diagnostics must not bury state history");
        check(journal.split("상태 순번:", -1).length - 1 == 5, "exactly five control changes");
        int position = -1;
        for (int index = 1; index <= 5; index++) {
            position = journal.indexOf("상태 순번: " + index + " (세션 내)", position + 1);
            check(position >= 0, "state sequence " + index);
        }
        check(journal.contains("^B(0x02) → ^F(0x06)"), "actual control byte transition");
        byte[] expected;
        try (InputStream input = new FileInputStream(csv)) { expected = read(input); }
        long deadline = System.currentTimeMillis() + 10000;
        while (!publicMatches(context, csv.getName(), expected) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        check(publicMatches(context, csv.getName(), expected), "public raw CSV must match private bytes");
        deadline = System.currentTimeMillis() + 10000;
        while (!publicMatches(context, journals[0].getName(), journalBytes) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        check(publicMatches(context, journals[0].getName(), journalBytes), "public state journal byte identity");
        return "PASS: lossless wire capture: 11 chunks, all 256 byte values, binary state analysis, exact public raw_hex CSV\n"
                + "PASS: ordered state journal: 5 sequential changes, exact control HEX, separate diagnostics, public LOG verified\n";
    }

    private static boolean publicMatches(Context context, String name, byte[] expected) throws Exception {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor cursor = context.getContentResolver().query(collection, new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                new String[]{name, "Download/SC-Log-Miner/%"}, null)) {
            if (cursor == null) return false;
            while (cursor.moveToNext()) {
                try (InputStream input = context.getContentResolver().openInputStream(ContentUris.withAppendedId(collection, cursor.getLong(0)))) {
                    if (input != null && Arrays.equals(expected, read(input))) return true;
                }
            }
        }
        return false;
    }
    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
