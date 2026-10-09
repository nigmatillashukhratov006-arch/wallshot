package com.drone.wallshot;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Plays the camera's RTSP stream into an ImageReader (no screen needed) and, on request, writes the next
 * decoded frame as a JPEG into one or more folders, with GPS EXIF when a position is known.
 */
public class FrameGrabber {

    private static class Request {
        final int n;
        final double[] pos;
        final List<File> dirs;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String name = null;

        Request(int n, double[] pos, List<File> dirs) {
            this.n = n;
            this.pos = pos;
            this.dirs = dirs;
        }
    }

    private final String url;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private MediaPlayer player;
    private ImageReader reader;
    private volatile boolean ready = false;
    private volatile String lastError = "";
    private volatile Request pending;

    FrameGrabber(String url) {
        this.url = url;
    }

    boolean isReady() {
        return ready;
    }

    String statusText() {
        if (ready) return lastError.isEmpty() ? "video OK" : "video OK, last error: " + lastError;
        return lastError.isEmpty() ? "connecting to video..." : lastError;
    }

    void start() {
        thread = new HandlerThread("wallshot-frames");
        thread.start();
        bg = new Handler(thread.getLooper());
        main.post(() -> {
            try {
                player = new MediaPlayer();
                player.setDataSource(url);
                player.setOnPreparedListener(mp -> {
                    try {
                        int w = mp.getVideoWidth();
                        int h = mp.getVideoHeight();
                        if (w <= 0 || h <= 0) {
                            lastError = "stream has no video size";
                            return;
                        }
                        reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3);
                        reader.setOnImageAvailableListener(this::onImage, bg);
                        mp.setSurface(reader.getSurface());
                        mp.start();
                        ready = true;
                    } catch (Exception e) {
                        lastError = "video setup failed: " + e.getMessage();
                    }
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    ready = false;
                    lastError = "video error " + what + "/" + extra;
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                lastError = "cannot open " + url + ": " + e.getMessage();
            }
        });
    }

    void stop() {
        ready = false;
        pending = null;
        main.post(() -> {
            try {
                if (player != null) player.release();
            } catch (Exception ignored) {
            }
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {
            }
            if (thread != null) thread.quitSafely();
        });
    }

    /** Blocks (max 2.5 s) until the next frame is saved. Returns the file name, or null on failure. */
    String saveFrame(int n, double[] pos, List<File> dirs) {
        if (!ready) return null;
        Request req = new Request(n, pos, dirs);
        pending = req;
        try {
            if (!req.done.await(2500, TimeUnit.MILLISECONDS)) {
                pending = null;
                lastError = "no frame arrived within 2.5 s";
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return req.name;
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            Request req = pending;
            if (req == null) return;
            pending = null;
            process(img, req);
        } catch (Exception e) {
            lastError = "frame error: " + e.getMessage();
        } finally {
            if (img != null) img.close();
        }
    }

    private void process(Image img, Request req) {
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            Image.Plane[] p = img.getPlanes();
            byte[] nv21 = Util.toNv21(p[0].getBuffer(), p[0].getRowStride(),
                    p[1].getBuffer(), p[2].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(), w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(600 * 1024);
            new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 95, bos);
            byte[] jpg = bos.toByteArray();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String name = String.format(Locale.US, "wall_%04d_%s.jpg", req.n, stamp);
            boolean any = false;
            for (File dir : req.dirs) {
                File f = new File(dir, name);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(jpg);
                    fo.flush();
                    any = true;
                } catch (IOException e) {
                    lastError = "write failed: " + e.getMessage();
                    continue;
                }
                try {
                    tagExif(f, req.pos);
                } catch (Exception e) {
                    lastError = "EXIF failed (photo kept): " + e.getMessage();
                }
            }
            req.name = any ? name : null;
        } catch (Exception e) {
            lastError = "convert failed: " + e.getMessage();
        } finally {
            req.done.countDown();
        }
    }

    private static void tagExif(File f, double[] pos) throws IOException {
        ExifInterface ex = new ExifInterface(f.getAbsolutePath());
        ex.setAttribute(ExifInterface.TAG_DATETIME,
                new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date()));
        if (pos != null) {
            ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE, Util.gpsRational(pos[0]));
            ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, pos[0] >= 0 ? "N" : "S");
            ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, Util.gpsRational(pos[1]));
            ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, pos[1] >= 0 ? "E" : "W");
        }
        ex.saveAttributes();
    }
}
