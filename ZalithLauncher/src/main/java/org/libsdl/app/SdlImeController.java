/*
 * Ported from ZalithLauncher2 (Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors,
 * GPL-3.0) and adapted for FlintLauncher's architecture:
 *  - No Jetpack Compose here (FlintLauncher's UI is View/Fragment-based), so
 *    SdlBridge.requestComposeFocus() calls are dropped -- there's no Compose
 *    focus system to notify.
 *  - No SdlBridge.kt state-owner object in FlintLauncher, so "is SDL enabled"
 *    is read directly off org.lwjgl.glfw.CallbackBridge.usingSdl3, matching
 *    what SDLSurface.java itself already gates on.
 *  - "Launcher force-opens the native text channel while the game's own
 *    channel is closed" (setNativeTextInputActive) needs a native JNI export
 *    that doesn't exist in FlintLauncher's input_bridge_v3.c yet. That one
 *    piece is stubbed to safely no-op (see forceActivateNativeChannel below)
 *    rather than guessing at native code that could corrupt SDL's window
 *    state -- add the native export first if you need mod-driven text UIs
 *    to be able to reopen the channel from the launcher side.
 *
 * Everything else (main-thread marshaling of show/hide, proper SDLDummyEdit
 * lifecycle, suppressing IME popping back up uninvited) is unchanged from
 * ZL2 and is what actually fixes the SDL_StopTextInput crash path.
 */

package org.libsdl.app;

import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_TEXT_VARIATION_NORMAL;

import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import org.lwjgl.glfw.CallbackBridge;

final class SdlImeController {
    enum Source { GAME, LAUNCHER, BACK }

    private static final String TAG = "SDLImeController";
    private static final int HEIGHT_PADDING = 15;

    private static SDLDummyEdit mEdit;
    private static boolean mTextInputActive;
    private static boolean mKeyboardShown;
    // Set when the launcher explicitly force-opened the native text channel
    // because the game's own channel was closed (e.g. a mod's self-drawn
    // input UI). See forceActivateNativeChannel() below -- currently a safe
    // no-op in FlintLauncher pending a native export.
    private static boolean mForcedByLauncher;

    private SdlImeController() {
    }

    static boolean isTextInputActive() {
        return mTextInputActive;
    }

    /** Whether the text-input channel can currently accept input (including a launcher-forced channel). */
    static boolean isInputAccepted() {
        return mTextInputActive || mForcedByLauncher;
    }

    static boolean isEditAvailable() {
        return mEdit != null;
    }

    static boolean isKeyboardShown() {
        return mKeyboardShown;
    }

    static void reset() {
        if (mEdit != null) {
            ViewParent parent = mEdit.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mEdit);
            }
            mEdit = null;
        }
        mTextInputActive = false;
        mKeyboardShown = false;
        mForcedByLauncher = false;
    }

    static void requestShow(Source source) {
        requestShow(source, TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL, -1, -1, -1, -1);
    }

    static boolean requestShow(Source source, int inputType, int x, int y, int w, int h) {
        Log.i(TAG, "IME: show requested by " + source);
        if (source == Source.GAME) {
            mTextInputActive = true;
        }
        return post(() -> doShow(source, inputType, x, y, w, h));
    }

    static void requestHide(Source source) {
        Log.i(TAG, "IME: hide requested by " + source);
        if (source == Source.GAME) {
            mTextInputActive = false;
        }
        post(() -> doHide(source));
    }

    /**
     * Reports system-insets-driven IME visibility. Only wire this up if
     * FlintLauncher has (or gains) a window-insets listener for the embedded
     * SDL surface; harmless to leave unused otherwise.
     */
    static void notifyVisibilityChanged(boolean visible) {
        if (!isSdlEnabled()) return;
        if (visible && isUnwantedImeVisible()) {
            Log.w(TAG, "IME: unwanted visibility while text input channel is closed, forcing hide");
            forceHideIme();
            return;
        }
        if (mKeyboardShown == visible) {
            return;
        }
        mKeyboardShown = visible;
        Log.i(TAG, "IME: visibility changed to " + (visible ? "shown" : "hidden"));
        if (visible) {
            SDLActivity.onNativeScreenKeyboardShown();
        } else {
            SDLActivity.onNativeScreenKeyboardHidden();
        }
    }

    private static boolean isSdlEnabled() {
        return CallbackBridge.usingSdl3;
    }

    /**
     * Stub for the launcher explicitly force-activating the native text
     * channel (e.g. a mod closed the game's own channel but the launcher
     * still wants to feed it text via its own UI). Returns false -- meaning
     * "not available" -- until a matching native JNI export exists on the
     * FlintLauncher side. Safe to leave as-is: doShow()/doHide() below
     * already handle a false return gracefully by just not force-opening.
     */
    private static boolean forceActivateNativeChannel(boolean active) {
        return false;
    }

    private static boolean post(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return true;
        }
        if (SDLActivity.mSingleton instanceof SDLActivity) {
            return ((SDLActivity) SDLActivity.mSingleton).commandHandler.post(task);
        } else if (SDLActivity.mSingleton != null) {
            // externalInitialize() flow: no live SDLActivity/commandHandler,
            // same fallback pattern as SDLActivity.sendMessage()/showTextInput().
            SDLActivity.mSingleton.runOnUiThread(task);
            return true;
        }
        return false;
    }

    private static void doShow(Source source, int inputType, int x, int y, int w, int h) {
        if (SDLActivity.mLayout == null) {
            Log.w(TAG, "IME: no layout available, show by " + source + " ignored");
            return;
        }

        if (source != Source.GAME && !mTextInputActive && !mForcedByLauncher) {
            if (!forceActivateNativeChannel(true)) {
                Log.w(TAG, "IME: show by " + source + " rejected, native text input unavailable");
                return;
            }
            mForcedByLauncher = true;
            Log.i(TAG, "IME: native text input force-activated by " + source);
        }

        if (mEdit == null) {
            mEdit = new SDLDummyEdit(SDLActivity.getContext());
            SDLActivity.mLayout.addView(mEdit, makeParams(x, y, w, h));
        } else if (x >= 0 && w > 0) {
            mEdit.setLayoutParams(makeParams(x, y, w, h));
        }
        mEdit.setInputType(inputType);
        mEdit.setFocusable(true);
        mEdit.setFocusableInTouchMode(true);

        mEdit.setVisibility(View.VISIBLE);
        if (!mEdit.hasFocus()) {
            mEdit.requestFocus();
        }

        if (mKeyboardShown) {
            Log.i(TAG, "IME: already visible, show by " + source + " ignored");
            return;
        }

        InputMethodManager imm = (InputMethodManager) SDLActivity.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(mEdit, 0);
        if (imm.isAcceptingText()) {
            mKeyboardShown = true;
            Log.i(TAG, "IME: shown by " + source);
            SDLActivity.onNativeScreenKeyboardShown();
        }
    }

    private static FrameLayout.LayoutParams makeParams(int x, int y, int w, int h) {
        if (x < 0 || w <= 0) {
            x = 0;
            y = 0;
            w = 1;
            h = 1;
        }
        if (h + HEIGHT_PADDING <= 0) {
            h = 1 - HEIGHT_PADDING;
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(w, h + HEIGHT_PADDING);
        params.leftMargin = x;
        params.topMargin = y;
        return params;
    }

    private static void doHide(Source source) {
        if (mForcedByLauncher && source != Source.GAME) {
            forceActivateNativeChannel(false);
        }
        mForcedByLauncher = false;

        if (mEdit == null) {
            Log.i(TAG, "IME: no text edit available, hide ignored");
            return;
        }
        forceHideIme();
        if (mKeyboardShown) {
            mKeyboardShown = false;
            Log.i(TAG, "IME: hidden");
            SDLActivity.onNativeScreenKeyboardHidden();
        }

        if (!isInputAccepted() && SDLActivity.mSingleton instanceof SDLActivity) {
            ((SDLActivity) SDLActivity.mSingleton).commandHandler.postDelayed(SdlImeController::recheckHidden, 300);
        }
    }

    private static void forceHideIme() {
        if (mEdit != null) {
            InputMethodManager imm = (InputMethodManager) SDLActivity.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (mEdit.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(mEdit.getWindowToken(), 0);
            }
            ViewParent parent = mEdit.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mEdit);
            }
            mEdit = null;
            Log.i(TAG, "IME: text edit removed from view tree");
        }
        ViewGroup layout = SDLActivity.mLayout;
        if (layout != null && layout.getWindowToken() != null) {
            InputMethodManager imm = (InputMethodManager) SDLActivity.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(layout.getWindowToken(), 0);
        }
        if (SDLActivity.mSurface != null) {
            SDLActivity.mSurface.requestFocus();
        }
    }

    private static boolean isUnwantedImeVisible() {
        if (!isSdlEnabled() || isInputAccepted()) {
            return false;
        }
        return mEdit != null && mEdit.hasFocus();
    }

    private static void recheckHidden() {
        if (mTextInputActive || mKeyboardShown) {
            return;
        }
        if (!isUnwantedImeVisible()) {
            return;
        }
        Log.i(TAG, "IME: re-hide to suppress stubborn IME");
        forceHideIme();
    }
}
