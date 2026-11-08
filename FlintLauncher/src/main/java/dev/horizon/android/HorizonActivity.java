package dev.horizon.android;

import android.app.NativeActivity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.app.AlertDialog;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;

/** NativeActivity entry point for Horizon's Android engine. */
public final class HorizonActivity extends NativeActivity implements ControlButtonMenuListener {
    private static final String CONTROLS_PREFS = "horizon_controls";
    private static final String CONTROLS_KEY = "flint_controls";
    private static final String HOST_CONTROLS_ENV = "IRONBRIDGE_HORIZON_HOST_CONTROLS";
    private final Handler hostInputHandler = new Handler(Looper.getMainLooper());
    private final Runnable hostGrabPoll = new Runnable() {
        @Override public void run() {
            if (controlsOverlay != null) {
                try {
                    boolean grabbed = HorizonInput.isGrabbed();
                    if (grabbed != CallbackBridge.isGrabbing()) CallbackBridge.setHostGrabbed(grabbed);
                } catch (UnsatisfiedLinkError ignored) { }
                hostInputHandler.postDelayed(this, 100L);
            } else {
                hostGrabPolling = false;
            }
        }
    };
    private FrameLayout horizonOverlay;
    private ControlLayout controlsOverlay;
    private ImageButton menuButton;
    private EditText keyboardProxy;
    private boolean flintControlsEnabled;
    private boolean hostGrabPolling;
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
        flintControlsEnabled = getSharedPreferences(CONTROLS_PREFS, MODE_PRIVATE)
                .getBoolean(CONTROLS_KEY, false);
        setEnv(HOST_CONTROLS_ENV, flintControlsEnabled ? "1" : null);
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
        net.kdt.pojavlaunch.Tools.setFullscreen(this);
        attachHostControls();
    }

    private void attachHostControls() {
        CallbackBridge.setHostInputSink(new CallbackBridge.HostInputSink() {
            @Override public void character(char character) { HorizonInput.text(String.valueOf(character)); }
            @Override public void cursorPos(float x, float y) { HorizonInput.cursor(x, y); }
            @Override public void cursorPosSilently(float x, float y) { HorizonInput.cursorSilently(x, y); }
            @Override public void key(int key, boolean down, int modifiers) { HorizonInput.key(key, down); }
            @Override public void mouseButton(int button, boolean down) { HorizonInput.mouseButton(button, down); }
            @Override public void scroll(double x, double y) { HorizonInput.scroll((float) x, (float) y); }
            @Override public void toggleVirtualMouse() {
                runOnUiThread(() -> android.widget.Toast.makeText(HorizonActivity.this,
                        "Horizon handles pointer capture automatically.", android.widget.Toast.LENGTH_SHORT).show());
            }
            @Override public void requestKeyboard() {
                if (keyboardProxy != null) {
                    keyboardProxy.requestFocus();
                    InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (input != null) input.showSoftInput(keyboardProxy, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });

        FrameLayout content = findViewById(android.R.id.content);
        if (content == null) return;
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        CallbackBridge.physicalWidth = CallbackBridge.windowWidth = metrics.widthPixels;
        CallbackBridge.physicalHeight = CallbackBridge.windowHeight = metrics.heightPixels;
        net.kdt.pojavlaunch.Tools.currentDisplayMetrics = metrics;

        horizonOverlay = new FrameLayout(this);
        horizonOverlay.setClipChildren(false);
        horizonOverlay.setClipToPadding(false);
        content.addView(horizonOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int size = dp(48), margin = dp(12);
        keyboardProxy = new EditText(this);
        keyboardProxy.setAlpha(0.01f);
        keyboardProxy.setSingleLine(false);
        keyboardProxy.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keyboardProxy.setFocusable(true);
        keyboardProxy.setFocusableInTouchMode(true);
        keyboardProxy.setOnKeyListener((view, keyCode, event) -> {
            int glfwKey = keyCode == KeyEvent.KEYCODE_DEL ? 259
                    : keyCode == KeyEvent.KEYCODE_ENTER ? 257
                    : keyCode == KeyEvent.KEYCODE_ESCAPE ? 256 : 0;
            if (glfwKey == 0) return false;
            HorizonInput.key(glfwKey, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        });
        keyboardProxy.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (count > 0) HorizonInput.text(text.subSequence(start, start + count).toString());
            }
            @Override public void afterTextChanged(Editable text) {
                if (text.length() > 0) text.clear();
            }
        });
        horizonOverlay.addView(keyboardProxy, new FrameLayout.LayoutParams(dp(1), dp(1), Gravity.TOP | Gravity.START));

        menuButton = new ImageButton(this);
        menuButton.setImageResource(android.R.drawable.ic_menu_manage);
        menuButton.setContentDescription("Horizon controls menu");
        menuButton.setBackgroundResource(com.flintlauncher.R.drawable.background_game_menu);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        buttonParams.setMargins(margin, margin, margin, margin);
        horizonOverlay.addView(menuButton, buttonParams);
        menuButton.setOnClickListener(view -> showControlsMenu());
        setFlintControlsVisible(flintControlsEnabled);
        startHostGrabPolling();
    }

    private void startHostGrabPolling() {
        if (hostGrabPolling || controlsOverlay == null) return;
        hostGrabPolling = true;
        hostGrabPoll.run();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setFlintControlsVisible(boolean enabled) {
        if (horizonOverlay == null) return;
        if (!enabled) {
            if (controlsOverlay != null) horizonOverlay.removeView(controlsOverlay);
            controlsOverlay = null;
            return;
        }
        if (controlsOverlay != null) return;
        controlsOverlay = new ControlLayout(this);
        controlsOverlay.setModifiable(false);
        controlsOverlay.setMenuListener(this);
        try {
            controlsOverlay.loadLayout((String) null);
            controlsOverlay.setControlVisible(true);
            horizonOverlay.addView(controlsOverlay, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            startHostGrabPolling();
        } catch (Exception error) {
            Log.e("FlintHorizon", "Could not load Flint's control layout", error);
            controlsOverlay = null;
        }
    }

    private void showControlsMenu() {
        String[] choices = {"Horizon touch controls", "Flint controls"};
        new AlertDialog.Builder(this)
                .setTitle("Minecraft controls")
                .setSingleChoiceItems(choices, flintControlsEnabled ? 1 : 0, (dialog, which) -> {
                    boolean enableFlint = which == 1;
                    flintControlsEnabled = enableFlint;
                    getSharedPreferences(CONTROLS_PREFS, MODE_PRIVATE).edit()
                            .putBoolean(CONTROLS_KEY, enableFlint).apply();
                    setEnv(HOST_CONTROLS_ENV, enableFlint ? "1" : null);
                    setFlintControlsVisible(enableFlint);
                    dialog.dismiss();
                    android.widget.Toast.makeText(this,
                            "Control mode saved. Restart Minecraft to fully switch Horizon's input device.",
                            android.widget.Toast.LENGTH_LONG).show();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override public void onClickedMenu() { showControlsMenu(); }

    @Override
    protected void onDestroy() {
        hostInputHandler.removeCallbacks(hostGrabPoll);
        hostGrabPolling = false;
        if (horizonOverlay != null) horizonOverlay.removeAllViews();
        controlsOverlay = null;
        CallbackBridge.setHostGrabbed(false);
        CallbackBridge.setHostInputSink(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 4101) {
            nativeDocumentResult(requestCode, resultCode, data == null || data.getData() == null ? null : data.getData().toString());
        }
    }
}
