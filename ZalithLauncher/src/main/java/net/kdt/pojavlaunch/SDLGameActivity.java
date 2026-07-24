package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;

import com.movtery.zalithlauncher.tools.Tools;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;

/**
 * Hosts Minecraft when the launched version needs the SDL3 backend
 * (26.3-snapshot-4 and later) instead of GLFW.
 *
 * Separate Activity from MainActivity on purpose — see the note in the
 * previous draft: SDLActivity owns its own Activity lifecycle, so rather
 * than patching SDL internals we let it own this Activity outright.
 *
 * STILL UNVERIFIED / needs real on-device testing:
 *   - The ONE real open risk: SDL's own native code creates its own EGL/GL
 *     context on the surface internally. JREUtils.setupBridgeWindow() (called
 *     in MinecraftSDLSurface.surfaceChanged below) asks FlintLauncher's native
 *     bridge to ALSO create a context on the same Surface. Might conflict —
 *     genuinely unknown without a device test. See the comment at that call
 *     site if Minecraft launches but renders nothing.
 *
 * Resolved already (kept here as a log of what's been checked):
 *   - Renderer/driver selection (Renderers.setCurrentRenderer, DriverPluginManager)
 *     is backend-agnostic — same calls as MainActivity, done in onCreate above.
 *   - CallbackBridge.nativeSetUseInputStackQueue(...) intentionally NOT called —
 *     it's GLFW-native-queue bookkeeping that SDL3-mode Minecraft never reads.
 *   - Touch controls: CallbackBridge.usingSdl3 flag (set above) routes all
 *     existing touch-control code through SDL3 automatically — no separate
 *     wiring needed here.
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
        // just picks which GL driver library gets loaded.
        Renderers.INSTANCE.setCurrentRenderer(this, minecraftVersion.getRenderer(), false);
        DriverPluginManager.setDriverByName(minecraftVersion.getDriver());
        Tools.getDisplayMetrics(this); // sets CallbackBridge.physicalWidth/Height, used by touch controls regardless of backend

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
                // UNVERIFIED / real risk: SDL's own native code already creates its
                // own EGL/GL context on this same Surface internally. Calling
                // JREUtils.setupBridgeWindow() here asks FlintLauncher's native
                // bridge to ALSO create an EGL context on it. This may conflict —
                // untested, needs a real device run to see what actually happens.
                // If Minecraft fails to render (black screen / EGL errors in logcat)
                // after launch actually starts, this line is the first suspect.
                JREUtils.setupBridgeWindow(holder.getSurface());
                onSdlSurfaceReady();
            }
        }
    }
}
