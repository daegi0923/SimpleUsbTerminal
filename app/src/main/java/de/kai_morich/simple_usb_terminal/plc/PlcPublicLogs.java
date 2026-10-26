package de.kai_morich.simple_usb_terminal.plc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One bounded copy job serves both USB and simulation without delaying capture writes. */
public final class PlcPublicLogs {
    @SuppressLint("StaticFieldLeak") // Only the process application context is retained.
    private static PlcPublicLogs instance;
    private final Context app;
    private final MutableLiveData<String> status = new MutableLiveData<>("공용 로그 저장 준비 중…");
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private PlcLogMirror.Target target;
    private boolean scheduled, fullCopy;
    private final Set<String> pending = new HashSet<>();
    private volatile String lastStatus;

    public static synchronized PlcPublicLogs get(Context context) {
        if (instance == null) instance = new PlcPublicLogs(context.getApplicationContext());
        return instance;
    }

    private PlcPublicLogs(Context app) { this.app = app; }

    public LiveData<String> status() { return status; }

    public synchronized void requestSync() {
        fullCopy = true;
        startIfIdle();
    }

    synchronized void requestSync(String path) {
        pending.add(path);
        startIfIdle();
    }

    private void startIfIdle() {
        if (!scheduled) {
            publish("공용 로그 복사 중 · 완료 전에는 앱을 삭제하지 마세요.");
            schedule(250);
        }
    }

    private void schedule(long delay) {
        scheduled = true;
        worker.schedule(this::sync, delay, TimeUnit.MILLISECONDS);
    }

    private void sync() {
        Set<String> paths;
        synchronized (this) {
            paths = fullCopy ? null : new HashSet<>(pending);
            fullCopy = false;
            pending.clear();
        }
        boolean failed = false;
        String complete = null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                publish("Android 9 이하는 ‘로그 자동 저장 폴더 선택’으로 공용 저장을 설정해 주세요. 내부 로그는 앱 삭제 시 삭제됩니다.");
                return;
            }
            if (target == null) target = new PlcPublicLogStorage(app);
            int files = PlcLogMirror.sync(new File(app.getFilesDir(), "plc-logs"), target, paths);
            complete = (files == 0 ? "로그 수신 대기 · " : "공용 로그 자동 저장 완료 · ") + "Download/SC-Log-Miner/";
        } catch (Exception e) {
            failed = true;
            String message = "공용 로그 저장 실패, 내부 원본은 유지됩니다: " + e.getMessage();
            if (!message.equals(lastStatus)) Log.e("PlcPublicLogs", message, e);
            publish(message);
        } finally {
            synchronized (this) {
                scheduled = false;
                if (failed) fullCopy = true;
                if (fullCopy || !pending.isEmpty()) schedule(failed ? 5000 : 250);
                else if (complete != null) publish(complete);
            }
        }
    }

    private synchronized void publish(String message) {
        if (!message.equals(lastStatus)) {
            lastStatus = message;
            status.postValue(message);
        }
    }
}
