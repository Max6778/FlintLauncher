package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.WindowManager;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

/** Captures the game display into the user-selected document. */
public final class ScreenRecordingService extends Service {
    public static final String ACTION = "net.kdt.pojavlaunch.action.SCREEN_RECORDING";
    public static final String ACTION_START = "start";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_STATUS = "net.kdt.pojavlaunch.action.SCREEN_RECORDING_STATUS";
    public static final String EXTRA_ACTION = "recording_action";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    public static final String EXTRA_OUTPUT_URI = "output_uri";
    public static final String EXTRA_WEBM = "webm";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ELAPSED_MS = "elapsed_ms";
    public static final String EXTRA_ERROR = "error";

    private static final String TAG = "ScreenRecording";
    private static final String CHANNEL_ID = "game_screen_recording";
    private static final int NOTIFICATION_ID = 42;
    private static final int DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private ParcelFileDescriptor outputDescriptor;
    private Uri outputUri;
    private boolean recording;
    private boolean paused;
    private boolean stopping;
    private long elapsedBeforePause;
    private long recordingStartedAt;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (recording) {
                publishState(paused ? "paused" : "recording", null);
                handler.postDelayed(this, 1000);
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
        if (ACTION_START.equals(action)) {
            startCapture(intent);
        } else if (ACTION_PAUSE.equals(action)) {
            pauseCapture();
        } else if (ACTION_RESUME.equals(action)) {
            resumeCapture();
        } else if (ACTION_STOP.equals(action)) {
            stopCapture(true);
        }
        return START_NOT_STICKY;
    }

    private void startCapture(Intent request) {
        if (recording) return;
        int resultCode = request.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = request.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = request.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        outputUri = request.getParcelableExtra(EXTRA_OUTPUT_URI);
        boolean webm = request.getBooleanExtra(EXTRA_WEBM, false);
        if (resultData == null || outputUri == null) {
            fail("Screen capture permission or output file is missing");
            return;
        }

        try {
            // Android 14 requires the foreground projection service to be active
            // before obtaining MediaProjection from the consent result.
            startProjectionForeground();
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("Android did not grant screen capture");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (!stopping) stopCapture(false);
                }
            }, handler);

            outputDescriptor = getContentResolver().openFileDescriptor(outputUri, "w");
            if (outputDescriptor == null) throw new IOException("Could not open the selected video file");

            int[] size = getCaptureSize();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(webm ? MediaRecorder.OutputFormat.WEBM : MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(webm ? MediaRecorder.VideoEncoder.VP8 : MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(size[0], size[1]);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(8_000_000);
            recorder.setOutputFile(outputDescriptor.getFileDescriptor());
            recorder.prepare();

            virtualDisplay = projection.createVirtualDisplay("FlintLauncher screen recording",
                    size[0], size[1], getResources().getDisplayMetrics().densityDpi,
                    DISPLAY_FLAGS, recorder.getSurface(), null, handler);
            if (virtualDisplay == null) throw new IllegalStateException("Could not create the capture display");
            recorder.start();
            recording = true;
            paused = false;
            elapsedBeforePause = 0;
            recordingStartedAt = android.os.SystemClock.elapsedRealtime();
            handler.post(ticker);
            publishState("recording", null);
        } catch (Exception e) {
            Log.e(TAG, "Could not start screen recording", e);
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private int[] getCaptureSize() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        //noinspection deprecation
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.max(2, metrics.widthPixels & ~1);
        int height = Math.max(2, metrics.heightPixels & ~1);
        return new int[] { width, height };
    }

    private void pauseCapture() {
        if (!recording || paused || recorder == null) return;
        try {
            recorder.pause();
            elapsedBeforePause += android.os.SystemClock.elapsedRealtime() - recordingStartedAt;
            paused = true;
            publishState("paused", null);
        } catch (RuntimeException e) {
            fail("Could not pause recording: " + e.getMessage());
        }
    }

    private void resumeCapture() {
        if (!recording || !paused || recorder == null) return;
        try {
            recorder.resume();
            recordingStartedAt = android.os.SystemClock.elapsedRealtime();
            paused = false;
            publishState("recording", null);
        } catch (RuntimeException e) {
            fail("Could not resume recording: " + e.getMessage());
        }
    }

    private void stopCapture(boolean stopProjection) {
        if (!recording && recorder == null && projection == null) {
            stopForeground(true);
            stopSelf();
            return;
        }
        if (stopping) return;
        stopping = true;
        handler.removeCallbacks(ticker);
        if (recording && !paused) {
            elapsedBeforePause += android.os.SystemClock.elapsedRealtime() - recordingStartedAt;
        }
        boolean saved = false;
        try {
            if (recorder != null && recording) {
                recorder.stop();
                saved = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Recorder did not produce a valid video", e);
        }
        recording = false;
        paused = false;
        releaseResources(stopProjection);
        if (!saved && outputUri != null) {
            try { DocumentsContract.deleteDocument(getContentResolver(), outputUri); }
            catch (Exception ignored) {}
        }
        publishState(saved ? "stopped" : "error", saved ? null : "Recording was too short to save");
        stopForeground(true);
        stopSelf();
        stopping = false;
    }

    private void fail(String message) {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacks(ticker);
        recording = false;
        paused = false;
        releaseResources(true);
        if (outputUri != null) {
            try { DocumentsContract.deleteDocument(getContentResolver(), outputUri); }
            catch (Exception ignored) {}
        }
        publishState("error", message);
        stopForeground(true);
        stopSelf();
        stopping = false;
    }

    private void releaseResources(boolean stopProjection) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) {}
            recorder.release();
            recorder = null;
        }
        if (projection != null) {
            if (stopProjection) {
                try { projection.stop(); } catch (RuntimeException ignored) {}
            }
            projection = null;
        }
        if (outputDescriptor != null) {
            try { outputDescriptor.close(); } catch (IOException ignored) {}
            outputDescriptor = null;
        }
    }

    private void startProjectionForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(getString(com.movtery.zalithlauncher.R.string.recording_notification_title))
                .setContentText(getString(com.movtery.zalithlauncher.R.string.recording_notification_text))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
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
        handler.removeCallbacks(ticker);
        stopping = true;
        if (recording || recorder != null || projection != null) releaseResources(true);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
