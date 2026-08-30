package net.kdt.pojavlaunch.utils;

import android.util.Log;

import com.movtery.zalithlauncher.setting.AllSettings;

import java.io.File;

public class LogcatCapture {
    private static final String TAG = "LogcatCapture";
    private static boolean sStarted = false;

    private LogcatCapture() {}

    private static native boolean nativeStart(String outPath, int pid);
    private static native void nativeStop();

    public static synchronized void startIfEnabled(File logDir) {
        if (!AllSettings.getSaveLogcat().getValue()) return;
        if (sStarted) return; // already running this session

        File logcatFile = new File(logDir, "logcat.txt");
        boolean ok = nativeStart(logcatFile.getAbsolutePath(), android.os.Process.myPid());
        if (ok) {
            sStarted = true;
            Log.i(TAG, "Started native logcat capture -> " + logcatFile.getAbsolutePath());
        } else {
            Log.w(TAG, "Failed to start native logcat capture");
        }
    }

    public static synchronized void stop() {
        if (sStarted) {
            nativeStop();
            sStarted = false;
        }
    }
}
