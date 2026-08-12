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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 501;
    private static final int MAX_SCAN_ATTEMPTS = 8;

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
    private String selectedCameraId = null;

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
                runOnUiThread(() -> {
                    if (!ttsReady) {
                        setStatus("Camera/OCR test ready. Horizon system TTS has no English voice, so v0.2 will show recognized text instead.");
                    }
                });
            } else {
                log("TTS initialization FAILED status=" + status);
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
        title.setText("QuestReader • Scan Test v0.2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Starting camera diagnostics…");
        statusView.setTextColor(Color.rgb(224, 230, 240));
        statusView.setTextSize(17f);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusView.setBackgroundColor(Color.rgb(30, 33, 40));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(8));

        Button scan = new Button(this);
        scan.setText("Scan & Read");
        scan.setOnClickListener(v -> requestScan());
        buttons.addView(scan, new LinearLayout.LayoutParams(0, dp(58), 1f));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> {
            scanRequested = false;
            if (tts != null) tts.stop();
            setStatus("Stopped.");
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(58), 1f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(stop, stopParams);
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

        LinearLayout logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("Copy Test Log");
        copy.setOnClickListener(v -> copyLog());
        logs.addView(copy, new LinearLayout.LayoutParams(0, dp(52), 1f));
        Button share = new Button(this);
        share.setText("Share Test Log");
        share.setOnClickListener(v -> shareLog());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        shareParams.setMargins(dp(8), 0, 0, 0);
        logs.addView(share, shareParams);
        root.addView(logs);
        setContentView(root);
    }

    private void ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCameraIfNeeded();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            log("CAMERA permission granted");
            openCameraIfNeeded();
        }
    }

    private void requestScan() {
        bestText = "";
        scanAttemptsRemaining = MAX_SCAN_ATTEMPTS;
        scanRequested = true;
        log("Scan requested; attempts=" + MAX_SCAN_ATTEMPTS + "; camera=" + selectedCameraId);
        setStatus("Scanning… hold a printed page steady and centered.");
        openCameraIfNeeded();
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("QuestReaderCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private static Integer vendorInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > 0) return bytes[0] & 0xFF;
        }
        if (value instanceof int[]) {
            int[] ints = (int[]) value;
            if (ints.length > 0) return ints[0];
        }
        return null;
    }

    private static String vendorString(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof int[]) return Arrays.toString((int[]) value);
        return String.valueOf(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String selectPassthroughCamera() throws CameraAccessException {
        String metaCandidate = null;
        String backFacingCandidate = null;
        List<String> discovered = new ArrayList<>();

        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Integer source = null;
            Integer position = null;
            Object sourceRaw = null;
            Object positionRaw = null;

            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                if ("com.meta.extra_metadata.camera_source".equals(key.getName())) {
                    sourceRaw = c.get((CameraCharacteristics.Key) key);
                    source = vendorInt(sourceRaw);
                }
                if ("com.meta.extra_metadata.position".equals(key.getName())) {
                    positionRaw = c.get((CameraCharacteristics.Key) key);
                    position = vendorInt(positionRaw);
                }
            }

            log("camera " + id + " facing=" + facing
                    + " camera_source=" + vendorString(sourceRaw) + " decoded=" + source
                    + " position=" + vendorString(positionRaw) + " decoded=" + position);
            discovered.add(id + "(facing=" + facing + ",source=" + source + ",position=" + position + ")");

            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && backFacingCandidate == null) {
                backFacingCandidate = id;
            }
            if (source != null && source == 0 && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                if (metaCandidate == null || (position != null && position == 0)) {
                    metaCandidate = id;
                    if (position != null && position == 0) break;
                }
            }
        }

        log("Camera IDs decoded: " + discovered);
        if (metaCandidate != null) {
            log("Selected Meta passthrough RGB camera id=" + metaCandidate);
            return metaCandidate;
        }
        if (backFacingCandidate != null) {
            log("WARNING: Meta source tag not decoded; selecting back-facing camera id=" + backFacingCandidate);
            return backFacingCandidate;
        }
        log("ERROR: No passthrough/back-facing camera candidate found");
        return null;
    }

    private Size chooseOcrSize(CameraCharacteristics c) throws CameraAccessException {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No stream map");
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No YUV sizes");
        for (Size s : sizes) if (s.getWidth() == 1280 && s.getHeight() == 960) return s;
        return sizes[0];
    }

    private void openCameraIfNeeded() {
        if (cameraDevice != null || cameraManager == null || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            selectedCameraId = selectPassthroughCamera();
            if (selectedCameraId == null) {
                setStatus("No passthrough camera candidate found. Share the test log.");
                return;
            }
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedCameraId);
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) imageRotationDegrees = orientation;
            Size size = chooseOcrSize(c);
            log("Opening camera=" + selectedCameraId + " size=" + size + " rotation=" + imageRotationDegrees);
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
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
                }
                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera ERROR id=" + camera.getId() + " code=" + error);
                    camera.close(); cameraDevice = null;
                    runOnUiThread(() -> setStatus("Camera error " + error + ". Share the log."));
                }
            }, cameraHandler);
        } catch (Exception e) {
            log("openCamera exception: " + e);
            setStatus("Could not open passthrough camera: " + e.getMessage());
        }
    }

    private void createCaptureSession() {
        try {
            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(imageReader.getSurface());
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        captureSession.setRepeatingRequest(b.build(), null, cameraHandler);
                        log("Camera capture session running on id=" + selectedCameraId);
                        runOnUiThread(() -> setStatus("Passthrough camera ready: " + selectedCameraId + ". Hold a page in view and press Scan & Read."));
                    } catch (Exception e) { log("Repeating request failed: " + e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Capture session configuration FAILED");
                }
            }, cameraHandler);
        } catch (Exception e) { log("createCaptureSession failed: " + e); }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        if (!scanRequested || processingFrame) { image.close(); return; }

        processingFrame = true;
        scanAttemptsRemaining--;
        InputImage input = InputImage.fromMediaImage(image, imageRotationDegrees);
        long started = System.currentTimeMillis();
        Task<Text> task = recognizer.process(input);
        task.addOnSuccessListener(result -> {
            String recognized = result.getText() == null ? "" : result.getText().trim();
            log("OCR attempt camera=" + selectedCameraId + " chars=" + recognized.length()
                    + " blocks=" + result.getTextBlocks().size() + " elapsedMs=" + (System.currentTimeMillis() - started)
                    + " remaining=" + scanAttemptsRemaining);
            if (recognized.length() > bestText.length()) bestText = recognized;
            if (recognized.length() >= 30) {
                scanRequested = false;
                runOnUiThread(() -> {
                    textView.setText(recognized);
                    if (ttsReady) {
                        tts.speak(recognized, TextToSpeech.QUEUE_FLUSH, null, "questreader-v02");
                        setStatus("OCR SUCCESS: " + recognized.length() + " characters. Reading aloud.");
                    } else {
                        setStatus("OCR SUCCESS: " + recognized.length() + " characters. System TTS unavailable on this Quest.");
                    }
                });
            } else if (scanAttemptsRemaining <= 0) {
                scanRequested = false;
                String best = bestText;
                runOnUiThread(() -> {
                    if (!best.isEmpty()) textView.setText(best);
                    setStatus("No confident text yet. Share the log if this persists.");
                });
            }
        }).addOnFailureListener(e -> log("OCR FAILED: " + e))
          .addOnCompleteListener(done -> { image.close(); processingFrame = false; });
    }

    private void copyLog() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("QuestReader Scan Demo Log", buildShareLog()));
        setStatus("Test log copied.");
    }

    private void shareLog() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "QuestReader Scan Demo v0.2 Test Log");
        i.putExtra(Intent.EXTRA_TEXT, buildShareLog());
        startActivity(Intent.createChooser(i, "Share QuestReader test log"));
    }

    private String buildShareLog() {
        return "QuestReader Scan Demo v0.2 Test Log\n\n" + testLog + "\nLast recognized text:\n" + textView.getText();
    }

    private synchronized void log(String message) {
        String line = timeFormat.format(new Date()) + "  " + message;
        testLog.append(line).append('\n');
        android.util.Log.i("QuestReaderScanDemo", line);
    }

    private void setStatus(String text) { if (statusView != null) statusView.setText(text); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) recognizer.close();
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        if (cameraThread != null) cameraThread.quitSafely();
    }
}
