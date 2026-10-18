package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.MainActivity;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures Minecraft's render Surface directly and encodes the frames to MP4. */
public final class ScreenRecordingService extends Service {
    public static final String ACTION = "net.kdt.pojavlaunch.action.SCREEN_RECORDING";
    public static final String ACTION_START = "start";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_STATUS = "net.kdt.pojavlaunch.action.SCREEN_RECORDING_STATUS";
    public static final String EXTRA_ACTION = "recording_action";
    public static final String EXTRA_CAPTURE_SURFACE = "capture_surface";
    public static final String EXTRA_OUTPUT_URI = "output_uri";
    public static final String EXTRA_SOURCE_WIDTH = "source_width";
    public static final String EXTRA_SOURCE_HEIGHT = "source_height";
    public static final String EXTRA_RESOLUTION = "resolution_preset";
    public static final String EXTRA_INCLUDE_CONTROLS = "include_controls";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ELAPSED_MS = "elapsed_ms";
    public static final String EXTRA_ERROR = "error";

    private static final String TAG = "ScreenRecording";
    private static final String CHANNEL_ID = "game_screen_recording";
    private static final int NOTIFICATION_ID = 42;
    private static final int FRAME_RATE = 24;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;
    private Handler captureHandler;
    private Surface captureSurface;
    private Bitmap frameBitmap;
    private SurfaceVideoEncoder encoder;
    private Uri outputUri;
    private volatile boolean starting;
    private volatile boolean recording;
    private volatile boolean paused;
    private volatile boolean stopping;
    private boolean includeControls;
    private final AtomicBoolean framePending = new AtomicBoolean();
    private volatile boolean finalizing;
    private int width;
    private int height;
    private long elapsedBeforePause;
    private long recordingStartedAt;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (recording) {
                publishState(paused ? "paused" : "recording", null);
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable captureFrame = new Runnable() {
        @Override public void run() {
            if (!framePending.compareAndSet(false, true)) return;
            if (!recording || paused || stopping) {
                framePending.set(false);
                if (stopping) finishCaptureOnWorker();
                return;
            }
            if (captureSurface == null || !captureSurface.isValid()) {
                framePending.set(false);
                fail("Minecraft's render surface is no longer available");
                return;
            }
            try {
                PixelCopy.request(captureSurface, frameBitmap, result -> {
                    if (stopping || !recording) {
                        framePending.set(false);
                        if (stopping) finishCaptureOnWorker();
                        return;
                    }
                    if (result != PixelCopy.SUCCESS) {
                        framePending.set(false);
                        Log.w(TAG, "PixelCopy skipped a Minecraft frame: " + result);
                        if (stopping) finishCaptureOnWorker();
                        else scheduleNextFrame();
                        return;
                    }
                    if (includeControls) {
                        mainHandler.post(() -> {
                            if (recording && !stopping) MainActivity.drawRecordingControls(frameBitmap);
                            if (captureHandler != null) captureHandler.post(ScreenRecordingService.this::encodeFrame);
                        });
                    } else {
                        encodeFrame();
                    }
                }, captureHandler);
            } catch (RuntimeException e) {
                framePending.set(false);
                if (stopping) finishCaptureOnWorker();
                else fail("Could not copy Minecraft's render surface: " + e.getMessage());
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(com.movtery.zalithlauncher.R.string.recording_notification_channel),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getStringExtra(EXTRA_ACTION);
        if (ACTION_START.equals(action)) startCapture(intent);
        else if (ACTION_PAUSE.equals(action)) pauseCapture();
        else if (ACTION_RESUME.equals(action)) resumeCapture();
        else if (ACTION_STOP.equals(action)) stopCapture();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startCapture(Intent request) {
        if (stopping || starting || recording || encoder != null) return;
        captureSurface = request.getParcelableExtra(EXTRA_CAPTURE_SURFACE);
        outputUri = request.getParcelableExtra(EXTRA_OUTPUT_URI);
        int sourceWidth = request.getIntExtra(EXTRA_SOURCE_WIDTH, 0);
        int sourceHeight = request.getIntExtra(EXTRA_SOURCE_HEIGHT, 0);
        int resolutionPreset = request.getIntExtra(EXTRA_RESOLUTION, 0);
        includeControls = request.getBooleanExtra(EXTRA_INCLUDE_CONTROLS, false);
        if (captureSurface == null || outputUri == null || sourceWidth <= 0 || sourceHeight <= 0) {
            fail("Minecraft's render surface or MP4 output file is missing");
            return;
        }

        try {
            startRecordingForeground();
            int[] outputSize = getCaptureSize(sourceWidth, sourceHeight, resolutionPreset);
            width = outputSize[0];
            height = outputSize[1];
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            captureThread = new HandlerThread("FlintMinecraftRecorder");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
            starting = true;
            recording = false;
            paused = false;
            elapsedBeforePause = 0;
            recordingStartedAt = 0L;
            captureHandler.post(() -> {
                try {
                    encoder = new SurfaceVideoEncoder(this, outputUri, width, height, getBitRate(width, height));
                    if (stopping) return;
                    starting = false;
                    recording = true;
                    recordingStartedAt = android.os.SystemClock.elapsedRealtime();
                    mainHandler.post(ticker);
                    publishState("recording", null);
                    captureFrame.run();
                } catch (Exception e) {
                    Log.e(TAG, "Could not initialize the Minecraft MP4 encoder", e);
                    fail(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Could not start Minecraft surface recording", e);
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private int[] getCaptureSize(int sourceWidth, int sourceHeight, int preset) {
        int maxEdge;
        switch (preset) {
            case 1: maxEdge = 1920; break;
            case 2: maxEdge = 1280; break;
            case 3: maxEdge = 854; break;
            default: maxEdge = 0;
        }
        float scale = maxEdge == 0 ? 1f : Math.min(1f, maxEdge / (float) Math.max(sourceWidth, sourceHeight));
        int outputWidth = Math.max(2, ((int) (sourceWidth * scale)) & ~1);
        int outputHeight = Math.max(2, ((int) (sourceHeight * scale)) & ~1);
        return new int[] { outputWidth, outputHeight };
    }

    private int getBitRate(int outputWidth, int outputHeight) {
        long pixels = (long) outputWidth * outputHeight;
        return (int) Math.max(1_500_000L, Math.min(16_000_000L, pixels * 6L));
    }

    private void encodeFrame() {
        if (!recording || paused || stopping || encoder == null) {
            framePending.set(false);
            if (stopping) finishCaptureOnWorker();
            return;
        }
        try {
            encoder.writeFrame(frameBitmap, getElapsedMs() * 1000L);
        } catch (IOException | RuntimeException e) {
            framePending.set(false);
            if (stopping) finishCaptureOnWorker();
            else fail("Could not encode Minecraft frame: " + e.getMessage());
            return;
        }
        framePending.set(false);
        if (stopping) finishCaptureOnWorker();
        else scheduleNextFrame();
    }

    private void scheduleNextFrame() {
        if (recording && !paused && !stopping && captureHandler != null) {
            captureHandler.postDelayed(captureFrame, 1000L / FRAME_RATE);
        }
    }

    private void pauseCapture() {
        if (!recording || paused) return;
        elapsedBeforePause += android.os.SystemClock.elapsedRealtime() - recordingStartedAt;
        paused = true;
        updateRecordingNotification(true);
        publishState("paused", null);
    }

    private void resumeCapture() {
        if (!recording || !paused) return;
        recordingStartedAt = android.os.SystemClock.elapsedRealtime();
        paused = false;
        updateRecordingNotification(false);
        publishState("recording", null);
        if (captureHandler != null) captureHandler.post(captureFrame);
    }

    private void stopCapture() {
        if (stopping) return;
        if (!starting && !recording && encoder == null && captureThread == null) {
            stopForeground(true);
            stopSelf();
            return;
        }
        stopping = true;
        boolean wasPaused = paused;
        starting = false;
        recording = false;
        mainHandler.removeCallbacks(ticker);
        if (!wasPaused && recordingStartedAt != 0L) {
            elapsedBeforePause += android.os.SystemClock.elapsedRealtime() - recordingStartedAt;
        }
        Handler worker = captureHandler;
        if (worker != null) {
            worker.removeCallbacks(captureFrame);
            // PixelCopy may still be writing into frameBitmap. Its completion callback
            // owns the final handoff when a frame is in flight.
            if (framePending.get()) return;
            worker.removeCallbacksAndMessages(null);
            worker.post(this::finishCaptureOnWorker);
        } else {
            finishCaptureOnWorker();
        }
    }

    /** Finish codec/muxer work on the same thread that feeds frames into it. */
    private void finishCaptureOnWorker() {
        if (!stopping || finalizing) return;
        finalizing = true;
        boolean saved = false;
        if (encoder != null) {
            try { saved = encoder.finish(); }
            catch (IOException e) { Log.e(TAG, "Could not finalize MP4", e); }
            encoder = null;
        }
        releaseCaptureResources();
        if (saved) saved = publishOutput();
        if (!saved) deleteOutput();
        String error = saved ? null : "Could not finish the MP4 recording";
        publishState(saved ? "stopped" : "error", error);
        stopForeground(true);
        stopSelf();
    }

    private void fail(String message) {
        if (stopping) return;
        stopping = true;
        starting = false;
        recording = false;
        paused = false;
        mainHandler.removeCallbacks(ticker);
        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
            if (captureThread != null) captureThread.quitSafely();
        }
        if (encoder != null) {
            encoder.abort();
            encoder = null;
        }
        releaseCaptureResources();
        deleteOutput();
        publishState("error", message);
        stopForeground(true);
        stopSelf();
        stopping = false;
        finalizing = false;
    }

    private void releaseCaptureResources() {
        if (frameBitmap != null) {
            frameBitmap.recycle();
            frameBitmap = null;
        }
        if (captureSurface != null) {
            captureSurface.release();
            captureSurface = null;
        }
        captureHandler = null;
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        framePending.set(false);
    }

    private boolean publishOutput() {
        if (outputUri == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            try {
                if (getContentResolver().update(outputUri, values, null, null) <= 0) return false;
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not publish recorded video", e);
                return false;
            }
        }
        outputUri = null;
        return true;
    }

    private void deleteOutput() {
        if (outputUri == null) return;
        try { getContentResolver().delete(outputUri, null, null); }
        catch (RuntimeException e) { Log.w(TAG, "Could not delete incomplete recording", e); }
        outputUri = null;
    }

    private void startRecordingForeground() {
        Notification notification = buildRecordingNotification(false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildRecordingNotification(boolean isPaused) {
        String pauseAction = isPaused ? ACTION_RESUME : ACTION_PAUSE;
        int pauseIcon = isPaused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause;
        int pauseLabel = isPaused
                ? com.movtery.zalithlauncher.R.string.recording_resume
                : com.movtery.zalithlauncher.R.string.recording_pause;
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(getString(com.movtery.zalithlauncher.R.string.recording_notification_title))
                .setContentText(getString(com.movtery.zalithlauncher.R.string.recording_notification_text))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(pauseIcon, getString(pauseLabel), createActionIntent(pauseAction))
                .addAction(android.R.drawable.ic_media_stop,
                        getString(com.movtery.zalithlauncher.R.string.recording_stop), createActionIntent(ACTION_STOP))
                .build();
    }

    private PendingIntent createActionIntent(String action) {
        Intent command = new Intent(this, ScreenRecordingService.class)
                .setAction(ACTION + "." + action)
                .putExtra(EXTRA_ACTION, action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getForegroundService(this, action.hashCode(), command, flags);
    }

    private void updateRecordingNotification(boolean isPaused) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildRecordingNotification(isPaused));
    }

    private long getElapsedMs() {
        if (!recording || paused) return elapsedBeforePause;
        return elapsedBeforePause + android.os.SystemClock.elapsedRealtime() - recordingStartedAt;
    }

    private void publishState(String state, String error) {
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
        status.putExtra(EXTRA_STATE, state);
        status.putExtra(EXTRA_ELAPSED_MS, getElapsedMs());
        if (error != null) status.putExtra(EXTRA_ERROR, error);
        sendBroadcast(status);
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(ticker);
        stopping = true;
        starting = false;
        recording = false;
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        if (encoder != null) {
            encoder.abort();
            encoder = null;
        }
        releaseCaptureResources();
        deleteOutput();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
