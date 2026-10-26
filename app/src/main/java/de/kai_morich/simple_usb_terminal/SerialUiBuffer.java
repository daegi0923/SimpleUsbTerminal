package de.kai_morich.simple_usb_terminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Keeps recent display events while the activity is hidden; file logging is independent. */
public final class SerialUiBuffer {
    public enum Kind { DATA, PLC, STATUS }

    public static final class Event {
        public final Kind kind;
        public final byte[] data;
        public final String message;

        private Event(Kind kind, byte[] data, String message) {
            this.kind = kind;
            this.data = data;
            this.message = message;
        }

        private int size() {
            return data != null ? data.length : message.length() * 2;
        }
    }

    private static final String OVERFLOW_MESSAGE =
            "화면 표시 대기열 일부 생략. PLC 파일 기록은 별도로 계속됩니다.";

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final int maxBytes;
    private final int maxEvents;
    private int bytes;
    private boolean overflow;

    public SerialUiBuffer() {
        this(256 * 1024, 1024);
    }

    public SerialUiBuffer(int maxBytes, int maxEvents) {
        if (maxBytes <= 0 || maxEvents <= 0) {
            throw new IllegalArgumentException("Display buffer limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxEvents = maxEvents;
    }

    public synchronized void addData(byte[] data) {
        if (data.length == 0) return;
        int start = Math.max(0, data.length - maxBytes);
        if (start > 0) overflow = true;
        add(new Event(Kind.DATA, Arrays.copyOfRange(data, start, data.length), null));
    }

    public synchronized void addPlc(String message) {
        addMessage(Kind.PLC, message);
    }

    public synchronized void addStatus(String message) {
        addMessage(Kind.STATUS, message);
    }

    private void addMessage(Kind kind, String message) {
        int start = Math.max(0, message.length() - maxBytes / 2);
        if (start > 0) {
            overflow = true;
            // Do not retain half a surrogate pair when truncating a message.
            if (start < message.length() && Character.isLowSurrogate(message.charAt(start))) start++;
            message = message.substring(start);
        }
        add(new Event(kind, null, message));
    }

    private void add(Event event) {
        int size = event.size();
        while (!events.isEmpty() && (bytes > maxBytes - size || events.size() >= maxEvents)) {
            bytes -= events.removeFirst().size();
            overflow = true;
        }
        events.addLast(event);
        bytes += size;
    }

    public synchronized List<Event> drain() {
        List<Event> result = new ArrayList<>(events.size() + (overflow ? 1 : 0));
        if (overflow) result.add(new Event(Kind.STATUS, null, OVERFLOW_MESSAGE));
        result.addAll(events);
        events.clear();
        bytes = 0;
        overflow = false;
        return result;
    }
}
