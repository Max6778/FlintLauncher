package dev.horizon.android;

/** JNI installer API exported by the Horizon Android engine. */
public final class HorizonInstaller {
    public interface Listener {
        void onProgress(int stage, String message, long current, long total);
    }

    static {
        System.loadLibrary("horizon_android");
    }

    private HorizonInstaller() { }

    public static String install(String home, String cache, Listener listener) {
        return nativeInstall(home, cache, listener);
    }

    public static boolean isReady(String home) {
        return nativeReady(home);
    }

    private static native String nativeInstall(String home, String cache, Listener listener);
    private static native boolean nativeReady(String home);
}
