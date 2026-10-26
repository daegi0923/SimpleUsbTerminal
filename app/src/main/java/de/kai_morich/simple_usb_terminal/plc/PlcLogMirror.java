package de.kai_morich.simple_usb_terminal.plc;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

/** Reconciles public copies with complete private records without holding the capture lock. */
final class PlcLogMirror {
    interface Target {
        long length(String path) throws IOException;
        OutputStream append(String path) throws IOException;
        void newFile(String path) throws IOException;
    }

    static int sync(File directory, Target target) throws IOException {
        return sync(directory, target, null);
    }

    static int sync(File directory, Target target, Set<String> paths) throws IOException {
        PlcLogExport.Snapshot snapshot = PlcLogExport.snapshot(directory, paths);
        byte[] buffer = new byte[32 * 1024];
        for (int index = 0; index < snapshot.size(); index++) {
            String path = snapshot.path(index);
            long size = snapshot.length(index);
            long offset = target.length(path);
            if (offset < 0) throw new IOException("공용 로그 크기를 확인할 수 없습니다: " + path);
            if (offset > size) {
                // A user-modified or longer public file must remain untouched.
                target.newFile(path);
                offset = 0;
            }
            if (offset == size && size > 0) continue;
            try (FileInputStream input = new FileInputStream(new File(directory, path));
                 OutputStream output = target.append(path)) {
                if (output == null) throw new IOException("공용 로그를 열 수 없습니다: " + path);
                input.getChannel().position(offset);
                long remaining = size - offset;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException("복사 중 내부 로그가 변경됐습니다: " + path);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
        return snapshot.size();
    }

    private PlcLogMirror() { }
}
