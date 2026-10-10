package com.drone.wallshot;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends one RTSP DESCRIBE request to the camera and explains the answer in plain words: which video codec the
 * stream uses and whether the app's player can decode it. Pure Java, no Android classes.
 */
public class RtspProbe {

    static String probe(String url, int timeoutMs) {
        String host;
        int port = 554;
        try {
            URI u = new URI(url);
            host = u.getHost();
            if (u.getPort() > 0) port = u.getPort();
        } catch (Exception e) {
            return "Bad stream URL: " + e.getMessage();
        }
        if (host == null) return "Bad stream URL (no host name).";

        try (Socket s = new Socket()) {
            try {
                s.connect(new InetSocketAddress(host, port), timeoutMs);
            } catch (Exception e) {
                return "Cannot connect to " + host + ":" + port + " (" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + "). Check that the camera is on and this controller is linked to it.";
            }
            s.setSoTimeout(timeoutMs);
            String req = "DESCRIBE " + url + " RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: WallShot\r\n"
                    + "Accept: application/sdp\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            String raw;
            try {
                raw = readResponse(s.getInputStream());
            } catch (SocketTimeoutException e) {
                return "Connected to " + host + ":" + port + ", but the camera did not answer the DESCRIBE request within "
                        + String.format(java.util.Locale.US, "%.1f", timeoutMs / 1000.0) + " s.";
            }
            return summarize(raw);
        } catch (Exception e) {
            return "Stream check failed: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    static String readResponse(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        Pattern cl = Pattern.compile("(?i)content-length:\\s*(\\d+)");
        while (bo.size() < 65536) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                if (bo.size() > 0) break;
                throw e;
            }
            if (n < 0) break;
            bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
            int sep = 4;
            int he = s.indexOf("\r\n\r\n");
            if (he < 0) {
                he = s.indexOf("\n\n");
                sep = 2;
            }
            if (he >= 0) {
                Matcher m = cl.matcher(s.substring(0, he));
                int len = m.find() ? Integer.parseInt(m.group(1)) : 0;
                if (s.length() - (he + sep) >= len) return s;
            }
        }
        return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    static String summarize(String raw) {
        if (raw == null || !raw.startsWith("RTSP/")) {
            String shown = raw == null ? "" : raw.substring(0, Math.min(raw.length(), 200));
            return "The port answered, but not with RTSP: " + shown;
        }
        String[] lines = raw.split("\r?\n");
        String status = lines[0].trim();
        String[] parts = status.split(" ", 3);
        int code = 0;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Camera answered: ").append(status).append('\n');
        if (code != 200) {
            if (code == 401) sb.append("The stream asks for a user name and password.");
            else if (code == 404) sb.append("Wrong stream path. Try /main.264 or /sub.264 on the same address.");
            else sb.append("The camera refused the request.");
            return sb.toString();
        }

        String sdp = raw;
        int he = raw.indexOf("\r\n\r\n");
        int sep = 4;
        if (he < 0) {
            he = raw.indexOf("\n\n");
            sep = 2;
        }
        if (he >= 0) sdp = raw.substring(he + sep);

        Matcher mv = Pattern.compile("(?m)^m=video\\s+\\d+\\s+\\S+\\s+(\\d+)").matcher(sdp);
        if (!mv.find()) {
            sb.append("The stream description has no video track.");
            return sb.toString();
        }
        String pt = mv.group(1);
        String codec = "unknown";
        Matcher rm = Pattern.compile("(?m)^a=rtpmap:" + pt + "\\s+([A-Za-z0-9\\-]+)/(\\d+)").matcher(sdp);
        if (rm.find()) codec = rm.group(1).toUpperCase();
        if (codec.equals("HEVC")) codec = "H265";
        String fmtp = "";
        Matcher fm = Pattern.compile("(?m)^a=fmtp:" + pt + "\\s+(.*)$").matcher(sdp);
        if (fm.find()) fmtp = fm.group(1).trim().toLowerCase();

        sb.append("Video codec: ").append(codec).append('\n');
        if (codec.equals("H264")) {
            if (fmtp.contains("sprop-parameter-sets=")) {
                sb.append("Verdict: H264 with SPS/PPS in the description. The app's player should be able to decode this.");
            } else {
                sb.append("Verdict: H264, but the description has no SPS/PPS data. The app's player cannot start from this description.");
            }
        } else if (codec.equals("H265")) {
            sb.append("Verdict: H265. The Android RTSP player used by this app cannot decode H265. ")
                    .append("Set the camera's video encoding to H.264, then test again.");
        } else {
            sb.append("Verdict: the app's player cannot decode this codec.");
        }
        String sdpShown = sdp.length() > 1200 ? sdp.substring(0, 1200) + "..." : sdp;
        sb.append("\n\n--- stream description from the camera ---\n").append(sdpShown.trim());
        return sb.toString();
    }
}
