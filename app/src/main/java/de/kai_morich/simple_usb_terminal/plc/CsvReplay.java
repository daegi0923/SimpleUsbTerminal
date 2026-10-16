package de.kai_morich.simple_usb_terminal.plc;

import java.util.List;

/** CSV 시간 간격에 맞춘 재생을 화면 및 USB 통신과 분리합니다. */
final class CsvReplay {
    interface Scheduler {
        /** 단조 증가하는 현재 시각을 반환합니다. */
        long now();
        /** 지정한 지연 후 작업을 실행합니다. */
        void post(Runnable task, long delay);
        /** 예정된 작업을 취소합니다. */
        void cancel(Runnable task);
    }
    interface Listener {
        /** 원본 시각을 가진 CSV 메시지를 수신 경로에 전달합니다. */
        void receive(PlcCsv.Entry entry);
        /** 모든 메시지 주입이 완료됐음을 알립니다. */
        void complete();
    }
    private final Scheduler scheduler;
    private final Listener listener;
    private final Runnable tick = this::tick;
    private List<PlcCsv.Entry> entries;
    private int index;
    private long started;

    /** 재생 시계와 메시지 전달 대상을 설정합니다. */
    CsvReplay(Scheduler scheduler, Listener listener) { this.scheduler = scheduler; this.listener = listener; }

    /** 첫 메시지는 즉시, 이후 메시지는 원본 시간 간격대로 재생합니다. */
    void start(List<PlcCsv.Entry> entries) {
        stop();
        this.entries = entries;
        index = 0;
        started = scheduler.now();
        scheduler.post(tick, 0);
    }

    /** 재생 예약을 취소합니다. */
    void stop() { scheduler.cancel(tick); entries = null; }

    /** 동일 시각의 대량 메시지도 화면을 막지 않도록 나누어 전달합니다. */
    private void tick() {
        if (entries == null) return;
        int count = 0;
        while (index < entries.size()) {
            long delay = entries.get(index).timestamp - entries.get(0).timestamp - (scheduler.now() - started);
            if (delay > 0 || count == 100) { scheduler.post(tick, Math.max(0, delay)); return; }
            listener.receive(entries.get(index++));
            count++;
        }
        entries = null;
        listener.complete();
    }
}
