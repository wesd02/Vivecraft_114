package com.questhub.questreader.scandemo;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 501;
    private static final String HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA";
    private static final int MAX_SCAN_ATTEMPTS = 12;
    private static final long OCR_INTERVAL_MS = 220;

    // Meta's official native sample defines these vendor tags as Integer keys.
    private static final CameraCharacteristics.Key<Integer> META_CAMERA_SOURCE =
            new CameraCharacteristics.Key<>("com.meta.extra_metadata.camera_source", Integer.class);
    private static final CameraCharacteristics.Key<Integer> META_CAMERA_POSITION =
            new CameraCharacteristics.Key<>("com.meta.extra_metadata.position", Integer.class);
    private static final int META_SOURCE_PASSTHROUGH = 0;
    private static final int META_POSITION_LEFT = 0;
    private static final int META_POSITION_RIGHT = 1;

    private final StringBuilder testLog = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView statusView;
    private TextView textView;
    private Button scanButton;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private int imageRotationDegrees = 0;
    private String selectedCameraId = null;

    private TextRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean processingFrame = false;
    private volatile boolean scanRequested = false;
    private int scanAttemptsRemaining = 0;
    private String bestText = "";
    private long lastOcrStartedAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        log("QuestReader Scan Demo 0.2 started");
        log("Device SDK=" + android.os.Build.VERSION.SDK_INT + " build=" + android.os.Build.DISPLAY);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageResult = tts.setLanguage(Locale.US);
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA
                        && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
                log("TTS initialized; languageResult=" + languageResult + "; ready=" + ttsReady);
                if (!ttsReady) {
                    runOnUiThread(() -> setStatus("Quest system TTS has no English voice. OCR can still be tested in this build."));
                }
            } else {
                log("TTS initialization FAILED status=" + status);
            }
        });

        startCameraThread();
        ensureCameraPermissions();
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(12, 13, 16));

        TextView title = new TextView(this);
        title.setText("QuestReader • Scan Test v0.2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Meta passthrough Camera2 → bundled on-device OCR. System TTS is tested if available.");
        subtitle.setTextColor(Color.rgb(190, 195, 205));
        subtitle.setTextSize(15f);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText("Starting…");
        statusView.setTextColor(Color.rgb(224, 230, 240));
        statusView.setTextSize(17f);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusView.setBackgroundColor(Color.rgb(30, 33, 40));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(8));

        scanButton = new Button(this);
        scanButton.setText("Scan Page");
        scanButton.setOnClickListener(v -> requestScan());
        buttons.addView(scanButton, new LinearLayout.LayoutParams(0, dp(58), 1f));

        Button stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(v -> {
            if (tts != null) tts.stop();
            scanRequested = false;
            scanButton.setEnabled(true);
            setStatus("Stopped.");
            log("Stop requested by user");
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(58), 1f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(stopButton, stopParams);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        textView = new TextView(this);
        textView.setText("Recognized page text will appear here.");
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(18f);
        textView.setLineSpacing(0f, 1.22f);
        textView.setPadding(dp(14), dp(14), dp(14), dp(14));
        textView.setBackgroundColor(Color.rgb(20, 22, 27));
        scroll.addView(textView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        logButtons.setPadding(0, dp(8), 0, 0);

        Button copyLog = new Button(this);
        copyLog.setText("Copy Test Log");
        copyLog.setOnClickListener(v -> copyLog());
        logButtons.addView(copyLog, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button shareLog = new Button(this);
        shareLog.setText("Share Test Log");
        shareLog.setOnClickListener(v -> shareLog());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        shareParams.setMargins(dp(8), 0, 0, 0);
        logButtons.addView(shareLog, shareParams);
        root.addView(logButtons);

        setContentView(root);
    }

    private boolean hasCameraPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(HEADSET_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureCameraPermissions() {
        int androidCamera = checkSelfPermission(Manifest.permission.CAMERA);
        int headsetCamera = checkSelfPermission(HEADSET_CAMERA_PERMISSION);
        log("Permission state CAMERA=" + androidCamera + " HEADSET_CAMERA=" + headsetCamera);
        if (hasCameraPermissions()) {
            openCameraIfNeeded();
        } else {
            log("Requesting CAMERA + HEADSET_CAMERA permissions");
            requestPermissions(new String[]{Manifest.permission.CAMERA, HEADSET_CAMERA_PERMISSION}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            for (int i = 0; i < permissions.length; i++) {
                log("Permission result " + permissions[i] + "=" + (i < grantResults.length ? grantResults[i] : 999));
            }
            if (hasCameraPermissions()) {
                openCameraIfNeeded();
            } else {
                setStatus("Both Quest camera permissions are required. Reopen the app and allow camera access.");
            }
        }
    }

    private void requestScan() {
        if (!hasCameraPermissions()) {
            ensureCameraPermissions();
            return;
        }
        bestText = "";
        scanAttemptsRemaining = MAX_SCAN_ATTEMPTS;
        scanRequested = true;
        lastOcrStartedAt = 0;
        scanButton.setEnabled(false);
        log("Scan requested; attempts=" + MAX_SCAN_ATTEMPTS + " camera=" + selectedCameraId);
        setStatus("Scanning for about 3 seconds… keep the page steady and centered.");
        openCameraIfNeeded();
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("QuestReaderCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private String selectPassthroughCamera() throws CameraAccessException {
        String left = null;
        String right = null;
        List<String> discovered = new ArrayList<>();

        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Integer source = null;
            Integer position = null;
            try { source = c.get(META_CAMERA_SOURCE); } catch (Exception e) { log("camera " + id + " source tag read failed: " + e); }
            try { position = c.get(META_CAMERA_POSITION); } catch (Exception e) { log("camera " + id + " position tag read failed: " + e); }
            discovered.add(id + "(facing=" + facing + ",source=" + source + ",position=" + position + ")");
            if (source != null && source == META_SOURCE_PASSTHROUGH) {
                if (position != null && position == META_POSITION_RIGHT) right = id;
                else if (position != null && position == META_POSITION_LEFT) left = id;
                else if (right == null) right = id;
            }
        }

        log("Camera configs: " + discovered);
        String selected = right != null ? right : left;
        if (selected != null) {
            log("Selected Meta passthrough RGB camera id=" + selected + (selected.equals(right) ? " (right)" : " (left)"));
            return selected;
        }
        log("ERROR: no Meta passthrough camera matched official integer vendor tags; refusing wrong-camera fallback");
        return null;
    }

    private Size chooseOcrSize(CameraCharacteristics c) throws CameraAccessException {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No stream map");
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No YUV output size");
        for (Size s : sizes) if (s.getWidth() == 1280 && s.getHeight() == 960) return s;
        Size best = sizes[0];
        long target = 1280L * 960L;
        long bestDistance = Math.abs((long) best.getWidth() * best.getHeight() - target);
        for (Size s : sizes) {
            long distance = Math.abs((long) s.getWidth() * s.getHeight() - target);
            if (distance < bestDistance) { best = s; bestDistance = distance; }
        }
        return best;
    }

    private int calculateMlKitRotation(CameraCharacteristics c) {
        Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        int sensor = sensorOrientation == null ? 0 : sensorOrientation;
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        int deviceDegrees = displayRotation == Surface.ROTATION_90 ? 90
                : displayRotation == Surface.ROTATION_180 ? 180
                : displayRotation == Surface.ROTATION_270 ? 270 : 0;
        int result;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            result = (sensor + deviceDegrees) % 360;
        } else {
            result = (sensor - deviceDegrees + 360) % 360;
        }
        log("Rotation sensor=" + sensor + " displayDegrees=" + deviceDegrees + " facing=" + facing + " -> MLKit=" + result);
        return result;
    }

    private void openCameraIfNeeded() {
        if (cameraDevice != null || cameraManager == null || cameraHandler == null) return;
        if (!hasCameraPermissions()) return;
        try {
            selectedCameraId = selectPassthroughCamera();
            if (selectedCameraId == null) {
                setStatus("Meta passthrough RGB camera was not identified. Share the test log.");
                return;
            }
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedCameraId);
            imageRotationDegrees = calculateMlKitRotation(c);
            Size size = chooseOcrSize(c);
            log("Opening passthrough camera=" + selectedCameraId + " size=" + size.getWidth() + "x" + size.getHeight());
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    log("Camera opened id=" + camera.getId());
                    createCaptureSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    log("Camera disconnected id=" + camera.getId());
                    camera.close(); cameraDevice = null;
                    runOnUiThread(() -> setStatus("Camera disconnected."));
                }
                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera ERROR id=" + camera.getId() + " code=" + error);
                    camera.close(); cameraDevice = null;
                    runOnUiThread(() -> setStatus("Camera error " + error + ". Share the test log."));
                }
            }, cameraHandler);
        } catch (Exception e) {
            log("openCamera exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            setStatus("Could not open Quest passthrough camera: " + e.getMessage());
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) return;
        try {
            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(imageReader.getSurface());
                        captureSession.setRepeatingRequest(b.build(), null, cameraHandler);
                        log("Passthrough capture session running");
                        runOnUiThread(() -> setStatus("Passthrough camera ready. Hold a printed page in view and press Scan Page."));
                    } catch (CameraAccessException e) {
                        log("Repeating request failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Camera capture session configuration FAILED");
                    runOnUiThread(() -> setStatus("Passthrough camera stream could not be configured."));
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            log("createCaptureSession failed: " + e.getMessage());
        }
    }

    private String lumaStats(Image image) {
        try {
            Image.Plane p = image.getPlanes()[0];
            ByteBuffer b = p.getBuffer().duplicate();
            int rowStride = p.getRowStride();
            int pixelStride = p.getPixelStride();
            int w = image.getWidth();
            int h = image.getHeight();
            long sum = 0;
            int count = 0;
            int min = 255;
            int max = 0;
            for (int y = 0; y < h; y += 24) {
                int row = y * rowStride;
                for (int x = 0; x < w; x += 24) {
                    int index = row + x * pixelStride;
                    if (index >= 0 && index < b.limit()) {
                        int v = b.get(index) & 0xFF;
                        sum += v; count++; if (v < min) min = v; if (v > max) max = v;
                    }
                }
            }
            return count == 0 ? "luma=unknown" : "lumaAvg=" + (sum / count) + " range=" + min + "-" + max;
        } catch (Exception e) {
            return "lumaError=" + e.getClass().getSimpleName();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (!scanRequested || processingFrame || (lastOcrStartedAt != 0 && now - lastOcrStartedAt < OCR_INTERVAL_MS)) {
            image.close();
            return;
        }

        processingFrame = true;
        lastOcrStartedAt = now;
        scanAttemptsRemaining--;
        String frameStats = lumaStats(image);
        InputImage input = InputImage.fromMediaImage(image, imageRotationDegrees);
        long started = System.currentTimeMillis();
        Task<Text> task = recognizer.process(input);
        task.addOnSuccessListener(result -> {
            String recognized = normalizeText(result.getText());
            long elapsed = System.currentTimeMillis() - started;
            log("OCR chars=" + recognized.length() + " blocks=" + result.getTextBlocks().size()
                    + " elapsedMs=" + elapsed + " " + frameStats + " remaining=" + scanAttemptsRemaining);
            if (recognized.length() > bestText.length()) bestText = recognized;
            if (recognized.length() >= 40) {
                finishScan(recognized, true);
            } else if (scanAttemptsRemaining <= 0) {
                finishScan(bestText, false);
            } else {
                runOnUiThread(() -> setStatus("Scanning… " + (MAX_SCAN_ATTEMPTS - scanAttemptsRemaining) + "/" + MAX_SCAN_ATTEMPTS + " frames checked."));
            }
        }).addOnFailureListener(e -> {
            log("OCR FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " " + frameStats);
            if (scanAttemptsRemaining <= 0) finishScan(bestText, false);
        }).addOnCompleteListener(done -> {
            image.close();
            processingFrame = false;
        });
    }

    private void finishScan(String text, boolean confident) {
        scanRequested = false;
        runOnUiThread(() -> {
            scanButton.setEnabled(true);
            if (text != null && !text.isBlank()) textView.setText(text);
            if (confident) {
                setStatus("Captured " + text.length() + " characters." + (ttsReady ? " Reading aloud…" : " System TTS unavailable on this Quest build."));
                if (ttsReady) speakText(text);
            } else {
                setStatus(text != null && !text.isBlank()
                        ? "Only a partial capture was found. The text is shown below; share the log."
                        : "No text detected. Share this v0.2 log so we can inspect camera/luma diagnostics.");
            }
        });
    }

    private String normalizeText(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00AD', ' ')
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
    }

    private void speakText(String text) {
        if (!ttsReady || tts == null) return;
        tts.stop();
        int max = Math.min(3000, TextToSpeech.getMaxSpeechInputLength() - 100);
        List<String> chunks = chunkForTts(text, max);
        log("TTS speaking chunks=" + chunks.size() + " totalChars=" + text.length());
        for (int i = 0; i < chunks.size(); i++) {
            tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, null, "questreader-demo-" + i);
        }
    }

    private List<String> chunkForTts(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.trim();
        while (remaining.length() > maxChars) {
            int split = remaining.lastIndexOf('\n', maxChars);
            if (split < maxChars / 2) split = remaining.lastIndexOf('.', maxChars);
            if (split < maxChars / 2) split = remaining.lastIndexOf(' ', maxChars);
            if (split < maxChars / 2) split = maxChars;
            int end = split < remaining.length() && remaining.charAt(split) == '.' ? split + 1 : split;
            chunks.add(remaining.substring(0, end).trim());
            remaining = remaining.substring(Math.max(1, split)).trim();
            if (remaining.startsWith(".")) remaining = remaining.substring(1).trim();
        }
        if (!remaining.isBlank()) chunks.add(remaining);
        return chunks;
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("QuestReader Scan Demo Log", buildShareLog()));
        setStatus("Test log copied to clipboard.");
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "QuestReader Scan Demo v0.2 Test Log");
        intent.putExtra(Intent.EXTRA_TEXT, buildShareLog());
        startActivity(Intent.createChooser(intent, "Share QuestReader test log"));
    }

    private String buildShareLog() {
        return "QuestReader Scan Demo v0.2 Test Log\n\n" + testLog + "\n\nLast recognized text:\n" + (textView == null ? "" : textView.getText());
    }

    private synchronized void log(String message) {
        String line = timeFormat.format(new Date()) + "  " + message;
        testLog.append(line).append('\n');
        android.util.Log.i("QuestReaderScanDemo", line);
    }

    private void setStatus(String text) { if (statusView != null) statusView.setText(text); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanRequested = false;
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) recognizer.close();
        closeCamera();
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }
    }

    private void closeCamera() {
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        captureSession = null;
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        cameraDevice = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
    }
}
