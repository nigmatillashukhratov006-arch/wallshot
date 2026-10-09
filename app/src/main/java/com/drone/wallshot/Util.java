package com.drone.wallshot;

import java.nio.ByteBuffer;

/** Pure-Java helpers (no Android classes) so they can be unit-tested off the device. */
public class Util {

    /**
     * Converts the three planes of an Android YUV_420_888 image to NV21 (Y plane, then interleaved V,U).
     * Handles row padding and both planar (pixel stride 1) and semi-planar (pixel stride 2) chroma layouts.
     */
    static byte[] toNv21(ByteBuffer y, int yRowStride, ByteBuffer u, ByteBuffer v,
                         int uvRowStride, int uvPixelStride, int w, int h) {
        byte[] out = new byte[w * h * 3 / 2];
        int pos = 0;
        for (int r = 0; r < h; r++) {
            int start = r * yRowStride;
            for (int c = 0; c < w; c++) {
                int idx = start + c;
                out[pos++] = idx < y.limit() ? y.get(idx) : 0;
            }
        }
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                int idx = r * uvRowStride + c * uvPixelStride;
                out[pos++] = idx < v.limit() ? v.get(idx) : (byte) 128;
                out[pos++] = idx < u.limit() ? u.get(idx) : (byte) 128;
            }
        }
        return out;
    }

    /** Degrees to the EXIF rational string "d/1,m/1,s/10000" (sign is stored separately in the REF tag). */
    static String gpsRational(double deg) {
        deg = Math.abs(deg);
        int d = (int) deg;
        double minutes = (deg - d) * 60.0;
        int m = (int) minutes;
        double s = (minutes - m) * 60.0;
        return d + "/1," + m + "/1," + Math.round(s * 10000.0) + "/10000";
    }
}
