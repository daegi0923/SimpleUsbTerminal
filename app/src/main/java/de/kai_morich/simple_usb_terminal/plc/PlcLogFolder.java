package de.kai_morich.simple_usb_terminal.plc;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.util.HashMap;
import java.util.Map;

/** 사용자가 선택한 폴더에 날짜별 텍스트 로그를 자동 누적합니다. */
public final class PlcLogFolder implements PlcCaptureLog.Destination {
    private final Context context;
    private final Map<String, Uri> files = new HashMap<>();
    private final Map<String, OutputStream> streams = new HashMap<>();
    private final Map<String, String> streamNames = new HashMap<>();
    private String selected = "";

    /** 화면 수명과 무관하게 기록할 앱 컨텍스트를 보관합니다. */
    public PlcLogFolder(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 마지막으로 선택한 자동 저장 폴더 URI를 조회합니다. */
    static String get(Context context) {
        return context.getSharedPreferences("plc", Context.MODE_PRIVATE).getString("logFolder", "");
    }

    /** 원문과 상태 로그를 별도 텍스트 파일에 추가합니다. */
    @Override
    public void append(String name, byte[] line) throws IOException {
        String folder = get(context);
        if (folder.isEmpty()) return;
        if (!folder.equals(selected)) {
            close();
            files.clear();
            selected = folder;
        }
        String filename = name.endsWith(".log")
                ? name.replace("-state-", "-state-readable-").replace(".log", ".txt") : name;
        String kind = filename.contains("-plc-") ? "plc" : "state";
        if (filename.equals(streamNames.get(kind))) {
            OutputStream stream = streams.get(kind);
            stream.write(line);
            stream.flush();
            return;
        }
        OutputStream previous = streams.remove(kind);
        if (previous != null) previous.close();
        streamNames.remove(kind);
        Uri file = files.get(filename);
        if (file == null) {
            Uri tree = Uri.parse(folder);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) throw new IOException("저장 폴더를 읽을 수 없음");
                while (cursor.moveToNext()) {
                    if (filename.equals(cursor.getString(1))) {
                        file = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                        break;
                    }
                }
            }
            if (file == null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                file = DocumentsContract.createDocument(context.getContentResolver(), parent, filename.endsWith(".csv") ? "text/csv" : "text/plain", filename);
            }
            if (file == null) throw new IOException("로그 파일 생성 실패");
            files.put(filename, file);
        }
        boolean empty = false;
        if (filename.endsWith(".csv")) {
            try (Cursor cursor = context.getContentResolver().query(file,
                    new String[] {DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0))
                    throw new IOException("CSV 크기를 확인할 수 없습니다. 기기 내 저장 폴더를 선택해 주세요.");
                empty = cursor.getLong(0) == 0;
            }
        }
        OutputStream output = context.getContentResolver().openOutputStream(file, "wa");
        if (output == null) throw new IOException("로그 파일을 열 수 없음");
        OutputStream stream = new BufferedOutputStream(output);
        streams.put(kind, stream);
        streamNames.put(kind, filename);
        if (empty) stream.write(PlcCsv.HEADER.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        stream.write(line);
        stream.flush();
    }

    /** 저장 폴더 변경과 수집 종료 시 기록 스트림을 닫습니다. */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (OutputStream stream : streams.values()) {
            try { stream.close(); } catch (IOException e) { failure = e; }
        }
        streams.clear();
        streamNames.clear();
        if (failure != null) throw failure;
    }
}
