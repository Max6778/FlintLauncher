package dev.horizon.android;

/** Input bridge exported by Horizon's Android engine for launcher hosted controls. */
public final class HorizonInput {
    static {
        System.loadLibrary("horizon_android");
    }

    private HorizonInput() { }

    public static void cursor(float x, float y) { nativeCursor(x, y); }
    public static void cursorSilently(float x, float y) { nativeCursorSilently(x, y); }
    public static void key(int key, boolean down) { nativeKey(key, down); }
    public static void mouseButton(int button, boolean down) { nativeMouseButton(button, down); }
    public static void scroll(float x, float y) { nativeScroll(x, y); }
    public static void text(String text) { if (text != null && !text.isEmpty()) nativeText(text); }
    public static boolean isGrabbed() { return nativeIsGrabbed(); }

    private static native void nativeCursor(float x, float y);
    private static native void nativeCursorSilently(float x, float y);
    private static native void nativeKey(int key, boolean down);
    private static native void nativeMouseButton(int button, boolean down);
    private static native void nativeScroll(float x, float y);
    private static native void nativeText(String text);
    private static native boolean nativeIsGrabbed();
}
