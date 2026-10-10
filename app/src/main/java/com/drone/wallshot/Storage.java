package com.drone.wallshot;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the MK15's internal storage and SD card folders that the app can write to without extra permissions,
 * and copies photos and logs from internal storage to the SD card when a card is (or becomes) available.
 *
 * Save modes:
 *   "auto"     - always MK15 internal storage, plus the SD card whenever one is inserted (default)
 *   "internal" - MK15 internal storage only
 *   "sd"       - SD card only (falls back to internal storage when no card is found)
 *   "both"     - internal storage and SD card (falls back to internal only when no card is found)
 */
public class Storage {

    static File internalBase(Context c) {
        File[] d = c.getExternalFilesDirs(null);
        if (d != null && d.length > 0 && d[0] != null) return d[0];
        return c.getFilesDir();
    }

    /** First mounted removable volume (the microSD card), or null if Android does not see one. */
    static File sdBase(Context c) {
        File[] d = c.getExternalFilesDirs(null);
        if (d == null) return null;
        for (int i = 1; i < d.length; i++) {
            File f = d[i];
            if (f == null) continue;
            String state;
            try {
                state = Environment.getExternalStorageState(f);
            } catch (Exception e) {
                continue;
            }
            if (Environment.MEDIA_MOUNTED.equals(state)) return f;
        }
        return null;
    }

    /**
     * Turns what the user typed into a safe folder / file name: letters (any language), digits, '-' and '_'.
     * Spaces become '_'. Empty result = no name.
     */
    static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') b.append(ch);
            else if (ch == ' ' || ch == '.') b.append('_');
        }
        String s = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    /** Photo file name for shot number n, e.g. "Library_East_Wall_0007.jpg". */
    static String photoName(String prefix, int n) {
        return String.format(Locale.US, "%s_%04d.jpg", prefix, n);
    }

    /** Highest photo number already in these folders for this prefix, so a repeated mission continues. */
    static int lastNumber(List<File> dirs, String prefix) {
        int max = 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(prefix) + "_(\\d{4,})\\.jpg");
        for (File d : dirs) {
            String[] names = d.list();
            if (names == null) continue;
            for (String n : names) {
                java.util.regex.Matcher m = p.matcher(n);
                if (m.matches()) {
                    try {
                        max = Math.max(max, Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return max;
    }

    static File sessionDir(File base, String session) {
        File dir = new File(base, "photos/" + session);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir.isDirectory() ? dir : null;
    }

    static boolean usesSd(String mode) {
        return "auto".equals(mode) || "sd".equals(mode) || "both".equals(mode);
    }

    /** Folders to write the next photo into. Warnings are appended to note (may be null). */
    static List<File> targets(Context c, String mode, String session, StringBuilder note) {
        List<File> out = new ArrayList<>();
        File sd = usesSd(mode) ? sdBase(c) : null;
        boolean wantInternal = !"sd".equals(mode);
        boolean wantSd = usesSd(mode);
        if (wantSd && sd == null) {
            if (note != null && !"auto".equals(mode)) {
                note.append("SD card not found, saving to MK15 internal storage only. ");
            }
            wantInternal = true;
            wantSd = false;
        }
        if (wantInternal) {
            File d = sessionDir(internalBase(c), session);
            if (d != null) out.add(d);
            else if (note != null) note.append("Cannot create the internal folder. ");
        }
        if (wantSd) {
            File d = sessionDir(sd, session);
            if (d != null) out.add(d);
            else if (note != null) note.append("Cannot create the SD card folder. ");
        }
        return out;
    }

    /** Result of a copy to the SD card. */
    static class SyncResult {
        int copied = 0;
        int skipped = 0;
        int failed = 0;
        int repaired = 0;
        long bytes = 0;
        String error = "";
        boolean noCard = false;

        @Override
        public String toString() {
            if (noCard) return "SD card not found.";
            String s = String.format(Locale.US, "%d file(s) copied to the SD card (%.1f MB), %d already there",
                    copied, bytes / 1e6, skipped);
            if (repaired > 0) s += String.format(Locale.US, ", %d damaged copy(ies) replaced", repaired);
            if (failed > 0) s += String.format(Locale.US, ", %d failed (%s)", failed, error);
            return s + ".";
        }
    }

    /**
     * Copies every photo folder and CSV log from MK15 internal storage to the SD card, keeping the same folder
     * layout. Files that already exist on the card with the same size are skipped, so it is safe to run often.
     */
    static synchronized SyncResult syncToSd(Context c) {
        SyncResult r = new SyncResult();
        File sd = sdBase(c);
        if (sd == null) {
            r.noCard = true;
            return r;
        }
        File in = internalBase(c);
        copyTree(new File(in, "photos"), new File(sd, "photos"), r);
        File[] logs = in.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".csv"));
        if (logs != null) {
            for (File f : logs) copyIfNeeded(f, new File(sd, f.getName()), r, true);
        }
        return r;
    }

    private static void copyTree(File src, File dst, SyncResult r) {
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (k.isDirectory()) copyTree(k, t, r);
            else copyIfNeeded(k, t, r, false);
        }
    }

    /** CSV logs grow during a session, so they are re-copied when their size changed. */
    private static void copyIfNeeded(File src, File dst, SyncResult r, boolean growing) {
        long len = src.length();
        // Skip only real copies: same size AND the same bytes at the start and end. A copy that was damaged
        // (for example the card was removed before Android finished writing) is replaced.
        if (dst.isFile() && dst.length() == len && sameEnds(src, dst)) {
            r.skipped++;
            return;
        }
        if (dst.isFile() && dst.length() == len) r.repaired++;   // same size, wrong bytes = damaged copy
        File parent = dst.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(dst.getAbsolutePath() + ".part");
        try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            ((FileOutputStream) os).getFD().sync();   // make sure the data is really on the card
        } catch (IOException e) {
            r.failed++;
            r.error = e.getMessage();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (tmp.length() != len) {
            r.failed++;
            r.error = "copy incomplete";
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (tmp.renameTo(dst)) {
            r.copied++;
            r.bytes += len;
        } else {
            r.failed++;
            r.error = "rename failed";
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Compares the first and last 64 KB of two files of equal length. */
    static boolean sameEnds(File a, File b) {
        long len = a.length();
        int n = (int) Math.min(65536, len);
        try (java.io.RandomAccessFile fa = new java.io.RandomAccessFile(a, "r");
             java.io.RandomAccessFile fb = new java.io.RandomAccessFile(b, "r")) {
            byte[] x = new byte[n], y = new byte[n];
            fa.readFully(x);
            fb.readFully(y);
            if (!java.util.Arrays.equals(x, y)) return false;
            if (len > n) {
                fa.seek(len - n);
                fb.seek(len - n);
                fa.readFully(x);
                fb.readFully(y);
                if (!java.util.Arrays.equals(x, y)) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static int countPhotos(File base) {
        return countJpg(new File(base, "photos"));
    }

    private static int countJpg(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) {
            if (k.isDirectory()) n += countJpg(k);
            else if (k.getName().toLowerCase(Locale.US).endsWith(".jpg")) n++;
        }
        return n;
    }

    static String describe(Context c) {
        File in = internalBase(c);
        File sd = sdBase(c);
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "MK15 storage: %s/photos (%.1f GB free, %d photos)\n",
                in.getAbsolutePath(), in.getUsableSpace() / 1e9, countPhotos(in)));
        if (sd == null) {
            s.append("SD card: NOT FOUND (insert one; photos are copied to it automatically)");
        } else {
            s.append(String.format(Locale.US, "SD card: %s/photos (%.1f GB free, %d photos)",
                    sd.getAbsolutePath(), sd.getUsableSpace() / 1e9, countPhotos(sd)));
        }
        return s.toString();
    }
}
