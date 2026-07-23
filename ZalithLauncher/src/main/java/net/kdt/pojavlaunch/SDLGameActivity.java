package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;

import com.movtery.zalithlauncher.tools.Tools;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * Hosts Minecraft when the launched version needs the SDL3 backend
 * (26.3-snapshot-4 and later) instead of GLFW.
 *
 * Separate Activity from MainActivity on purpose — see the note in the
 * previous draft: SDLActivity owns its own Activity lifecycle, so rather
 * than patching SDL internals we let it own this Activity outright.
 *
 * STILL UNVERIFIED / needs real on-device testing:
 *   - Whether Renderers.setCurrentRenderer(...) / DriverPluginManager still
 *     apply the same way here. Those exist for your GLFW/EGL pipeline
 *     (egl_bridge.c) — SDL3 creates and owns its own native window/GL
 *     context internally, so it may not need (or may conflict with) that
 *     renderer-selection step. This needs to be tested on a device, not
 *     guessed at from source alone.
 *   - CallbackBridge.nativeSetUseInputStackQueue(...) — this is GLFW/
 *     CallbackBridge-specific bookkeeping; almost certainly NOT needed here
 *     since SDL3 doesn't route through CallbackBridge at all. Left out
 *     below on purpose — flagging in case something downstream assumes
 *     it's always been called.
 *   - Touch controls overlay is not wired up yet — that's the
 *     CallbackBridge SDL3-branching step we talked about doing next.
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
                onSdlSurfaceReady();
            }
        }
    }
}
