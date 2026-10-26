package de.kai_morich.simple_usb_terminal.plc;

import java.io.IOException;

/** PLC packets contain binary values, so text decoding must never be the byte archive. */
public final class PlcBytes {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 3);
        for (byte value : data) {
            if (text.length() > 0) text.append(' ');
            int b = value & 255;
            text.append(HEX[b >>> 4]).append(HEX[b & 15]);
        }
        return text.toString();
    }

    static byte[] fromHex(String text) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int high = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') continue;
            int digit = ch >= '0' && ch <= '9' ? ch - '0'
                    : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10
                    : ch >= 'a' && ch <= 'f' ? ch - 'a' + 10 : -1;
            if (digit < 0) throw new IOException("raw_hex에는 16진수 바이트만 입력할 수 있습니다.");
            if (high < 0) high = digit;
            else { bytes.write(high * 16 + digit); high = -1; }
        }
        if (high >= 0) throw new IOException("raw_hex의 바이트는 두 자리여야 합니다.");
        return bytes.toByteArray();
    }

    public static String display(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte value : data) {
            int b = value & 255;
            if (b == '\r' || b == '\n' || (b >= 32 && b < 127)) text.append((char) b);
            else if (b < 32) text.append('^').append((char) (b + 64));
            else text.append("\\x").append(HEX[b >>> 4]).append(HEX[b & 15]);
        }
        return text.toString();
    }

    private PlcBytes() { }
}
