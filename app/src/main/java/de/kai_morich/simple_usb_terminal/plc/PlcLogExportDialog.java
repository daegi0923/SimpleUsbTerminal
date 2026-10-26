package de.kai_morich.simple_usb_terminal.plc;

import android.app.Application;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lets the user select saved logs before choosing the export folder. */
public final class PlcLogExportDialog extends DialogFragment {
    public static final String TAG = "plc_log_export";
    private ExportModel model;
    private TextView message;
    private ListView files;
    private LinearLayout selectionButtons;
    private PlcLogExport.Snapshot listed;
    private final ActivityResultLauncher<Uri> chooseFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), folder -> model.pickerReturned(folder));

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        model = new ViewModelProvider(this).get(ExportModel.class);
        model.state.observe(this, this::render);
        model.load();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, dp(8), padding, dp(8));
        message = new TextView(requireContext());
        message.setTextSize(16);
        content.addView(message);
        selectionButtons = new LinearLayout(requireContext());
        Button all = new Button(requireContext());
        all.setText("전체 선택");
        all.setOnClickListener(v -> model.selectAll(true));
        Button none = new Button(requireContext());
        none.setText("선택 해제");
        none.setOnClickListener(v -> model.selectAll(false));
        selectionButtons.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        selectionButtons.addView(none, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(selectionButtons);
        files = new ListView(requireContext());
        files.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        files.setOnItemClickListener((parent, view, position, id) ->
                model.select(position, files.isItemChecked(position)));
        content.addView(files, new LinearLayout.LayoutParams(-1, dp(280)));
        return new AlertDialog.Builder(requireContext()).setTitle("쌓인 로그 내보내기")
                .setView(content).setNegativeButton("취소", null)
                .setPositiveButton("선택 파일 내보내기", null).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            State state = model.state.getValue();
            if (state != null && state.phase == Phase.SELECTING) {
                if (model.downloadsFallback() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    model.export(null);
                } else if (model.beginPicker()) {
                    try {
                        chooseFolder.launch(null);
                    } catch (ActivityNotFoundException | SecurityException | IllegalStateException e) {
                        model.pickerUnavailable(e);
                    }
                }
            }
            else if (state != null && state.phase == Phase.FINISHED) dismiss();
        });
        render(model.state.getValue());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void render(State state) {
        if (state == null || message == null || !(getDialog() instanceof AlertDialog)) return;
        message.setText(state.message);
        boolean selecting = state.phase == Phase.SELECTING;
        files.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionButtons.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            if (listed != model.snapshot) {
                listed = model.snapshot;
                String[] names = new String[listed.size()];
                for (int i = 0; i < names.length; i++) names[i] = listed.path(i) + "\n"
                        + Formatter.formatShortFileSize(requireContext(), listed.length(i));
                files.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_multiple_choice, names));
            }
            for (int i = 0; i < listed.size(); i++) files.setItemChecked(i, model.selected.contains(listed.path(i)));
        }
        AlertDialog dialog = (AlertDialog) getDialog();
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setText(selecting ? (model.downloadsFallback() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "다운로드에 저장" : "선택 파일 내보내기") : "확인");
            positive.setEnabled(state.phase == Phase.FINISHED
                    || (selecting && !model.selected.isEmpty() && !model.pickerPending()));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(state.phase == Phase.LOADING || selecting ? View.VISIBLE : View.GONE);
        }
        setCancelable(state.phase != Phase.COPYING && !model.pickerPending());
    }

    @Override
    public void onDestroyView() {
        message = null;
        files = null;
        selectionButtons = null;
        listed = null;
        super.onDestroyView();
    }

    enum Phase { LOADING, SELECTING, COPYING, FINISHED }
    static final class State {
        final Phase phase;
        final String message;
        State(Phase phase, String message) { this.phase = phase; this.message = message; }
    }

    /** Selection and the copy job survive rotation without retaining an activity. */
    public static final class ExportModel extends AndroidViewModel {
        final MutableLiveData<State> state = new MutableLiveData<>();
        final Set<String> selected = new HashSet<>();
        PlcLogExport.Snapshot snapshot;
        private final SavedStateHandle saved;
        private boolean cleared;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();

        public ExportModel(@NonNull Application application, SavedStateHandle saved) {
            super(application);
            this.saved = saved;
        }

        void load() {
            if (state.getValue() != null) return;
            if (Boolean.TRUE.equals(saved.get("copying"))) {
                saved.set("copying", false);
                state.setValue(new State(Phase.FINISHED, "앱이 다시 시작되어 내보내기가 중단됐습니다.\n원본은 유지됩니다. 파일 목록을 다시 열어 내보내 주세요."));
                return;
            }
            state.setValue(new State(Phase.LOADING, "저장된 CSV/LOG 파일을 확인하는 중입니다…"));
            worker.execute(() -> {
                try {
                    PlcLogExport.Snapshot loaded = PlcLogExport.snapshot(logDirectory());
                    main.post(() -> {
                        if (cleared) return;
                        snapshot = loaded;
                        if (snapshot.size() == 0) {
                            state.setValue(new State(Phase.FINISHED, "내보낼 CSV/LOG 파일이 없습니다.\nUSB 수신 또는 CSV 재생 후 다시 시도해 주세요."));
                        } else {
                            ArrayList<String> restored = saved.get("selection");
                            for (int i = 0; i < snapshot.size(); i++) {
                                String path = snapshot.path(i);
                                if (restored == null || restored.contains(path)) selected.add(path);
                            }
                            showSelection();
                            Uri pending = saved.get("folder");
                            if (pending != null) {
                                saved.remove("folder");
                                export(pending);
                            }
                        }
                    });
                } catch (Exception e) { fail(e); }
            });
        }

        void select(int index, boolean checked) {
            String path = snapshot.path(index);
            if (checked) selected.add(path); else selected.remove(path);
            showSelection();
        }

        void selectAll(boolean checked) {
            selected.clear();
            if (checked) for (int i = 0; i < snapshot.size(); i++) selected.add(snapshot.path(i));
            showSelection();
        }

        private void showSelection() {
            saved.set("selection", new ArrayList<>(selected));
            state.setValue(new State(Phase.SELECTING, "총 " + snapshot.size() + "개 중 " + selected.size()
                    + "개 선택\n" + (downloadsFallback()
                    ? "폴더 선택창을 열 수 없습니다. 파일 앱 또는 기기 관리 정책을 확인해 주세요.\n"
                        + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? "‘다운로드에 저장’을 누르면 공용 Download 폴더에 복사합니다. 원본은 유지됩니다."
                        : "관리자에게 시스템 파일 선택창 사용을 요청해 주세요. 원본은 유지됩니다.")
                    : "파일명과 용량을 확인한 뒤 내보낼 파일을 선택하세요. 원본은 유지됩니다.")));
        }

        boolean downloadsFallback() { return Boolean.TRUE.equals(saved.get("downloadsFallback")); }

        boolean pickerPending() { return Boolean.TRUE.equals(saved.get("pickerPending")); }

        boolean beginPicker() {
            if (pickerPending() || selected.isEmpty()) return false;
            saved.set("pickerPending", true);
            showSelection();
            return true;
        }

        void pickerUnavailable(RuntimeException error) {
            Log.w("PlcLogExport", "시스템 폴더 선택창 열기 실패", error);
            saved.set("pickerPending", false);
            saved.set("downloadsFallback", true);
            showSelection();
        }

        void pickerReturned(@Nullable Uri folder) {
            if (cleared) return;
            saved.set("pickerPending", false);
            if (snapshot == null) {
                // The result may arrive before the restored file list finishes loading.
                if (folder != null) saved.set("folder", folder);
                return;
            }
            if (folder != null) export(folder); else showSelection();
        }

        private File logDirectory() { return new File(getApplication().getFilesDir(), "plc-logs"); }

        void export(@Nullable Uri folder) {
            State current = state.getValue();
            if (cleared || selected.isEmpty() || current == null || current.phase != Phase.SELECTING) return;
            Set<String> paths = new HashSet<>(selected);
            saved.set("copying", true);
            state.setValue(new State(Phase.COPYING, "선택한 로그 파일을 준비하는 중입니다…"));
            worker.execute(() -> {
                PlcLogDownloadsFolder downloads = null;
                try {
                    Application app = getApplication();
                    // Refresh selected file lengths after folder selection, before copying.
                    PlcLogExport.Snapshot fresh = PlcLogExport.snapshot(logDirectory()).select(paths);
                    if (fresh.size() != paths.size()) throw new IOException("선택한 로그가 변경됐습니다. 파일 목록을 다시 열어 주세요.");
                    PlcLogExport.Destination destination;
                    String location;
                    if (folder == null) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                            throw new IOException("다운로드 저장은 Android 10 이상에서 지원합니다.");
                        downloads = new PlcLogDownloadsFolder(app.getContentResolver());
                        destination = downloads;
                        location = "Download/" + downloads.name;
                    } else {
                        PlcLogExportFolder chosen = new PlcLogExportFolder(app.getContentResolver(), folder);
                        destination = chosen;
                        location = chosen.name;
                    }
                    PlcLogExport.Result result = PlcLogExport.copy(fresh, destination, (count, total) ->
                            state.postValue(new State(Phase.COPYING, "로그 복사 중: " + count + " / " + total
                                    + "개\n원본은 유지되며 수신은 계속됩니다.")));
                    if (downloads != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) downloads.commit();
                    finish("내보내기 완료: " + result.files + "개 파일 · "
                            + Formatter.formatShortFileSize(app, result.bytes) + "\n폴더: " + location
                            + "\n\n원본 로그는 유지했습니다. 내보내기 시작 후 추가된 기록은 다음 내보내기에 포함됩니다.");
                } catch (Exception e) {
                    if (downloads != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) downloads.abort();
                    fail(e);
                }
            });
        }

        private void fail(Exception e) {
            Log.w("PlcLogExport", "로그 내보내기 실패", e);
            finish("로그 내보내기 실패: " + e.getMessage()
                    + "\n\n폴더 접근 권한과 저장 공간을 확인해 주세요. 일부 복사 파일이 남아 있을 수 있으며 원본은 유지됩니다.");
        }

        private void finish(String message) {
            main.post(() -> {
                if (cleared) return;
                saved.set("copying", false);
                state.setValue(new State(Phase.FINISHED, message));
            });
        }

        @Override
        protected void onCleared() {
            cleared = true;
            worker.shutdown();
        }
    }
}
