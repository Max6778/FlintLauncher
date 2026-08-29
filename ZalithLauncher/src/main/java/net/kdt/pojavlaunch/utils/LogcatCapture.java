package net.kdt.pojavlaunch.utils;

import android.os.Process;
import android.util.Log;

import com.movtery.zalithlauncher.setting.AllSettings;

import java.io.File;
import java.io.IOException;

/**
 * LogcatCapture.java  (2026-08-29)
 *
 * Optional, settings-gated companion to latestlog.txt: while enabled
 * (Settings -> Experimental -> "Save logcat"), continuously pipes this
 * process's own logcat output into "logcat.txt", written right next to
 * latestlog.txt. Unlike latestlog.txt (which only captures the game's own
 * stdout/stderr, see stdio_is.c's Logger_begin/logger_thread) and unlike
 * the native_crash_handler.c capture (which only fires on a fatal native
 * signal), this runs for the whole session and captures everything ART/
 * the Android framework log -- e.g. a "JNI DETECTED ERROR..." line, or any
 * other diagnostic that only ever goes to the log driver -- without
 * needing adb, and without needing a crash to have happened at all.
 *
 * Reads only this process's own log entries (`--pid=<own pid>`), which
 * Android permits without any special permission -- no READ_LOGS needed.
 *
 * Call startIfEnabled() right after Logger.begin() in each of the three
 * activities that call it (MainActivity, JavaGUILauncherActivity,
 * SDLGameActivity), passing the same directory latestlog.txt lives in.
 * Call stop() from onDestroy() of whichever activity started it, so the
 * child process doesn't linger past the game session.
 */
public class LogcatCapture {
    private static final String TAG = "LogcatCapture";
    private static Process sLogcatProcess = null;

    private LogcatCapture() {}

    public static synchronized void startIfEnabled(File logDir) {
        if (!AllSettings.getSaveLogcat().getValue()) return;
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
