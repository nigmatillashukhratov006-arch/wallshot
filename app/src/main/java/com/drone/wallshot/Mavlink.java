package com.drone.wallshot;

/** Tiny MAVLink reader: extracts GLOBAL_POSITION_INT (msg 33) from v1/v2 frames. No CRC check. */
public class Mavlink {

    /** Returns {lat_deg, lon_deg, rel_alt_m, heading_deg} or null if the datagram holds no position. */
    static double[] parsePosition(byte[] buf, int len) {
        double[] found = null;
        int i = 0;
        while (i < len) {
            int b = buf[i] & 0xFF;
            if (b == 0xFD) { // MAVLink v2
                if (i + 10 > len) break;
                int plen = buf[i + 1] & 0xFF;
                int incompat = buf[i + 2] & 0xFF;
                int msgid = (buf[i + 7] & 0xFF) | ((buf[i + 8] & 0xFF) << 8) | ((buf[i + 9] & 0xFF) << 16);
                int total = 10 + plen + 2 + ((incompat & 1) != 0 ? 13 : 0);
                if (i + total > len) break;
                if (msgid == 33) found = decode(buf, i + 10, plen);
                i += total;
            } else if (b == 0xFE) { // MAVLink v1
                if (i + 6 > len) break;
                int plen = buf[i + 1] & 0xFF;
                int msgid = buf[i + 5] & 0xFF;
                int total = 6 + plen + 2;
                if (i + total > len) break;
                if (msgid == 33) found = decode(buf, i + 6, plen);
                i += total;
            } else {
                i++;
            }
        }
        return found;
    }

    private static double[] decode(byte[] buf, int off, int plen) {
        byte[] p = new byte[28]; // v2 may truncate trailing zero bytes
        System.arraycopy(buf, off, p, 0, Math.min(plen, 28));
        double lat = i32(p, 4) / 1e7;
        double lon = i32(p, 8) / 1e7;
        double rel = i32(p, 16) / 1000.0;
        double hdg = (((p[26] & 0xFF) | ((p[27] & 0xFF) << 8))) / 100.0;
        if (lat == 0.0 && lon == 0.0) return null;
        return new double[]{lat, lon, rel, hdg};
    }

    private static int i32(byte[] p, int o) {
        return (p[o] & 0xFF) | ((p[o + 1] & 0xFF) << 8) | ((p[o + 2] & 0xFF) << 16) | ((p[o + 3] & 0xFF) << 24);
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
