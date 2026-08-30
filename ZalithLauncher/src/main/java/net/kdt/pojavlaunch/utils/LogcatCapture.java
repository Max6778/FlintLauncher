package net.kdt.pojavlaunch.utils;

import android.os.Process;
import android.util.Log;

import com.movtery.zalithlauncher.setting.AllSettings;

import java.io.File;
import java.io.IOException;

/**
 * LogcatCapture.java 
 */
public class LogcatCapture {
    private static final String TAG = "LogcatCapture";
    private static Process sLogcatProcess = null;

    private LogcatCapture() {}

    public static synchronized void startIfEnabled(File logDir) {
        if (!AllSettings.INSTANCE.getSaveLogcat().getValue()) return;
        if (sLogcatProcess != null) return; // already running this session

        try {
            File logcatFile = new File(logDir, "logcat.txt");
            ProcessBuilder pb = new ProcessBuilder(
                    "logcat",
                    "-v", "threadtime",
                    "--pid=" + Process.myPid()
            );
            pb.redirectOutput(ProcessBuilder.Redirect.to(logcatFile));
            pb.redirectErrorStream(true);
            sLogcatProcess = pb.start();
            Log.i(TAG, "Started logcat capture -> " + logcatFile.getAbsolutePath());
        } catch (IOException e) {
            // Not fatal -- latestlog.txt still works either way, this is a
            // bonus capture, so just log and move on rather than surface
            // an error to the user over a missing diagnostic file.
            Log.w(TAG, "Failed to start logcat capture", e);
            sLogcatProcess = null;
        }
    }

    public static synchronized void stop() {
        if (sLogcatProcess != null) {
            sLogcatProcess.destroy();
            sLogcatProcess = null;
        }
    }
}
