package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;


import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.security.InvalidParameterException;

public class SerialSocket implements SerialInputOutputManager.Listener {

    private static final int WRITE_WAIT_MILLIS = 200; // 0 blocked infinitely on unprogrammed arduino
    private final static String TAG = SerialSocket.class.getSimpleName();


    private volatile SerialListener listener;
    private UsbDeviceConnection connection;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;

    SerialSocket(Context context, UsbDeviceConnection connection, UsbSerialPort serialPort) {
        if(context instanceof Activity)
            throw new InvalidParameterException("expected non UI context");
        this.connection = connection;
        this.serialPort = serialPort;

    }

    String getName() { return serialPort.getDriver().getClass().getSimpleName().replace("SerialDriver",""); }

    /** USB control transfers can block, so the service calls this on its port worker. */
    void prepare() throws IOException {
        try {
            serialPort.setDTR(true);
            serialPort.setRTS(true);
        } catch (UnsupportedOperationException e) {
            Log.d(TAG, "Failed to set initial DTR/RTS", e);
        }
    }

    void connect(SerialListener listener) throws IOException {
        this.listener = listener;
        ioManager = new SerialInputOutputManager(serialPort, this);
        ioManager.start();
    }

    void disconnect() {
        listener = null; // ignore remaining data and errors
        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
            ioManager = null;
        }
        if (serialPort != null) {
            try {
                serialPort.setDTR(false);
                serialPort.setRTS(false);
            } catch (Exception ignored) {
            }
            try {
                serialPort.close();
            } catch (Exception ignored) {
            }
            serialPort = null;
        }
        if(connection != null) {
            connection.close();
            connection = null;
        }
    }

    void write(byte[] data) throws IOException {
        if(serialPort == null)
            throw new IOException("not connected");
        serialPort.write(data, WRITE_WAIT_MILLIS);
    }

    @Override
    public void onNewData(byte[] data) {
        SerialListener current = listener;
        if(current != null)
            current.onSerialRead(data);
    }

    @Override
    public void onRunError(Exception e) {
        SerialListener current = listener;
        if (current != null)
            current.onSerialIoError(e);
    }
}
