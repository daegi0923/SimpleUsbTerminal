package de.kai_morich.simple_usb_terminal.plc;

import de.kai_morich.simple_usb_terminal.R;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CSV 파일 선택과 재생 결과 표시만 담당하는 테스트 화면입니다. */
public class PlcTestFragment extends Fragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private PlcCaptureLog capture;
    private CsvReplay replay;
    private List<PlcCsv.Entry> entries = Collections.emptyList();
    private TextView logs, fileLabel, equipment;
    private Button run, choose;
    private boolean playing;
    private int loadVersion, playVersion;

    /** USB와 동일한 수집기 및 화면과 분리된 CSV 재생기를 준비합니다. */
    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        capture = new PlcCaptureLog(new File(requireContext().getFilesDir(), "plc-logs/simulation"),
                5 * 1024 * 1024, "SIMULATION", this::showLog, this::showLog);
        capture.setDestination(new PlcLogFolder(requireContext()));
        replay = new CsvReplay(new CsvReplay.Scheduler() {
            /** 시스템 시각 변경의 영향을 받지 않는 재생 시각을 제공합니다. */
            public long now() { return SystemClock.elapsedRealtime(); }
            /** 다음 메시지의 재생을 예약합니다. */
            public void post(Runnable task, long delay) { handler.postDelayed(task, delay); }
            /** 예약한 재생을 취소합니다. */
            public void cancel(Runnable task) { handler.removeCallbacks(task); }
        }, new CsvReplay.Listener() {
            /** CSV 메시지를 실제 USB와 같은 분석 경로에 전달합니다. */
            public void receive(PlcCsv.Entry entry) {
                capture.receive(entry.message.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(), ScSettings.get(requireContext()));
            }
            /** 파일 기록까지 처리된 뒤 완료 상태를 표시합니다. */
            public void complete() {
                int version = playVersion;
                capture.session("CSV_COMPLETE");
                capture.flush(error -> handler.post(() -> {
                    if (version != playVersion || run == null) return;
                    playing = false;
                    run.setText("재생 시작");
                    choose.setEnabled(true);
                    showLog(error == null ? "재생 완료: " + entries.size() + "개 레코드" : "주입 완료, 기록 지연: " + error);
                }));
            }
        });
    }

    /** CSV 선택·재생·중지 버튼과 결과 화면을 연결합니다. */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_plc_test, container, false);
        logs = view.findViewById(R.id.plc_logs);
        run = view.findViewById(R.id.run_test);
        choose = view.findViewById(R.id.choose_csv);
        fileLabel = view.findViewById(R.id.csv_file);
        equipment = view.findViewById(R.id.sc_number);
        updateNumber();
        run.setEnabled(false);
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[] {"text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel"});
            startActivityForResult(intent, 45);
        });
        run.setOnClickListener(v -> { if (playing) stop(); else start(); });
        loadBuiltInCycle();
        return view;
    }

    /** 화면 진입 시 내장된 한 사이클 CSV를 기본 재생 데이터로 준비합니다. */
    private void loadBuiltInCycle() {
        int version = ++loadVersion;
        android.content.res.Resources resources = getResources();
        choose.setEnabled(false);
        fileLabel.setText("내장 한 사이클 CSV를 읽는 중입니다…");
        loader.execute(() -> {
            try (InputStream stream = resources.openRawResource(R.raw.plc_full_cycle_approx_1s)) {
                List<PlcCsv.Entry> loaded = PlcCsv.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
                handler.post(() -> {
                    if (version != loadVersion || fileLabel == null) return;
                    entries = loaded;
                    fileLabel.setText("내장 한 사이클 (가상 데이터) · " + loaded.size() + "개 레코드 · 약 50초");
                    run.setEnabled(true);
                    choose.setEnabled(true);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (version != loadVersion || fileLabel == null) return;
                    fileLabel.setText("내장 CSV 읽기 실패: " + e.getMessage());
                    choose.setEnabled(true);
                });
            }
        });
    }

    /** 저장된 SC 호기와 설정 메뉴의 위치를 표시합니다. */
    public void updateNumber() {
        String number = ScSettings.get(requireContext());
        equipment.setText("SC 호기: " + (number.isEmpty() ? "미설정 · 상단 메뉴에서 입력" : number));
        requireActivity().invalidateOptionsMenu();
    }

    /** 선택한 UTF-8 CSV를 작업 스레드에서 검증하고 화면에 준비 상태를 표시합니다. */
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 45 || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        android.content.ContentResolver resolver = requireContext().getContentResolver();
        int version = ++loadVersion;
        entries = Collections.emptyList();
        run.setEnabled(false);
        choose.setEnabled(false);
        fileLabel.setText("CSV를 읽는 중입니다…");
        loader.execute(() -> {
            try {
                String name = "선택한 CSV";
                try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                List<PlcCsv.Entry> loaded;
                try (InputStream stream = resolver.openInputStream(uri)) {
                    if (stream == null) throw new java.io.IOException("파일을 열 수 없습니다.");
                    loaded = PlcCsv.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
                String label = name + " · " + loaded.size() + "개 레코드";
                handler.post(() -> {
                    if (version != loadVersion || fileLabel == null) return;
                    entries = loaded;
                    fileLabel.setText(label);
                    run.setEnabled(true);
                    choose.setEnabled(true);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (version != loadVersion || fileLabel == null) return;
                    fileLabel.setText("CSV 읽기 실패: " + e.getMessage());
                    choose.setEnabled(true);
                });
            }
        });
    }

    /** 저장한 호기를 확인하고 CSV 원본의 시간 간격으로 재생합니다. */
    private void start() {
        if (!ScNumber.isValid(ScSettings.get(requireContext()))) {
            ScSettings.edit(requireContext(), this::updateNumber);
            return;
        }
        logs.setText("");
        playVersion++;
        playing = true;
        choose.setEnabled(false);
        run.setText("재생 중지");
        capture.session("CSV_START " + fileLabel.getText() + " firstTimestamp=" + entries.get(0).timestamp);
        replay.start(entries);
    }

    /** 진행 중인 재생을 취소하고 분석 세션을 종료합니다. */
    private void stop() {
        playVersion++;
        replay.stop();
        if (playing) capture.session("CSV_STOP");
        playing = false;
        if (run != null) run.setText("재생 시작");
        if (choose != null) choose.setEnabled(true);
    }

    /** 이전·현재 상태와 작업 패턴을 화면 및 Logcat에 표시합니다. */
    private void showLog(String message) {
        android.util.Log.i("PlcMock", message);
        handler.post(() -> {
            if (logs == null) return;
            logs.append(message + "\n");
            if (logs.length() > 30000) logs.setText(logs.getText().subSequence(logs.length() - 20000, logs.length()));
        });
    }

    /** 화면 종료 시 재생과 대기 중인 화면 갱신을 무효화합니다. */
    @Override
    public void onDestroyView() {
        stop();
        loadVersion++;
        entries = Collections.emptyList();
        logs = null; fileLabel = null; equipment = null; run = null; choose = null;
        super.onDestroyView();
    }

    /** 파일 읽기와 수집 작업을 종료합니다. */
    @Override
    public void onDestroy() {
        loader.shutdownNow();
        capture.close();
        super.onDestroy();
    }
}
