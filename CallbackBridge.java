package org.lwjgl.glfw;
import org.libsdl.app.SDLInputConnection;

import android.content.ClipData;
import android.content.ClipDescription;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import android.view.MotionEvent;

import org.libsdl.app.SDLActivity;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;

import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    /** Set once at launch by SDLGameActivity. When true, every send*
     *  method below routes to SDL3 instead of the native GLFW bridge. */
    public static volatile boolean usingSdl3 = false;
    private static boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;
    
    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(l -> putMouseEventWithCoords(button, false, x, y), 33);
    }
    
    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y /* , int dz, long nanos */) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }


    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        if (usingSdl3) {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, mouseX, mouseY, true);
        } else {
            nativeSendCursorPos(mouseX, mouseY);
        }
            }
public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        if (usingSdl3) {
            if (keycode != 0) {
                int androidKeycode = EfficientAndroidLWJGLKeycode.getAndroidKeycode(keycode);
                if (isDown) SDLActivity.onNativeKeyDown(androidKeycode);
                else SDLActivity.onNativeKeyUp(androidKeycode);
            }
            if (isDown && keychar != '\u0000') {
                SDLInputConnection.nativeCommitText(String.valueOf(keychar), 1);
                }
        } else {
            // TODO CHECK: This may cause input issue, not receive input!
            if(keycode != 0)  nativeSendKey(keycode,scancode,isDown ? 1 : 0, modifiers);
            if(isDown && keychar != '\u0000') {
                nativeSendCharMods(keychar,modifiers);
                nativeSendChar(keychar);
            }
        }
}

    public static void sendChar(char keychar, int modifiers){
        nativeSendCharMods(keychar,modifiers);
        nativeSendChar(keychar);
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        CallbackBridge.sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        if (usingSdl3) {
            // GLFW button index (0=left,1=right,2=middle) -> Android MotionEvent button mask
            int androidButton;
            switch (button) {
                case 1: androidButton = MotionEvent.BUTTON_SECONDARY; break;
                case 2: androidButton = MotionEvent.BUTTON_TERTIARY; break;
                default: androidButton = MotionEvent.BUTTON_PRIMARY; break;
            }
            SDLActivity.onNativeMouse(androidButton,
                    isDown ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                    mouseX, mouseY, true);
        } else {
            nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
        }
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }
    
    public static void sendScroll(double xoffset, double yoffset) {
        if (usingSdl3) {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, (float) xoffset, (float) yoffset, false);
        } else {
            nativeSendScroll(xoffset, yoffset);
        }
                                  }
    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        // Avoid going through the JNI each time.
        return isGrabbing;
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                MainActivity.GLOBAL_CLIPBOARD.setPrimaryClip(ClipData.newPlainText("Copy", copy));
                return null;

            case CLIPBOARD_PASTE:
                if (MainActivity.GLOBAL_CLIPBOARD.hasPrimaryClip() && MainActivity.GLOBAL_CLIPBOARD.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    return MainActivity.GLOBAL_CLIPBOARD.getPrimaryClip().getItemAt(0).getText().toString();
                } else {
                    return "";
                }

            case CLIPBOARD_OPEN:
                MainActivity.openLink(copy);
                return null;
            default: return null;
        }
    }


    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        } if (holdingCapslock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        } if (holdingCtrl) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        } if (holdingNumlock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        } if (holdingShift) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown){
        switch (keyCode){
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
        }
    }

    // Called from JRE side via JNI (org.lwjgl.glfw.GLFW's Android-DPI queries,
    // e.g. glfwGetWindowContentScale). Standard display density, no custom scale.
    @SuppressWarnings("unused")
    private static float getAndroidDPI() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        metrics.setToDefaults();
        return metrics.density;
    }

    // Called from JRE side via JNI when gamepad direct-input mode is enabled.
    // FlintLauncher doesn't have a dedicated gamepad-direct-input handler yet,
    // so this is currently just a no-op hook to satisfy the native bridge.
    @SuppressWarnings("unused")
    private static void onDirectInputEnable() {
        android.util.Log.i("CallbackBridge", "onDirectInputEnable()");
    }

    // Notification types/actions sent by native_hooks/sdl_hook.c (bytehook hook
    // on SDL_InitSubSystem), matching the constants in jni/utils.h.
    public static final int NOTIF_TYPE_SDL = 0;
    public static final int ACTION_INIT_LAUNCHER_INTEGRATION = 0;
    public static final int ACTION_SEND_TEXTBOX_RECT = 1;

    /**
     * Called from JRE side via JNI, the instant native SDL_InitSubSystem is
     * hooked (see jni/native_hooks.h + jni/sdl_hook.c) -- i.e. the same
     * moment SDL/LWJGL is initializing, same hook mechanism Amethyst-Android
     * uses. This runs regardless of which Activity is hosting the SDL
     * surface, so it complements (doesn't replace) the existing usingSdl3
     * routing set up by SDLGameActivity.
     * @return if notification successful
     */
    @SuppressWarnings("unused")
    @Keep
    private static boolean notifyLauncher(int type, int... action) {
        switch (type) {
            case NOTIF_TYPE_SDL:
                if (action[0] == ACTION_INIT_LAUNCHER_INTEGRATION) {
                    try {
                        // Load these ourselves because some mods skip loading them due to
                        // broken logic somewhere.
                        System.loadLibrary("SDL3");
                        org.libsdl.app.SDL.setupJNI();
                        onDirectInputEnable();
                        usingSdl3 = true;
                        if (SDLActivity.getSDLSurface() != null) {
                            // Notifies SDL of native surface res, needed for proper input handling
                            SDLActivity.getSDLSurface().nativeResize(windowWidth, windowHeight);
                        }
                        Logger.appendToLog("FlintLauncher: SDL support enabled via native hook!");
                        return true;
                    } catch (Throwable e) {
                        Logger.appendToLog("FlintLauncher: Failed to initialize SDL launcher-side integration! We will likely crash: " + e);
                    }
                }
                if (action[0] == ACTION_SEND_TEXTBOX_RECT) {
                    // Not implemented yet -- matches Amethyst-Android's current state too.
                }
                break;
        }
        return false;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            // If the grab re-changed, skip notify process
            if(isGrabbing != grabbing) return;

            System.out.println("Grab changed : " + grabbing);
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }

        }, 16);

    }
    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }
    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    // private static native void nativeSendCursorEnter(int entered);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native int getCurrentFps();

    static {
        System.loadLibrary("pojavexec");
    }
}

