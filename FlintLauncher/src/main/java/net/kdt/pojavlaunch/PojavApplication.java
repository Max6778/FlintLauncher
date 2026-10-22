package net.kdt.pojavlaunch;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.flintlauncher.utils.ZHTools.getVersionCode;
import static com.flintlauncher.utils.ZHTools.getVersionName;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;

import com.flintlauncher.InfoDistributor;
import com.flintlauncher.context.ContextExecutor;
import com.flintlauncher.context.LocaleHelper;
import com.flintlauncher.feature.log.Logging;
import com.flintlauncher.setting.AllSettings;
import com.flintlauncher.ui.activity.ErrorActivity;
import com.flintlauncher.utils.path.PathManager;
import com.flintlauncher.utils.ZHTools;

import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Date;

public class PojavApplication extends Application {
	public static final String CRASH_REPORT_TAG = "FlintCrashReport";

	@Override
	public void onCreate() {
		ContextExecutor.setApplication(this);

		Thread.setDefaultUncaughtExceptionHandler((thread, th) -> {
			boolean storagePermAllowed = (Build.VERSION.SDK_INT >= 29 || ActivityCompat.checkSelfPermission(PojavApplication.this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) && Tools.checkStorageRoot();
			File crashFile = new File(storagePermAllowed ? PathManager.DIR_LAUNCHER_LOG : PathManager.DIR_DATA, "latestcrash.txt");
			try {
				FileUtils.ensureParentDirectory(crashFile);
				PrintStream crashStream = new PrintStream(crashFile);
				crashStream.append(InfoDistributor.APP_NAME + " crash report\n");
				crashStream.append(" - Time: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n");
				crashStream.append(" - Device: ").append(Build.PRODUCT).append(" ").append(Build.MODEL).append("\n");
				crashStream.append(" - Android version: ").append(Build.VERSION.RELEASE).append("\n");
				crashStream.append(" - Launcher version: ").append(getVersionName()).append(" (").append(String.valueOf(getVersionCode())).append(")").append("\n");
				crashStream.append(" - Crash stack trace:\n");
				crashStream.append(Log.getStackTraceString(th));
				crashStream.close();
			} catch (Throwable throwable) {
				Logging.e(CRASH_REPORT_TAG, " - Exception attempt saving crash stack trace:", throwable);
				Logging.e(CRASH_REPORT_TAG, " - The crash stack trace was:", th);
			}

			ErrorActivity.showLauncherCrash(PojavApplication.this, crashFile.getAbsolutePath(), th);
			ZHTools.killProcess();
		});
		
		try {
			super.onCreate();
			PathManager.DIR_DATA = getDir("files", MODE_PRIVATE).getParent();
			PathManager.DIR_CACHE = getCacheDir();
			PathManager.DIR_ACCOUNT_NEW = PathManager.DIR_DATA + "/accounts";
			Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();
			if(Architecture.isx86Device() && Architecture.is32BitsDevice()){
				String originalJNIDirectory = getApplicationInfo().nativeLibraryDir;
				getApplicationInfo().nativeLibraryDir = originalJNIDirectory.substring(0,
								originalJNIDirectory.lastIndexOf("/"))
								.concat("/x86");
			}
		} catch (Throwable throwable) {
			Intent ferrorIntent = new Intent(this, ErrorActivity.class);
			ferrorIntent.putExtra("throwable", throwable);
			ferrorIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			startActivity(ferrorIntent);
		}

		String launcherTheme = AllSettings.getLauncherTheme().getValue();
		int nightMode = "light".equalsIgnoreCase(launcherTheme)
				? AppCompatDelegate.MODE_NIGHT_NO
				: "system".equalsIgnoreCase(launcherTheme)
						? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
						: AppCompatDelegate.MODE_NIGHT_YES;
		AppCompatDelegate.setDefaultNightMode(nightMode);
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		ContextExecutor.clearApplication();
	}

	@Override
    protected void attachBaseContext(Context base) {
		ContextExecutor.setApplication(this);
        super.attachBaseContext(LocaleHelper.Companion.setLocale(base));
    }
}

