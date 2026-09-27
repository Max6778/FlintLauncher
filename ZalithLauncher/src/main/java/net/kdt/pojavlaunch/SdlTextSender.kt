// Ported from ZalithLauncher2 (com.movtery.zalithlauncher.game.sdl.SdlTextSender).
// Adapted for FlintLauncher:
//   - package renamed to net.kdt.pojavlaunch
//   - SdlBridge.sdlEnabled (Zalith's activity-less SDL lifecycle flag, not present
//     in Flint) replaced with CallbackBridge.usingSdl3, Flint's own equivalent flag
//     (flipped true in CallbackBridge.notifyLauncher() once real SDL init begins)
//   - EfficientAndroidLWJGLKeycode already exists in Flint at this same package,
//     so the import just drops the com.movtery.zalithlauncher.game.input prefix

package net.kdt.pojavlaunch

import android.view.KeyEvent
import org.libsdl.app.SDLActivity
import org.lwjgl.glfw.CallbackBridge

object SdlTextSender {
    @JvmStatic
    fun sendChar(character: Char) {
        if (!CallbackBridge.usingSdl3) return
        SDLActivity.onNativeTextInput(character.toString())
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
        val keyCode = EfficientAndroidLWJGLKeycode.getSdlAndroidKeycode(lwjglGlfwKeycode)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        SDLActivity.onNativeKeyDown(keyCode)
        SDLActivity.onNativeKeyUp(keyCode)
    }
}
