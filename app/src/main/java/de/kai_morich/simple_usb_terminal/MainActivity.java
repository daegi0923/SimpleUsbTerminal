package de.kai_morich.simple_usb_terminal;

import de.kai_morich.simple_usb_terminal.plc.PlcTestFragment;
import de.kai_morich.simple_usb_terminal.plc.ScSettings;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

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
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
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
        startActivityForResult(intent, 44);
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
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

}
