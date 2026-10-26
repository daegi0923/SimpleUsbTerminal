package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.XonXoffFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, True }

    private final Handler mainLooper;
    private Context boundContext;
    private String lastStatus;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ImageButton sendBtn;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean hexEnabled = false;
    private enum SendButtonState {Idle, Busy, Disabled};

    private ControlLines controlLines = new ControlLines();
    private XonXoffFilter flowControlFilter;

    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    public TerminalFragment() {
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        Context context = requireContext();
        if (context.bindService(new Intent(context, SerialService.class), this, Context.BIND_AUTO_CREATE))
            boundContext = context;
    }

    @Override
    public void onStop() {
        if (service != null) service.detach();
        if (boundContext != null) {
            boundContext.unbindService(this);
            boundContext = null;
        }
        service = null;
        usbSerialPort = null;
        connected = Connected.False;
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateConnectionState();
    }

    @Override
    public void onPause() {
        controlLines.stop();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        controlLines.stop();
        controlLines.onDestroyView();
        receiveText = null;
        sendText = null;
        sendBtn = null;
        hexWatcher = null;
        super.onDestroyView();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (boundContext == null || receiveText == null) return;
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        service.ensureMonitoring();
        onSerialStatus(service.getStatus());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        updateConnectionState();
    }

    private void updateConnectionState() {
        usbSerialPort = service != null ? service.getSerialPort() : null;
        connected = service != null && service.isConnected() ? Connected.True : Connected.False;
        if (connected == Connected.True && isResumed()) controlLines.start();
        else controlLines.stop();
        if (getActivity() != null) getActivity().invalidateOptionsMenu();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        controlLines.onCreateView(view);
        lastStatus = null;
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        TextView storage = view.findViewById(R.id.public_log_status);
        de.kai_morich.simple_usb_terminal.plc.PlcPublicLogs publicLogs =
                de.kai_morich.simple_usb_terminal.plc.PlcPublicLogs.get(requireContext());
        publicLogs.status().observe(getViewLifecycleOwner(), storage::setText);
        publicLogs.requestSync();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        controlLines.onPrepareOptionsMenu(menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).setChecked(service != null && service.areNotificationsEnabled());
        } else {
            menu.findItem(R.id.backgroundNotification).setChecked(true);
            menu.findItem(R.id.backgroundNotification).setEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, which) -> {
                newline = newlineValues[which];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.controlLines) {
            item.setChecked(controlLines.showControlLines(!item.isChecked()));
            return true;
        } else if (id == R.id.flowControl) {
            controlLines.selectFlowControl();
            return true;
        } else if (id == R.id.backgroundNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (service != null && !service.areNotificationsEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
                } else {
                    showNotificationSettings();
                }
            }
            return true;
        } else if (id == R.id.sendBreak) {
            try {
                usbSerialPort.setBreak(true);
                Thread.sleep(100);
                status("send BREAK");
                usbSerialPort.setBreak(false);
            } catch (Exception e) {
                status("send BREAK failed: " + e.getMessage());
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*
     * Serial + UI
     */
    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String msg;
        byte[] data;
        if(hexEnabled) {
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, TextUtil.fromHexString(str));
            TextUtil.toHexString(sb, newline.getBytes());
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
        } else {
            msg = str;
            data = (str + newline).getBytes();
        }
        try {
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            appendReceived(spn);
            service.write(data);
        } catch (SerialTimeoutException e) { // e.g. writing large data at low baud rate or suspended by flow control
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void sendAgain(byte[] data0, int offset) {
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Busy : SendButtonState.Disabled);
        if (connected != Connected.True) {
            return;
        }
        byte[] data;
        if (offset == 0) {
            data = data0;
        } else {
            data = new byte[data0.length - offset];
            System.arraycopy(data0, offset, data, 0, data.length);
        }
        try {
            service.write(data);
        } catch (SerialTimeoutException e) {
            mainLooper.post(() -> sendAgain(data, e.bytesTransferred));
            return;
        } catch (Exception e) {
            onSerialIoError(e);
        }
        updateSendBtn(controlLines.sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
    }

    private void receive(ArrayDeque<byte[]> datas) {
        if (receiveText == null) return;
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (flowControlFilter != null)
                data = flowControlFilter.filter(data);
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = de.kai_morich.simple_usb_terminal.plc.PlcBytes.display(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }
        appendReceived(spn);
    }

    private void appendReceived(CharSequence text) {
        if (receiveText == null) return;
        receiveText.append(text);
        if (receiveText.length() > 30000) {
            CharSequence tail = receiveText.getText().subSequence(receiveText.length() - 20000, receiveText.length());
            receiveText.setText(new SpannableStringBuilder(tail));
        }
    }

    void status(String str) {
        if (receiveText == null) return;
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendReceived(spn);
    }

    void updateSendBtn(SendButtonState state) {
        if (sendBtn == null) return;
        sendBtn.setEnabled(state == SendButtonState.Idle);
        sendBtn.setImageAlpha(state == SendButtonState.Idle ? 255 : 64);
        sendBtn.setImageResource(state == SendButtonState.Disabled ? R.drawable.ic_block_white_24dp : R.drawable.ic_send_white_24dp);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", getActivity().getPackageName());
        startActivity(intent);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialStatus(String message) {
        updateConnectionState();
        if (message != null && !message.equals(lastStatus)) {
            status(message);
            lastStatus = message;
        }
    }

    @Override
    public void onSerialConnect() {
        updateConnectionState();
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        updateConnectionState();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    /** USB 원문 사이에 공통 분석기의 상태 변화 로그를 구분하여 표시합니다. */
    @Override
    public void onPlcLog(String message) {
        pendingNewline = false;
        status("\n" + message);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        updateConnectionState();
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;

        private View frame;
        private ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        private boolean showControlLines;                                               // show & update control line buttons
        private UsbSerialPort.FlowControl flowControl = UsbSerialPort.FlowControl.NONE; // !NONE: update send button state

        boolean sendAllowed = true;

        ControlLines() {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        }

        void onCreateView(View view) {
            frame = view.findViewById(R.id.controlLines);
            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        void onPrepareOptionsMenu(Menu menu) {
            menu.findItem(R.id.controlLines).setEnabled(connected == Connected.True);
            menu.findItem(R.id.flowControl).setEnabled(connected == Connected.True);
            menu.findItem(R.id.sendBreak).setEnabled(connected == Connected.True);
            try {
                EnumSet<UsbSerialPort.ControlLine> scl = usbSerialPort.getSupportedControlLines();
                EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
                menu.findItem(R.id.controlLines).setEnabled(!scl.isEmpty());
                menu.findItem(R.id.controlLines).setChecked(showControlLines);
                menu.findItem(R.id.flowControl).setEnabled(sfc.size() > 1);
            } catch (Exception ignored) {
            }
        }

        void selectFlowControl() {
            EnumSet<UsbSerialPort.FlowControl> sfc = usbSerialPort.getSupportedFlowControl();
            UsbSerialPort.FlowControl fc = usbSerialPort.getFlowControl();
            ArrayList<String> names = new ArrayList<>();
            ArrayList<UsbSerialPort.FlowControl> values = new ArrayList<>();
            int pos = 0;
            names.add("<none>");
            values.add(UsbSerialPort.FlowControl.NONE);
            if (sfc.contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                names.add("RTS/CTS control lines");
                values.add(UsbSerialPort.FlowControl.RTS_CTS);
                if (fc == UsbSerialPort.FlowControl.RTS_CTS) pos = names.size() -1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.DTR_DSR)) {
                names.add("DTR/DSR control lines");
                values.add(UsbSerialPort.FlowControl.DTR_DSR);
                if (fc == UsbSerialPort.FlowControl.DTR_DSR) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF) pos = names.size() - 1;
            }
            if (sfc.contains(UsbSerialPort.FlowControl.XON_XOFF_INLINE)) {
                names.add("XON/XOFF characters");
                values.add(UsbSerialPort.FlowControl.XON_XOFF_INLINE);
                if (fc == UsbSerialPort.FlowControl.XON_XOFF_INLINE) pos = names.size() - 1;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Flow Control");
            builder.setSingleChoiceItems(names.toArray(new CharSequence[0]), pos, (dialog, which) -> {
                dialog.dismiss();
                try {
                    flowControl = values.get(which);
                    usbSerialPort.setFlowControl(flowControl);
                    flowControlFilter = usbSerialPort.getFlowControl() == UsbSerialPort.FlowControl.XON_XOFF_INLINE ? new XonXoffFilter() : null;
                    start();
                } catch (Exception e) {
                    status("Set flow control failed: "+e.getClass().getName()+" "+e.getMessage());
                    flowControl = UsbSerialPort.FlowControl.NONE;
                    flowControlFilter = null;
                    start();
                }
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
            builder.setNeutralButton("Info", (dialog, which) -> {
                dialog.dismiss();
                AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
                builder2.setTitle("Flow Control").setMessage("If send is stopped by the external device, the 'Send' button changes to 'Blocked' icon.");
                builder2.create().show();
            });
            builder.create().show();
        }

        public boolean showControlLines(boolean show) {
            showControlLines = show;
            start();
            return showControlLines;
        }

        void start() {
            if (frame == null || usbSerialPort == null || !isResumed()) return;
            flowControl = usbSerialPort.getFlowControl();
            if (flowControl == UsbSerialPort.FlowControl.XON_XOFF_INLINE && flowControlFilter == null)
                flowControlFilter = new XonXoffFilter();
            if (flowControl != UsbSerialPort.FlowControl.XON_XOFF_INLINE) flowControlFilter = null;
            if (showControlLines) {
                try {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getSupportedControlLines();
                    rtsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RTS) ? View.VISIBLE : View.INVISIBLE);
                    ctsBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CTS) ? View.VISIBLE : View.INVISIBLE);
                    dtrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DTR) ? View.VISIBLE : View.INVISIBLE);
                    dsrBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.DSR) ? View.VISIBLE : View.INVISIBLE);
                    cdBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.CD)   ? View.VISIBLE : View.INVISIBLE);
                    riBtn.setVisibility(lines.contains(UsbSerialPort.ControlLine.RI)   ? View.VISIBLE : View.INVISIBLE);
                } catch (IOException e) {
                    showControlLines = false;
                    status("getSupportedControlLines() failed: " + e.getMessage());
                }
            }
            frame.setVisibility(showControlLines ? View.VISIBLE : View.GONE);
            if(flowControl == UsbSerialPort.FlowControl.NONE) {
                sendAllowed = true;
                updateSendBtn(SendButtonState.Idle);
            }

            mainLooper.removeCallbacks(runnable);
            if (showControlLines || flowControl != UsbSerialPort.FlowControl.NONE) {
                run();
            }
        }

        void onDestroyView() {
            frame = null;
            rtsBtn = ctsBtn = dtrBtn = dsrBtn = cdBtn = riBtn = null;
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            if (frame == null) return;
            sendAllowed = true;
            updateSendBtn(SendButtonState.Idle);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }

        private void run() {
            if (connected != Connected.True || frame == null || usbSerialPort == null)
                return;
            try {
                if (showControlLines) {
                    EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                    if(rtsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setChecked(!rtsBtn.isChecked());
                    if(ctsBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setChecked(!ctsBtn.isChecked());
                    if(dtrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setChecked(!dtrBtn.isChecked());
                    if(dsrBtn.isChecked() != lines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setChecked(!dsrBtn.isChecked());
                    if(cdBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.CD))  cdBtn.setChecked(!cdBtn.isChecked());
                    if(riBtn.isChecked()  != lines.contains(UsbSerialPort.ControlLine.RI))  riBtn.setChecked(!riBtn.isChecked());
                }
                if (flowControl != UsbSerialPort.FlowControl.NONE) {
                    switch (usbSerialPort.getFlowControl()) {
                        case DTR_DSR:         sendAllowed = usbSerialPort.getDSR(); break;
                        case RTS_CTS:         sendAllowed = usbSerialPort.getCTS(); break;
                        case XON_XOFF:        sendAllowed = usbSerialPort.getXON(); break;
                        case XON_XOFF_INLINE: sendAllowed = flowControlFilter != null && flowControlFilter.getXON(); break;
                        default:              sendAllowed = true;
                    }
                    updateSendBtn(sendAllowed ? SendButtonState.Idle : SendButtonState.Disabled);
                }
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (connected != Connected.True) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + " failed: " + e.getMessage());
            }
        }

    }

}
