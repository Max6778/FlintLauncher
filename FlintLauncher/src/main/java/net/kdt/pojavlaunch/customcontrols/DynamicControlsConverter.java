package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;

import com.flintlauncher.ui.subassembly.customcontrols.ControlInfoData;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Converts responsive DroidBridge/Flint dynamic control profiles to Flint's live control model. */
public final class DynamicControlsConverter {
    private DynamicControlsConverter() {}

    public static boolean isDynamicProfile(JSONObject json) {
        return "DroidBridgeTouchControls".equals(json.optString("format"));
    }

    public static CustomControls convert(Context context, JSONObject json) throws JSONException {
        int sourceWidth = Math.max(1, json.optInt("sourceWidth", 1920));
        int sourceHeight = Math.max(1, json.optInt("sourceHeight", 1080));
        float displayScale = Math.min(
                (float) Tools.currentDisplayMetrics.widthPixels / sourceWidth,
                (float) Tools.currentDisplayMetrics.heightPixels / sourceHeight);
        float scalePercent = Math.max(10, json.optInt("preferredScale", 100));
        JSONArray sourceControls = json.optJSONArray("controls");
        List<ControlData> controls = new ArrayList<>();
        if (sourceControls != null) {
            for (int i = 0; i < sourceControls.length(); i++) {
                JSONObject button = sourceControls.optJSONObject(i);
                if (button == null) continue;
                ControlData converted = convertButton(button, sourceWidth, sourceHeight, displayScale, scalePercent);
                if (converted != null) controls.add(converted);
            }
        }

        ControlInfoData info = new ControlInfoData();
        info.name = json.optString("name", "Dynamic Touch Controls");
        info.version = "DroidBridge " + json.optInt("version", 1);
        info.author = "FlintLauncher";
        info.desc = "Responsive dynamic touch-control profile";
        return new CustomControls(controls, new ArrayList<>(), new ArrayList<>(), info);
    }

    private static ControlData convertButton(JSONObject button, int sourceWidth, int sourceHeight,
                                             float displayScale, float preferredScale) {
        String label = button.optString("label", "");
        String action = button.optString("action", "key");
        int[] keys = actionKeys(button, action);
        if (keys == null) return null;

        float x = (float) button.optDouble("x", 0);
        float y = (float) button.optDouble("y", 0);
        float width = Math.max(1f, (float) button.optDouble("width", 72));
        float height = Math.max(1f, (float) button.optDouble("height", 48));
        float sizePercent = (float) button.optDouble("sizePercent", 100) * preferredScale / 100f;
        float widthDp = Tools.pxToDp(width * displayScale * sizePercent / 100f);
        float heightDp = Tools.pxToDp(height * displayScale * sizePercent / 100f);
        float strokeDp = Tools.pxToDp((float) button.optDouble("strokeWidth", 1) * displayScale);
        float radiusPx = (float) button.optDouble("cornerRadius", 8);
        float radiusPercent = Math.min(100f, radiusPx * 200f / Math.min(width, height));

        String dynamicX = x + width / 2f > sourceWidth / 2f
                ? "${screen_width} - ${width} - " + ((sourceWidth - x - width) / sourceWidth) + " * ${screen_width}"
                : (x / sourceWidth) + " * ${screen_width}";
        String dynamicY = y + height / 2f > sourceHeight / 2f
                ? "${screen_height} - ${height} - " + ((sourceHeight - y - height) / sourceHeight) + " * ${screen_height}"
                : (y / sourceHeight) + " * ${screen_height}";

        return new ControlData(
                label, keys, dynamicX, dynamicY, widthDp, heightDp,
                button.optBoolean("toggle", false),
                (float) button.optDouble("opacity", 0.8),
                button.optInt("backgroundColor", 0x66000000),
                button.optInt("strokeColor", 0x99FFFFFF),
                strokeDp, radiusPercent,
                button.optBoolean("visibleInGame", true),
                button.optBoolean("visibleInMenu", true),
                button.optBoolean("swipeGesture", false),
                button.optBoolean("mousePassThrough", false));
    }

    private static int[] actionKeys(JSONObject button, String action) {
        switch (action) {
            case "keyboard": return special(ControlData.SPECIALBTN_KEYBOARD);
            case "toggle_controls": return special(ControlData.SPECIALBTN_TOGGLECTRL);
            case "virtual_mouse": return special(ControlData.SPECIALBTN_VIRTUALMOUSE);
            case "mouse":
                switch (button.optInt("mouseButton", 0)) {
                    case 1: return special(ControlData.SPECIALBTN_MOUSESEC);
                    case 2: return special(ControlData.SPECIALBTN_MOUSEMID);
                    default: return special(ControlData.SPECIALBTN_MOUSEPRI);
                }
            case "scroll":
                // DroidBridge follows GLFW wheel convention: positive Y scrolls up.
                return special(button.optDouble("scrollY", 0) >= 0
                        ? ControlData.SPECIALBTN_SCROLLUP : ControlData.SPECIALBTN_SCROLLDOWN);
            case "key":
                JSONArray jsonKeys = button.optJSONArray("keyCodes");
                int count = jsonKeys == null ? 1 : jsonKeys.length();
                int[] keys = new int[4];
                Arrays.fill(keys, LwjglGlfwKeycode.GLFW_KEY_UNKNOWN);
                int inserted = 0;
                for (int i = 0; i < count && inserted < keys.length; i++) {
                    int key = jsonKeys == null ? button.optInt("keyCode", LwjglGlfwKeycode.GLFW_KEY_UNKNOWN)
                            : jsonKeys.optInt(i, LwjglGlfwKeycode.GLFW_KEY_UNKNOWN);
                    if (key > LwjglGlfwKeycode.GLFW_KEY_UNKNOWN) keys[inserted++] = key;
                }
                return inserted == 0 ? null : keys;
            default:
                return null;
        }
    }

    private static int[] special(int key) {
        int[] keys = new int[4];
        Arrays.fill(keys, LwjglGlfwKeycode.GLFW_KEY_UNKNOWN);
        keys[0] = key;
        return keys;
    }
}
