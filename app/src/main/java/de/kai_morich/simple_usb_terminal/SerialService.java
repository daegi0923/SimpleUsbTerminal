package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import de.kai_morich.simple_usb_terminal.plc.PlcCaptureLog;
import de.kai_morich.simple_usb_terminal.plc.PlcLogFolder;
import de.kai_morich.simple_usb_terminal.plc.ScSettings;

/** Owns the USB session independently of the visible screen. */
public class SerialService extends Service {
    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final IBinder binder = new SerialBinder();
    private final ExecutorService portWorker = Executors.newSingleThreadExecutor();
    private final UsbAutoConnectPolicy policy = new UsbAutoConnectPolicy();
    private final SerialUiBuffer display = new SerialUiBuffer();
    private final AtomicBoolean displayScheduled = new AtomicBoolean();
    private final Object serialLock = new Object();
    private volatile SerialListener listener;
    private volatile boolean connected;
    private volatile long generation;
    private SerialSocket socket;
    private UsbSerialPort serialPort;
    private UsbDevice activeDevice, openingDevice, permissionDevice;
    private UsbManager usbManager;
    private SharedPreferences settings;
    private PlcCaptureLog plcCapture;
    private PowerManager.WakeLock wakeLock;
    private long wakeRenewedAt;
    private boolean permissionRequested, started, destroyed, automatic;
    private int baudRate;
    private String status = "USB 자동 연결 준비 중";

    private final Runnable scanTask = this::scanUsb;
    private final Runnable displayTask = this::deliverDisplay;
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Constants.INTENT_ACTION_GRANT_USB.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (permissionDevice == null || device == null
                        || permissionDevice.getDeviceId() != device.getDeviceId()) return;
                boolean granted = usbManager.hasPermission(device);
                policy.onPermissionResult(device.getDeviceId(), granted);
                permissionDevice = null;
                permissionRequested = false;
                if (!granted) updateStatus("USB 접근이 거부됐습니다. 허용하려면 메뉴에서 USB 다시 연결을 선택하세요.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    int id = device.getDeviceId();
                    policy.onDetached(id);
                    if (activeDevice != null && activeDevice.getDeviceId() == id) {
                        connectionFailed(new IOException("USB 장치 분리"));
                    } else if (openingDevice != null && openingDevice.getDeviceId() == id) {
                        closeConnection("OPEN_DEVICE_DETACHED");
                        policy.onConnectionFailed(SystemClock.elapsedRealtime());
                    }
                    if (permissionDevice != null && permissionDevice.getDeviceId() == id) {
                        permissionDevice = null;
                        permissionRequested = false;
                    }
                }
            }
            scheduleScan();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        settings = getSharedPreferences("usb", MODE_PRIVATE);
        baudRate = settings.getInt("baudRate", 19200);
        automatic = settings.getBoolean("automatic", true);
        if (settings.contains("vendorId")) policy.setPreferred(settings.getInt("vendorId", 0),
                settings.getInt("productId", 0), settings.getInt("port", 0));
        if (!automatic) {
            policy.stop();
            status = "USB 자동 연결 중지. 메뉴에서 USB 다시 연결로 재개할 수 있습니다.";
        }
        plcCapture = new PlcCaptureLog(new File(getFilesDir(), "plc-logs"), 5 * 1024 * 1024, "USB",
                this::onPlcLog, message -> {
                    android.util.Log.e("PlcMock", message);
                    mainLooper.post(() -> updateStatus(message));
                });
        plcCapture.setDestination(new PlcLogFolder(this));
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":UsbCapture");
        wakeLock.setReferenceCounted(false);
        IntentFilter filter = new IntentFilter(Constants.INTENT_ACTION_GRANT_USB);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        initNotification();
        plcCapture.diagnostic(status);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        if (intent != null && Constants.INTENT_ACTION_DISCONNECT.equals(intent.getAction())) stopAutomatic();
        showNotification();
        scheduleScan();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        destroyed = true;
        listener = null;
        mainLooper.removeCallbacks(scanTask);
        mainLooper.removeCallbacks(displayTask);
        unregisterReceiver(usbReceiver);
        closeConnection("SERVICE_STOP");
        portWorker.shutdown();
        plcCapture.close();
        stopForeground(true);
        super.onDestroy();
    }

    void ensureMonitoring() {
        if (!started) ContextCompat.startForegroundService(this, new Intent(this, SerialService.class));
        else scheduleScan();
    }

    void retryAutomatic() {
        closeConnection("RETRY");
        permissionDevice = null;
        permissionRequested = false;
        policy.stop();
        policy.resume();
        automatic = true;
        settings.edit().putBoolean("automatic", true).apply();
        updateStatus("USB 자동 연결을 다시 시도합니다.");
        ensureMonitoring();
    }

    void stopAutomatic() {
        automatic = false;
        settings.edit().putBoolean("automatic", false).apply();
        policy.stop();
        permissionDevice = null;
        permissionRequested = false;
        closeConnection("MANUAL_STOP");
        updateStatus("USB 자동 연결 중지. 메뉴에서 USB 다시 연결로 재개할 수 있습니다.");
    }

    void selectDevice(int deviceId, int port, int baud) {
        UsbDevice device = null;
        for (UsbDevice candidate : usbManager.getDeviceList().values()) {
            if (candidate.getDeviceId() == deviceId) device = candidate;
        }
        if (device == null) {
            updateStatus("선택한 USB 장치가 분리됐습니다.");
            return;
        }
        closeConnection("DEVICE_CHANGED");
        policy.stop();
        policy.setPreferred(device.getVendorId(), device.getProductId(), port);
        policy.setPreferredAttachedDevice(device.getDeviceId());
        policy.resume();
        baudRate = baud;
        settings.edit().putInt("vendorId", device.getVendorId()).putInt("productId", device.getProductId())
                .putInt("port", port).putInt("baudRate", baudRate).putBoolean("automatic", true).apply();
        permissionDevice = null;
        permissionRequested = false;
        automatic = true;
        updateStatus("선택한 USB 장치에 자동 연결합니다.");
        // A manual choice identifies this attached device even when two adapters share VID/PID.
        beginAction(policy.scan(Collections.singletonList(new UsbAutoConnectPolicy.Candidate(
                device.getDeviceId(), device.getVendorId(), device.getProductId(), port,
                usbManager.hasPermission(device))), SystemClock.elapsedRealtime()), usbManager.getDeviceList());
        ensureMonitoring();
    }

    boolean isConnected() { return connected; }
    UsbSerialPort getSerialPort() { return serialPort; }
    String getStatus() { return status; }
    int getBaudRate() { return baudRate; }

    void attach(SerialListener next) {
        listener = next;
        next.onSerialStatus(status);
        if (connected) next.onSerialConnect();
        scheduleDisplay();
        scheduleScan();
    }

    void detach() { listener = null; }

    public void write(byte[] data) throws IOException {
        SerialSocket current = socket;
        if (!connected || current == null) throw new IOException("not connected");
        current.write(data);
    }

    private void scheduleScan() {
        if (destroyed || !started) return;
        mainLooper.removeCallbacks(scanTask);
        mainLooper.post(scanTask);
    }

    private void scanUsb() {
        if (destroyed || !started) return;
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (activeDevice != null && !containsDevice(devices, activeDevice)) connectionFailed(new IOException("USB 장치 분리"));
        if (openingDevice != null && !containsDevice(devices, openingDevice)) {
            closeConnection("OPEN_DEVICE_DETACHED");
            policy.onConnectionFailed(SystemClock.elapsedRealtime());
        }
        if (permissionDevice != null && !containsDevice(devices, permissionDevice)) {
            permissionDevice = null;
            permissionRequested = false;
        }
        List<UsbAutoConnectPolicy.Candidate> candidates = new ArrayList<>();
        for (UsbDevice device : devices.values()) {
            UsbSerialDriver driver = probe(device);
            if (driver == null) continue;
            for (int port = 0; port < driver.getPorts().size(); port++) {
                candidates.add(new UsbAutoConnectPolicy.Candidate(device.getDeviceId(), device.getVendorId(),
                        device.getProductId(), port, usbManager.hasPermission(device)));
            }
        }
        UsbAutoConnectPolicy.Action action = policy.scan(candidates, SystemClock.elapsedRealtime());
        if (automatic && !connected && openingDevice == null && permissionDevice == null) {
            if (action.kind != UsbAutoConnectPolicy.Kind.NONE) beginAction(action, devices);
            else if (candidates.isEmpty()) {
                updateStatus("USB 시리얼 장치를 기다리는 중 · 연결하면 자동으로 수신합니다.");
            } else {
                int matching = 0;
                for (UsbAutoConnectPolicy.Candidate candidate : candidates) {
                    if (!settings.contains("vendorId") || (candidate.vendorId == settings.getInt("vendorId", 0)
                            && candidate.productId == settings.getInt("productId", 0)
                            && candidate.port == settings.getInt("port", 0))) matching++;
                }
                if (matching == 0) updateStatus("저장한 USB 장치를 기다리는 중 · 변경하려면 메뉴에서 장치를 선택하세요.");
                else if (matching > 1) updateStatus("여러 USB 장치/포트가 있습니다. 메뉴에서 수신할 장치를 선택하세요.");
            }
        }
        if (automatic && permissionDevice != null && !permissionRequested) {
            // Only Android's required USB consent may interrupt; never open our activity.
            requestPermission();
        }
        if (connected && SystemClock.elapsedRealtime() - wakeRenewedAt >= 5 * 60 * 1000) holdWakeLock();
        mainLooper.removeCallbacks(scanTask);
        mainLooper.postDelayed(scanTask, UsbAutoConnectPolicy.RETRY_DELAY_MILLIS);
    }

    private void beginAction(UsbAutoConnectPolicy.Action action, Map<String, UsbDevice> devices) {
        if (action.kind == UsbAutoConnectPolicy.Kind.REQUEST_PERMISSION) {
            permissionDevice = findDevice(devices, action.candidate.deviceId);
            permissionRequested = false;
        } else if (action.kind == UsbAutoConnectPolicy.Kind.CONNECT) {
            UsbDevice device = findDevice(devices, action.candidate.deviceId);
            if (device != null) openPort(device, action.candidate.port);
        }
    }

    private static boolean containsDevice(Map<String, UsbDevice> devices, UsbDevice target) {
        return findDevice(devices, target.getDeviceId()) != null;
    }

    private static UsbDevice findDevice(Map<String, UsbDevice> devices, int id) {
        for (UsbDevice device : devices.values()) if (device.getDeviceId() == id) return device;
        return null;
    }

    private UsbSerialDriver probe(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        return driver != null ? driver : CustomProber.getCustomProber().probeDevice(device);
    }

    private void requestPermission() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_MUTABLE;
        Intent result = new Intent(Constants.INTENT_ACTION_GRANT_USB).setPackage(getPackageName());
        PendingIntent permission = PendingIntent.getBroadcast(this, permissionDevice.getDeviceId(), result, flags);
        permissionRequested = true;
        updateStatus("USB 접근 허용을 기다리는 중");
        try {
            usbManager.requestPermission(permissionDevice, permission);
        } catch (SecurityException | IllegalArgumentException e) {
            policy.onPermissionResult(permissionDevice.getDeviceId(), false);
            permissionDevice = null;
            permissionRequested = false;
            updateStatus("USB 권한 요청 실패. 장치를 확인한 뒤 USB 다시 연결을 선택하세요: " + e.getMessage());
        }
    }

    private void openPort(UsbDevice device, int portIndex) {
        openingDevice = device;
        long token = ++generation;
        int baud = baudRate;
        updateStatus("USB 연결 중 · " + baud + " baud");
        portWorker.execute(() -> {
            if (destroyed || token != generation) return;
            UsbDeviceConnection connection = null;
            UsbSerialPort port = null;
            try {
                UsbSerialDriver driver = probe(device);
                if (driver == null || portIndex < 0 || portIndex >= driver.getPorts().size())
                    throw new IOException("사용 가능한 USB 포트가 없습니다.");
                connection = usbManager.openDevice(device);
                if (connection == null) throw new IOException("USB 장치를 열 수 없습니다.");
                port = driver.getPorts().get(portIndex);
                port.open(connection);
                port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                SerialSocket opened = new SerialSocket(getApplicationContext(), connection, port);
                opened.prepare();
                if (destroyed || token != generation) {
                    opened.disconnect();
                    return;
                }
                UsbSerialPort openedPort = port;
                mainLooper.post(() -> finishOpen(token, device, portIndex, baud, openedPort, opened));
            } catch (Exception error) {
                if (port != null) try { port.close(); } catch (Exception ignored) { }
                if (connection != null) connection.close();
                mainLooper.post(() -> {
                    if (token != generation || destroyed) return;
                    openingDevice = null;
                    policy.onConnectionFailed(SystemClock.elapsedRealtime());
                    updateStatus("USB 연결 실패 · 2초 후 재시도: " + error.getMessage());
                    SerialListener current = listener;
                    if (current != null) current.onSerialConnectError(error);
                    scheduleScan();
                });
            }
        });
    }

    private void finishOpen(long token, UsbDevice device, int port, int baud, UsbSerialPort openedPort, SerialSocket opened) {
        if (destroyed || token != generation || !automatic) {
            closeSocketAsync(opened);
            return;
        }
        openingDevice = null;
        activeDevice = device;
        serialPort = openedPort;
        socket = opened;
        synchronized (serialLock) {
            plcCapture.session("CONNECT " + opened.getName() + " baud=" + baud + " dataBits=8 stopBits=1 parity=NONE");
            connected = true;
        }
        try {
            opened.connect(new SerialListener() {
                public void onSerialConnect() { }
                public void onSerialConnectError(Exception error) { fail(error); }
                public void onSerialIoError(Exception error) { fail(error); }
                public void onSerialRead(ArrayDeque<byte[]> data) { throw new UnsupportedOperationException(); }
                public void onSerialRead(byte[] data) {
                    synchronized (serialLock) {
                        if (!connected || token != generation) return;
                        plcCapture.receive(data, System.currentTimeMillis(), ScSettings.get(SerialService.this));
                    }
                    display.addData(data);
                    scheduleDisplay();
                }
                private void fail(Exception error) {
                    mainLooper.post(() -> {
                        if (!destroyed && token == generation) connectionFailed(error);
                    });
                }
            });
            policy.onConnected();
            policy.setPreferred(device.getVendorId(), device.getProductId(), port);
            policy.setPreferredAttachedDevice(device.getDeviceId());
            settings.edit().putInt("vendorId", device.getVendorId()).putInt("productId", device.getProductId())
                    .putInt("port", port).apply();
            holdWakeLock();
            updateStatus("USB 연결됨 · " + opened.getName() + " · " + baud + " baud · 백그라운드 수신 중");
            SerialListener current = listener;
            if (current != null) current.onSerialConnect();
        } catch (Exception error) {
            connectionFailed(error);
        }
    }

    private void connectionFailed(Exception error) {
        closeConnection("IO_ERROR " + error.getMessage());
        policy.onConnectionFailed(SystemClock.elapsedRealtime());
        updateStatus("USB 연결 끊김 · 장치를 확인하고 자동 재연결합니다: " + error.getMessage());
        SerialListener current = listener;
        if (current != null) current.onSerialIoError(error);
        scheduleScan();
    }

    private void closeConnection(String reason) {
        synchronized (serialLock) {
            generation++;
            if (connected) plcCapture.session(reason);
            connected = false;
        }
        SerialSocket previous = socket;
        socket = null;
        serialPort = null;
        activeDevice = null;
        openingDevice = null;
        if (previous != null) closeSocketAsync(previous);
        if (wakeLock.isHeld()) wakeLock.release();
    }

    private void closeSocketAsync(SerialSocket previous) {
        try {
            portWorker.execute(previous::disconnect);
        } catch (RejectedExecutionException e) {
            // A completed open callback can race the service's executor shutdown.
            new Thread(previous::disconnect, "UsbFinalClose").start();
        }
    }

    private void holdWakeLock() {
        wakeLock.acquire(10 * 60 * 1000L);
        wakeRenewedAt = SystemClock.elapsedRealtime();
    }

    private void updateStatus(String message) {
        if (destroyed || message.equals(status)) return;
        status = message;
        android.util.Log.i("UsbStatus", message);
        plcCapture.diagnostic(message);
        display.addStatus(message);
        scheduleDisplay();
        if (started) showNotification();
    }

    private void onPlcLog(String message) {
        if (destroyed) return;
        android.util.Log.i("PlcMock", message);
        display.addPlc(message);
        scheduleDisplay();
    }

    private void scheduleDisplay() {
        if (listener != null && displayScheduled.compareAndSet(false, true))
            mainLooper.postDelayed(displayTask, 100);
    }

    private void deliverDisplay() {
        displayScheduled.set(false);
        SerialListener current = listener;
        if (current == null) return;
        ArrayDeque<byte[]> raw = new ArrayDeque<>();
        for (SerialUiBuffer.Event event : display.drain()) {
            if (current != listener) return;
            if (event.kind == SerialUiBuffer.Kind.DATA) raw.add(event.data);
            else {
                if (!raw.isEmpty()) { current.onSerialRead(raw); raw = new ArrayDeque<>(); }
                if (event.kind == SerialUiBuffer.Kind.PLC) current.onPlcLog(event.message);
                else current.onSerialStatus(event.message);
            }
        }
        if (!raw.isEmpty()) current.onSerialRead(raw);
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(Constants.NOTIFICATION_CHANNEL,
                    "PLC USB 수신 (조용한 상주 알림)", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public boolean areNotificationsEnabled() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = manager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void showNotification() {
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SerialService.class).setAction(Constants.INTENT_ACTION_DISCONNECT), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name))
                .setContentText("USB 백그라운드 수신 서비스 · 연결 상태와 오류는 로그에 기록합니다.")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true).setOnlyAlertOnce(true)
                .setContentIntent(open).setOngoing(true)
                .addAction(R.drawable.ic_clear_white_24dp, "자동 연결 중지", stop).build();
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // No-device waiting has no USB grant yet, so connectedDevice cannot be used there.
            type = connected && activeDevice != null && usbManager.hasPermission(activeDevice)
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        ServiceCompat.startForeground(this, Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification, type);
    }
}
