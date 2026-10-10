package com.drone.wallshot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private SharedPreferences sp;
    private EditText etMission, etD, etAlt, etHfov, etW, etOv, etSpeed, etPath, etIp, etPort, etMav, etRtsp;
    private RadioButton rbTime, rbDist, rbAuto, rbInternal, rbSd, rbBoth;
    private CheckBox cbCam, cbTcp, cbHwDec;
    private TextView tvCalc, tvStatus, tvStore, tvFolder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean sdWasPresent = false;
    private boolean syncing = false;
    private int tick = 0;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            double d = num(etD, 3.0), alt = num(etAlt, 6.0), hfov = num(etHfov, 90);
            double wOver = num(etW, 0), ov = num(etOv, 80), v = num(etSpeed, 0.3), path = num(etPath, 27);
            double w = wOver > 0 ? wOver : 2.0 * d * Math.tan(Math.toRadians(hfov / 2.0));
            double gsd = w / 1920.0 * 1000.0;
            double spacing = w * (1.0 - ov / 100.0);
            double interval = v > 0 ? spacing / v : 0;
            int photos = spacing > 0 ? (int) Math.ceil(path / spacing) + 1 : 0;
            double rowTime = v > 0 ? path / v : 0;
            tvCalc.setText(String.format(Locale.US,
                    "MISSION: fly %.1f m from the wall at %.1f m AGL, gimbal pitch 0 deg, nose pointing at the wall\n"
                            + "Wall width per photo W: %.2f m (%s)\nDetail: %.1f mm/pixel (1920 px wide)\n"
                            + "Photo spacing: %.2f m\nTime interval: %.1f s\nPhotos per row: %d\nRow time: %.0f s",
                    d, alt, w, wOver > 0 ? "measured" : "computed from FOV", gsd,
                    spacing, interval, photos, rowTime));
            String log = CaptureService.logPath.isEmpty() ? "" : "\nLog: " + CaptureService.logPath;
            String where = CaptureService.photoPaths.isEmpty() ? "" : "\nPhotos: " + CaptureService.photoPaths;
            String last = CaptureService.lastFile.isEmpty() ? "" : "\nLast photo: " + CaptureService.lastFile;
            String sync = CaptureService.syncNote.isEmpty() ? "" : "\n" + CaptureService.syncNote;
            tvStatus.setText("Status: " + CaptureService.status + last + where + log + sync);
            String m = Storage.cleanName(etMission.getText().toString());
            tvFolder.setText(m.isEmpty()
                    ? "No name: photos go into a date-time folder, named wall_0001.jpg, wall_0002.jpg, ..."
                    : "Folder: photos/" + m + "/   Photos: " + Storage.photoName(m, 1) + ", "
                      + Storage.photoName(m, 2) + ", ...   Log: " + m + "_log.csv");
            // Every 2 s: refresh the storage lines and copy to a newly inserted SD card.
            if (tick++ % 4 == 0) checkSdCard();
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private double num(EditText e, double def) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (Exception ex) {
            return def;
        }
    }

    private EditText field(LinearLayout root, String label, String key, String def, boolean numeric) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(numeric ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_TEXT);
        e.setText(sp.getString(key, def));
        root.addView(e);
        return e;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("cfg", Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("WallShot v1.4 - SIYI A2 Mini wall photo trigger");
        title.setTextSize(20);
        root.addView(title);

        etMission = field(root, "Mission name (folder and photo names), e.g. Library_East_Wall", "mission", "", false);
        tvFolder = new TextView(this);
        tvFolder.setPadding(0, 0, 0, dp(8));
        root.addView(tvFolder);

        etD = field(root, "Standoff from wall (m)", "d", "3.0", true);
        etAlt = field(root, "Flight altitude AGL (m)", "alt", "6.0", true);
        etHfov = field(root, "Corrected camera horizontal FOV (deg) - 90 or 120 until you measure", "hfov", "90", true);
        etW = field(root, "Measured wall width per photo W (m) - leave empty to compute from FOV", "wover", "", true);
        etOv = field(root, "Forward overlap (%)", "ov", "80", true);
        etSpeed = field(root, "Flight speed (m/s)", "speed", "0.3", true);
        etPath = field(root, "Path length per row (m)", "path", "27", true);
        etIp = field(root, "Camera IP", "ip", "192.168.144.25", false);
        etPort = field(root, "Camera control UDP port", "port", "37260", true);
        etMav = field(root, "MAVLink UDP port (position log / distance mode)", "mav", "14550", true);
        etRtsp = field(root, "Video stream URL (photos are saved from this stream)", "rtsp",
                "rtsp://192.168.144.25:8554/main.264", false);

        cbTcp = new CheckBox(this);
        cbTcp.setText("Use RTSP over TCP (turn off if the stream will not open)");
        cbTcp.setChecked(!"0".equals(sp.getString("tcp", "1")));
        root.addView(cbTcp);

        cbHwDec = new CheckBox(this);
        cbHwDec.setText("Use the hardware video decoder (leave OFF on the MK15: it gives green, scrambled photos)");
        cbHwDec.setChecked("1".equals(sp.getString("hwdec", "0")));
        root.addView(cbHwDec);

        RadioGroup rg = new RadioGroup(this);
        rbTime = new RadioButton(this);
        rbTime.setText("Trigger by time (recommended)");
        rbDist = new RadioButton(this);
        rbDist.setText("Trigger by distance (needs MAVLink position)");
        rg.addView(rbTime);
        rg.addView(rbDist);
        if ("dist".equals(sp.getString("mode", "time"))) rbDist.setChecked(true);
        else rbTime.setChecked(true);
        root.addView(rg);

        TextView saveLabel = new TextView(this);
        saveLabel.setText("Save photos to:");
        saveLabel.setPadding(0, dp(12), 0, 0);
        root.addView(saveLabel);
        RadioGroup rgs = new RadioGroup(this);
        rbAuto = new RadioButton(this);
        rbAuto.setText("Automatic: MK15 storage, plus the SD card whenever one is inserted (recommended)");
        rbInternal = new RadioButton(this);
        rbInternal.setText("MK15 internal storage");
        rbSd = new RadioButton(this);
        rbSd.setText("SD card in the MK15");
        rbBoth = new RadioButton(this);
        rbBoth.setText("Both (two copies)");
        rgs.addView(rbAuto);
        rgs.addView(rbInternal);
        rgs.addView(rbSd);
        rgs.addView(rbBoth);
        String store = sp.getString("store", "auto");
        if ("sd".equals(store)) rbSd.setChecked(true);
        else if ("both".equals(store)) rbBoth.setChecked(true);
        else if ("internal".equals(store)) rbInternal.setChecked(true);
        else rbAuto.setChecked(true);
        root.addView(rgs);

        tvStore = new TextView(this);
        root.addView(tvStore);

        Button copySd = new Button(this);
        copySd.setText("Copy all photos to SD card now");
        copySd.setOnClickListener(v -> copyToSd(true));
        root.addView(copySd);

        cbCam = new CheckBox(this);
        cbCam.setText("Also send the take-photo command to the camera (its own storage, if it has any)");
        cbCam.setChecked(!"0".equals(sp.getString("camcmd", "1")));
        root.addView(cbCam);

        tvCalc = new TextView(this);
        tvCalc.setTextSize(16);
        tvCalc.setPadding(0, dp(12), 0, dp(12));
        root.addView(tvCalc);

        Button probe = new Button(this);
        probe.setText("1. Check video stream (RTSP)");
        probe.setOnClickListener(v -> checkStream());
        root.addView(probe);

        Button testFrame = new Button(this);
        testFrame.setText("2. Test video frame save (camera on, up to 25 s)");
        testFrame.setOnClickListener(v -> testFrameSave());
        root.addView(testFrame);

        Button test = new Button(this);
        test.setText("Test camera command");
        test.setOnClickListener(v -> testPhoto());
        root.addView(test);

        Button start = new Button(this);
        start.setText("Start capture");
        start.setOnClickListener(v -> {
            save();
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START);
            startForegroundService(i);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop capture");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(i);
        });
        root.addView(stop);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private String storeMode() {
        if (rbAuto.isChecked()) return "auto";
        if (rbSd.isChecked()) return "sd";
        if (rbBoth.isChecked()) return "both";
        return "internal";
    }

    private void save() {
        sp.edit()
                .putString("mission", etMission.getText().toString().trim())
                .putString("d", etD.getText().toString().trim())
                .putString("alt", etAlt.getText().toString().trim())
                .putString("hfov", etHfov.getText().toString().trim())
                .putString("wover", etW.getText().toString().trim())
                .putString("ov", etOv.getText().toString().trim())
                .putString("speed", etSpeed.getText().toString().trim())
                .putString("path", etPath.getText().toString().trim())
                .putString("ip", etIp.getText().toString().trim())
                .putString("port", etPort.getText().toString().trim())
                .putString("mav", etMav.getText().toString().trim())
                .putString("rtsp", etRtsp.getText().toString().trim())
                .putString("tcp", cbTcp.isChecked() ? "1" : "0")
                .putString("hwdec", cbHwDec.isChecked() ? "1" : "0")
                .putString("mode", rbDist.isChecked() ? "dist" : "time")
                .putString("store", storeMode())
                .putString("camcmd", cbCam.isChecked() ? "1" : "0")
                .apply();
    }

    /** Shows a selectable, scrollable message so it can be read or photographed. */
    private void showText(final String title, final String msg) {
        runOnUiThread(() -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .create();
            dialog.show();
            TextView tv = (TextView) dialog.findViewById(android.R.id.message);
            if (tv != null) tv.setTextIsSelectable(true);
        });
    }

    private void testPhoto() {
        save();
        final String ip = etIp.getText().toString().trim();
        final int port = (int) num(etPort, 37260);
        new Thread(() -> {
            String msg;
            try {
                boolean ack = Siyi.takePhoto(ip, port);
                msg = ack ? "Camera answered. Check the camera for a new photo."
                        : "No answer from camera at " + ip + ":" + port;
            } catch (Exception e) {
                msg = "Send failed: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** Asks the camera to describe its video stream and explains the answer. */
    private void checkStream() {
        save();
        final String url = etRtsp.getText().toString().trim();
        if (url.isEmpty()) {
            showText("Video stream check", "The video stream URL is empty.");
            return;
        }
        Toast.makeText(this, "Asking the camera about its video stream...", Toast.LENGTH_SHORT).show();
        new Thread(() -> showText("Video stream check", url + "\n\n" + RtspProbe.probe(url, 5000))).start();
    }

    /** Connects to the video stream, saves one frame to the chosen storage and reports what happened. */
    private void testFrameSave() {
        save();
        final String url = etRtsp.getText().toString().trim();
        final String mode = storeMode();
        final boolean tcp = cbTcp.isChecked();
        final boolean sw = !cbHwDec.isChecked();
        Toast.makeText(this, "Connecting to video, please wait...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            FrameGrabber g = new FrameGrabber(getApplicationContext(), url, tcp, sw);
            g.start();
            long end = System.currentTimeMillis() + 25000;
            while (!g.isReady() && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
            String msg;
            if (!g.isReady()) {
                msg = "No video after 25 s.\n" + g.statusText() + "\n\nRTSP over TCP: " + (tcp ? "on" : "off");
            } else {
                StringBuilder note = new StringBuilder();
                // Test photos are kept apart from mission folders.
                List<File> dirs = Storage.targets(this, mode, "_test", note);
                String testName = "test_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new java.util.Date()) + ".jpg";
                String name = dirs.isEmpty() ? null : g.saveFrame(testName, null, dirs);
                if (name != null) {
                    StringBuilder where = new StringBuilder();
                    for (File d : dirs) where.append('\n').append(d.getAbsolutePath());
                    msg = "Saved " + name + " to:" + where + "\n" + note + "\n\n" + g.frameCheck();
                } else {
                    msg = "Video is up but saving failed: " + g.statusText() + " " + note;
                }
            }
            g.stop();
            showText("Video frame test", msg);
        }).start();
    }

    /** Updates the storage lines; when a card appears (or at start-up) copies missing photos to it. */
    private void checkSdCard() {
        tvStore.setText(Storage.describe(this));
        boolean present = Storage.sdBase(this) != null;
        if (present && !sdWasPresent && Storage.usesSd(storeMode()) && !CaptureService.running) {
            copyToSd(false);
        }
        sdWasPresent = present;
    }

    /** Copies every photo and CSV log from MK15 storage to the SD card (files already there are skipped). */
    private void copyToSd(boolean userAsked) {
        if (syncing) {
            if (userAsked) Toast.makeText(this, "Already copying...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Storage.sdBase(this) == null) {
            if (userAsked) showText("Copy to SD card", "No SD card found. Insert one and try again.");
            return;
        }
        syncing = true;
        Toast.makeText(this, "Copying photos to the SD card...", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(() -> {
            Storage.SyncResult r = Storage.syncToSd(app);
            runOnUiThread(() -> {
                syncing = false;
                tvStore.setText(Storage.describe(this));
                if (userAsked || r.copied > 0 || r.failed > 0) showText("Copy to SD card", r.toString());
            });
        }, "wallshot-sync").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvStore.setText(Storage.describe(this));
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
