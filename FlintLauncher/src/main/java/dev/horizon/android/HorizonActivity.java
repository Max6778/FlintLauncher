package dev.horizon.android;

import android.app.NativeActivity;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;

import java.io.File;

/** NativeActivity entry point for Horizon's Android engine. */
public final class HorizonActivity extends NativeActivity {
    public static final String EXTRA_ENGINE_HOME = "dev.horizon.android.extra.ENGINE_HOME";
    public static final String EXTRA_GAME_DIR = "dev.horizon.android.extra.GAME_DIR";
    public static final String EXTRA_SESSION_FILE = "dev.horizon.android.extra.SESSION_FILE";

    private native void nativeDocumentResult(int requestCode, int resultCode, String uri);

    private static void setEnv(String key, String value) {
        try {
            if (value == null) Os.unsetenv(key);
            else Os.setenv(key, value, true);
        } catch (Exception error) {
            Log.w("FlintHorizon", "Could not set " + key, error);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        String home = getIntent().getStringExtra(EXTRA_ENGINE_HOME);
        if (home == null || home.trim().isEmpty()) home = new File(getFilesDir(), "horizon").getAbsolutePath();
        new File(home).mkdirs();
        setEnv("IRONBRIDGE_HORIZON_HOME", home);
        setEnv("IRONBRIDGE_HORIZON_GAME_DIR", getIntent().getStringExtra(EXTRA_GAME_DIR));
        setEnv("IRONBRIDGE_HORIZON_SESSION", getIntent().getStringExtra(EXTRA_SESSION_FILE));
        setEnv("IRONBRIDGE_HORIZON_AUTOPLAY", "1");
        setEnv("HORIZON_TELEMETRY_URL", "off");
        setEnv("HORIZON_NO_HEARTBEAT", "1");
        File locks = new File(getCacheDir(), "horizon-world-locks");
        //noinspection ResultOfMethodCallIgnored
        locks.mkdirs();
        setEnv("IRONBRIDGE_HORIZON_LOCK_DIR", locks.getAbsolutePath());
        setEnv("TMPDIR", getCacheDir().getAbsolutePath());
        System.loadLibrary("horizon_android");
        super.onCreate(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 4101) {
            nativeDocumentResult(requestCode, resultCode, data == null || data.getData() == null ? null : data.getData().toString());
        }
    }
}
