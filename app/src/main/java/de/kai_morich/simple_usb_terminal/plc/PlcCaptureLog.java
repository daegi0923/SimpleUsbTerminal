package de.kai_morich.simple_usb_terminal.plc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** USB 원문과 PLC 분석 결과를 제한된 파일 용량으로 보관합니다. */
public final class PlcCaptureLog {
    public interface Destination {
        /** 선택한 폴더의 로그 파일에 기록을 추가합니다. */
        void append(String name, byte[] line) throws IOException;
        /** 수집 종료 시 열려 있는 기록 파일을 닫습니다. */
        default void close() throws IOException { }
    }

    public interface Logger {
        /** 수집 결과와 오류를 호출 측에 전달합니다. */
        void log(String message);
    }

    interface Completion {
        /** 대기 중인 기록의 처리 완료를 알립니다. */
        void done(String error);
    }

    private final File directory;
    private final long limit;
    private final String source;
    private String scNumber = "";
    private final Map<String, File> activeFiles = new HashMap<>();
    private volatile Destination destination;
    private final Logger errors;
    private final Logger events;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256)) {
        /** 모든 대기 기록이 끝나면 선택 폴더의 파일을 닫습니다. */
        @Override
        protected void terminated() {
            try {
                if (destination != null) destination.close();
            } catch (IOException e) {
                errors.log("PLC 로그 파일 닫기 실패: " + e);
            }
        }
    };
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final PlcStateMonitor monitor;
    private long observedAt;
    private long lastSequence;
    private boolean reportedError;

    /** 기록 경로와 파일별 용량, 로그 출력 대상을 설정합니다. */
    public PlcCaptureLog(File directory, long limit, String source, Logger events,
                  Logger errors) {
        this.directory = directory;
        this.limit = limit;
        this.source = source;
        this.events = events;
        this.errors = errors;
        monitor = new PlcStateMonitor(this::analysis, () -> observedAt);
    }

    /** 수신 시각과 원문을 복사하여 별도 작업 스레드에 기록을 요청합니다. */
    public synchronized void receive(byte[] data, long receivedAt, String equipment) {
        long number = sequence.incrementAndGet();
        byte[] copy = data.clone();
        submit(() -> {
            observedAt = receivedAt;
            checkGap(number);
            String next = ScNumber.isValid(equipment) ? equipment : "";
            if (!scNumber.equals(next)) {
                scNumber = next;
                monitor.setScNumber(next);
                append("SC_CHANGED", next);
            }
            append("RX", new String(copy, StandardCharsets.UTF_8));
            monitor.accept(copy);
        });
    }

    /** 세션 구분을 기록하고 이전 연결의 분석 상태를 초기화합니다. */
    public synchronized void session(String name) {
        long number = sequence.incrementAndGet();
        long time = System.currentTimeMillis();
        submit(() -> {
            observedAt = time;
            checkGap(number);
            append("SESSION", name);
            monitor.reset();
        });
    }

    /** 사용자 선택 폴더에 파일을 자동 누적할 기록 대상을 설정합니다. */
    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    /** 앞서 요청한 기록이 모두 처리되면 완료를 알립니다. */
    void flush(Completion completion) {
        try {
            worker.execute(() -> completion.done(null));
        } catch (RejectedExecutionException e) {
            completion.done("기록 대기열이 가득 참");
        }
    }

    /** 이미 대기 중인 기록을 마친 뒤 작업 스레드를 종료합니다. */
    public void close() {
        worker.shutdown();
    }

    /** 누락이 발생한 순서에서 분석 상태를 초기화하여 메시지 오조립을 막습니다. */
    private void checkGap(long number) {
        if (number != lastSequence + 1) {
            append("GAP", "droppedEvents=" + (number - lastSequence - 1));
            monitor.reset();
        }
        lastSequence = number;
    }

    /** 대기열 초과 시 누락 수를 집계하고 로그 수집 문제를 알립니다. */
    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            dropped.incrementAndGet();
            if (overflowReported.compareAndSet(false, true))
                errors.log("PLC 로그 대기열 초과: 일부 기록이 누락됨");
        }
    }

    /** 분석 이벤트를 파일에 남기고 요약 및 모의 요청을 Logcat에 전달합니다. */
    private void analysis(String message) {
        if (message.startsWith("FRAME")) return;
        append("ANALYSIS", message);
        events.log(source + " SC=" + scNumber + " " + message);
    }

    /** JSON 문자열의 제어 문자와 구분 문자를 이스케이프합니다. */
    private String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' || ch == '"') result.append('\\').append(ch);
            else if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.toString();
    }

    /** PLC 원문 CSV와 상태 JSONL을 날짜별로 기록하고 파일을 분할합니다. */
    private void append(String type, String value) {
        boolean raw = type.equals("RX");
        byte[] line = raw ? PlcCsv.row(observedAt, value).getBytes(StandardCharsets.UTF_8)
                : ("{\"receivedAtEpochMs\":" + observedAt + ",\"type\":\"" + type
                + "\",\"source\":\"" + source + "\",\"scNumber\":\"" + scNumber
                + "\",\"value\":\"" + escape(value) + "\"}\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("로그 폴더 생성 실패");
            File activeFile = selectFile(raw ? "plc" : "state", line.length);
            boolean header = raw && activeFile.length() == 0;
            byte[] headerBytes = PlcCsv.HEADER.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = new FileOutputStream(activeFile, true)) {
                if (header) output.write(headerBytes);
                output.write(line);
            }
            Destination selected = destination;
            if (selected != null) selected.append(activeFile.getName(), line);
        } catch (IOException | SecurityException e) {
            if (!reportedError) errors.log("PLC 로그 저장 실패: " + e);
            reportedError = true;
        }
    }

    /** 원문과 상태 로그를 한국 날짜별로 나누고 파일 용량 초과 시 분할합니다. */
    private File selectFile(String kind, int size) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        String day = format.format(new Date(observedAt));
        String extension = kind.equals("plc") ? ".csv" : ".jsonl";
        String prefix = source.toLowerCase(Locale.US) + "-" + kind + "-" + day + "-";
        File active = activeFiles.get(prefix);
        if (active == null) {
            int part = 0;
            File[] files = directory.listFiles((dir, name) -> name.matches(prefix + "[0-9]+\\" + extension));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    part = Math.max(part, Integer.parseInt(name.substring(prefix.length(), name.length() - extension.length())));
                }
            }
            active = new File(directory, prefix + String.format(Locale.US, "%06d", part) + extension);
        }
        if (active.length() + size > limit && active.length() > 0) {
            String name = active.getName();
            int part = Integer.parseInt(name.substring(prefix.length(), name.length() - extension.length())) + 1;
            active = new File(directory, prefix + String.format(Locale.US, "%06d", part) + extension);
        }
        activeFiles.put(prefix, active);
        return active;
    }
}
