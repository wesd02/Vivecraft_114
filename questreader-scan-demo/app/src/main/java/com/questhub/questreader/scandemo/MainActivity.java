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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 501;
    private static final int MAX_SCAN_ATTEMPTS = 5;

    private final StringBuilder testLog = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView statusView;
    private TextView textView;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private int imageRotationDegrees = 0;

    private TextRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean processingFrame = false;
    private volatile boolean scanRequested = false;
    private int scanAttemptsRemaining = 0;
    private String bestText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        log("QuestReader Scan Demo 0.1 started");
        log("Device SDK=" + android.os.Build.VERSION.SDK_INT + " build=" + android.os.Build.DISPLAY);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageResult = tts.setLanguage(Locale.US);
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA
                        && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
                log("TTS initialized; languageResult=" + languageResult + "; ready=" + ttsReady);
                runOnUiThread(() -> setStatus(ttsReady
                        ? "Ready. Hold a book page in front of you and press Scan & Read."
                        : "Camera/OCR can still be tested, but the headset has no usable English system TTS voice."));
            } else {
                ttsReady = false;
                log("TTS initialization FAILED status=" + status);
                runOnUiThread(() -> setStatus("TTS did not initialize. Camera/OCR can still be tested."));
            }
        });

        startCameraThread();
        ensureCameraPermission();
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(12, 13, 16));

        TextView title = new TextView(this);
        title.setText("QuestReader • Scan Test");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Quest 3 Camera2 → bundled on-device OCR → free system TTS");
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

        Button scanButton = new Button(this);
        scanButton.setText("Scan & Read");
        scanButton.setOnClickListener(v -> requestScan());
        buttons.addView(scanButton, new LinearLayout.LayoutParams(0, dp(58), 1f));

        Button stopButton = new Button(this);
        stopButton.setText("Stop Speech");
        stopButton.setOnClickListener(v -> {
            if (tts != null) tts.stop();
            scanRequested = false;
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

    private void ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCameraIfNeeded();
        } else {
            log("Requesting CAMERA permission");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("CAMERA permission granted");
                openCameraIfNeeded();
            } else {
                log("CAMERA permission denied");
                setStatus("Camera permission is required for this scan test.");
            }
        }
    }

    private void requestScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ensureCameraPermission();
            return;
        }
        bestText = "";
        scanAttemptsRemaining = MAX_SCAN_ATTEMPTS;
        scanRequested = true;
        log("Scan requested; attempts=" + MAX_SCAN_ATTEMPTS);
        setStatus("Scanning… hold the page steady and centered in front of you.");
        openCameraIfNeeded();
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("QuestReaderCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String selectPassthroughCamera() throws CameraAccessException {
        String fallback = null;
        List<String> discovered = new ArrayList<>();
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            discovered.add(id + "(facing=" + facing + ")");
            if (fallback == null) fallback = id;
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                if ("com.meta.extra_metadata.camera_source".equals(key.getName())) {
                    Object value = c.get((CameraCharacteristics.Key) key);
                    log("camera " + id + " vendor camera_source=" + value);
                    if (value instanceof Number && ((Number) value).intValue() == 0) {
                        log("Selected Meta passthrough RGB camera id=" + id);
                        return id;
                    }
                }
            }
        }
        log("Camera IDs discovered: " + discovered);
        log("Meta vendor tag match not found; falling back to camera id=" + fallback);
        return fallback;
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
            if (distance < bestDistance) {
                best = s;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void openCameraIfNeeded() {
        if (cameraDevice != null || cameraManager == null || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            String cameraId = selectPassthroughCamera();
            if (cameraId == null) {
                setStatus("No camera was exposed to the app.");
                log("ERROR: no camera IDs available");
                return;
            }
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null && (orientation == 0 || orientation == 90 || orientation == 180 || orientation == 270)) {
                imageRotationDegrees = orientation;
            }
            Size size = chooseOcrSize(c);
            log("Opening camera=" + cameraId + " size=" + size.getWidth() + "x" + size.getHeight() + " rotation=" + imageRotationDegrees);
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    log("Camera opened id=" + camera.getId());
                    createCaptureSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    log("Camera disconnected id=" + camera.getId());
                    camera.close();
                    cameraDevice = null;
                    runOnUiThread(() -> setStatus("Camera disconnected."));
                }
                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera ERROR id=" + camera.getId() + " code=" + error);
                    camera.close();
                    cameraDevice = null;
                    runOnUiThread(() -> setStatus("Camera error " + error + ". Share the test log."));
                }
            }, cameraHandler);
        } catch (Exception e) {
            log("openCamera exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            setStatus("Could not open Quest camera: " + e.getMessage());
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
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        captureSession.setRepeatingRequest(b.build(), null, cameraHandler);
                        log("Camera capture session running");
                        runOnUiThread(() -> setStatus("Camera ready. Hold a printed page in view and press Scan & Read."));
                    } catch (CameraAccessException e) {
                        log("Repeating request failed: " + e.getMessage());
                        runOnUiThread(() -> setStatus("Camera stream failed: " + e.getMessage()));
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Camera capture session configuration FAILED");
                    runOnUiThread(() -> setStatus("Camera stream could not be configured."));
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            log("createCaptureSession failed: " + e.getMessage());
            setStatus("Camera session failed: " + e.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        if (!scanRequested || processingFrame) {
            image.close();
            return;
        }
        processingFrame = true;
        scanAttemptsRemaining--;
        InputImage input = InputImage.fromMediaImage(image, imageRotationDegrees);
        long started = System.currentTimeMillis();
        Task<Text> task = recognizer.process(input);
        task.addOnSuccessListener(result -> {
            String recognized = normalizeText(result.getText());
            long elapsed = System.currentTimeMillis() - started;
            log("OCR attempt chars=" + recognized.length() + " blocks=" + result.getTextBlocks().size() + " elapsedMs=" + elapsed + " remainingAttempts=" + scanAttemptsRemaining);
            if (recognized.length() > bestText.length()) bestText = recognized;
            if (recognized.length() >= 40) {
                scanRequested = false;
                runOnUiThread(() -> {
                    textView.setText(recognized);
                    setStatus("Captured " + recognized.length() + " characters. Reading aloud…");
                    speakText(recognized);
                });
            } else if (scanAttemptsRemaining <= 0) {
                scanRequested = false;
                String finalBest = bestText;
                runOnUiThread(() -> {
                    if (finalBest.length() > 0) textView.setText(finalBest);
                    setStatus("No confident page capture. Try better lighting, closer distance, and scan again.");
                });
            } else {
                runOnUiThread(() -> setStatus("Still looking for clear text… keep the page steady."));
            }
        }).addOnFailureListener(e -> {
            log("OCR FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (scanAttemptsRemaining <= 0) {
                scanRequested = false;
                runOnUiThread(() -> setStatus("OCR failed. Share the test log."));
            }
        }).addOnCompleteListener(done -> {
            image.close();
            processingFrame = false;
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
        if (!ttsReady || tts == null) {
            log("TTS skipped: engine not ready");
            setStatus("OCR succeeded, but system TTS is unavailable. The recognized text is shown above.");
            return;
        }
        tts.stop();
        int max = Math.min(3000, TextToSpeech.getMaxSpeechInputLength() - 100);
        List<String> chunks = chunkForTts(text, max);
        log("TTS speaking chunks=" + chunks.size() + " totalChars=" + text.length());
        for (int i = 0; i < chunks.size(); i++) {
            int r = tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, null, "questreader-demo-" + i);
            log("TTS enqueue chunk=" + i + " chars=" + chunks.get(i).length() + " result=" + r);
        }
        setStatus("Reading captured page aloud. Press Scan & Read again for another page.");
    }

    private List<String> chunkForTts(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.trim();
        while (remaining.length() > maxChars) {
            int split = remaining.lastIndexOf('\n', maxChars);
            if (split < maxChars / 2) split = remaining.lastIndexOf('.', maxChars);
            if (split < maxChars / 2) split = remaining.lastIndexOf(' ', maxChars);
            if (split < maxChars / 2) split = maxChars;
            int end = split;
            if (split < remaining.length() && remaining.charAt(split) == '.') end = split + 1;
            chunks.add(remaining.substring(0, end).trim());
            remaining = remaining.substring(split).trim();
            if (remaining.startsWith(".")) remaining = remaining.substring(1).trim();
        }
        if (remaining.length() > 0) chunks.add(remaining);
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
        intent.putExtra(Intent.EXTRA_SUBJECT, "QuestReader Scan Demo Test Log");
        intent.putExtra(Intent.EXTRA_TEXT, buildShareLog());
        startActivity(Intent.createChooser(intent, "Share QuestReader test log"));
    }

    private String buildShareLog() {
        return "QuestReader Scan Demo Test Log\n\n" + testLog + "\n\nLast recognized text:\n" + (textView == null ? "" : textView.getText());
    }

    private synchronized void log(String message) {
        String line = timeFormat.format(new Date()) + "  " + message;
        testLog.append(line).append('\n');
        android.util.Log.i("QuestReaderScanDemo", line);
    }

    private void setStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanRequested = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (recognizer != null) recognizer.close();
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
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
