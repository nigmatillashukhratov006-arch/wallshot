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
        // Y plane: copy whole rows at once (absolute reads, buffer positions untouched).
        ByteBuffer yd = y.duplicate();
        int pos = 0;
        for (int r = 0; r < h; r++) {
            int start = r * yRowStride;
            int n = Math.max(0, Math.min(w, yd.limit() - start));
            if (n > 0) {
                yd.position(start);
                yd.get(out, pos, n);
            }
            pos += w;
        }
        // Chroma: read each row once, then interleave V,U for NV21.
        int cw = w / 2;
        int rowLen = (cw - 1) * uvPixelStride + 1;
        byte[] urow = new byte[rowLen];
        byte[] vrow = new byte[rowLen];
        ByteBuffer ud = u.duplicate();
        ByteBuffer vd = v.duplicate();
        for (int r = 0; r < h / 2; r++) {
            int start = r * uvRowStride;
            int nu = Math.max(0, Math.min(rowLen, ud.limit() - start));
            int nv = Math.max(0, Math.min(rowLen, vd.limit() - start));
            java.util.Arrays.fill(urow, nu, rowLen, (byte) 128);
            java.util.Arrays.fill(vrow, nv, rowLen, (byte) 128);
            if (nu > 0) {
                ud.position(start);
                ud.get(urow, 0, nu);
            }
            if (nv > 0) {
                vd.position(start);
                vd.get(vrow, 0, nv);
            }
            for (int c = 0; c < cw; c++) {
                int i = c * uvPixelStride;
                out[pos++] = vrow[i];
                out[pos++] = urow[i];
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
