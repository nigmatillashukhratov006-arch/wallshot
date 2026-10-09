package com.drone.wallshot;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Minimal SIYI camera SDK client (UDP). Packet: 55 66 | ctrl | len(2,LE) | seq(2,LE) | cmd | data | crc16(LE). */
public class Siyi {

    private static int seq = 0;

    static int crc16(byte[] b, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                else crc = (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    static synchronized byte[] packet(int cmd, byte[] data) {
        int n = data.length;
        byte[] p = new byte[10 + n];
        p[0] = 0x55;
        p[1] = 0x66;
        p[2] = 0x01; // need ack
        p[3] = (byte) (n & 0xFF);
        p[4] = (byte) ((n >> 8) & 0xFF);
        p[5] = (byte) (seq & 0xFF);
        p[6] = (byte) ((seq >> 8) & 0xFF);
        p[7] = (byte) cmd;
        System.arraycopy(data, 0, p, 8, n);
        int c = crc16(p, 8 + n);
        p[8 + n] = (byte) (c & 0xFF);
        p[9 + n] = (byte) ((c >> 8) & 0xFF);
        seq = (seq + 1) & 0xFFFF;
        return p;
    }

    /** Sends the take-photo command (cmd 0x0C, data 0x00). Returns true if the camera answered with any packet. */
    static boolean takePhoto(String ip, int port) throws Exception {
        byte[] pkt = packet(0x0C, new byte[]{0x00});
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(700);
            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(ip), port));
            try {
                byte[] buf = new byte[64];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                s.receive(r);
                return r.getLength() >= 10 && (buf[0] & 0xFF) == 0x55 && (buf[1] & 0xFF) == 0x66;
            } catch (java.net.SocketTimeoutException e) {
                return false;
            }
        }
    }
}
