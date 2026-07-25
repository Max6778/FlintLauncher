package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;

import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.launch.LaunchGame;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.renderer.Renderers;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

/**
 * Hosts Minecraft when the launched version needs the SDL3 backend
 * (26.3-snapshot-4 and later) instead of GLFW.
 *
 * Separate Activity from MainActivity on purpose — SDLActivity owns its
 * own Activity lifecycle, so rather than patching SDL internals we let
 * it own this Activity outright.
 *
 * RESOLVED — the EGL context question flagged in earlier drafts: SDL's own
 * native code creates its own EGL surface internally via SDLActivity's
 * onNativeSurfaceChanged (confirmed from SDL's own source/issue tracker).
 * Android only allows one EGL producer per Surface at a time, so
 * JREUtils.setupBridgeWindow() (the GLFW path's EGL setup call) must NOT be
 * called here — it would conflict with SDL's own context instead of
 * cooperating with it. See the comment in MinecraftSDLSurface.surfaceChanged
 * below.
 *
 * Known simplification: the display-metrics setup below is a reduced version
 * of Tools.updateWindowSize()/getDisplayMetrics() — those require BaseActivity
 * specifically (they call activity.shouldIgnoreNotch(), a custom BaseActivity
 * method), which SDLGameActivity can never satisfy. This skips notch-cropping
 * and multi-window-mode sizing that the GLFW path has. Fine for a first test;
 * revisit if display sizing looks wrong on a notched or split-screen device.
 */
public class SDLGameActivity extends SDLActivity {

    private static final String TAG = "SDLGameActivity";

    private Version minecraftVersion;
    private boolean hasLaunched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        minecraftVersion = getIntent().getParcelableExtra(MainActivity.INTENT_VERSION);
        if (minecraftVersion == null) {
            Log.e(TAG, "onCreate: no Version passed via MainActivity.INTENT_VERSION, cannot launch");
            finish();
            return;
        }
        Log.i(TAG, "onCreate: launching " + minecraftVersion.getVersionName() + " with SDL3 backend");

        // Same renderer/driver selection MainActivity does — backend-agnostic,
        // just picks which GL driver library gets loaded. init() must run first
        // to populate the available-renderers list (MainActivity calls this too,
        // but SDLGameActivity is a fresh process/activity that never went through
        // MainActivity's onCreate, so it has to do this itself).
        Renderers.INSTANCE.init(false);
        Renderers.INSTANCE.setCurrentRenderer(this, minecraftVersion.getRenderer(), false);
        DriverPluginManager.setDriverByName(minecraftVersion.getDriver());

        // Reduced stand-in for Tools.updateWindowSize() — see class javadoc.
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
     * Fires once the SDL3 surface reports it's actually ready to render
     * into — the same "wait for surface, then launch" pattern MainActivity
     * uses via mainGameRenderView.setSurfaceReadyListener(...).
     */
    private void onSdlSurfaceReady() {
        if (hasLaunched || minecraftVersion == null) return;
        hasLaunched = true;
        try {
            JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(minecraftVersion);
            LaunchGame.runGame(this, minecraftVersion, versionInfo);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to launch Minecraft on SDL3 backend", e);
            Tools.showErrorRemote(e);
        }
    }

    public class MinecraftSDLSurface extends SDLSurface {
        protected MinecraftSDLSurface(Context context) {
            super(context);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.surfaceChanged(holder, format, width, height);
            if (mIsSurfaceReady) {
                // Do NOT call JREUtils.setupBridgeWindow() here. SDL's own native
                // code already creates its own EGL surface on this Surface via
                // SDLActivity's native onNativeSurfaceChanged, which super.surfaceChanged()
                // above already triggered. Android only allows one EGL producer per
                // Surface at a time, so calling setupBridgeWindow() too would conflict
                // with SDL's own context instead of cooperating with it.
                onSdlSurfaceReady();
            }
        }
    }
}
