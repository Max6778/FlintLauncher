// Ported from ZalithLauncher2 (com.movtery.zalithlauncher.game.sdl.SdlTextSender).
// Adapted for FlintLauncher:
//   - package renamed to net.kdt.pojavlaunch
//   - SdlBridge.sdlEnabled (Zalith's activity-less SDL lifecycle flag, not present
//     in Flint) replaced with CallbackBridge.usingSdl3, Flint's own equivalent flag
//     (flipped true in CallbackBridge.notifyLauncher() once real SDL init begins)
//   - Flint has no SDLActivity.onNativeTextInput(): text is fed to SDL through
//     SDLInputConnection.nativeCommitText(String, int) instead (confirmed by reading
//     SDLInputConnection.java) -- fixed after the first attempt failed to compile
//     with "Unresolved reference 'onNativeTextInput'"
//   - Flint's actual keycode-lookup method is
//     EfficientAndroidLWJGLKeycode.getAndroidKeycode(int), not getSdlAndroidKeycode()
//     -- fixed after the first attempt failed to compile with
//     "Unresolved reference 'getSdlAndroidKeycode'"

package net.kdt.pojavlaunch

import android.view.KeyEvent
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLInputConnection
import org.lwjgl.glfw.CallbackBridge

object SdlTextSender {
    @JvmStatic
    fun sendChar(character: Char) {
        if (!CallbackBridge.usingSdl3) return
        SDLInputConnection.nativeCommitText(character.toString(), 1)
    }

    @JvmStatic
    fun sendEnter() {
        if (!CallbackBridge.usingSdl3) return
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER)
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER)
    }

    @JvmStatic
    fun sendKey(lwjglGlfwKeycode: Int) {
        if (!CallbackBridge.usingSdl3) return
        val keyCode = EfficientAndroidLWJGLKeycode.getAndroidKeycode(lwjglGlfwKeycode)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        SDLActivity.onNativeKeyDown(keyCode)
        SDLActivity.onNativeKeyUp(keyCode)
    }
}
