package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;

import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.launch.LaunchGame;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.utils.path.PathManager;

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

        // Reduced stand-in for Tools.updateWindowSize() -- see class javadoc.
        DisplayMetrics displayMetrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getDisplay().getRealMetrics(displayMetrics);
        } else {
            getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        }
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
        controlLayout.loadLayout(minecraftVersion.getControl());
        controlLayout.toggleControlVisible();
        ((ViewGroup) findViewById(android.R.id.content)).addView(
                controlLayout,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
    }

    /**
     * Fired when the in-game menu/pause button is tapped. MainActivity has a
     * full pause menu (GameMenuViewWrapper etc.) -- this is a minimal stand-in
     * that just exits the game, not a full port of that menu. Revisit if a
     * proper in-game menu is wanted for the SDL3 path.
     */
    @Override
    public void onClickedMenu() {
        finish();
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
     * LaunchGame.runGame() blocks until the JVM exits, so returning from
     * this method (and SDL's own finish() call right after) lines up
     * correctly with "Minecraft exited, close the Activity."
     */
    @Override
    protected void main() {
        try {
            JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(minecraftVersion);
            LaunchGame.runGame(this, minecraftVersion, versionInfo);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to launch Minecraft on SDL3 backend", e);
            Tools.showErrorRemote(e);
        }
    }
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

        // Reduced stand-in for Tools.updateWindowSize() -- see class javadoc.
        DisplayMetrics displayMetrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getDisplay().getRealMetrics(displayMetrics);
        } else {
            getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        }
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
        controlLayout.loadLayout(minecraftVersion.getControl());
        controlLayout.toggleControlVisible();
        ((ViewGroup) findViewById(android.R.id.content)).addView(
                controlLayout,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
    }

    /**
     * Fired when the in-game menu/pause button is tapped. MainActivity has a
     * full pause menu (GameMenuViewWrapper etc.) -- this is a minimal stand-in
     * that just exits the game, not a full port of that menu. Revisit if a
     * proper in-game menu is wanted for the SDL3 path.
     */
    @Override
    public void onClickedMenu() {
        finish();
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
     * LaunchGame.runGame() blocks until the JVM exits, so returning from
     * this method (and SDL's own finish() call right after) lines up
     * correctly with "Minecraft exited, close the Activity."
     */
    @Override
    protected void main() {
        try {
            JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(minecraftVersion);
            LaunchGame.runGame(this, minecraftVersion, versionInfo);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to launch Minecraft on SDL3 backend", e);
            Tools.showErrorRemote(e);
        }
    }
}
