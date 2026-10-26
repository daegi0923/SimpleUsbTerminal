package de.kai_morich.simple_usb_terminal;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises display buffering without an Android runtime. */
public final class SerialUiBufferTest {
    public static void main(String[] args) throws Exception {
        preservesChronologicalKinds();
        boundsBytesAndEvents();
        retainsOversizedTails();
        copiesInputAndResetsOnDrain();
        acceptsConcurrentEvents();
        System.out.println("PASS: FIFO kinds, byte/event caps, overflow warning, oversized tails, defensive copy, drain reset, concurrent writes");
    }

    private static void preservesChronologicalKinds() {
        SerialUiBuffer buffer = new SerialUiBuffer();
        buffer.addData(new byte[]{1, 2});
        buffer.addPlc("PLC event");
        buffer.addStatus("connected");
        List<SerialUiBuffer.Event> events = buffer.drain();
        check(events.size() == 3, "three events retained");
        check(events.get(0).kind == SerialUiBuffer.Kind.DATA, "DATA first");
        check(Arrays.equals(events.get(0).data, new byte[]{1, 2}), "raw bytes unchanged");
        check(events.get(1).kind == SerialUiBuffer.Kind.PLC, "PLC second");
        check(events.get(1).message.equals("PLC event"), "PLC message unchanged");
        check(events.get(2).kind == SerialUiBuffer.Kind.STATUS, "STATUS third");
        check(events.get(2).message.equals("connected"), "status message unchanged");
    }

    private static void boundsBytesAndEvents() {
        SerialUiBuffer byBytes = new SerialUiBuffer(6, 10);
        byBytes.addData(new byte[]{1, 2, 3});
        byBytes.addPlc("AB");
        byBytes.addData(new byte[]{4, 5});
        List<SerialUiBuffer.Event> events = byBytes.drain();
        checkWarning(events.get(0));
        check(events.size() == 3, "oldest DATA evicted to keep six bytes");
        check(events.get(1).message.equals("AB"), "four-byte PLC message retained");
        check(Arrays.equals(events.get(2).data, new byte[]{4, 5}), "newest two raw bytes retained");

        SerialUiBuffer byEvents = new SerialUiBuffer(100, 2);
        byEvents.addStatus("oldest");
        byEvents.addStatus("middle");
        byEvents.addStatus("newest");
        events = byEvents.drain();
        checkWarning(events.get(0));
        check(events.size() == 3, "two stored events plus one generated warning");
        check(events.get(1).message.equals("middle"), "oldest event evicted");
        check(events.get(2).message.equals("newest"), "chronological tail retained");

        SerialUiBuffer defaults = new SerialUiBuffer();
        for (int i = 0; i < 1025; i++) defaults.addData(new byte[]{(byte) i});
        events = defaults.drain();
        checkWarning(events.get(0));
        check(events.size() == 1025, "default event cap is 1024 plus generated warning");
        check(events.get(1).data[0] == 1, "default cap discards oldest event");
    }

    private static void retainsOversizedTails() {
        SerialUiBuffer data = new SerialUiBuffer(4, 4);
        data.addData(new byte[]{1, 2, 3, 4, 5, 6});
        List<SerialUiBuffer.Event> events = data.drain();
        checkWarning(events.get(0));
        check(Arrays.equals(events.get(1).data, new byte[]{3, 4, 5, 6}), "oversized data retains recent tail");

        SerialUiBuffer text = new SerialUiBuffer(6, 4);
        text.addStatus("012345");
        events = text.drain();
        checkWarning(events.get(0));
        check(events.get(1).message.equals("345"), "text bounded at two bytes per character");
        text.addPlc("A\uD83D\uDE00B");
        events = text.drain();
        checkWarning(events.get(0));
        check(events.get(1).message.equals("\uD83D\uDE00B"), "complete surrogate pair retained");

        SerialUiBuffer splitPair = new SerialUiBuffer(4, 4);
        splitPair.addStatus("A\uD83D\uDE00B");
        events = splitPair.drain();
        check(events.get(1).message.equals("B"), "truncation does not split surrogate pair");

        SerialUiBuffer tiny = new SerialUiBuffer(1, 1);
        tiny.addStatus("AB");
        events = tiny.drain();
        checkWarning(events.get(0));
        check(events.get(1).message.isEmpty(), "text fits a one-byte budget without crashing");

        SerialUiBuffer defaults = new SerialUiBuffer();
        defaults.addData(new byte[256 * 1024 + 1]);
        events = defaults.drain();
        checkWarning(events.get(0));
        check(events.get(1).data.length == 256 * 1024, "default byte cap is 256 KiB");
    }

    private static void copiesInputAndResetsOnDrain() {
        SerialUiBuffer buffer = new SerialUiBuffer(2, 1);
        byte[] input = {1, 2};
        buffer.addData(input);
        input[0] = 9;
        List<SerialUiBuffer.Event> first = buffer.drain();
        check(first.get(0).data[0] == 1, "caller mutation cannot alter stored bytes");
        check(buffer.drain().isEmpty(), "drain clears events");
        buffer.addData(new byte[]{3});
        buffer.addData(new byte[]{4});
        checkWarning(buffer.drain().get(0));
        buffer.addData(new byte[]{5, 6});
        List<SerialUiBuffer.Event> last = buffer.drain();
        check(last.size() == 1 && last.get(0).kind == SerialUiBuffer.Kind.DATA,
                "drain resets byte accounting and overflow state");
        check(first.get(0).data[0] == 1, "drained events remain independent");
    }

    private static void acceptsConcurrentEvents() throws Exception {
        SerialUiBuffer buffer = new SerialUiBuffer(10000, 10000);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] producers = new Thread[4];
        for (int writer = 0; writer < producers.length; writer++) {
            final int id = writer;
            producers[writer] = new Thread(() -> {
                try {
                    ready.await();
                    for (int i = 0; i < 1000; i++) buffer.addData(new byte[]{(byte) id});
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            producers[writer].start();
        }
        ready.countDown();
        for (Thread producer : producers) producer.join();
        check(failure.get() == null, "concurrent writers do not fail");
        int[] counts = new int[4];
        List<SerialUiBuffer.Event> events = buffer.drain();
        check(events.size() == 4000, "all concurrent events retained");
        for (SerialUiBuffer.Event event : events) counts[event.data[0]]++;
        for (int count : counts) check(count == 1000, "each writer's events retained exactly once");
    }

    private static void checkWarning(SerialUiBuffer.Event event) {
        check(event.kind == SerialUiBuffer.Kind.STATUS, "overflow is a STATUS event");
        check(event.message.equals("화면 표시 대기열 일부 생략. PLC 파일 기록은 별도로 계속됩니다."),
                "warning distinguishes omitted display from independent file recording");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
