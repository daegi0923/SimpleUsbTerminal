package de.kai_morich.simple_usb_terminal;

import java.util.ArrayDeque;

interface SerialListener {
    void onSerialConnect      ();
    void onSerialConnectError (Exception e);
    void onSerialRead         (byte[] data);                // socket -> service
    void onSerialRead         (ArrayDeque<byte[]> datas);   // service -> UI thread
    default void onSerialStatus(String message) { }
    /** PLC 분석 결과를 USB 수신 화면에 전달합니다. */
    default void onPlcLog(String message) { }
    void onSerialIoError      (Exception e);
}
