package de.kai_morich.simple_usb_terminal;

import de.kai_morich.simple_usb_terminal.plc.PlcTestFragment;
import de.kai_morich.simple_usb_terminal.plc.PlcLogExportDialog;
import de.kai_morich.simple_usb_terminal.plc.ScSettings;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private SerialService serialService;
    private boolean serviceBound;
    private int[] pendingDeviceSelection;
    private boolean retryPending, stopPending;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serialService = ((SerialService.SerialBinder) binder).getService();
            serialService.ensureMonitoring();
            if (pendingDeviceSelection != null) {
                serialService.selectDevice(pendingDeviceSelection[0], pendingDeviceSelection[1], pendingDeviceSelection[2]);
                pendingDeviceSelection = null;
            }
            if (retryPending) serialService.retryAutomatic();
            if (stopPending) serialService.stopAutomatic();
            retryPending = stopPending = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serialService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new TerminalFragment(), "terminal").commit();
        else {
            onBackStackChanged();
            if (getIntent().getAction() == null) showTerminal();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, SerialService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        serviceBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        serialService = null;
        super.onStop();
    }

    int getUsbBaudRate() {
        return serialService != null ? serialService.getBaudRate()
                : getSharedPreferences("usb", MODE_PRIVATE).getInt("baudRate", 19200);
    }

    void selectUsbDevice(int deviceId, int port, int baudRate) {
        retryPending = stopPending = false;
        if (serialService != null) serialService.selectDevice(deviceId, port, baudRate);
        else pendingDeviceSelection = new int[]{deviceId, port, baudRate};
        showTerminal();
    }

    private void showTerminal() {
        getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (!(getSupportFragmentManager().findFragmentById(R.id.fragment) instanceof TerminalFragment))
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new TerminalFragment(), "terminal").commit();
    }

    /** 장비 연결 없이도 SC 설정과 가상 테스트 메뉴를 제공합니다. */
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_plc, menu);
        return true;
    }

    /** 한 번 선택한 폴더에 이후 로그를 자동 저장하도록 접근을 요청합니다. */
    void chooseLogFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, 44);
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException e) {
            android.util.Log.w("PlcLogExport", "자동 저장 폴더 선택창 열기 실패", e);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("폴더 선택창을 열 수 없습니다")
                    .setMessage("파일 앱 또는 기기 관리 정책을 확인해 주세요.\n내부 로그 기록은 계속됩니다.\nAndroid 10 이상에서는 ‘쌓인 로그 내보내기’의 ‘다운로드에 저장’을 이용할 수 있습니다.")
                    .setPositiveButton("확인", null).show();
        }
    }

    /** 폴더 접근 권한을 유지하여 앱 재실행 후에도 자동 기록합니다. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 44 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if ((data.getFlags() & flags) != flags) throw new SecurityException("읽기/쓰기 권한 없음");
            getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getSharedPreferences("plc", MODE_PRIVATE).edit().putString("logFolder", data.getData().toString()).apply();
            invalidateOptionsMenu();
            android.widget.Toast.makeText(this, "선택한 폴더에 PLC 수신 및 상태 로그가 자동으로 저장됩니다.", android.widget.Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            android.widget.Toast.makeText(this, "폴더 접근 권한을 저장하지 못했습니다. 폴더를 다시 선택해 주세요.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** 현재 저장된 SC 호기를 메뉴에 표시합니다. */
    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        android.view.MenuItem item = menu.findItem(R.id.sc_settings);
        String number = ScSettings.get(this);
        if (item != null) item.setTitle("SC 호기: " + (number.isEmpty() ? "미설정" : number) + " · 수정");
        return super.onPrepareOptionsMenu(menu);
    }

    /** 공통 SC 설정과 가상 데이터 테스트 화면을 실행합니다. */
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.usb_terminal) {
            showTerminal();
            return true;
        }
        if (item.getItemId() == R.id.usb_devices) {
            if (!(getSupportFragmentManager().findFragmentById(R.id.fragment) instanceof DevicesFragment))
                getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new DevicesFragment(), "devices")
                        .addToBackStack("usb_devices").commit();
            return true;
        }
        if (item.getItemId() == R.id.usb_retry) {
            stopPending = false;
            if (serialService != null) serialService.retryAutomatic();
            else retryPending = true;
            showTerminal();
            return true;
        }
        if (item.getItemId() == R.id.usb_stop) {
            retryPending = false;
            pendingDeviceSelection = null;
            if (serialService != null) serialService.stopAutomatic();
            else stopPending = true;
            return true;
        }
        if (item.getItemId() == R.id.log_export) {
            if (getSupportFragmentManager().findFragmentByTag(PlcLogExportDialog.TAG) == null) {
                new PlcLogExportDialog().show(getSupportFragmentManager(), PlcLogExportDialog.TAG);
            }
            return true;
        }
        if (item.getItemId() == R.id.log_folder) {
            chooseLogFolder();
            return true;
        }
        if (item.getItemId() == R.id.sc_settings) {
            ScSettings.edit(this, () -> {
                invalidateOptionsMenu();
                androidx.fragment.app.Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment);
                if (current instanceof PlcTestFragment) ((PlcTestFragment) current).updateNumber();
            });
            return true;
        }
        if (item.getItemId() == R.id.plc_test) {
            if (!(getSupportFragmentManager().findFragmentById(R.id.fragment) instanceof PlcTestFragment))
                getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new PlcTestFragment(), "plc_test")
                        .addToBackStack(null).commit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getAction() == null) {
            showTerminal();
            if (serialService != null) serialService.ensureMonitoring();
        }
    }

}
