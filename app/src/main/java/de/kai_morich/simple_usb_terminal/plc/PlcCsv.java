package de.kai_morich.simple_usb_terminal.plc;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** PLC 원문 기록과 재생에 공통으로 사용하는 두 열 CSV 형식입니다. */
final class PlcCsv {
    static final String HEADER = "timestamp,message\r\n";

    static final class Entry {
        final long timestamp;
        final String message;
        /** CSV 원본 시각과 수신 원문을 보관합니다. */
        Entry(long timestamp, String message) { this.timestamp = timestamp; this.message = message; }
    }

    /** 원문 안의 쉼표·따옴표·줄바꿈을 보존하여 CSV 한 행을 생성합니다. */
    static String row(long timestamp, String message) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        String time = format.format(new Date(timestamp));
        time = time.substring(0, time.length() - 2) + ":" + time.substring(time.length() - 2);
        return time + ",\"" + message.replace("\"", "\"\"") + "\"\r\n";
    }

    /** 헤더·시각 순서·열 수를 검증하고 재생할 CSV 레코드를 읽습니다. */
    static List<Entry> read(Reader reader) throws IOException {
        PushbackReader input = new PushbackReader(reader, 1);
        int first = input.read();
        if (first != -1 && first != '\ufeff') input.unread(first);
        List<String> header = record(input);
        if (header == null || header.size() != 2 || !header.get(0).equals("timestamp") || !header.get(1).equals("message"))
            throw new IOException("CSV 첫 줄은 timestamp,message 형식이어야 합니다.");
        List<Entry> result = new ArrayList<>();
        List<String> fields;
        int row = 1;
        long characters = 0;
        while ((fields = record(input)) != null) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("CSV 읽기가 취소되었습니다.");
            row++;
            if (fields.size() == 1 && fields.get(0).isEmpty()) continue;
            if (fields.size() != 2) throw new IOException(row + "번째 레코드: 시간과 원문 두 열이 필요합니다.");
            characters += fields.get(0).length() + fields.get(1).length();
            if (characters > 16 * 1024 * 1024) throw new IOException("CSV 내용은 최대 16MiB까지 읽을 수 있습니다.");
            long time = timestamp(fields.get(0), row);
            if (!result.isEmpty() && time < result.get(result.size() - 1).timestamp)
                throw new IOException(row + "번째 레코드: 시간이 이전 행보다 앞서 있습니다.");
            result.add(new Entry(time, fields.get(1)));
            if (result.size() > 100000) throw new IOException("한 번에 최대 100,000개 레코드까지 재생할 수 있습니다.");
        }
        if (result.isEmpty()) throw new IOException("CSV에 재생할 데이터가 없습니다.");
        return result;
    }

    /** 시간대가 포함된 밀리초 시각을 엄격하게 검증합니다. */
    private static long timestamp(String text, int row) throws IOException {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}(Z|[+-][0-9]{2}:[0-9]{2})"))
            throw new IOException(row + "번째 레코드: 시각을 2026-10-15T10:00:00.000+09:00 형식으로 입력해 주세요.");
        String normalized = text.endsWith("Z") ? text.substring(0, text.length() - 1) + "+0000"
                : text.substring(0, text.length() - 3) + text.substring(text.length() - 2);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(normalized, position);
        if (date == null || position.getIndex() != normalized.length()) throw new IOException(row + "번째 레코드: 올바르지 않은 시각입니다.");
        return date.getTime();
    }

    /** 따옴표 안의 줄바꿈을 포함한 CSV 레코드 하나를 읽습니다. */
    private static List<String> record(PushbackReader input) throws IOException {
        List<String> values = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false, any = false;
        int value;
        while ((value = input.read()) != -1) {
            any = true;
            char ch = (char) value;
            if (quoted) {
                if (ch == '"') {
                    int next = input.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; closed = true; if (next != -1) input.unread(next); }
                } else field.append(ch);
            } else if (ch == ',' || ch == '\r' || ch == '\n') {
                values.add(field.toString());
                field.setLength(0);
                closed = false;
                if (ch != ',') {
                    if (ch == '\r') { int next = input.read(); if (next != '\n' && next != -1) input.unread(next); }
                    return values;
                }
            } else if (ch == '"' && field.length() == 0 && !closed) quoted = true;
            else {
                if (closed || ch == '"') throw new IOException("CSV 따옴표 형식이 올바르지 않습니다.");
                field.append(ch);
            }
            if (field.length() > 1048576 || values.size() > 2) throw new IOException("CSV 레코드가 너무 크거나 열 수가 올바르지 않습니다.");
        }
        if (quoted) throw new IOException("CSV 따옴표가 닫히지 않았습니다.");
        if (!any && values.isEmpty()) return null;
        values.add(field.toString());
        return values;
    }
}
