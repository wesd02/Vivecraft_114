package com.questhub.questreader.scandemo;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
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
import android.widget.ImageView;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 501;
    private static final int MAX_SCAN_ATTEMPTS = 12;
    private static final int STRONG_TEXT_CHARS = 500;
    private static final int STRONG_FRAMES_TO_FINISH = 3;
    private static final long MIN_FRAME_INTERVAL_MS = 180;

    private final StringBuilder testLog = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final List<Candidate> candidates = new ArrayList<>();

    private TextView statusView;
    private TextView metricsView;
    private TextView textView;
    private ImageView previewView;
    private Button scanButton;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private int imageRotationDegrees = 0;
    private String selectedCameraId = null;
    private Size selectedSize = null;

    private TextRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean processingFrame = false;
    private volatile boolean scanRequested = false;
    private int scanAttemptsRemaining = 0;
    private int strongFrames = 0;
    private long lastFrameStartedAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        log("QuestReader Scan Demo 0.3 started");
        log("Device SDK=" + android.os.Build.VERSION.SDK_INT + " build=" + android.os.Build.DISPLAY);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageResult = tts.setLanguage(Locale.US);
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA
                        && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
                log("TTS initialized; languageResult=" + languageResult + "; ready=" + ttsReady);
            } else {
                log("TTS initialization FAILED status=" + status);
            }
        });

        startCameraThread();
        ensureCameraPermission();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(12, 13, 16));

        TextView title = new TextView(this);
        title.setText("QuestReader • Scan Quality Test v0.3");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Starting camera diagnostics…");
        statusView.setTextColor(Color.rgb(232, 236, 244));
        statusView.setTextSize(17f);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackgroundColor(Color.rgb(30, 33, 40));
        root.addView(statusView);

        previewView = new ImageView(this);
        previewView.setAdjustViewBounds(true);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        previewParams.setMargins(0, dp(8), 0, dp(4));
        root.addView(previewView, previewParams);

        metricsView = new TextView(this);
        metricsView.setText("Camera preview appears here during a scan. This is approximately what OCR sees.");
        metricsView.setTextColor(Color.rgb(190, 198, 210));
        metricsView.setTextSize(14f);
        metricsView.setPadding(dp(4), dp(2), dp(4), dp(4));
        root.addView(metricsView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(6), 0, dp(6));

        scanButton = new Button(this);
        scanButton.setText("Scan Page (Best of 12)");
        scanButton.setOnClickListener(v -> requestScan());
        buttons.addView(scanButton, new LinearLayout.LayoutParams(0, dp(56), 1f));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> {
            scanRequested = false;
            scanButton.setEnabled(true);
            if (tts != null) tts.stop();
            setStatus("Stopped.");
            log("Stop requested by user");
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(56), 0.45f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(stop, stopParams);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        textView = new TextView(this);
        textView.setText("Best recognized page text will appear here after the multi-frame scan.");
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(17f);
        textView.setLineSpacing(0f, 1.18f);
        textView.setPadding(dp(12), dp(12), dp(12), dp(12));
        textView.setBackgroundColor(Color.rgb(20, 22, 27));
        scroll.addView(textView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.HORIZONTAL);
        logs.setPadding(0, dp(6), 0, 0);
        Button copy = new Button(this);
        copy.setText("Copy Test Log");
        copy.setOnClickListener(v -> copyLog());
        logs.addView(copy, new LinearLayout.LayoutParams(0, dp(50), 1f));
        Button share = new Button(this);
        share.setText("Share Test Log");
        share.setOnClickListener(v -> shareLog());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ensureCameraPermission();
            return;
        }
        candidates.clear();
        strongFrames = 0;
        scanAttemptsRemaining = MAX_SCAN_ATTEMPTS;
        scanRequested = true;
        lastFrameStartedAt = 0;
        scanButton.setEnabled(false);
        log("Scan requested; attempts=" + MAX_SCAN_ATTEMPTS + "; camera=" + selectedCameraId + "; size=" + selectedSize);
        setStatus("Scanning several frames… keep the whole page steady and make it fill the camera preview.");
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
        String left = null;
        String right = null;
        String anyMeta = null;
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

            if (source != null && source == 0) {
                if (anyMeta == null) anyMeta = id;
                if (position != null && position == 0) left = id;
                if (position != null && position == 1) right = id;
            }
        }

        log("Camera IDs decoded: " + discovered);
        String selected = left != null ? left : (right != null ? right : anyMeta);
        if (selected != null) {
            log("Selected Meta passthrough RGB camera id=" + selected + " left=" + left + " right=" + right);
            return selected;
        }
        log("ERROR: No Meta passthrough RGB camera found");
        return null;
    }

    private Size chooseOcrSize(CameraCharacteristics c) throws CameraAccessException {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No stream map");
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No YUV sizes");
        log("Available YUV sizes=" + Arrays.toString(sizes));
        for (Size s : sizes) if (s.getWidth() == 1280 && s.getHeight() == 1280) return s;
        for (Size s : sizes) if (s.getWidth() == 1280 && s.getHeight() == 960) return s;
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private void openCameraIfNeeded() {
        if (cameraDevice != null || cameraManager == null || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            selectedCameraId = selectPassthroughCamera();
            if (selectedCameraId == null) {
                setStatus("No Meta passthrough camera found. Share the test log.");
                return;
            }
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedCameraId);
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) imageRotationDegrees = orientation;
            selectedSize = chooseOcrSize(c);
            log("Opening camera=" + selectedCameraId + " size=" + selectedSize + " rotation=" + imageRotationDegrees);
            imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    log("Camera opened id=" + camera.getId());
                    createCaptureSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    log("Camera disconnected id=" + camera.getId());
                    camera.close();
                    cameraDevice = null;
                }
                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera ERROR id=" + camera.getId() + " code=" + error);
                    camera.close();
                    cameraDevice = null;
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
                        runOnUiThread(() -> setStatus("Camera ready at " + selectedSize + ". Press Scan Page, then fill the preview with the page."));
                    } catch (Exception e) {
                        log("Repeating request failed: " + e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Capture session configuration FAILED");
                }
            }, cameraHandler);
        } catch (Exception e) {
            log("createCaptureSession failed: " + e);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (!scanRequested || processingFrame || (lastFrameStartedAt > 0 && now - lastFrameStartedAt < MIN_FRAME_INTERVAL_MS)) {
            image.close();
            return;
        }

        processingFrame = true;
        lastFrameStartedAt = now;
        scanAttemptsRemaining--;

        FrameQuality frameQuality = measureFrame(image);
        Bitmap preview = makeGrayPreview(image, 360);
        InputImage input = InputImage.fromMediaImage(image, imageRotationDegrees);
        long started = System.currentTimeMillis();

        Task<Text> task = recognizer.process(input);
        task.addOnSuccessListener(result -> {
            String recognized = result.getText() == null ? "" : result.getText().trim();
            OcrGeometry geometry = measureGeometry(result);
            int blocks = result.getTextBlocks().size();
            int lines = geometry.lineCount;
            double baseScore = recognized.length()
                    + blocks * 10.0
                    + lines * 2.0
                    + Math.min(geometry.avgWordHeightPx, 30.0) * 4.0
                    + Math.min(frameQuality.edgeEnergy, 30.0) * 3.0;

            Candidate candidate = new Candidate(recognized, blocks, lines, geometry.avgWordHeightPx,
                    geometry.coverageWidth, geometry.coverageHeight, frameQuality, preview, baseScore);
            if (!recognized.isEmpty()) candidates.add(candidate);
            if (recognized.length() >= STRONG_TEXT_CHARS) strongFrames++;

            log(String.format(Locale.US,
                    "OCR attempt camera=%s chars=%d blocks=%d lines=%d wordPx=%.1f coverage=%.2fx%.2f mean=%.1f contrast=%.1f edge=%.1f score=%.1f elapsedMs=%d remaining=%d strong=%d",
                    selectedCameraId, recognized.length(), blocks, lines, geometry.avgWordHeightPx,
                    geometry.coverageWidth, geometry.coverageHeight, frameQuality.mean, frameQuality.stdDev,
                    frameQuality.edgeEnergy, baseScore, System.currentTimeMillis() - started,
                    scanAttemptsRemaining, strongFrames));

            String guidance = guidance(candidate);
            runOnUiThread(() -> {
                if (preview != null) previewView.setImageBitmap(preview);
                metricsView.setText(String.format(Locale.US,
                        "Live sample: %d chars • word height %.0f px • sharpness %.1f • %s",
                        recognized.length(), geometry.avgWordHeightPx, frameQuality.edgeEnergy, guidance));
                setStatus("Collecting best frames… " + (MAX_SCAN_ATTEMPTS - scanAttemptsRemaining) + "/" + MAX_SCAN_ATTEMPTS
                        + " • strong frames " + strongFrames + "/" + STRONG_FRAMES_TO_FINISH);
            });

            if (strongFrames >= STRONG_FRAMES_TO_FINISH || scanAttemptsRemaining <= 0) {
                finishScan();
            }
        }).addOnFailureListener(e -> {
            log("OCR FAILED: " + e);
            if (scanAttemptsRemaining <= 0) finishScan();
        }).addOnCompleteListener(done -> {
            image.close();
            processingFrame = false;
        });
    }

    private void finishScan() {
        if (!scanRequested) return;
        scanRequested = false;
        Candidate best = chooseConsensusCandidate();
        runOnUiThread(() -> {
            scanButton.setEnabled(true);
            if (best == null) {
                setStatus("No readable text found. Use the camera preview to make the page fill more of the frame, then retry.");
                metricsView.setText("No OCR candidates. Try closer distance, flatter page, brighter/even lighting, and hold still.");
                return;
            }
            textView.setText(best.text);
            if (best.preview != null) previewView.setImageBitmap(best.preview);
            String guidance = guidance(best);
            metricsView.setText(String.format(Locale.US,
                    "BEST: %d chars • %d blocks • %d lines • word height %.0f px • sharpness %.1f • page coverage %.0f%%×%.0f%% • %s",
                    best.text.length(), best.blocks, best.lines, best.avgWordHeightPx, best.quality.edgeEnergy,
                    best.coverageWidth * 100.0, best.coverageHeight * 100.0, guidance));
            setStatus("Best of " + candidates.size() + " readable frames selected. " + guidance);
            if (ttsReady && best.text.length() > 0) {
                tts.speak(best.text, TextToSpeech.QUEUE_FLUSH, null, "questreader-v03");
            }
        });
        if (best != null) {
            log(String.format(Locale.US,
                    "FINAL best chars=%d blocks=%d lines=%d wordPx=%.1f coverage=%.2fx%.2f mean=%.1f contrast=%.1f edge=%.1f baseScore=%.1f consensusScore=%.1f guidance=%s",
                    best.text.length(), best.blocks, best.lines, best.avgWordHeightPx,
                    best.coverageWidth, best.coverageHeight, best.quality.mean, best.quality.stdDev,
                    best.quality.edgeEnergy, best.baseScore, best.consensusScore, guidance(best)));
        }
    }

    private Candidate chooseConsensusCandidate() {
        if (candidates.isEmpty()) return null;
        Candidate best = null;
        double bestScore = -1;
        for (Candidate a : candidates) {
            double similaritySum = 0;
            int compared = 0;
            for (Candidate b : candidates) {
                if (a == b || b.text.length() < 100) continue;
                similaritySum += jaccardWords(a.text, b.text);
                compared++;
            }
            double consensus = compared == 0 ? 0 : similaritySum / compared;
            a.consensusScore = a.baseScore + consensus * 450.0;
            if (a.consensusScore > bestScore) {
                bestScore = a.consensusScore;
                best = a;
            }
        }
        return best;
    }

    private double jaccardWords(String a, String b) {
        Set<String> sa = wordSet(a);
        Set<String> sb = wordSet(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        int intersection = 0;
        for (String word : sa) if (sb.contains(word)) intersection++;
        int union = sa.size() + sb.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private Set<String> wordSet(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}']+")) {
            if (w.length() >= 2) out.add(w);
        }
        return out;
    }

    private String guidance(Candidate c) {
        if (c == null) return "Try again";
        if (c.text.length() < 200) return "Move closer / keep more of the page inside the OCR camera view";
        if (c.avgWordHeightPx > 0 && c.avgWordHeightPx < 14) return "Move the book closer; the printed characters are too small for reliable OCR";
        if (c.quality.mean < 55) return "Add more even light; the page is too dark";
        if (c.quality.mean > 220) return "Reduce glare / direct light on the page";
        if (c.quality.stdDev < 22) return "Increase contrast or lighting; the page looks washed out";
        if (c.quality.edgeEnergy < 7) return "Hold the book/headset steadier; the image looks soft";
        if (c.coverageWidth > 0 && c.coverageWidth < 0.45) return "Move closer; text occupies too little of the camera width";
        return "Good capture geometry — remaining errors are OCR/model cleanup work";
    }

    private FrameQuality measureFrame(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int step = 8;
        long count = 0;
        double sum = 0;
        double sumSq = 0;
        double edgeSum = 0;
        long edgeCount = 0;
        for (int y = 0; y < height - step; y += step) {
            for (int x = 0; x < width - step; x += step) {
                int v = yValue(buffer, rowStride, pixelStride, x, y);
                int vr = yValue(buffer, rowStride, pixelStride, x + step, y);
                int vd = yValue(buffer, rowStride, pixelStride, x, y + step);
                sum += v;
                sumSq += v * v;
                count++;
                edgeSum += Math.abs(v - vr) + Math.abs(v - vd);
                edgeCount += 2;
            }
        }
        double mean = count == 0 ? 0 : sum / count;
        double variance = count == 0 ? 0 : Math.max(0, sumSq / count - mean * mean);
        double stdDev = Math.sqrt(variance);
        double edge = edgeCount == 0 ? 0 : edgeSum / edgeCount;
        return new FrameQuality(mean, stdDev, edge);
    }

    private int yValue(ByteBuffer buffer, int rowStride, int pixelStride, int x, int y) {
        int index = y * rowStride + x * pixelStride;
        if (index < 0 || index >= buffer.limit()) return 0;
        return buffer.get(index) & 0xFF;
    }

    private Bitmap makeGrayPreview(Image image, int targetWidth) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int srcW = image.getWidth();
            int srcH = image.getHeight();
            int dstW = Math.min(targetWidth, srcW);
            int dstH = Math.max(1, Math.round((float) srcH * dstW / srcW));
            int[] pixels = new int[dstW * dstH];
            for (int y = 0; y < dstH; y++) {
                int sy = y * srcH / dstH;
                for (int x = 0; x < dstW; x++) {
                    int sx = x * srcW / dstW;
                    int v = yValue(buffer, rowStride, pixelStride, sx, sy);
                    pixels[y * dstW + x] = Color.rgb(v, v, v);
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH);
            return bitmap;
        } catch (Exception e) {
            log("Preview generation failed: " + e);
            return null;
        }
    }

    private OcrGeometry measureGeometry(Text result) {
        int lineCount = 0;
        double wordHeightSum = 0;
        int wordCount = 0;
        Rect union = null;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lineCount++;
                for (Text.Element element : line.getElements()) {
                    Rect r = element.getBoundingBox();
                    if (r == null) continue;
                    wordHeightSum += r.height();
                    wordCount++;
                    if (union == null) union = new Rect(r);
                    else union.union(r);
                }
            }
        }
        double avgWordHeight = wordCount == 0 ? 0 : wordHeightSum / wordCount;
        double coverageW = 0;
        double coverageH = 0;
        if (union != null && selectedSize != null) {
            coverageW = Math.min(1.0, (double) union.width() / selectedSize.getWidth());
            coverageH = Math.min(1.0, (double) union.height() / selectedSize.getHeight());
        }
        return new OcrGeometry(lineCount, avgWordHeight, coverageW, coverageH);
    }

    private void copyLog() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("QuestReader Scan Demo Log", buildShareLog()));
        setStatus("Test log copied.");
    }

    private void shareLog() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "QuestReader Scan Demo v0.3 Test Log");
        i.putExtra(Intent.EXTRA_TEXT, buildShareLog());
        startActivity(Intent.createChooser(i, "Share QuestReader test log"));
    }

    private String buildShareLog() {
        return "QuestReader Scan Demo v0.3 Test Log\n\n" + testLog
                + "\n\nLast recognized text:\n" + (textView == null ? "" : textView.getText());
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
        if (cameraThread != null) cameraThread.quitSafely();
    }

    private void closeCamera() {
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        captureSession = null;
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        cameraDevice = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
    }

    private static class FrameQuality {
        final double mean;
        final double stdDev;
        final double edgeEnergy;
        FrameQuality(double mean, double stdDev, double edgeEnergy) {
            this.mean = mean;
            this.stdDev = stdDev;
            this.edgeEnergy = edgeEnergy;
        }
    }

    private static class OcrGeometry {
        final int lineCount;
        final double avgWordHeightPx;
        final double coverageWidth;
        final double coverageHeight;
        OcrGeometry(int lineCount, double avgWordHeightPx, double coverageWidth, double coverageHeight) {
            this.lineCount = lineCount;
            this.avgWordHeightPx = avgWordHeightPx;
            this.coverageWidth = coverageWidth;
            this.coverageHeight = coverageHeight;
        }
    }

    private static class Candidate {
        final String text;
        final int blocks;
        final int lines;
        final double avgWordHeightPx;
        final double coverageWidth;
        final double coverageHeight;
        final FrameQuality quality;
        final Bitmap preview;
        final double baseScore;
        double consensusScore;
        Candidate(String text, int blocks, int lines, double avgWordHeightPx,
                  double coverageWidth, double coverageHeight, FrameQuality quality,
                  Bitmap preview, double baseScore) {
            this.text = text;
            this.blocks = blocks;
            this.lines = lines;
            this.avgWordHeightPx = avgWordHeightPx;
            this.coverageWidth = coverageWidth;
            this.coverageHeight = coverageHeight;
            this.quality = quality;
            this.preview = preview;
            this.baseScore = baseScore;
        }
    }
}
