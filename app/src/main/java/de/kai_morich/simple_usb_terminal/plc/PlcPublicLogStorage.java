package de.kai_morich.simple_usb_terminal.plc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.AtomicFile;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** Uses only this installation's MediaStore entries, leaving older installations intact. */
@RequiresApi(Build.VERSION_CODES.Q)
final class PlcPublicLogStorage implements PlcLogMirror.Target {
    private final ContentResolver resolver;
    private final SharedPreferences mappings;
    private final String installation;
    private final Map<String, Long> expectedLengths = new HashMap<>();

    PlcPublicLogStorage(Context context) throws IOException {
        try {
            Context app = context.getApplicationContext();
            resolver = app.getContentResolver();
            mappings = app.getSharedPreferences("plc_public_logs", Context.MODE_PRIVATE);
            installation = installation(app);
        } catch (RuntimeException e) {
            throw new IOException("공용 로그 저장소 준비 실패", e);
        }
    }

    private static synchronized String installation(Context app) throws IOException {
        AtomicFile file = new AtomicFile(new File(app.getNoBackupFilesDir(), "plc-public-installation"));
        try {
            String value = new String(file.readFully(), StandardCharsets.UTF_8).trim();
            if (!value.matches("[0-9]{8}-[0-9]{6}-[0-9a-f]{8}"))
                throw new IOException("공용 로그 설치 식별자 파일이 손상됐습니다.");
            return value;
        } catch (FileNotFoundException missing) {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
            String value = format.format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8);
            FileOutputStream output = file.startWrite();
            try {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                file.finishWrite(output);
                if (!value.equals(new String(file.readFully(), StandardCharsets.UTF_8)))
                    throw new IOException("공용 로그 설치 식별자를 저장하지 못했습니다.");
                return value;
            } catch (IOException | RuntimeException e) {
                file.failWrite(output);
                throw e;
            }
        }
    }

    private Uri mapped(String path) {
        String value = mappings.getString(installation + "/" + path, null);
        return value == null ? null : Uri.parse(value);
    }

    @Override
    public long length(String path) throws IOException {
        try {
            Uri uri = mapped(path);
            if (uri == null) {
                expectedLengths.put(path, 0L);
                return 0;
            }
            try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
                if (file == null) throw new IOException("공용 로그 파일을 열 수 없습니다: " + path);
                long size = file.getStatSize();
                if (size < 0) throw new IOException("공용 로그 파일 크기를 확인할 수 없습니다: " + path);
                expectedLengths.put(path, size);
                return size;
            } catch (FileNotFoundException | SecurityException missing) {
                newFile(path);
                return 0;
            }
        } catch (RuntimeException e) {
            throw new IOException("공용 로그 파일 확인 실패: " + path, e);
        }
    }

    @Override
    public OutputStream append(String path) throws IOException {
        try {
            Uri uri = mapped(path);
            boolean existed = uri != null;
            if (!existed) {
                newFile(path);
                uri = mapped(path);
            }
            ParcelFileDescriptor file = null;
            try {
                file = resolver.openFileDescriptor(uri, "wa");
                if (file == null) throw new IOException("공용 로그 파일 열기 실패: " + path);
                Long expected = expectedLengths.get(path);
                if (expected == null || file.getStatSize() != expected)
                    throw new IOException("공용 로그 파일 크기가 변경됐습니다. 다시 복사합니다: " + path);
                OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(file);
                file = null;
                return output;
            } catch (FileNotFoundException | SecurityException missing) {
                if (existed) newFile(path);
                // The mirror must recalculate the source offset for a replacement file.
                throw new IOException("공용 로그 파일이 변경됐습니다. 다시 복사합니다: " + path, missing);
            } finally {
                if (file != null) file.close();
            }
        } catch (RuntimeException e) {
            throw new IOException("공용 로그 파일 기록 준비 실패: " + path, e);
        }
    }

    @Override
    public void newFile(String path) throws IOException {
        try {
            int separator = path.lastIndexOf('/');
            String filename = path.substring(separator + 1);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE,
                    filename.toLowerCase(Locale.US).endsWith(".csv") ? "text/csv" : "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS
                    + "/SC-Log-Miner/" + installation + "/" + path.substring(0, separator + 1));
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) throw new IOException("공용 로그 파일 생성 실패: " + path);
            if (!mappings.edit().putString(installation + "/" + path, uri.toString()).commit())
                throw new IOException("공용 로그 파일 위치 저장 실패: " + path);
            expectedLengths.put(path, 0L);
        } catch (RuntimeException e) {
            throw new IOException("공용 로그 파일 생성 실패: " + path, e);
        }
    }
}
