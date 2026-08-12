package com.questhub.questreader.scandemo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Demo-only evidence uploader.
 *
 * The APK never receives Google credentials. It LAN-pairs with QuestHub for a
 * scoped device token, then sends OCR evidence to QuestHub's authenticated
 * diagnostics ingest. QuestHub/QuestLink owns the Google Drive write.
 */
public final class DiagnosticUploader {
    private static final String DEFAULT_PAIR_URL = "http://raspberrypi.local:8792";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 90000;

    private final Context context;
    private final Consumer<String> log;
    private final Consumer<String> status;
    private final Consumer<Boolean> readyChanged;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger uploaded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    private volatile String controlUrl = "";
    private volatile String token = "";
    private volatile String sessionId = "";
    private volatile String drivePath = "";
    private volatile JSONObject cameraInfo = new JSONObject();
    private volatile boolean ready = false;
    private volatile boolean pairing = false;

    DiagnosticUploader(Context context, Consumer<String> log, Consumer<String> status, Consumer<Boolean> readyChanged) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.status = status;
        this.readyChanged = readyChanged;
    }

    boolean isReady() { return ready; }
    String drivePath() { return drivePath; }
    int queuedCount() { return queued.get(); }
    int uploadedCount() { return uploaded.get(); }
    int failedCount() { return failed.get(); }

    void setCameraInfo(JSONObject info) {
        cameraInfo = info == null ? new JSONObject() : info;
        executor.execute(this::ensureReady);
    }

    void startPairing() {
        executor.execute(this::ensureReady);
    }

    void enqueueFrame(int frameIndex, byte[] jpeg, JSONObject metadata) {
        queued.incrementAndGet();
        publishStatus("queued frame " + frameIndex);
        executor.execute(() -> {
            try {
                if (!ensureReady()) throw new IllegalStateException("QuestHub diagnostic session is not ready");
                uploadFrame(frameIndex, jpeg, metadata);
                uploaded.incrementAndGet();
                log.accept("Drive frame upload OK index=" + frameIndex + " bytes=" + jpeg.length);
            } catch (Exception e) {
                failed.incrementAndGet();
                log.accept("Drive frame upload FAILED index=" + frameIndex + " error=" + compact(e));
            }
            publishStatus("frame " + frameIndex);
        });
    }

    void finish(JSONObject finalResult, JSONObject summary) {
        executor.execute(() -> {
            String finishingSession = sessionId;
            if (finishingSession.isEmpty()) return;
            try {
                JSONObject body = new JSONObject();
                body.put("final", finalResult == null ? new JSONObject() : finalResult);
                body.put("summary", summary == null ? new JSONObject() : summary);
                JSONObject response = jsonRequest("POST", controlUrl + "/v1/questreader/diagnostics/sessions/" + finishingSession + "/finish", body, true);
                log.accept("Diagnostic session finish OK session=" + finishingSession + " framesUploaded=" + response.optInt("frames_uploaded", uploaded.get()));
                sessionId = "";
                drivePath = "";
                ready = false;
                notifyReady(false);
            } catch (Exception e) {
                log.accept("Diagnostic session finish FAILED session=" + finishingSession + " error=" + compact(e));
            }
            publishStatus("finish");
        });
    }

    void shutdown() {
        executor.shutdown();
    }

    private boolean ensureReady() {
        if (ready && !token.isEmpty() && !sessionId.isEmpty()) return true;
        if (pairing) return false;
        pairing = true;
        try {
            if (token.isEmpty() || controlUrl.isEmpty()) pair();
            if (cameraInfo.length() == 0) {
                status.accept("Drive diagnostics: paired; waiting for camera…");
                return false;
            }
            startSession();
            ready = true;
            notifyReady(true);
            return true;
        } catch (Exception e) {
            ready = false;
            notifyReady(false);
            log.accept("Diagnostic pairing/session FAILED: " + compact(e));
            status.accept("Drive diagnostics unavailable: " + compact(e));
            return false;
        } finally {
            pairing = false;
        }
    }

    private void pair() throws Exception {
        status.accept("Drive diagnostics: pairing with QuestHub…");
        SharedPreferences prefs = context.getSharedPreferences("questreader_demo", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", "");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = "questreader-" + UUID.randomUUID();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        JSONObject body = new JSONObject();
        body.put("device_id", deviceId);
        body.put("device_name", "QuestReader Scan Demo on " + Build.MODEL);
        JSONObject response = jsonRequest("POST", DEFAULT_PAIR_URL + "/pair", body, false);
        token = response.optString("token", "");
        controlUrl = response.optString("control_url", DEFAULT_PAIR_URL).replaceAll("/+$", "");
        if (token.isEmpty()) throw new IllegalStateException("QuestHub pairing returned no device token");
        log.accept("QuestHub LAN pairing OK node=" + response.optString("node_name", "") + " controlUrl=" + controlUrl);
    }

    private void startSession() throws Exception {
        status.accept("Drive diagnostics: creating test session…");
        JSONObject body = new JSONObject();
        JSONObject device = new JSONObject();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("model", Build.MODEL);
        device.put("device", Build.DEVICE);
        device.put("sdk", Build.VERSION.SDK_INT);
        device.put("display", Build.DISPLAY);
        body.put("device", device);
        JSONObject app = new JSONObject();
        app.put("name", "QuestReader Scan Demo");
        app.put("version", "0.4-demo");
        app.put("full_diagnostic_upload", true);
        body.put("app", app);
        body.put("camera", cameraInfo);
        JSONObject settings = new JSONObject();
        settings.put("mode", "continuous_keyframe_ocr");
        settings.put("upload_every_ocr_frame", true);
        settings.put("image_representation", "full_resolution_luminance_jpeg");
        body.put("settings", settings);
        JSONObject response = jsonRequest("POST", controlUrl + "/v1/questreader/diagnostics/sessions", body, true);
        sessionId = response.optString("session_id", "");
        drivePath = response.optString("drive_path", "");
        if (sessionId.isEmpty()) throw new IllegalStateException("QuestHub returned no diagnostic session id");
        log.accept("Diagnostic session READY session=" + sessionId + " drivePath=" + drivePath);
        status.accept("Drive diagnostics READY • " + drivePath);
    }

    private void uploadFrame(int frameIndex, byte[] jpeg, JSONObject metadata) throws Exception {
        String boundary = "----QuestReader" + UUID.randomUUID().toString().replace("-", "");
        URL url = new URL(controlUrl + "/v1/questreader/diagnostics/sessions/" + sessionId + "/frames");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        ByteArrayOutputStream body = new ByteArrayOutputStream(jpeg.length + 8192);
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Disposition: form-data; name=\"metadata\"\r\n");
        writeAscii(body, "Content-Type: application/json; charset=utf-8\r\n\r\n");
        body.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
        writeAscii(body, "\r\n--" + boundary + "\r\n");
        writeAscii(body, "Content-Disposition: form-data; name=\"image\"; filename=\"frame_" + String.format(java.util.Locale.US, "%06d", frameIndex) + ".jpg\"\r\n");
        writeAscii(body, "Content-Type: image/jpeg\r\n\r\n");
        body.write(jpeg);
        writeAscii(body, "\r\n--" + boundary + "--\r\n");

        byte[] bytes = body.toByteArray();
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        int code = conn.getResponseCode();
        String response = readResponse(conn, code);
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + response);
        conn.disconnect();
    }

    private JSONObject jsonRequest(String method, String urlText, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (authenticated) conn.setRequestProperty("Authorization", "Bearer " + token);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        int code = conn.getResponseCode();
        String response = readResponse(conn, code);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + response);
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private static String readResponse(HttpURLConnection conn, int code) throws Exception {
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static void writeAscii(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private void publishStatus(String reason) {
        status.accept("Drive upload • queued " + queued.get() + " • uploaded " + uploaded.get() + " • failed " + failed.get()
                + (drivePath.isEmpty() ? "" : " • " + drivePath));
    }

    private void notifyReady(boolean value) {
        try { readyChanged.accept(value); } catch (Exception ignored) { }
    }

    private static String compact(Throwable t) {
        String value = t.getMessage();
        if (value == null || value.trim().isEmpty()) value = t.getClass().getSimpleName();
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }
}
