package de.kai_morich.simple_usb_terminal.plc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Exports to Downloads when the system folder picker cannot be opened. */
@RequiresApi(Build.VERSION_CODES.Q)
final class PlcLogDownloadsFolder implements PlcLogExport.Destination {
    private final ContentResolver resolver;
    private final List<Uri> pending = new ArrayList<>();
    final String name;

    PlcLogDownloadsFolder(ContentResolver resolver) {
        this.resolver = resolver;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        name = "SC-Log-Miner-logs-" + format.format(new Date()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public OutputStream create(String relativePath) throws IOException {
        int separator = relativePath.lastIndexOf('/');
        String filename = relativePath.substring(separator + 1);
        String subdirectory = relativePath.substring(0, separator + 1);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        // text/plain can cause providers to append .txt to a .log filename.
        values.put(MediaStore.Downloads.MIME_TYPE,
                filename.toLowerCase(Locale.US).endsWith(".csv") ? "text/csv" : "application/octet-stream");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + name + "/" + subdirectory);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri file = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (file == null) throw new IOException("다운로드 파일 생성 실패: " + filename);
        pending.add(file);
        OutputStream output = resolver.openOutputStream(file, "w");
        if (output == null) throw new IOException("다운로드 파일 열기 실패: " + filename);
        return output;
    }

    void commit() throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        for (Uri file : pending) {
            if (resolver.update(file, values, null, null) != 1)
                throw new IOException("다운로드 파일 공개 실패: " + file);
        }
        pending.clear();
    }

    /** A failed export must never delete files belonging to earlier exports. */
    void abort() {
        Iterator<Uri> files = pending.iterator();
        while (files.hasNext()) {
            Uri file = files.next();
            try {
                resolver.delete(file, null, null);
                files.remove();
            } catch (RuntimeException e) {
                Log.w("PlcLogExport", "미완료 내보내기 파일 정리 실패: " + file, e);
            }
        }
    }
}
