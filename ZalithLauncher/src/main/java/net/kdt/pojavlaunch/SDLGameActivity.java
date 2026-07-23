package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * Hosts Minecraft when the launched version needs the SDL3 backend
 * (26.3-snapshot-4 and later) instead of GLFW.
 *
 * This is a SEPARATE Activity from MainActivity/GameActivity on purpose:
 * SDLActivity assumes it owns the whole Activity lifecycle (it creates its
 * own surface and sets its own singleton in onCreate), so rather than
 * patching SDL's internals to embed inside our existing GLFW-based
 * GameActivity, we let SDL own this Activity outright and pick which
 * Activity to launch based on the target Minecraft version.
 *
 * STATUS: skeleton only. Still needed before this actually launches Minecraft:
 *   - Kick off the actual JVM/game process (mirror whatever MainActivity /
 *     LaunchGame currently does), once we've decided where that hook goes.
 *   - Wire touch controls into this surface once CallbackBridge has SDL3
 *     branching (the "step 2" we talked about doing next).
 *   - Pull in BaseActivity's shared setup (theming etc.) manually, since we
 *     can't extend both SDLActivity and BaseActivity.
 */
public class SDLGameActivity extends SDLActivity {

    private static final String TAG = "SDLGameActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate: launching with SDL3 backend");
        super.onCreate(savedInstanceState);
    }

    /**
     * SDL calls this itself during its onCreate to build the surface it
     * will render into. Returning our own subclass here is the supported
     * extension point instead of us touching SDLActivity internals.
     */
    @Override
    protected SDLSurface createSDLSurface(Context context) {
        Log.i(TAG, "createSDLSurface: creating FlintLauncher SDL3 surface");
        return new MinecraftSDLSurface(context);
    }

    /**
     * Minimal subclass for now — just proves the surface is being created
     * and lets us log surface lifecycle events while we build this out.
     * Once CallbackBridge has SDL3 branching, FlintLauncher-specific touch
     * handling will likely live here too, alongside what SDLSurface already
     * does for us (it already forwards touch/mouse/resize to
     * SDLActivity.onNativeXxx on its own — see SDLSurface.java).
     */
    public static class MinecraftSDLSurface extends SDLSurface {
        protected MinecraftSDLSurface(Context context) {
            super(context);
        }
    }
}
