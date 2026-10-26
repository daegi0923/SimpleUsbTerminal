package de.kai_morich.simple_usb_terminal.plc;

import java.io.File;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Copies saved logs without changing the originals. */
public final class PlcLogExport {
    public interface Destination {
        OutputStream create(String relativePath) throws IOException;
    }

    public interface Progress {
        void copied(int count, int total);
    }

    public static final class Snapshot {
        private final List<Entry> entries = new ArrayList<>();
        public int size() { return entries.size(); }
        public String path(int index) { return entries.get(index).path; }
        public long length(int index) { return entries.get(index).length; }

        public Snapshot select(Set<String> paths) {
            Snapshot selected = new Snapshot();
            for (Entry entry : entries) if (paths.contains(entry.path)) selected.entries.add(entry);
            return selected;
        }
    }

    private static final class Entry {
        final File file;
        final String path;
        final long length;
        Entry(File file, String path) {
            this.file = file;
            this.path = path;
            this.length = file.length();
        }
    }

    public static final class Result {
        public final int files;
        public final long bytes;
        Result(int files, long bytes) { this.files = files; this.bytes = bytes; }
    }

    /** Record lengths between complete capture writes, then release the writer immediately. */
    public static Snapshot snapshot(File directory) throws IOException {
        Snapshot snapshot = new Snapshot();
        synchronized (PlcCaptureLog.FILE_LOCK) {
            if (directory.exists()) collect(directory, "", snapshot.entries);
        }
        return snapshot;
    }

    static Snapshot snapshot(File directory, Set<String> paths) throws IOException {
        if (paths == null) return snapshot(directory);
        Snapshot snapshot = new Snapshot();
        synchronized (PlcCaptureLog.FILE_LOCK) {
            for (String path : new TreeSet<>(paths)) {
                File file = new File(directory, path);
                if (!file.isFile()) throw new IOException("내부 로그를 읽을 수 없습니다: " + path);
                snapshot.entries.add(new Entry(file, path));
            }
        }
        return snapshot;
    }

    private static void collect(File directory, String prefix, List<Entry> entries) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("로그 폴더를 읽을 수 없습니다: " + directory.getName());
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) {
            String path = prefix + file.getName();
            if (file.isDirectory()) collect(file, path + "/", entries);
            else if (file.isFile()) {
                String name = file.getName().toLowerCase(Locale.US);
                if (name.endsWith(".csv") || name.endsWith(".log")) entries.add(new Entry(file, path));
            }
        }
    }

    public static Result copy(Snapshot snapshot, Destination destination, Progress progress) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int files = 0;
        long bytes = 0;
        for (Entry entry : snapshot.entries) {
            // Append-only capture may continue; never copy beyond the recorded boundary.
            try (FileInputStream input = new FileInputStream(entry.file);
                 OutputStream output = destination.create(entry.path)) {
                if (output == null) throw new IOException("내보낼 파일을 만들 수 없습니다: " + entry.path);
                long remaining = entry.length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException("복사 중 원본 로그가 변경됐습니다: " + entry.path);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            files++;
            bytes += entry.length;
            progress.copied(files, snapshot.size());
        }
        return new Result(files, bytes);
    }

    private PlcLogExport() { }
}
