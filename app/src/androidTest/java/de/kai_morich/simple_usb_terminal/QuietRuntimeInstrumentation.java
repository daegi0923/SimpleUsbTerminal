package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Injects I/O failures into the real service while the host test keeps Settings in front. */
@android.annotation.TargetApi(26)
public final class QuietRuntimeInstrumentation extends Instrumentation {
    private SerialService service;
    private boolean wire;
    private final CountDownLatch bound = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((SerialService.SerialBinder) binder).getService();
            bound.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        wire = arguments != null && "wire".equals(arguments.getString("mode"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean binding = false;
        try {
            Context context = getTargetContext();
            context.startForegroundService(new Intent(context, SerialService.class));
            binding = context.bindService(new Intent(context, SerialService.class), connection, Context.BIND_AUTO_CREATE);
            check(binding && bound.await(10, TimeUnit.SECONDS), "service bind");
            String wireResult = "";
            if (wire) {
                java.lang.reflect.Field capture = SerialService.class.getDeclaredField("plcCapture");
                capture.setAccessible(true);
                wireResult = de.kai_morich.simple_usb_terminal.plc.PlcWireRuntimeChecks.run(context,
                        (de.kai_morich.simple_usb_terminal.plc.PlcCaptureLog) capture.get(service));
            }
            Method failure = SerialService.class.getDeclaredMethod("connectionFailed", Exception.class);
            failure.setAccessible(true);
            for (int index = 0; index < 4; index++) {
                final int number = index;
                runOnMainSync(() -> {
                    try { failure.invoke(service, new IOException("QUIET_TEST_IO_ERROR_" + number)); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
                Thread.sleep(2200);
            }
            File logs = new File(context.getFilesDir(), "plc-logs");
            for (int index = 0; index < 4; index++) {
                String marker = "QUIET_TEST_IO_ERROR_" + index;
                long deadline = System.currentTimeMillis() + 5000;
                while (!contains(logs, marker) && System.currentTimeMillis() < deadline) Thread.sleep(100);
                check(contains(logs, marker), "persistent diagnostic: " + marker);
            }
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = manager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
            check(channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_LOW, "quiet channel");
            check(channel.getSound() == null && !channel.shouldVibrate(), "no channel sound or vibration");
            Notification notification = null;
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() == Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE) notification = active.getNotification();
            }
            check(notification != null, "foreground notification retained");
            check(notification.fullScreenIntent == null, "no fullscreen intent");
            check(notification.priority <= Notification.PRIORITY_LOW, "low notification priority");
            check((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0, "quiet updates");
            check(notification.sound == null && (notification.defaults & Notification.DEFAULT_SOUND) == 0, "no sound");
            check(!notification.extras.getString(Notification.EXTRA_TEXT, "").contains("QUIET_TEST"), "errors stay out of notification text");
            result.putString("stream", wireResult + "PASS: 4 service I/O failures persisted, quiet ongoing notification, no fullscreen intent\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (binding) getTargetContext().unbindService(connection);
        }
    }

    private static boolean contains(File directory, String marker) throws IOException {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".log"));
        if (files == null) return false;
        for (File file : files) {
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                int offset = 0, read;
                while (offset < data.length && (read = input.read(data, offset, data.length - offset)) > 0) offset += read;
                if (new String(data, 0, offset, StandardCharsets.UTF_8).contains(marker)) return true;
            }
        }
        return false;
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
