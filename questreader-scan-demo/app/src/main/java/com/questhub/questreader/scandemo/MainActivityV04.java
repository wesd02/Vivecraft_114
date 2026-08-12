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
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * QuestReader v0.4 hardware recorder.
 *
 * This build intentionally captures much more evidence than production will.
 * Every keyframe sent to OCR is paired with a full-resolution luminance JPEG,
 * raw OCR hierarchy, image metrics, and Camera2 capture metadata, then queued to
 * QuestHub for Drive archival. Continuous capture stops only when the user says
 * so; it does not pretend the first plausible OCR frame is final truth.
 */
public class MainActivityV04 extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 504;
    private static final long OCR_KEYFRAME_INTERVAL_MS = 650;
    private static final int JPEG_QUALITY = 91;

    private final StringBuilder testLog = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView statusView;
    private TextView uploadView;
    private TextView metricsView;
    private TextView textView;
    private ImageView previewView;
    private Button captureButton;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private TextRecognizer recognizer;
    private DiagnosticUploader uploader;

    private volatile boolean processingFrame = false;
    private volatile boolean captureActive = false;
    private volatile boolean cameraReady = false;
    private volatile boolean uploadReady = false;
    private volatile long lastOcrStartedAt = 0;
    private volatile JSONObject latestCaptureMetadata = new JSONObject();

    private String selectedCameraId = "";
    private String leftCameraId = "";
    private String rightCameraId = "";
    private Size selectedSize;
    private int imageRotationDegrees = 0;
    private int frameIndex = 0;
    private int readableFrames = 0;
    private int zeroTextFrames = 0;
    private Candidate bestCandidate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        log("QuestReader Scan Demo 0.4 started");
        log("Device SDK=" + Build.VERSION.SDK_INT + " build=" + Build.DISPLAY + " model=" + Build.MODEL);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        uploader = new DiagnosticUploader(
                this,
                this::log,
                message -> runOnUiThread(() -> uploadView.setText(message)),
                ready -> runOnUiThread(() -> {
                    uploadReady = ready;
                    updateCaptureButton();
                }));
        uploader.startPairing();
        startCameraThread();
        ensureCameraPermission();
    }

    private void buildUi() {
        int pad = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(11, 12, 15));

        TextView title = new TextView(this);
        title.setText("QuestReader • Continuous OCR Recorder v0.4");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        statusView = makeLabel("Starting camera…", 17f, Color.rgb(236, 239, 246));
        statusView.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusView.setBackgroundColor(Color.rgb(29, 32, 39));
        root.addView(statusView);

        uploadView = makeLabel("Drive diagnostics: pairing with QuestHub…", 14f, Color.rgb(206, 215, 230));
        uploadView.setPadding(dp(8), dp(6), dp(8), dp(6));
        root.addView(uploadView);

        TextView privacy = makeLabel(
                "DEMO DIAGNOSTICS ON: every OCR keyframe image + raw recognized text is uploaded to your linked Google Drive test folder.",
                13f, Color.rgb(255, 220, 160));
        privacy.setPadding(dp(8), dp(2), dp(8), dp(6));
        root.addView(privacy);

        previewView = new ImageView(this);
        previewView.setAdjustViewBounds(true);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(185));
        root.addView(previewView, previewParams);

        metricsView = makeLabel("Waiting for OCR keyframes…", 14f, Color.rgb(190, 198, 210));
        metricsView.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(metricsView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(4), 0, dp(5));

        captureButton = new Button(this);
        captureButton.setText("Waiting…");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> toggleCapture());
        buttons.addView(captureButton, new LinearLayout.LayoutParams(0, dp(54), 1.2f));

        Button finishButton = new Button(this);
        finishButton.setText("Finish & Sync");
        finishButton.setOnClickListener(v -> finishDiagnosticSession());
        LinearLayout.LayoutParams finishParams = new LinearLayout.LayoutParams(0, dp(54), 0.75f);
        finishParams.setMargins(dp(6), 0, 0, 0);
        buttons.addView(finishButton, finishParams);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        textView = makeLabel("Best raw OCR text seen so far will appear here. Nothing is language-model corrected in this demo.", 16f, Color.WHITE);
        textView.setLineSpacing(0f, 1.16f);
        textView.setPadding(dp(10), dp(10), dp(10), dp(10));
        textView.setBackgroundColor(Color.rgb(19, 21, 26));
        scroll.addView(textView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("Copy Log");
        copy.setOnClickListener(v -> copyLog());
        logs.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button share = new Button(this);
        share.setText("Share Log");
        share.setOnClickListener(v -> shareLog());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        shareParams.setMargins(dp(6), 0, 0, 0);
        logs.addView(share, shareParams);
        root.addView(logs);

        setContentView(root);
    }

    private TextView makeLabel(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void toggleCapture() {
        if (captureActive) {
            captureActive = false;
            setStatus("Capture paused. Frames already queued continue uploading to Drive.");
        } else {
            if (!cameraReady || !uploadReady) {
                setStatus("Camera and Drive diagnostics must both be ready before capture starts.");
                return;
            }
            captureActive = true;
            lastOcrStartedAt = 0;
            setStatus("Continuous OCR capture ACTIVE. Hold/read the page naturally; press Stop Capture when done.");
            log("Continuous capture STARTED");
        }
        updateCaptureButton();
    }

    private void finishDiagnosticSession() {
        captureActive = false;
        updateCaptureButton();
        JSONObject finalResult = new JSONObject();
        JSONObject summary = new JSONObject();
        try {
            finalResult.put("text", bestCandidate == null ? "" : bestCandidate.text);
            finalResult.put("best_frame_index", bestCandidate == null ? 0 : bestCandidate.frameIndex);
            finalResult.put("best_score", bestCandidate == null ? 0 : bestCandidate.score);
            summary.put("ocr_frames", frameIndex);
            summary.put("readable_frames", readableFrames);
            summary.put("zero_text_frames", zeroTextFrames);
            summary.put("queued_uploads", uploader.queuedCount());
            summary.put("uploaded", uploader.uploadedCount());
            summary.put("failed", uploader.failedCount());
        } catch (Exception ignored) { }
        uploader.finish(finalResult, summary);
        log("Finish & Sync requested frames=" + frameIndex + " readable=" + readableFrames + " zeroText=" + zeroTextFrames);
        setStatus("Capture stopped. Finishing Drive manifest after queued frame uploads.");
    }

    private void updateCaptureButton() {
        if (captureActive) {
            captureButton.setEnabled(true);
            captureButton.setText("Stop Capture");
        } else {
            boolean enabled = cameraReady && uploadReady;
            captureButton.setEnabled(enabled);
            captureButton.setText(enabled ? "Start Continuous Capture" : "Waiting for Camera + Drive…");
        }
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
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            setStatus("Camera permission denied.");
            log("CAMERA permission denied");
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("QuestReaderCameraV04");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private static Integer vendorInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof byte[] && ((byte[]) value).length > 0) return ((byte[]) value)[0] & 0xFF;
        if (value instanceof int[] && ((int[]) value).length > 0) return ((int[]) value)[0];
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
        List<String> configs = new ArrayList<>();
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Object sourceRaw = null;
            Object positionRaw = null;
            Integer source = null;
            Integer position = null;
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                if ("com.meta.extra_metadata.camera_source".equals(key.getName())) {
                    sourceRaw = c.get((CameraCharacteristics.Key) key);
                    source = vendorInt(sourceRaw);
                } else if ("com.meta.extra_metadata.position".equals(key.getName())) {
                    positionRaw = c.get((CameraCharacteristics.Key) key);
                    position = vendorInt(positionRaw);
                }
            }
            configs.add(id + "(facing=" + facing + ",source=" + source + ",position=" + position + ")");
            log("camera " + id + " facing=" + facing + " camera_source=" + vendorString(sourceRaw) + " decoded=" + source
                    + " position=" + vendorString(positionRaw) + " decoded=" + position);
            if (source != null && source == 0) {
                if (position != null && position == 0) left = id;
                if (position != null && position == 1) right = id;
            }
        }
        leftCameraId = left == null ? "" : left;
        rightCameraId = right == null ? "" : right;
        log("Camera IDs decoded: " + configs);
        String selected = !leftCameraId.isEmpty() ? leftCameraId : rightCameraId;
        if (!selected.isEmpty()) log("Selected Meta passthrough RGB camera id=" + selected + " left=" + leftCameraId + " right=" + rightCameraId);
        return selected.isEmpty() ? null : selected;
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
        for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    private void openCameraIfNeeded() {
        if (cameraDevice != null || cameraManager == null || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            selectedCameraId = selectPassthroughCamera();
            if (selectedCameraId == null) {
                setStatus("No Meta passthrough RGB camera found.");
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
                    camera.close(); cameraDevice = null; cameraReady = false;
                    runOnUiThread(() -> { setStatus("Camera disconnected."); updateCaptureButton(); });
                }
                @Override public void onError(CameraDevice camera, int error) {
                    log("Camera ERROR id=" + camera.getId() + " code=" + error);
                    camera.close(); cameraDevice = null; cameraReady = false;
                    runOnUiThread(() -> { setStatus("Camera error " + error); updateCaptureButton(); });
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
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(imageReader.getSurface());
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        session.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                                latestCaptureMetadata = captureMetadata(result);
                            }
                        }, cameraHandler);
                        cameraReady = true;
                        JSONObject camera = cameraInfoJson();
                        uploader.setCameraInfo(camera);
                        log("Camera capture session running on id=" + selectedCameraId);
                        runOnUiThread(() -> {
                            setStatus("Camera ready at " + selectedSize + ". Waiting for Drive diagnostic session.");
                            updateCaptureButton();
                        });
                    } catch (Exception e) {
                        log("Repeating request failed: " + e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    log("Capture session configuration FAILED");
                    runOnUiThread(() -> setStatus("Camera stream configuration failed."));
                }
            }, cameraHandler);
        } catch (Exception e) {
            log("createCaptureSession failed: " + e);
        }
    }

    private JSONObject cameraInfoJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("selected_camera_id", selectedCameraId);
            out.put("left_camera_id", leftCameraId);
            out.put("right_camera_id", rightCameraId);
            out.put("width", selectedSize == null ? 0 : selectedSize.getWidth());
            out.put("height", selectedSize == null ? 0 : selectedSize.getHeight());
            out.put("rotation_degrees", imageRotationDegrees);
            out.put("format", "YUV_420_888");
            out.put("ocr_keyframe_interval_ms", OCR_KEYFRAME_INTERVAL_MS);
        } catch (Exception ignored) { }
        return out;
    }

    private JSONObject captureMetadata(TotalCaptureResult result) {
        JSONObject out = new JSONObject();
        try {
            out.put("frame_number", result.getFrameNumber());
            put(out, "sensor_timestamp_ns", result.get(CaptureResult.SENSOR_TIMESTAMP));
            put(out, "exposure_time_ns", result.get(CaptureResult.SENSOR_EXPOSURE_TIME));
            put(out, "iso", result.get(CaptureResult.SENSOR_SENSITIVITY));
            put(out, "focus_distance_diopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE));
            put(out, "focal_length_mm", result.get(CaptureResult.LENS_FOCAL_LENGTH));
            put(out, "aperture", result.get(CaptureResult.LENS_APERTURE));
            put(out, "af_state", result.get(CaptureResult.CONTROL_AF_STATE));
            put(out, "ae_state", result.get(CaptureResult.CONTROL_AE_STATE));
            put(out, "awb_state", result.get(CaptureResult.CONTROL_AWB_STATE));
            put(out, "lens_state", result.get(CaptureResult.LENS_STATE));
        } catch (Exception e) {
            try { out.put("metadata_error", e.toString()); } catch (Exception ignored) { }
        }
        return out;
    }

    private static void put(JSONObject object, String key, Object value) {
        if (value == null) return;
        try { object.put(key, value); } catch (Exception ignored) { }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (!captureActive || processingFrame || (lastOcrStartedAt > 0 && now - lastOcrStartedAt < OCR_KEYFRAME_INTERVAL_MS)) {
            image.close();
            return;
        }
        processingFrame = true;
        lastOcrStartedAt = now;
        int index = ++frameIndex;
        FrameQuality quality = measureFrame(image);
        FrameImage frameImage = makeGrayJpegAndPreview(image, 360);
        JSONObject cameraCapture = latestCaptureMetadata;
        InputImage input = InputImage.fromMediaImage(image, imageRotationDegrees);
        long ocrStarted = System.currentTimeMillis();

        recognizer.process(input).addOnSuccessListener(result -> {
            long ocrMs = System.currentTimeMillis() - ocrStarted;
            String raw = result.getText() == null ? "" : result.getText().trim();
            OcrGeometry geometry = measureGeometry(result);
            int blocks = result.getTextBlocks().size();
            if (raw.isEmpty()) zeroTextFrames++; else readableFrames++;
            double score = raw.length() + blocks * 8.0 + geometry.lineCount * 2.0
                    + Math.min(geometry.avgWordHeightPx, 32) * 3.0 + Math.min(quality.edgeEnergy, 20) * 5.0;
            Candidate candidate = new Candidate(index, raw, score, geometry, quality);
            if (bestCandidate == null || candidate.score > bestCandidate.score) bestCandidate = candidate;

            JSONObject metadata = buildOcrMetadata(index, result, raw, ocrMs, geometry, quality, cameraCapture, score, null);
            if (frameImage.jpeg != null) uploader.enqueueFrame(index, frameImage.jpeg, metadata);

            log(String.format(Locale.US,
                    "OCR frame=%d chars=%d blocks=%d lines=%d wordPx=%.1f coverage=%.2fx%.2f mean=%.1f contrast=%.1f edge=%.1f score=%.1f ocrMs=%d jpegBytes=%d",
                    index, raw.length(), blocks, geometry.lineCount, geometry.avgWordHeightPx,
                    geometry.coverageWidth, geometry.coverageHeight, quality.mean, quality.stdDev,
                    quality.edgeEnergy, score, ocrMs, frameImage.jpeg == null ? 0 : frameImage.jpeg.length));

            String guidance = guidance(candidate);
            runOnUiThread(() -> {
                if (frameImage.preview != null) previewView.setImageBitmap(frameImage.preview);
                metricsView.setText(String.format(Locale.US,
                        "Frame %d • %d chars • %d lines • word %.0f px • coverage %.0f%%×%.0f%% • luminance %.0f • sharpness %.1f • %s",
                        index, raw.length(), geometry.lineCount, geometry.avgWordHeightPx,
                        geometry.coverageWidth * 100, geometry.coverageHeight * 100,
                        quality.mean, quality.edgeEnergy, guidance));
                if (bestCandidate != null) textView.setText(bestCandidate.text);
                setStatus("Continuous capture ACTIVE • OCR frames " + frameIndex + " • readable " + readableFrames + " • zero-text " + zeroTextFrames);
            });
        }).addOnFailureListener(error -> {
            long ocrMs = System.currentTimeMillis() - ocrStarted;
            JSONObject metadata = buildOcrMetadata(index, null, "", ocrMs,
                    new OcrGeometry(0, 0, 0, 0), quality, cameraCapture, 0, error.toString());
            if (frameImage.jpeg != null) uploader.enqueueFrame(index, frameImage.jpeg, metadata);
            log("OCR FAILED frame=" + index + " elapsedMs=" + ocrMs + " error=" + error);
        }).addOnCompleteListener(done -> {
            image.close();
            processingFrame = false;
        });
    }

    private JSONObject buildOcrMetadata(int index, Text result, String raw, long ocrMs, OcrGeometry geometry,
                                        FrameQuality quality, JSONObject captureMeta, double score, String error) {
        JSONObject out = new JSONObject();
        try {
            out.put("frame_index", index);
            out.put("client_time_ms", System.currentTimeMillis());
            out.put("camera_id", selectedCameraId);
            out.put("width", selectedSize == null ? 0 : selectedSize.getWidth());
            out.put("height", selectedSize == null ? 0 : selectedSize.getHeight());
            out.put("rotation_degrees", imageRotationDegrees);
            out.put("raw_text", raw);
            out.put("ocr_latency_ms", ocrMs);
            out.put("ocr_chars", raw.length());
            out.put("ocr_score", score);
            out.put("ocr_error", error == null ? JSONObject.NULL : error);
            JSONObject q = new JSONObject();
            q.put("luma_mean", quality.mean);
            q.put("luma_stddev", quality.stdDev);
            q.put("edge_energy", quality.edgeEnergy);
            q.put("dark_fraction", quality.darkFraction);
            q.put("highlight_fraction", quality.highlightFraction);
            out.put("image_quality", q);
            JSONObject g = new JSONObject();
            g.put("line_count", geometry.lineCount);
            g.put("avg_word_height_px", geometry.avgWordHeightPx);
            g.put("coverage_width", geometry.coverageWidth);
            g.put("coverage_height", geometry.coverageHeight);
            out.put("ocr_geometry", g);
            out.put("capture", captureMeta == null ? new JSONObject() : captureMeta);
            out.put("blocks", result == null ? new JSONArray() : ocrHierarchy(result));
        } catch (Exception e) {
            try { out.put("metadata_build_error", e.toString()); } catch (Exception ignored) { }
        }
        return out;
    }

    private JSONArray ocrHierarchy(Text result) {
        JSONArray blocks = new JSONArray();
        try {
            for (Text.TextBlock block : result.getTextBlocks()) {
                JSONObject b = new JSONObject();
                b.put("text", block.getText());
                b.put("bounding_box", rectJson(block.getBoundingBox()));
                b.put("corner_points", pointsJson(block.getCornerPoints()));
                JSONArray lines = new JSONArray();
                for (Text.Line line : block.getLines()) {
                    JSONObject l = new JSONObject();
                    l.put("text", line.getText());
                    l.put("bounding_box", rectJson(line.getBoundingBox()));
                    l.put("corner_points", pointsJson(line.getCornerPoints()));
                    JSONArray elements = new JSONArray();
                    for (Text.Element element : line.getElements()) {
                        JSONObject e = new JSONObject();
                        e.put("text", element.getText());
                        e.put("bounding_box", rectJson(element.getBoundingBox()));
                        e.put("corner_points", pointsJson(element.getCornerPoints()));
                        elements.put(e);
                    }
                    l.put("elements", elements);
                    lines.put(l);
                }
                b.put("lines", lines);
                blocks.put(b);
            }
        } catch (Exception e) {
            log("OCR hierarchy serialization warning: " + e);
        }
        return blocks;
    }

    private JSONObject rectJson(Rect r) {
        if (r == null) return new JSONObject();
        JSONObject out = new JSONObject();
        try {
            out.put("left", r.left); out.put("top", r.top); out.put("right", r.right); out.put("bottom", r.bottom);
            out.put("width", r.width()); out.put("height", r.height());
        } catch (Exception ignored) { }
        return out;
    }

    private JSONArray pointsJson(Point[] points) {
        JSONArray out = new JSONArray();
        if (points == null) return out;
        try {
            for (Point p : points) {
                JSONObject item = new JSONObject();
                item.put("x", p.x); item.put("y", p.y); out.put(item);
            }
        } catch (Exception ignored) { }
        return out;
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
                    wordHeightSum += r.height(); wordCount++;
                    if (union == null) union = new Rect(r); else union.union(r);
                }
            }
        }
        double avg = wordCount == 0 ? 0 : wordHeightSum / wordCount;
        double cw = 0, ch = 0;
        if (union != null && selectedSize != null) {
            cw = Math.min(1.0, (double) union.width() / selectedSize.getWidth());
            ch = Math.min(1.0, (double) union.height() / selectedSize.getHeight());
        }
        return new OcrGeometry(lineCount, avg, cw, ch);
    }

    private FrameQuality measureFrame(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int step = 8;
        long count = 0, dark = 0, highlight = 0, edgeCount = 0;
        double sum = 0, sumSq = 0, edgeSum = 0;
        for (int y = 0; y < height - step; y += step) {
            for (int x = 0; x < width - step; x += step) {
                int v = yValue(buffer, rowStride, pixelStride, x, y);
                int vr = yValue(buffer, rowStride, pixelStride, x + step, y);
                int vd = yValue(buffer, rowStride, pixelStride, x, y + step);
                sum += v; sumSq += v * v; count++;
                if (v < 18) dark++;
                if (v > 245) highlight++;
                edgeSum += Math.abs(v - vr) + Math.abs(v - vd); edgeCount += 2;
            }
        }
        double mean = count == 0 ? 0 : sum / count;
        double variance = count == 0 ? 0 : Math.max(0, sumSq / count - mean * mean);
        return new FrameQuality(mean, Math.sqrt(variance), edgeCount == 0 ? 0 : edgeSum / edgeCount,
                count == 0 ? 0 : (double) dark / count,
                count == 0 ? 0 : (double) highlight / count);
    }

    private FrameImage makeGrayJpegAndPreview(Image image, int previewWidth) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = image.getWidth();
            int h = image.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = yValue(buffer, rowStride, pixelStride, x, y);
                    pixels[y * w + x] = Color.rgb(v, v, v);
                }
            }
            Bitmap full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
            int pw = Math.min(previewWidth, w);
            int ph = Math.max(1, Math.round((float) h * pw / w));
            Bitmap preview = Bitmap.createScaledBitmap(full, pw, ph, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            full.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
            full.recycle();
            return new FrameImage(bytes.toByteArray(), preview);
        } catch (Exception e) {
            log("Frame JPEG generation failed: " + e);
            return new FrameImage(null, null);
        }
    }

    private int yValue(ByteBuffer buffer, int rowStride, int pixelStride, int x, int y) {
        int index = y * rowStride + x * pixelStride;
        return index >= 0 && index < buffer.limit() ? buffer.get(index) & 0xFF : 0;
    }

    private String guidance(Candidate c) {
        if (c == null) return "waiting";
        if (c.text.length() < 100) return "little/no text detected";
        if (c.geometry.avgWordHeightPx > 0 && c.geometry.avgWordHeightPx < 14) return "move closer";
        if (c.quality.mean < 25 || c.quality.darkFraction > 0.55) return "page genuinely dark";
        if (c.quality.highlightFraction > 0.12) return "possible glare/clipping";
        if (c.quality.edgeEnergy < 3.2) return "soft/blurred image";
        if (c.geometry.coverageWidth < 0.42) return "fill more of camera width";
        return "capture geometry usable; compare OCR across frames";
    }

    private void copyLog() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("QuestReader v0.4 Diagnostic Log", buildLog()));
        setStatus("Log copied. Full frame evidence should already be in Drive.");
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "QuestReader v0.4 Diagnostic Log");
        intent.putExtra(Intent.EXTRA_TEXT, buildLog());
        startActivity(Intent.createChooser(intent, "Share QuestReader diagnostic log"));
    }

    private String buildLog() {
        return "QuestReader Scan Demo v0.4 Test Log\n\n" + testLog
                + "\nDrive path: " + (uploader == null ? "" : uploader.drivePath())
                + "\nUploads: queued=" + (uploader == null ? 0 : uploader.queuedCount())
                + " uploaded=" + (uploader == null ? 0 : uploader.uploadedCount())
                + " failed=" + (uploader == null ? 0 : uploader.failedCount())
                + "\n\nBest raw OCR text:\n" + (bestCandidate == null ? "" : bestCandidate.text);
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
        captureActive = false;
        if (recognizer != null) recognizer.close();
        if (uploader != null) uploader.shutdown();
        closeCamera();
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }

    private void closeCamera() {
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) { }
        captureSession = null;
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) { }
        cameraDevice = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) { }
        imageReader = null;
    }

    private static class FrameQuality {
        final double mean, stdDev, edgeEnergy, darkFraction, highlightFraction;
        FrameQuality(double mean, double stdDev, double edgeEnergy, double darkFraction, double highlightFraction) {
            this.mean = mean; this.stdDev = stdDev; this.edgeEnergy = edgeEnergy;
            this.darkFraction = darkFraction; this.highlightFraction = highlightFraction;
        }
    }

    private static class OcrGeometry {
        final int lineCount;
        final double avgWordHeightPx, coverageWidth, coverageHeight;
        OcrGeometry(int lineCount, double avgWordHeightPx, double coverageWidth, double coverageHeight) {
            this.lineCount = lineCount; this.avgWordHeightPx = avgWordHeightPx;
            this.coverageWidth = coverageWidth; this.coverageHeight = coverageHeight;
        }
    }

    private static class FrameImage {
        final byte[] jpeg;
        final Bitmap preview;
        FrameImage(byte[] jpeg, Bitmap preview) { this.jpeg = jpeg; this.preview = preview; }
    }

    private static class Candidate {
        final int frameIndex;
        final String text;
        final double score;
        final OcrGeometry geometry;
        final FrameQuality quality;
        Candidate(int frameIndex, String text, double score, OcrGeometry geometry, FrameQuality quality) {
            this.frameIndex = frameIndex; this.text = text; this.score = score;
            this.geometry = geometry; this.quality = quality;
        }
    }
}
