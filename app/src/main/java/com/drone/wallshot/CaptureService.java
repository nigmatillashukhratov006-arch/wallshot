package com.drone.wallshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Foreground service: every trigger (by time or distance) it saves a frame from the video stream to the chosen
 * storage, optionally also sends the SIYI take-photo command, and logs a CSV row.
 */
public class CaptureService extends Service {

    static final String ACTION_START = "com.drone.wallshot.START";
    static final String ACTION_STOP = "com.drone.wallshot.STOP";

    static volatile boolean running = false;
    static volatile String status = "Idle";
    static volatile int shots = 0;
    static volatile int saved = 0;
    static volatile int failed = 0;
    static volatile String logPath = "";
    static volatile String photoPaths = "";

    private Thread worker;
    private Thread telemetry;
    private volatile double[] lastPos; // lat, lon, relAlt, heading
    private volatile DatagramSocket mavSocket;
    private PowerManager.WakeLock wakeLock;
    private FrameGrabber grabber;

    static double num(SharedPreferences sp, String key, double def) {
        try {
            return Double.parseDouble(sp.getString(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWork();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        startForeground(1, buildNotification("Capturing"));
        startWork();
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel("wallshot") == null) {
            nm.createNotificationChannel(
                    new NotificationChannel("wallshot", "WallShot", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, "wallshot")
                .setContentTitle("WallShot")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void startWork() {
        SharedPreferences sp = getSharedPreferences("cfg", Context.MODE_PRIVATE);
        final double wOver = num(sp, "wover", 0);
        final double widthM = wOver > 0
                ? wOver
                : 2.0 * num(sp, "d", 3.0) * Math.tan(Math.toRadians(num(sp, "hfov", 90) / 2.0));
        final double overlap = num(sp, "ov", 80);
        final double speed = num(sp, "speed", 0.3);
        final String ip = sp.getString("ip", "192.168.144.25").trim();
        final int camPort = (int) num(sp, "port", 37260);
        final int mavPort = (int) num(sp, "mav", 14550);
        final boolean distMode = "dist".equals(sp.getString("mode", "time"));
        final boolean camCmd = !"0".equals(sp.getString("camcmd", "1"));
        final String rtsp = sp.getString("rtsp", "rtsp://192.168.144.25:8554/main.264").trim();
        final String store = sp.getString("store", "internal");

        final double spacing = Math.max(0.05, widthM * (1.0 - overlap / 100.0));
        final long intervalMs = (long) (1000.0 * spacing / Math.max(0.01, speed));

        final String session = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        StringBuilder note = new StringBuilder();
        final List<File> dirs = Storage.targets(this, store, session, note);
        StringBuilder paths = new StringBuilder();
        for (File d : dirs) paths.append(d.getAbsolutePath()).append('\n');
        photoPaths = paths.toString().trim();

        shots = 0;
        saved = 0;
        failed = 0;
        lastPos = null;
        running = true;
        status = (distMode ? "Waiting for GPS (MAVLink)... " : "Starting... ") + note;

        if (!rtsp.isEmpty() && !dirs.isEmpty()) {
            grabber = new FrameGrabber(rtsp);
            grabber.start();
        } else if (dirs.isEmpty()) {
            status = "No folder to save photos into. " + note;
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wallshot:capture");
        wakeLock.acquire(60L * 60L * 1000L);

        telemetry = new Thread(() -> {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(mavPort));
                mavSocket = s;
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    double[] pos = Mavlink.parsePosition(buf, p.getLength());
                    if (pos != null) lastPos = pos;
                }
            } catch (Exception ignored) {
                // No telemetry: time mode still works, CSV position columns stay empty.
            }
        }, "wallshot-mavlink");
        telemetry.setDaemon(true);
        telemetry.start();

        worker = new Thread(() -> {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                File dir = getExternalFilesDir(null);
                File f = new File(dir, "wallshot_" + session + ".csv");
                logPath = f.getAbsolutePath();
                try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                    pw.println("n,time_utc,lat,lon,rel_alt_m,heading_deg,camera_ack,frame_saved,file");
                    pw.flush();
                    long nextAt = System.currentTimeMillis();
                    double[] lastShotPos = null;
                    while (running) {
                        long now = System.currentTimeMillis();
                        double[] pos = lastPos;
                        boolean fire = false;
                        if (!distMode) {
                            if (now >= nextAt) {
                                fire = true;
                                nextAt = now + intervalMs;
                            }
                        } else if (pos != null) {
                            if (lastShotPos == null
                                    || Mavlink.haversineMeters(lastShotPos[0], lastShotPos[1], pos[0], pos[1]) >= spacing) {
                                fire = true;
                            }
                        }
                        if (fire) {
                            boolean ack = false;
                            if (camCmd) {
                                try {
                                    ack = Siyi.takePhoto(ip, camPort);
                                } catch (Exception e) {
                                    // keep going; the CSV shows ack=false
                                }
                            }
                            shots++;
                            if (distMode && pos != null) lastShotPos = pos;
                            String file = null;
                            if (grabber != null) file = grabber.saveFrame(shots, pos, dirs);
                            if (file != null) saved++;
                            else failed++;
                            String posPart = pos == null
                                    ? ",,,,"
                                    : String.format(Locale.US, "%.7f,%.7f,%.2f,%.1f,", pos[0], pos[1], pos[2], pos[3]);
                            pw.println(shots + "," + iso.format(new Date()) + "," + posPart + ack + ","
                                    + (file != null) + "," + (file == null ? "" : file));
                            pw.flush();
                            status = String.format(Locale.US, "Running. Shots %d, saved %d, failed %d. %s",
                                    shots, saved, failed, grabber == null ? "No video stream configured" : grabber.statusText());
                        }
                        Thread.sleep(50);
                    }
                }
            } catch (InterruptedException ignored) {
                // normal stop
            } catch (Exception e) {
                status = "Error: " + e.getMessage();
            } finally {
                running = false;
            }
        }, "wallshot-capture");
        worker.start();
    }

    private void stopWork() {
        running = false;
        if (worker != null) worker.interrupt();
        DatagramSocket s = mavSocket;
        if (s != null) s.close();
        if (grabber != null) {
            grabber.stop();
            grabber = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (!status.startsWith("Error")) {
            status = String.format(Locale.US, "Stopped. Shots %d, saved %d, failed %d", shots, saved, failed);
        }
    }

    @Override
    public void onDestroy() {
        stopWork();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
