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
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;

import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    /** True once notifyLauncher() (called from sdl_hook.c the instant real SDL
     *  init starts) has run. When true, every send* method below routes to
     *  SDL3 instead of the native GLFW bridge. */
    public static volatile boolean usingSdl3 = false;
    private static boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    // FIX: SDLSurface's own touch/mouse dispatch (onTouch's TOOL_TYPE_MOUSE
    // branch, onCapturedPointerEvent) always passes SDL3's onNativeMouse()
    // the FULL current Android button-state bitmask, i.e.
    // MotionEvent.getButtonState() -- every button currently held, OR'd
    // together, on every single call. sendMouseKeycode() below was instead
    // passing only the ONE button being pressed/released, in isolation, on
    // both the down AND the up call. SDL3's native button handling diffs
    // the incoming mask against its own last-seen mask to decide what
    // changed; being handed a single-bit mask instead of the true
    // cumulative state desyncs that internal state after the first click,
    // since the "state" SDL3 thinks it's in no longer matches what it's
    // being told -- explaining a click working once, then silently not
    // registering afterwards until an unrelated bitmask (e.g. left+right
    // pressed together) happens to jolt the two back into agreement. This
    // field tracks the same accumulated bitmask locally so
    // sendMouseKeycode can pass SDL3 the same kind of value SDLSurface does.
    private static int sdl3MouseButtonState = 0;
    
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;
    
    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    public static void putMouseEventWithCoords(int button, float x, float y) {
        // sendCursorPos (hover-move) and the click-down used to fire synchronously,
        // back-to-back, in the same call/frame. Minecraft's own per-frame input
        // processing can pick up the click before it's incorporated the hover-move
        // that just landed right before it, missing the target even though the
        // cursor position was correct. Delaying the down edge by one frame (same as
        // the up edge already was) puts the hover-move and the click in separate
        // frames, giving Minecraft a chance to catch up first. Matches the observed
        // symptom exactly: holding two touches down (which naturally spans multiple
        // frames) worked, a fast single tap (same-frame move+click) didn't.
        // Deliberately calling sendMouseKeycode directly here (not
        // putMouseEventWithCoords(button, isDown, x, y)) -- that overload also calls
        // sendCursorPos, which would just reintroduce the same same-frame race one
        // frame later instead of actually fixing it.
        sendCursorPos(x, y);
        final int mods = CallbackBridge.getCurrentMods();
        sChoreographer.postFrameCallbackDelayed(l -> sendMouseKeycode(button, mods, true), 16);
        sChoreographer.postFrameCallbackDelayed(l -> sendMouseKeycode(button, mods, false), 33);
    }
    
    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y /* , int dz, long nanos */) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }


    // FIX: sendCursorPos always passed the raw ABSOLUTE mouseX/mouseY through
    // to onNativeMouse, only toggling the trailing "relative" boolean based
    // on isGrabbing() -- but relative=true tells SDL3 to treat the (x,y)
    // arguments themselves AS a motion delta to add to the current cursor
    // position, not as an absolute coordinate to jump to. Confirmed by
    // direct comparison against DroidBridge's own working equivalent
    // (DroidBridgeSDL3Bootstrap.routeVirtualCursor()), which explicitly
    // computes (current - last) as a small delta before calling
    // onNativeMouse(..., relative=true) -- it never passes the absolute
    // position through on that path. Passing full absolute screen
    // coordinates (potentially hundreds of pixels) as if they were a tiny
    // per-frame delta is exactly what produced the reported symptom: the
    // camera snapping/spinning wildly the instant you start moving it,
    // since SDL3 was adding what it thought was a small delta but was
    // actually your raw on-screen position. lastRelativeX/Y plus the
    // baseline-valid flag mirror DroidBridge's lastVirtualCursorX/Y and
    // virtualCursorBaselineValid exactly, including not sending any motion
    // at all on the very first move after grab starts (nothing to diff
    // against yet) rather than one large delta from an arbitrary origin.
    private static float lastRelativeMouseX, lastRelativeMouseY;
    private static boolean relativeMouseBaselineValid = false;

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        if (usingSdl3) {
            if (isGrabbing()) {
                if (!relativeMouseBaselineValid) {
                    lastRelativeMouseX = x;
                    lastRelativeMouseY = y;
                    relativeMouseBaselineValid = true;
                    return; // nothing to diff against yet -- see comment above
                }
                float deltaX = x - lastRelativeMouseX;
                float deltaY = y - lastRelativeMouseY;
                lastRelativeMouseX = x;
                lastRelativeMouseY = y;
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, deltaX, deltaY, true);
            } else {
                relativeMouseBaselineValid = false;
                // Absolute position, relative=false -- this path was already
                // correct (menu taps need to land where you actually touched).
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, mouseX, mouseY, false);
            }
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
            // Accumulate into the full held-buttons bitmask (see field comment
            // above) instead of sending just this one button's bit -- matches
            // what SDLSurface's own onTouch/onCapturedPointerEvent pass for
            // real touch/mouse input, which is the calling convention SDL3's
            // native side actually expects.
            if (isDown) sdl3MouseButtonState |= androidButton;
            else sdl3MouseButtonState &= ~androidButton;

            // FIX: same bug shape as sendCursorPos above -- this was passing
            // the raw absolute mouseX/mouseY as the trailing coordinate
            // arguments alongside isGrabbing() as the relative flag. A
            // button press/release isn't motion at all; while grabbed,
            // relative=true tells SDL3 to treat those coordinates as a
            // delta to ADD to the cursor position, so every single click
            // during gameplay was injecting a one-off "jump" equal to your
            // full absolute on-screen position -- a small but real camera
            // hitch on every click, isolated now that continuous movement
            // itself (sendCursorPos) sends proper zero-drift deltas. A
            // button event should carry zero motion, so this sends an
            // explicit (0,0) delta when grabbed, and only uses the actual
            // absolute position (relative=false) for menu/GUI clicks,
            // matching sendCursorPos's own split exactly.
            if (isGrabbing()) {
                SDLActivity.onNativeMouse(sdl3MouseButtonState,
                        isDown ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                        0f, 0f, true);
            } else {
                SDLActivity.onNativeMouse(sdl3MouseButtonState,
                        isDown ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                        mouseX, mouseY, false);
            }
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

    // Called from JRE side via JNI for misc launcher-side notifications (SDL init,
    // IME textbox rects, etc). Called from native (sdl_hook.c) via JNI the instant
    // real SDL_InitSubSystem starts running, i.e. right as a 26.2+ Minecraft version
    // begins its SDL3 init. Brings up the Java-side SDL bridge (SDL.setupJNI(),
    // usingSdl3 routing) before SDL's own init continues, so by the time Minecraft
    // actually starts sending/receiving input, everything is already wired.
    public static final int NOTIF_TYPE_SDL = 0;
    public static final int ACTION_INIT_LAUNCHER_INTEGRATION = 0;

    @SuppressWarnings("unused")
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        if (type == NOTIF_TYPE_SDL && action.length > 0 && action[0] == ACTION_INIT_LAUNCHER_INTEGRATION) {
            try {
                // Load explicitly since some mods/versions skip loading it themselves.
                // NOT SDL2 here -- confirmed via on-device testing that this APK/version
                // combo only ships libSDL3.so; SDL2 doesn't exist and loading it used to
                // throw UnsatisfiedLinkError here, which aborted this entire try block
                // *before* reaching usingSdl3/surfaceChanged() below, leaving SDL's native
                // init to proceed with zero Java-side setup and crash inside itself.
                System.loadLibrary("SDL3");
                org.libsdl.app.SDL.setupJNI();
                usingSdl3 = true;
                // input_bridge_v3.c's isInputReady gate only affects the GLFW-native
                // critical_send_* functions (the `else` branch of sendCursorPos/
                // sendMouseKeycode below) -- SDL3's branch calls SDLActivity.onNativeMouse()
                // directly and never passes through that gate. Kept as harmless hygiene
                // (matches what glfwPollEvents() would do on the GLFW path) but it is NOT
                // what was blocking menu clicks -- see the relative-cursor fix in
                // sendCursorPos/sendMouseKeycode for the actual fix.
                nativeSetInputReady(true);
                if (SDLActivity.getSDLSurface() != null) {
                    // This is the real fix, not onNativeResize() alone: usingSdl3 only
                    // flips true here, deep into JVM startup -- long after Android's own
                    // surfaceCreated/surfaceChanged callbacks already fired once (when
                    // MinecraftGLSurface first stood up its TextureView/SurfaceView), back
                    // when usingSdl3 was still false so SDLSurface's own callbacks no-op'd.
                    // Without this call SDL is told a surface *exists* (via
                    // setNativeSurface -> surfaceCreated in externalInitialize) but never
                    // that it's actually ready to render into -- surfaceChanged() is what
                    // calls onNativeSurfaceChanged() and drives SDL to NativeState.RESUMED.
                    // Skipping it means SDL has no real native window, which is why init
                    // succeeds but the app then aborts shortly after.
                    SDLActivity.getSDLSurface().surfaceChanged(null, 0, windowWidth, windowHeight);
                }
                return true;
            } catch (Throwable t) {
                System.err.println("Failed to initialize SDL launcher-side integration: " + t);
            }
        }
        return false;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        // Matches DroidBridgeSDL3Bootstrap.onSdlRelativeMouseChanged() resetting
        // virtualCursorBaselineValid immediately on grab-state change, rather
        // than waiting for the next sendCursorPos call to notice -- avoids any
        // window where a stale baseline from the previous grab session could
        // produce one bad delta right at the start of a new one.
        relativeMouseBaselineValid = false;
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

    // Opens the native input gate inside libdroidbridge_runtime.so. On the normal
    // GLFW path this is called automatically from GLFW.glfwPollEvents() every frame;
    // SDL3 never calls glfwPollEvents(), so on the SDL3 path nothing ever opens the
    // gate and every touch/mouse event gets silently dropped at the native layer.
    // See the one-shot call in notifyLauncher() below.
    @Keep @CriticalNative public static native boolean nativeSetInputReady(boolean ready);
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


