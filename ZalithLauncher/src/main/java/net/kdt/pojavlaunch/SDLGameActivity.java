package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.SeekBar;

import com.movtery.zalithlauncher.databinding.ViewGameMenuBinding;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.launch.LaunchGame;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.setting.AllStaticSettings;
import com.movtery.zalithlauncher.ui.fragment.settings.VideoSettingsFragment;
import com.movtery.zalithlauncher.ui.subassembly.menu.MenuUtils;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;

import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

/**
 * Hosts Minecraft when the launched version needs the SDL3 backend
 * (26.3-snapshot-5 and later, currently) instead of GLFW.
 *
 * Separate Activity from MainActivity on purpose — SDLActivity owns its
 * own Activity lifecycle, so rather than patching SDL internals we let
 * it own this Activity outright.
 *
 * EGL context: SDL's own native code creates its own EGL surface
 * internally via SDLActivity's onNativeSurfaceChanged (confirmed from
 * SDL's own source/issue tracker). Android only allows one EGL producer
 * per Surface at a time, so JREUtils.setupBridgeWindow() (the GLFW path's
 * EGL setup call) must NEVER be called here.
 *
 * main()/getLibraries() override: stock SDLActivity assumes it's hosting
 * a native C game — it tries to dlopen "libmain.so" (via getLibraries())
 * and run a native "SDL_main" entry point (via main()), then calls
 * finish() on the Activity the moment main() returns. Minecraft is a
 * Java program run inside a separately-launched JVM (LaunchGame.runGame
 * -> JREUtils.launchWithUtils -> VMLauncher.launchJVM), not a native SDL
 * program, so:
 *   - getLibraries() drops "main" from the default {"SDL3","main"} list,
 *     since there's no libmain.so to load. SDL3 stays, since that's the
 *     actual System.loadLibrary("SDL3", ...) call.
 *   - main() is overridden to call LaunchGame.runGame(...) directly
 *     instead of the default nativeRunMain()/SDL_main behavior.
 *     LaunchGame.runGame() already blocks until the JVM exits, so this
 *     lines up exactly with SDL's own "block in main(), finish() when it
 *     returns" behavior -- when Minecraft exits, the Activity correctly
 *     closes, same as it would for a real native SDL game.
 *   - main() runs on SDL's own "SDLThread", which SDL itself only spawns
 *     once the surface is ready AND the Activity is resumed AND has
 *     focus (see SDLActivity.handleNativeState()) -- this is actually
 *     MORE correct than the previous manual surfaceChanged()-based
 *     "wait for surface" hook, which didn't check resume/focus state.
 *
 * Known simplification: the display-metrics setup below is a reduced
 * version of Tools.updateWindowSize()/getDisplayMetrics() -- those
 * require BaseActivity specifically (they call
 * activity.shouldIgnoreNotch(), a custom BaseActivity method), which
 * SDLGameActivity can never satisfy. This skips notch-cropping and
 * multi-window-mode sizing that the GLFW path has. Fine for a first
 * test; revisit if display sizing looks wrong on a notched or
 * split-screen device.
 */
public class SDLGameActivity extends SDLActivity implements ControlButtonMenuListener {

    private static final String TAG = "SDLGameActivity";

    private Version minecraftVersion;
    private ControlLayout controlLayout;
    private ViewGameMenuBinding gameMenuBinding;
    private androidx.drawerlayout.widget.DrawerLayout gameMenuDrawer;
    private com.kdt.LoggerView loggerView;
    private com.movtery.zalithlauncher.ui.dialog.KeyboardDialog keyboardDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // BaseActivity (which MainActivity extends) fixes up PathManager's path
        // constants via attachBaseContext() -> LocaleUtils.setLocale() ->
        // PathManager.initContextConstants(). SDLGameActivity extends SDLActivity
        // extends plain Activity, so it never goes through that path at all --
        // PathManager.DIR_ACCOUNT_NEW (and potentially others) stay stuck at
        // whatever PojavApplication.onCreate() set them to, which uses a
        // DIFFERENT (wrong) formula than initContextConstants() does. Confirmed
        // via diagnostic logging: this was the actual root cause of the
        // AccountsManager NPE, not anything about AccountsManager itself.
        PathManager.initContextConstants(this);

        // BaseActivity.onCreate()/onResume() does all of this for every other
        // Activity in the app -- SDLGameActivity skips BaseActivity entirely
        // (same reason as the PathManager fix above), so it has to do this
        // itself too. Tools.setFullscreen() is the actual real immersive-mode
        // call (hides nav bar/status bar) -- its absence here was the real
        // cause of the misplaced controls, not just the display-metrics
        // mismatch fixed separately below: MainActivity's window is genuinely
        // a different (larger, system-bars-hidden) size than what
        // SDLGameActivity was rendering into before this fix.
        Tools.setFullscreen(this);
        com.movtery.zalithlauncher.context.ContextExecutor.setActivity(this);
        com.movtery.zalithlauncher.utils.StoragePermissionsUtils.checkPermissions(this);
        com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager.INSTANCE.refreshPath();

        // Tools.ignoreNotch()/Tools.updateWindowSize() intentionally NOT called --
        // both require BaseActivity specifically (shouldIgnoreNotch() is a custom
        // BaseActivity method), which SDLGameActivity can never satisfy. Notch
        // cropping is skipped; display-metrics equivalent is handled manually
        // below via getResources().getDisplayMetrics(), matching what
        // updateWindowSize() does internally.

        minecraftVersion = getIntent().getParcelableExtra(MainActivity.INTENT_VERSION);
        if (minecraftVersion == null) {
            Log.e(TAG, "onCreate: no Version passed via MainActivity.INTENT_VERSION, cannot launch");
            finish();
            return;
        }
        Log.i(TAG, "onCreate: launching " + minecraftVersion.getVersionName() + " with SDL3 backend");

        // These match what MainActivity.initLayout() does before launch --
        // found by reading MainActivity's actual onCreate rather than
        // waiting for each one to crash separately.
        try {
            java.io.File latestLogFile = new java.io.File(PathManager.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile()) {
                throw new java.io.IOException("Failed to create a new log file");
            }
            Logger.begin(latestLogFile.getAbsolutePath());
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to start Logger", e);
        }
        MainActivity.GLOBAL_CLIPBOARD = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        // AccountsManager.currentAccount is a computed getter that falls back to
        // accounts.firstOrNull() if the persisted UUID lookup fails -- but
        // `accounts` is empty until reload() scans the accounts folder. Same
        // "used before init" pattern as Renderers/DriverPluginManager/MCOptions
        // above; without this, LaunchGame.runGame()'s `currentAccount!!` NPEs.
        com.movtery.zalithlauncher.feature.accounts.AccountsManager.INSTANCE.reload();

        // MCOptions has a lateinit var that's only set by this call.
        com.movtery.zalithlauncher.feature.MCOptions.INSTANCE.setup(this, () -> minecraftVersion);

        // Renderer/driver selection -- backend-agnostic, same calls MainActivity
        // makes. init()/initDriver() populate the available lists; MainActivity
        // calls those too, but SDLGameActivity is a fresh process/activity that
        // never went through MainActivity's onCreate, so it has to do this itself.
        // Plugin renderers (MobileGlues, Krypton Wrapper, etc.) are only known to
        // Renderers after this scan runs. Without it, setCurrentRenderer() below
        // won't recognize a plugin renderer ID and silently falls back to the
        // first built-in one (GL4ES) instead of what was actually selected.
        com.movtery.zalithlauncher.plugins.PluginLoader.loadAllPlugins(this, false);

        Renderers.INSTANCE.init(false);
        Renderers.INSTANCE.setCurrentRenderer(this, minecraftVersion.getRenderer(), false);
        DriverPluginManager.INSTANCE.initDriver(this, false);
        DriverPluginManager.setDriverByName(minecraftVersion.getDriver());

        // Matches Tools.getDisplayMetrics()/updateWindowSize()'s real implementation
        // exactly: getResources().getDisplayMetrics(), NOT getRealMetrics(). These
        // genuinely differ -- getRealMetrics() includes system bars/navigation
        // regardless of what's actually usable, getResources().getDisplayMetrics()
        // reflects the app's actual window area. The control-button positioning
        // formulas (ControlButton's dynamicX/dynamicY, using ${screen_width}/
        // ${screen_height} resolved from CallbackBridge.physicalWidth/Height) were
        // built against the real implementation's metrics, so using the wrong one
        // here was producing incorrectly-placed buttons, especially right/bottom-
        // anchored ones. See class javadoc -- this replaces the earlier
        // "known simplification" that used getRealMetrics().
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        Tools.currentDisplayMetrics = displayMetrics;
        CallbackBridge.physicalWidth = displayMetrics.widthPixels;
        CallbackBridge.physicalHeight = displayMetrics.heightPixels;

        CallbackBridge.usingSdl3 = true;

        super.onCreate(savedInstanceState);

        // MainActivity's layout (activity_game.xml) wraps its render surface
        // AND the touch controls inside the same ControlLayout. SDLActivity's
        // own onCreate() already set up its own minimal content view with
        // just the SDL surface -- we're adding a ControlLayout as an ADDITIONAL
        // overlay on top (via android.R.id.content), rather than nesting the
        // SDL surface inside it like MainActivity does, since SDL's surface
        // needs to stay inside SDL's own managed view hierarchy for its native
        // surface callbacks to keep firing correctly.
        //
        // UNVERIFIED: ControlLayout normally has MinecraftGLSurface as a
        // direct XML child in activity_game.xml. Using it standalone (no
        // render-surface child) here is untested -- if buttons don't render
        // or touches don't route correctly, this is the first thing to
        // revisit.
        controlLayout = new ControlLayout(this);
        controlLayout.setModifiable(false);
        controlLayout.setMenuListener(this);
        try {
            controlLayout.loadLayout(minecraftVersion.getControl());
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to load control layout, falling back to default", e);
            try {
                controlLayout.loadLayout((String) null);
            } catch (java.io.IOException e2) {
                Log.e(TAG, "Failed to load default control layout too", e2);
            }
        }
        controlLayout.toggleControlVisible();
        ((ViewGroup) findViewById(android.R.id.content)).addView(
                controlLayout,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );

        // Real pause/settings menu, same layout + logic MainActivity uses --
        // scoped to the 3 things actually asked for (force close, FPS toggle,
        // resolution scaler). The XML also has log output, custom key, memory
        // toggle, gesture/mouse-speed settings, and custom-control-replacement --
        // those are present in the inflated view but NOT wired up here, so
        // tapping them currently does nothing. Separate follow-up if wanted.
        gameMenuBinding = ViewGameMenuBinding.inflate(getLayoutInflater());

        gameMenuBinding.forceClose.setOnClickListener(v -> ZHTools.dialogForceClose(this));

        gameMenuBinding.openFpsInfo.setChecked(AllSettings.getGameMenuShowFPS().getValue());
        gameMenuBinding.openFpsInfoLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.openFpsInfo));
        gameMenuBinding.openFpsInfo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AllSettings.getGameMenuShowFPS().put(isChecked).save();
            // NOTE: MainActivity also calls mGameMenuWrapper.refreshSettingsState()
            // here to update a floating FPS/memory badge -- that widget isn't
            // ported for the SDL3 path, so the setting saves correctly but
            // there's no floating badge to refresh yet.
        });

        MenuUtils.initSeekBarValue(gameMenuBinding.resolutionScaler, AllSettings.getResolutionRatio().getValue(), gameMenuBinding.resolutionScalerValue, "%");
        gameMenuBinding.resolutionScalerPreview.setText(VideoSettingsFragment.getResolutionRatioPreview(getResources(), AllSettings.getResolutionRatio().getValue()));
        gameMenuBinding.resolutionScalerRemove.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.resolutionScaler, -1));
        gameMenuBinding.resolutionScalerAdd.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.resolutionScaler, 1));
        gameMenuBinding.resolutionScaler.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                MenuUtils.updateSeekbarValue(progress, gameMenuBinding.resolutionScalerValue, "%");
                gameMenuBinding.resolutionScalerPreview.setText(VideoSettingsFragment.getResolutionRatioPreview(getResources(), progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                AllSettings.getResolutionRatio().put(progress).save();
                AllStaticSettings.scaleFactor = progress / 100f;
                // NOTE: MainActivity also calls mainGameRenderView.refreshSize()
                // here to apply the new resolution live, without restarting.
                // There's no SDL-side equivalent hook confirmed yet -- the
                // setting saves correctly and will take effect on next launch,
                // but may not resize live during this session. Flagging rather
                // than guessing at a live-resize call that might not exist.
            }
        });

        // Real log output view + button, same as MainActivity's
        // binding.logOutput -> MainActivity.binding.mainLoggerView.toggleViewWithAnim()
        loggerView = new com.kdt.LoggerView(this);
        loggerView.setVisibility(android.view.View.GONE);
        gameMenuBinding.logOutput.setOnClickListener(v -> loggerView.toggleViewWithAnim());
        ((ViewGroup) findViewById(android.R.id.content)).addView(
                loggerView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );

        // Real DrawerLayout, same slide-in-from-side mechanism MainActivity uses
        // (mainDrawerOptions.openDrawer/closeDrawer with GravityCompat.START) --
        // not a flat show/hide toggle.
        gameMenuDrawer = new androidx.drawerlayout.widget.DrawerLayout(this);
        android.view.View dummyMainContent = new android.view.View(this);
        gameMenuDrawer.addView(dummyMainContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        androidx.drawerlayout.widget.DrawerLayout.LayoutParams drawerParams =
                new androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        drawerParams.gravity = android.view.Gravity.START;
        gameMenuDrawer.addView(gameMenuBinding.getRoot(), drawerParams);

        gameMenuBinding.openMemoryInfo.setChecked(AllSettings.getGameMenuShowMemory().getValue());
        gameMenuBinding.openMemoryInfoLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.openMemoryInfo));
        gameMenuBinding.openMemoryInfo.setOnCheckedChangeListener((b, isChecked) -> AllSettings.getGameMenuShowMemory().put(isChecked).save());

        gameMenuBinding.disableGestures.setChecked(AllSettings.getDisableGestures().getValue());
        gameMenuBinding.disableGesturesLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.disableGestures));
        gameMenuBinding.disableGestures.setOnCheckedChangeListener((b, isChecked) -> {
            gameMenuBinding.timeLongPressTriggerLayout.setVisibility(isChecked ? android.view.View.GONE : android.view.View.VISIBLE);
            AllSettings.getDisableGestures().put(isChecked).save();
        });

        gameMenuBinding.disableDoubleTap.setChecked(AllSettings.getDisableDoubleTap().getValue());
        gameMenuBinding.disableDoubleTapLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.disableDoubleTap));
        gameMenuBinding.disableDoubleTap.setOnCheckedChangeListener((b, isChecked) -> {
            AllSettings.getDisableDoubleTap().put(isChecked).save();
            AllStaticSettings.disableDoubleTap = isChecked;
        });

        // NOTE: enableGyro/gyroInvertX/gyroInvertY save settings correctly, but
        // skip mGyroControl.updateOrientation()/enable()/disable() -- that object
        // is MainActivity-specific and not ported. Gyro control itself may not
        // actually turn on/off live even though the setting saves.
        gameMenuBinding.enableGyro.setChecked(AllSettings.getEnableGyro().getValue());
        gameMenuBinding.enableGyroLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.enableGyro));
        gameMenuBinding.enableGyro.setOnCheckedChangeListener((b, isChecked) -> {
            gameMenuBinding.gyroLayout.setVisibility(isChecked ? android.view.View.VISIBLE : android.view.View.GONE);
            AllSettings.getEnableGyro().put(isChecked).save();
            AllStaticSettings.enableGyro = isChecked;
        });
        gameMenuBinding.gyroInvertX.setChecked(AllSettings.getGyroInvertX().getValue());
        gameMenuBinding.gyroInvertXLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.gyroInvertX));
        gameMenuBinding.gyroInvertX.setOnCheckedChangeListener((b, isChecked) -> {
            AllSettings.getGyroInvertX().put(isChecked).save();
            AllStaticSettings.gyroInvertX = isChecked;
        });
        gameMenuBinding.gyroInvertY.setChecked(AllSettings.getGyroInvertY().getValue());
        gameMenuBinding.gyroInvertYLayout.setOnClickListener(v -> MenuUtils.toggleSwitchState(gameMenuBinding.gyroInvertY));
        gameMenuBinding.gyroInvertY.setOnCheckedChangeListener((b, isChecked) -> {
            AllSettings.getGyroInvertY().put(isChecked).save();
            AllStaticSettings.gyroInvertY = isChecked;
        });

        gameMenuBinding.timeLongPressTriggerRemove.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.timeLongPressTrigger, -1));
        gameMenuBinding.timeLongPressTriggerAdd.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.timeLongPressTrigger, 1));
        gameMenuBinding.timeLongPressTrigger.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                MenuUtils.updateSeekbarValue(progress, gameMenuBinding.timeLongPressTriggerValue, "ms");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int progress = s.getProgress();
                AllSettings.getTimeLongPressTrigger().put(progress).save();
                AllStaticSettings.timeLongPressTrigger = progress;
            }
        });

        gameMenuBinding.mouseSpeedRemove.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.mouseSpeed, -1));
        gameMenuBinding.mouseSpeedAdd.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.mouseSpeed, 1));
        gameMenuBinding.mouseSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                MenuUtils.updateSeekbarValue(progress, gameMenuBinding.mouseSpeedValue, "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                AllSettings.getMouseSpeed().put(s.getProgress()).save();
            }
        });

        gameMenuBinding.gyroSensitivityRemove.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.gyroSensitivity, -1));
        gameMenuBinding.gyroSensitivityAdd.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.gyroSensitivity, 1));
        gameMenuBinding.gyroSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                MenuUtils.updateSeekbarValue(progress, gameMenuBinding.gyroSensitivityValue, "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int progress = s.getProgress();
                AllSettings.getGyroSensitivity().put(progress).save();
                AllStaticSettings.gyroSensitivity = progress;
            }
        });

        // NOTE: hotbarWidth/Height save correctly and post the real HotbarChangeEvent
        // (same as MainActivity), so anything listening for that event (e.g. the
        // hotbar UI itself, if present) still updates live.
        gameMenuBinding.hotbarWidthRemove.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.hotbarWidth, -1));
        gameMenuBinding.hotbarWidthAdd.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.hotbarWidth, 1));
        gameMenuBinding.hotbarWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                MenuUtils.updateSeekbarValue(progress, gameMenuBinding.hotbarWidthValue, "px");
                org.greenrobot.eventbus.EventBus.getDefault().post(new com.movtery.zalithlauncher.event.value.HotbarChangeEvent(progress, gameMenuBinding.hotbarHeight.getProgress()));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                AllSettings.getHotbarWidth().getValue().put(s.getProgress()).save();
            }
        });
        gameMenuBinding.hotbarHeightRemove.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.hotbarHeight, -1));
        gameMenuBinding.hotbarHeightAdd.setOnClickListener(v -> MenuUtils.adjustSeekbar(gameMenuBinding.hotbarHeight, 1));
        gameMenuBinding.hotbarHeight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                MenuUtils.updateSeekbarValue(progress, gameMenuBinding.hotbarHeightValue, "px");
                org.greenrobot.eventbus.EventBus.getDefault().post(new com.movtery.zalithlauncher.event.value.HotbarChangeEvent(gameMenuBinding.hotbarWidth.getProgress(), progress));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                AllSettings.getHotbarHeight().getValue().put(s.getProgress()).save();
            }
        });

        // Still NOT wired (need extra dialog classes not yet verified for this
        // path): sendCustomKey (dialogSendCustomKey), customMouse (SelectMouseDialog),
        // replacementCustomcontrol/editControl (control-scheme editor), hotbarType
        // spinner. Tapping these still does nothing.

        // Custom key input -- same real logic as MainActivity's dialogSendCustomKey()/
        // sendKeyPress(): CallbackBridge already branches on usingSdl3 internally
        // (from earlier work), so this routes to SDL3 correctly with no extra code.
        keyboardDialog = new com.movtery.zalithlauncher.ui.dialog.KeyboardDialog(this).setShowSpecialButtons(false);
        gameMenuBinding.sendCustomKey.setOnClickListener(v -> keyboardDialog.setOnMultiKeycodeSelectListener(selectedKeycodes -> {
            com.movtery.zalithlauncher.task.Task.runTask(() -> {
                selectedKeycodes.forEach(keycode -> {
                    int lwjglKeycode = EfficientAndroidLWJGLKeycode.getValueByIndex(keycode);
                    if (keycode >= LwjglGlfwKeycode.GLFW_KEY_UNKNOWN) {
                        CallbackBridge.sendKeyPress(lwjglKeycode, CallbackBridge.getCurrentMods(), true);
                        CallbackBridge.setModifiers(lwjglKeycode, true);
                    }
                });
                return null;
            }).ended(a -> {
                try { Thread.sleep(50); } catch (InterruptedException ignore) {}
                selectedKeycodes.forEach(keycode -> {
                    int lwjglKeycode = EfficientAndroidLWJGLKeycode.getValueByIndex(keycode);
                    if (keycode >= LwjglGlfwKeycode.GLFW_KEY_UNKNOWN) {
                        CallbackBridge.sendKeyPress(lwjglKeycode, CallbackBridge.getCurrentMods(), false);
                        CallbackBridge.setModifiers(lwjglKeycode, false);
                    }
                });
            }).execute();
        }).show());

        // Mouse cursor picker -- same real dialog MainActivity uses. NOTE: the
        // refresh callback is a no-op here (MainActivity refreshes its Touchpad
        // view's cursor drawable; we don't have that view in the SDL3 overlay),
        // so the chosen cursor is saved but won't visually refresh until next launch.
        gameMenuBinding.customMouse.setOnClickListener(v ->
                new com.movtery.zalithlauncher.ui.dialog.SelectMouseDialog(this, () -> {}).show());

        // Control-scheme replacement -- same real dialog, applied to our own
        // controlLayout instead of MainActivity's mainControlLayout.
        gameMenuBinding.replacementCustomcontrol.setOnClickListener(v -> {
            com.movtery.zalithlauncher.ui.dialog.SelectControlsDialog dialog =
                    new com.movtery.zalithlauncher.ui.dialog.SelectControlsDialog(this, file -> {
                        try {
                            controlLayout.loadLayout(file.getAbsolutePath());
                        } catch (java.io.IOException ignored) {}
                    });
            dialog.setTitleText(com.movtery.zalithlauncher.R.string.replacement_customcontrol);
            dialog.show();
        });

        // Control-scheme editor -- minimal version: enables drag-to-edit mode on
        // our existing controlLayout overlay directly, rather than MainActivity's
        // full navigation-drawer-content-swap flow (mControlSettingsBinding etc,
        // not ported). Tapping editControl again does NOT exit edit mode yet --
        // only entry is wired.
        gameMenuBinding.editControl.setOnClickListener(v -> controlLayout.setModifiable(true));

        ((ViewGroup) findViewById(android.R.id.content)).addView(
                gameMenuDrawer,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        Tools.setFullscreen(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Tools.setFullscreen(this);
    }

    /**
     * Fired when the in-game menu/pause button is tapped. Toggles the real
     * settings menu (force close, FPS toggle, resolution scaler) on/off,
     * same layout MainActivity uses.
     */
    @Override
    public void onClickedMenu() {
        if (gameMenuDrawer == null) return;
        if (gameMenuDrawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            gameMenuDrawer.closeDrawer(androidx.core.view.GravityCompat.START);
        } else {
            gameMenuDrawer.openDrawer(androidx.core.view.GravityCompat.START);
        }
    }

    /**
     * SDL calls this itself during its onCreate to build the surface it
     * will render into. Returning our own subclass here is the supported
     * extension point instead of touching SDLActivity internals.
     */
    @Override
    protected SDLSurface createSDLSurface(Context context) {
        Log.i(TAG, "createSDLSurface: creating FlintLauncher SDL3 surface");
        return new MinecraftSDLSurface(context);
    }

    /**
     * SDLSurface's constructor is protected -- can't call `new SDLSurface(context)`
     * directly from outside org.libsdl.app, even from createSDLSurface() here.
     * This subclass exists only to satisfy that; no behavior is overridden.
     */
    public static class MinecraftSDLSurface extends SDLSurface {
        protected MinecraftSDLSurface(Context context) {
            super(context);
        }
    }

    /**
     * SDL only ships the actual "SDL3" native library -- there's no
     * libmain.so, since Minecraft isn't a native SDL C program. Dropping
     * "main" from the default {"SDL3","main"} list.
     */
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3" };
    }

    /**
     * Runs on SDL's own "SDLThread", which SDL spawns once the surface is
     * ready, the Activity is resumed, and has focus (see
     * SDLActivity.handleNativeState()). Replaces the default
     * nativeRunMain()/SDL_main behavior with actually launching Minecraft.
     *
     * SDLThread has no Android Looper by default. LaunchGame.runGame() ->
     * checkMemory() can construct a real Dialog (the low-RAM warning), and
     * Dialog/Handler construction requires a Looper on the calling thread --
     * confirmed via a real crash: "Can't create handler inside thread
     * Thread[SDLThread] that has not called Looper.prepare()". MainActivity's
     * own launch flow runs on a thread that already has one; SDLThread
     * doesn't. Fix: give this thread a real Looper, post the actual launch
     * work to it, and quit the looper only once that work finishes -- this
     * keeps main() blocking for the whole Minecraft session (so SDL's own
     * finish()-after-main() behavior still fires at the right time) while
     * making Dialog/Handler construction during that session actually work.
     */
    @Override
    protected void main() {
        android.os.Looper.prepare();
        new android.os.Handler(android.os.Looper.myLooper()).post(() -> {
            try {
                JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(minecraftVersion);
                LaunchGame.runGame(this, minecraftVersion, versionInfo);
            } catch (Throwable e) {
                Log.e(TAG, "Failed to launch Minecraft on SDL3 backend", e);
                Tools.showErrorRemote(e);
            } finally {
                android.os.Looper.myLooper().quitSafely();
            }
        });
        android.os.Looper.loop();
    }
}

