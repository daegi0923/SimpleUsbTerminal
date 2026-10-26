package de.kai_morich.simple_usb_terminal.plc;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Creates a separate export directory without touching automatic log destinations. */
final class PlcLogExportFolder implements PlcLogExport.Destination {
    private final ContentResolver resolver;
    private final Map<String, Uri> directories = new HashMap<>();
    final String name;

    PlcLogExportFolder(ContentResolver resolver, Uri tree) throws IOException {
        this.resolver = resolver;
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Set<String> existing = new HashSet<>();
        try (Cursor cursor = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IOException("선택한 폴더를 읽을 수 없습니다.");
            while (cursor.moveToNext()) existing.add(cursor.getString(0));
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        String base = "SC-Log-Miner-logs-" + format.format(new Date());
        String selected = base;
        for (int suffix = 2; existing.contains(selected); suffix++) selected = base + "-" + suffix;
        name = selected;
        Uri directory = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (directory == null) throw new IOException("내보내기 폴더를 만들 수 없습니다.");
        directories.put("", directory);
    }

    @Override
    public OutputStream create(String relativePath) throws IOException {
        String[] parts = relativePath.split("/");
        String prefix = "";
        Uri parent = directories.get(prefix);
        for (int index = 0; index < parts.length - 1; index++) {
            prefix += parts[index] + "/";
            Uri next = directories.get(prefix);
            if (next == null) {
                next = DocumentsContract.createDocument(resolver, parent,
                        DocumentsContract.Document.MIME_TYPE_DIR, parts[index]);
                if (next == null) throw new IOException("하위 폴더 생성 실패: " + prefix);
                directories.put(prefix, next);
            }
            parent = next;
        }
        String filename = parts[parts.length - 1];
        // text/plain makes Android's Documents provider append .txt to .log names.
        Uri file = DocumentsContract.createDocument(resolver, parent,
                filename.toLowerCase(Locale.US).endsWith(".csv") ? "text/csv" : "application/octet-stream", filename);
        if (file == null) throw new IOException("파일 생성 실패: " + filename);
        OutputStream output = resolver.openOutputStream(file, "w");
        if (output == null) throw new IOException("파일 열기 실패: " + filename);
        return output;
    }
}
