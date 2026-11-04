package dev.horizon.android;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installs Horizon's runtime once, then opens its native Android activity. */
public final class HorizonRuntime {
    private static final String TAG = "FlintHorizon";
    private static final ExecutorService INSTALLER = Executors.newSingleThreadExecutor();

    private HorizonRuntime() { }

    public static boolean isSupported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public static void launch(Activity activity, File gameDir, MinecraftAccount account) {
        if (!isSupported()) {
            activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                    "Horizon currently requires a 64-bit ARM Android device.", android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        boolean microsoftAccount = account != null && "Microsoft".equalsIgnoreCase(account.accountType);
        boolean offlineAccount = account == null || "Local".equalsIgnoreCase(account.accountType);
        if (microsoftAccount && (account.accessToken == null || account.accessToken.trim().isEmpty()
                || account.profileId == null || account.username == null)) {
            activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                    "This Microsoft account is missing its Minecraft profile or access token. Sign in again.", android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        if (!microsoftAccount && !offlineAccount) {
            activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                    "Horizon supports Microsoft and Local accounts. Select a Local account for offline play.", android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        if (offlineAccount && account != null && (account.username == null
                || !account.username.matches("[A-Za-z0-9_]{3,16}"))) {
            activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                    "Horizon offline names must be 3–16 letters, numbers, or underscores.", android.widget.Toast.LENGTH_LONG).show());
            return;
        }

        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Preparing Horizon");
        progress.setMessage("Installing Horizon files. This only happens once.");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        INSTALLER.execute(() -> {
            try {
                File home = new File(activity.getFilesDir(), "horizon");
                File cache = new File(activity.getCacheDir(), "horizon");
                if (!home.isDirectory() && !home.mkdirs()) throw new IllegalStateException("Cannot create Horizon data directory");
                if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalStateException("Cannot create Horizon cache directory");
                if (!HorizonInstaller.isReady(home.getAbsolutePath())) {
                    File archive = new File(home, "engine-data.zip");
                    if (!archive.isFile()) copyAsset(activity, "horizon/engine-data.zip", archive);
                    String error = HorizonInstaller.install(home.getAbsolutePath(), cache.getAbsolutePath(),
                            (stage, message, current, total) -> Log.i(TAG, "Install " + stage + ": " + message + " (" + current + "/" + total + ")"));
                    if (error != null || !HorizonInstaller.isReady(home.getAbsolutePath())) {
                        throw new IllegalStateException(error == null ? "Horizon installation did not complete" : error);
                    }
                }
                File session = null;
                if (microsoftAccount) {
                    session = new File(home, "flint-session.json");
                    writeSession(session, account);
                } else {
                    File staleSession = new File(home, "flint-session.json");
                    if (staleSession.exists() && !staleSession.delete()) {
                        throw new IllegalStateException("Cannot remove the previous Horizon sign-in session");
                    }
                    if (account != null) writeOfflineName(home, account.username);
                }
                final String sessionPath = session == null ? null : session.getAbsolutePath();
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    if (activity.isFinishing()) return;
                    Intent intent = new Intent(activity, HorizonActivity.class);
                    intent.putExtra(HorizonActivity.EXTRA_ENGINE_HOME, home.getAbsolutePath());
                    intent.putExtra(HorizonActivity.EXTRA_GAME_DIR, gameDir.getAbsolutePath());
                    intent.putExtra(HorizonActivity.EXTRA_SESSION_FILE, sessionPath);
                    activity.startActivity(intent);
                });
            } catch (Throwable error) {
                Log.e(TAG, "Horizon launch failed", error);
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    new android.app.AlertDialog.Builder(activity)
                        .setTitle("Could not start Horizon")
                        .setMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                        .setPositiveButton(android.R.string.ok, null).show();
                });
            }
        });
    }

    private static void copyAsset(Activity activity, String assetName, File destination) throws Exception {
        File partial = new File(destination.getParentFile(), destination.getName() + ".partial");
        try (InputStream input = activity.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("Cannot replace Horizon engine data archive");
        if (!partial.renameTo(destination)) throw new IllegalStateException("Cannot save Horizon engine data archive");
    }

    private static void writeSession(File file, MinecraftAccount account) throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", account.username);
        json.put("uuid", account.profileId.replace("-", ""));
        json.put("access_token", account.accessToken);
        json.put("user_type", "msa");
        if (account.xuid != null && !account.xuid.isEmpty()) json.put("xuid", account.xuid);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        // Session credentials are private to Flint's app sandbox.
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
    }

    private static void writeOfflineName(File home, String name) throws Exception {
        File destination = new File(home, "horizon-player.txt");
        File partial = new File(home, "horizon-player.txt.partial");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(("name:" + name + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Cannot update Horizon's offline player name");
        }
        if (!partial.renameTo(destination)) {
            throw new IllegalStateException("Cannot save Horizon's offline player name");
        }
    }
}
