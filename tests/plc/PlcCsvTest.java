package de.kai_morich.simple_usb_terminal.plc;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** CSV 기록·검증과 원본 시간 간격 재생을 검증합니다. */
public final class PlcCsvTest {
    /** 원문 보존, 잘못된 CSV 거부, 재생 간격과 중지를 검사합니다. */
    public static void main(String[] args) throws Exception {
        long start = 1792026000000L;
        String message = "B012\r\n\"junk,noise\"\n343000";
        List<PlcCsv.Entry> roundtrip = PlcCsv.read(new StringReader("\ufeff" + PlcCsv.HEADER + PlcCsv.row(start, message)));
        check(roundtrip.size() == 1 && roundtrip.get(0).timestamp == start && roundtrip.get(0).message.equals(message), "raw CSV round trip");
        for (String invalid : new String[] {
                "time,message\n", PlcCsv.HEADER,
                "timestamp,message\n2026-02-30T10:00:00.000+09:00,B012343000\n",
                "timestamp,message\n2026-10-15 10:00:00,B012343000\n",
                PlcCsv.HEADER + PlcCsv.row(start + 1, "B") + PlcCsv.row(start, "A"),
                "timestamp,message\n2026-10-15T10:00:00.000+09:00,\"unterminated",
                "timestamp,message\n2026-10-15T10:00:00.000+09:00,abc,extra\n"}) {
            try { PlcCsv.read(new StringReader(invalid)); throw new AssertionError("invalid CSV accepted"); }
            catch (IOException expected) { }
        }
        List<PlcCsv.Entry> entries = PlcCsv.read(new StringReader(PlcCsv.HEADER
                + PlcCsv.row(start, "B012343000") + PlcCsv.row(start + 100, "F012343000")
                + PlcCsv.row(start + 100, "E012343000") + PlcCsv.row(start + 4000, "A012343000")));
        FakeScheduler scheduler = new FakeScheduler();
        List<Long> times = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PlcStateMonitor monitor = new PlcStateMonitor(events::add, () -> scheduler.now);
        monitor.setScNumber("301");
        int[] complete = {0};
        CsvReplay replay = new CsvReplay(scheduler, new CsvReplay.Listener() {
            /** 재생 메시지를 같은 상태 분석기에 전달합니다. */
            public void receive(PlcCsv.Entry entry) {
                times.add(scheduler.now);
                monitor.accept(entry.message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            /** 정상 재생 완료 횟수를 집계합니다. */
            public void complete() { complete[0]++; }
        });
        replay.start(entries);
        scheduler.drain();
        check(times.toString().equals("[0, 100, 100, 4000]"), "relative replay times");
        check(complete[0] == 1 && events.stream().anyMatch(e -> e.contains("컨테이너 픽업 완료 패턴 추정")), "same analysis pipeline");
        int previous = times.size();
        replay.start(entries);
        replay.stop();
        scheduler.drain();
        check(times.size() == previous && complete[0] == 1, "stop cancels replay");
        List<PlcCsv.Entry> dense = new ArrayList<>();
        for (int i = 0; i < 1000; i++) dense.add(new PlcCsv.Entry(start, "B012343000"));
        replay.start(dense);
        scheduler.drain();
        check(times.size() == previous + 1000 && complete[0] == 2, "equal timestamps batched without loss");
        System.out.println("PASS: CSV quoting/BOM/timestamps/errors, relative timing, cancellation, 1000 simultaneous rows, pickup inference");
    }

    private static final class FakeScheduler implements CsvReplay.Scheduler {
        long now;
        Runnable task;
        long due;
        /** 테스트 시각을 반환합니다. */
        public long now() { return now; }
        /** 단일 재생 예약을 보관합니다. */
        public void post(Runnable task, long delay) { this.task = task; due = now + delay; }
        /** 예약을 취소합니다. */
        public void cancel(Runnable task) { if (this.task == task) this.task = null; }
        /** 예약 시각으로 이동하며 재생을 완료합니다. */
        void drain() {
            while (task != null) { Runnable next = task; task = null; now = due; next.run(); }
        }
    }

    /** 검증 조건을 충족하지 못하면 테스트를 실패 처리합니다. */
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
