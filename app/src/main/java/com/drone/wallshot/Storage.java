package com.drone.wallshot;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds the MK15's internal storage and SD card folders that the app can write to without extra permissions. */
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
            if (f != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(f))) return f;
        }
        return null;
    }

    private static File session(File base, String session) {
        File dir = new File(base, "photos/" + session);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir.isDirectory() ? dir : null;
    }

    /** mode is "internal", "sd" or "both". Warnings are appended to note. */
    static List<File> targets(Context c, String mode, String session, StringBuilder note) {
        List<File> out = new ArrayList<>();
        File sd = sdBase(c);
        boolean wantInternal = !"sd".equals(mode);
        boolean wantSd = "sd".equals(mode) || "both".equals(mode);
        if (wantSd && sd == null) {
            note.append("SD card not found, saving to internal storage only. ");
            wantInternal = true;
            wantSd = false;
        }
        if (wantInternal) {
            File d = session(internalBase(c), session);
            if (d != null) out.add(d);
            else note.append("Cannot create the internal folder. ");
        }
        if (wantSd) {
            File d = session(sd, session);
            if (d != null) out.add(d);
            else note.append("Cannot create the SD card folder. ");
        }
        return out;
    }

    static String describe(Context c) {
        File in = internalBase(c);
        File sd = sdBase(c);
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "Internal: %s (%.1f GB free)\n", in.getAbsolutePath(), in.getUsableSpace() / 1e9));
        if (sd == null) s.append("SD card: NOT FOUND (insert it and reopen the app)");
        else s.append(String.format(Locale.US, "SD card: %s (%.1f GB free)", sd.getAbsolutePath(), sd.getUsableSpace() / 1e9));
        return s.toString();
    }
}
