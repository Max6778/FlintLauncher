package net.kdt.pojavlaunch.utils;

import static com.movtery.zalithlauncher.utils.path.PathManager.DIR_NATIVE_LIB;
import static net.kdt.pojavlaunch.Architecture.ARCH_X86;
import static net.kdt.pojavlaunch.Architecture.is64BitsDevice;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.event.value.JvmExitEvent;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.VersionInfo;
import com.movtery.zalithlauncher.plugins.driver.DriverPluginManager;
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager;
import com.movtery.zalithlauncher.plugins.renderer.RendererPlugin;
import com.movtery.zalithlauncher.renderer.RendererInterface;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.ui.activity.ErrorActivity;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.LibPath;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.plugins.FFmpegPlugin;

import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public final class JREUtils {
    private JREUtils() {}

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;

    public static String findInLdLibPath(String libName) {
        if (Os.getenv("LD_LIBRARY_PATH")==null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            }catch (ErrnoException e) {
                Logging.e("JREUtils", Tools.printToString(e));
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if (list != null) {
            for(File f : list) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    returnValue.add(f);
                }else if (f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    public static void initJavaRuntime(String jreHome) {
        dlopen(findInLdLibPath("libjli.so"));
        if (!dlopen("libjvm.so")) {
            Logging.w("DynamicLoader","Failed to load with no path, trying with full path");
            dlopen(jvmLibraryPath+"/libjvm.so");
        }
        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        // dlopen(findInLdLibPath("libjsig.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        for (File f : locateLibs(new File(jreHome, Tools.DIRNAME_HOME_JRE))) {
            dlopen(f.getAbsolutePath());
        }
    }

    public static void redirectAndPrintJRELog() {

        Logging.v("jrelog","Log starts here");
        new Thread(new Runnable(){
            int failTime = 0;
            ProcessBuilder logcatPb;
            @Override
            public void run() {
                try {
                    if (logcatPb == null) {
                        logcatPb = new ProcessBuilder().command("logcat", /* "-G", "1mb", */ "-v", "brief", "-s", "jrelog:I", "LIBGL:I", "NativeInput").redirectErrorStream(true);
                    }

                    Logging.i("jrelog-logcat","Clearing logcat");
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();
                    Logging.i("jrelog-logcat","Starting logcat");
                    Process p = logcatPb.start();

                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = p.getInputStream().read(buf)) != -1) {
                        String currStr = new String(buf, 0, len);
                        Logger.appendToLog(currStr);
                    }

                    if (p.waitFor() != 0) {
                        Logging.e("jrelog-logcat", "Logcat exited with code " + p.exitValue());
                        failTime++;
                        Logging.i("jrelog-logcat", (failTime <= 10 ? "Restarting logcat" : "Too many restart fails") + " (attempt " + failTime + "/10");
                        if (failTime <= 10) {
                            run();
                        } else {
                            Logger.appendToLog("ERROR: Unable to get more Logging.");
                        }
                    }
                } catch (Throwable e) {
                    Logging.e("jrelog-logcat", "Exception on logging thread", e);
                    Logger.appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                }
            }
        }).start();
        Logging.i("jrelog-logcat","Logcat thread started");

    }

    /**
     * Directory used to hold glibc-compat shim libraries (libpthread.so.0,
     * libm.so.6, etc). Must be a directory the app can actually WRITE to at
     * runtime -- unlike DIR_NATIVE_LIB, which is the APK's installed native
     * library folder and is read-only for the app process (owned by the
     * system package manager). Writing there fails silently.
     */
    private static File getGlibcShimDir() {
        return new File(PathManager.DIR_DATA, "glibc_shims");
    }

    public static void relocateLibPath(Runtime runtime, String jreHome) {
        String JRE_ARCHITECTURE = runtime.arch;
        if (Architecture.archAsInt(JRE_ARCHITECTURE) == ARCH_X86){
            JRE_ARCHITECTURE = "i386/i486/i586";
        }

        for (String arch : JRE_ARCHITECTURE.split("/")) {
            File f = new File(jreHome, "lib/" + arch);
            if (f.exists() && f.isDirectory()) {
                Tools.DIRNAME_HOME_JRE = "lib/" + arch;
            }
        }

        String libName = is64BitsDevice() ? "lib64" : "lib";
        StringBuilder ldLibraryPath = new StringBuilder();
        if (FFmpegPlugin.isAvailable) {
            ldLibraryPath.append(FFmpegPlugin.libraryPath).append(":");
        }
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();
        if (customRenderer != null) {
            ldLibraryPath.append(customRenderer.getPath()).append(":");
        }
        ldLibraryPath.append(jreHome)
                .append("/").append(Tools.DIRNAME_HOME_JRE)
                .append("/jli:").append(jreHome).append("/").append(Tools.DIRNAME_HOME_JRE)
                .append(":");
        ldLibraryPath.append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:");
        File runtimeModDir = PathManager.DIR_RUNTIME_MOD;
        if (runtimeModDir != null) {
            ldLibraryPath.append(runtimeModDir.getAbsolutePath()).append(":");
        }
        // Writable directory holding glibc-compat shim libraries, so the
        // linker can find them when resolving DT_NEEDED deps like
        // libpthread.so.0 / libm.so.6 for desktop-Linux-built mod natives.
        ldLibraryPath.append(getGlibcShimDir().getAbsolutePath()).append(":");
        ldLibraryPath.append(DIR_NATIVE_LIB);
        LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    private static void initLdLibraryPath(String jreHome) {
        File serverFile = new File(jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/server/libjvm.so");
        jvmLibraryPath = jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/" + (serverFile.exists() ? "server" : "client");
        Logging.d("DynamicLoader","Base LD_LIBRARY_PATH: " + LD_LIBRARY_PATH);
        Logging.d("DynamicLoader","Internal LD_LIBRARY_PATH: "+jvmLibraryPath + ":" + LD_LIBRARY_PATH);
        setLdLibraryPath(jvmLibraryPath + ":" + LD_LIBRARY_PATH);
    }

    /**
     * Installs empty "shim" native libraries for glibc-only SONAMEs
     * (libpthread.so.0, libm.so.6, libdl.so.2, librt.so.1) that some
     * desktop-Linux-built mod natives (e.g. Distant Horizons' zstd-jni,
     * Simple Voice Chat's opus/rnnoise/speex/lame natives) declare as
     * DT_NEEDED dependencies but that don't exist on Android, since
     * Bionic libc already contains those symbols directly.
     * <p>
     * IMPORTANT: these must be written to a WRITABLE directory that is
     * also present in LD_LIBRARY_PATH (see relocateLibPath) -- NOT
     * DIR_NATIVE_LIB, which is the read-only APK-installed native lib
     * folder and cannot be written to by the app at runtime.
     */
    private static void installGlibcShims(Context context) {
        String[] shimNames = {"libpthread.so.0", "libm.so.6", "libdl.so.2", "librt.so.1"};
        File shimDir = getGlibcShimDir();
        if (!shimDir.exists() && !shimDir.mkdirs()) {
            Logging.e("GlibcShim", "Failed to create shim dir: " + shimDir);
            return;
        }
        for (String name : shimNames) {
            File dest = new File(shimDir, name);
            if (dest.exists() && dest.length() > 0) continue;
            try (InputStream in = context.getAssets().open("components/glibc-shims/arm64-v8a/" + name);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                Logging.i("GlibcShim", "Installed shim: " + dest.getAbsolutePath());
            } catch (Exception e) {
                Logging.e("GlibcShim", "Failed to install shim " + name + ": " + e);
            }
        }
    }

    private static void setJavaEnv(Map<String, String> envMap, String jreHome) {
        envMap.put("POJAV_NATIVEDIR", DIR_NATIVE_LIB);
        envMap.put("DRIVER_PATH", DriverPluginManager.getDriver().getPath());
        envMap.put("JAVA_HOME", jreHome);
        envMap.put("HOME", PathManager.DIR_GAME_HOME);
        envMap.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", jreHome + "/bin:" + Os.getenv("PATH"));
        envMap.put("FORCE_VSYNC", String.valueOf(AllSettings.getForceVsync().getValue()));
        envMap.put("AWTSTUB_WIDTH", Integer.toString(CallbackBridge.windowWidth > 0 ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth));
        envMap.put("AWTSTUB_HEIGHT", Integer.toString(CallbackBridge.windowHeight > 0 ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight));
        envMap.put("MOD_ANDROID_RUNTIME", PathManager.DIR_RUNTIME_MOD != null ? PathManager.DIR_RUNTIME_MOD.getAbsolutePath() : "");

        if (AllSettings.getDumpShaders().getValue())
            envMap.put("LIBGL_VGPU_DUMP", "1");
        if (AllSettings.getZinkPreferSystemDriver().getValue())
            envMap.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "1");
        if (AllSettings.getVsyncInZink().getValue())
            envMap.put("POJAV_VSYNC_IN_ZINK", "1");
        if (AllSettings.getBigCoreAffinity().getValue())
            envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
        if (FFmpegPlugin.isAvailable)
            envMap.put("POJAV_FFMPEG_PATH", FFmpegPlugin.executablePath);
    }

    private static void setRendererEnv(Map<String, String> envMap) {
        RendererInterface currentRenderer = Renderers.INSTANCE.getCurrentRenderer();
        String rendererId = currentRenderer.getRendererId();

        if (rendererId.startsWith("opengles2")) {
            envMap.put("LIBGL_ES", "2");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NOERROR", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_NORMALIZE", "1");
        }

        envMap.putAll(currentRenderer.getRendererEnv().getValue());

        String eglName = currentRenderer.getRendererEGL();
        if (eglName != null) envMap.put("POJAVEXEC_EGL", eglName);

        envMap.put("POJAV_RENDERER", rendererId);

        if (RendererPluginManager.getSelectedRendererPlugin() != null) return;

        if (!rendererId.startsWith("opengles")) {
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envMap.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("LIB_MESA_NAME", loadGraphicsLibrary());
        }

        if (!envMap.containsKey("LIBGL_ES")) {
            int glesMajor = getDetectedVersion();
            Logging.i("glesDetect","GLES version detected: "+glesMajor);

            if (glesMajor < 3) {
                //fallback to 2 since it's the minimum for the entire app
                envMap.put("LIBGL_ES","2");
            } else if (rendererId.startsWith("opengles")) {
                envMap.put("LIBGL_ES", rendererId.replace("opengles", "").replace("_5", ""));
            } else {
                // TODO if can: other backends such as Vulkan.
                // Sure, they should provide GLES 3 support.
                envMap.put("LIBGL_ES", "3");
            }
        }
    }

    private static void setCustomEnv(Map<String, String> envMap) throws Throwable {
        File customEnvFile = new File(PathManager.DIR_GAME_HOME, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf("=");
                envMap.put(line.substring(0, index), line.substring(index + 1));
            }
            reader.close();
        }
    }

    private static void checkAndUsedJSPH(Map<String, String> envMap, final Runtime runtime) {
        boolean onUseJSPH = runtime.javaVersion > 11;
        if (!onUseJSPH) return;
        File dir = new File(DIR_NATIVE_LIB);
        if (!dir.isDirectory()) return;
        String jsphName = runtime.javaVersion == 17 ? "libjsph17" : "libjsph21";
        File[] files = dir.listFiles((dir1, name) -> name.startsWith(jsphName));
        if (files != null && files.length > 0) {
            String libName = DIR_NATIVE_LIB + "/" + jsphName + ".so";
            envMap.put("JSP", libName);
        }
    }

    private static void setEnv(String jreHome, final Runtime runtime, Version gameVersion) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();

        setJavaEnv(envMap, jreHome);
        setCustomEnv(envMap);

        if (gameVersion != null) {
            checkAndUsedJSPH(envMap, runtime);

            VersionInfo versionInfo = gameVersion.getVersionInfo();
            if (versionInfo != null && versionInfo.getLoaderInfo() != null) {
                for (VersionInfo.LoaderInfo loaderInfo : versionInfo.getLoaderInfo()) {
                    if (loaderInfo.getLoaderEnvKey() != null) {
                        envMap.put(loaderInfo.getLoaderEnvKey(), "1");
                    }
                }
            }

            if (Renderers.INSTANCE.isCurrentRendererValid()) {
                setRendererEnv(envMap);
            }

            envMap.put("ZALITH_VERSION_CODE", String.valueOf(ZHTools.getVersionCode()));
        }

        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            }catch (NullPointerException exception){
                Logging.e("JREUtils", exception.toString());
            }
        }
    }

    private static void initGraphicAndSoundEngine(boolean renderer) {
        dlopen(DIR_NATIVE_LIB + "/libopenal.so");

        if (!renderer) return;

        String rendererLib = loadGraphicsLibrary();
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();

        if (customRenderer != null) {
            customRenderer.getDlopen().forEach(lib -> dlopen(customRenderer.getPath() + "/" + lib));
        }

        if (!dlopen(rendererLib) && !dlopen(findInLdLibPath(rendererLib))) {
            Logging.e("RENDER_LIBRARY", "Failed to load renderer " + rendererLib);
        }
    }

    /**
     * NOTE: parameter widened from AppCompatActivity to Activity so this also
     * works with the SDL3 launch path, which runs on a plain Activity rather
     * than an AppCompatActivity. Nothing below uses AppCompat-specific APIs
     * (supportFragmentManager, getSupportActionBar, etc) -- only
     * runOnUiThread / getApplication() / getString(), which are all on the
     * base Activity/Context, so this is a safe widening.
     */
    private static void launchJavaVM(
            final Activity activity,
            String runtimeHome,
            Version gameVersion,
            final List<String> JVMArgs,
            final String userArgsString
    ) {
        List<String> userArgs = getJavaArgs(runtimeHome, userArgsString);
        //Remove arguments that can interfere with the good working of the launcher
        purgeArg(userArgs,"-Xms");
        purgeArg(userArgs,"-Xmx");
        purgeArg(userArgs,"-d32");
        purgeArg(userArgs,"-d64");
        purgeArg(userArgs, "-Xint");
        purgeArg(userArgs, "-XX:+UseTransparentHugePages");
        purgeArg(userArgs, "-XX:+UseLargePagesInMetaspace");
        purgeArg(userArgs, "-XX:+UseLargePages");
        purgeArg(userArgs, "-Dorg.lwjgl.opengl.libname");
        // Don't let the user specify a custom Freetype library (as the user is unlikely to specify a version compiled for Android)
        purgeArg(userArgs, "-Dorg.lwjgl.freetype.libname");
        // Overridden by us to specify the exact number of cores that the android system has
        purgeArg(userArgs, "-XX:ActiveProcessorCount");

        userArgs.add("-javaagent:" + LibPath.MIO_LIB_PATCHER.getAbsolutePath());

        //Add automatically generated args
        userArgs.add("-Xms" + AllSettings.getRamAllocation().getValue().getValue() + "M");
        userArgs.add("-Xmx" + AllSettings.getRamAllocation().getValue().getValue() + "M");
        if (Renderers.INSTANCE.isCurrentRendererValid()) userArgs.add("-Dorg.lwjgl.opengl.libname=" + loadGraphicsLibrary());

        // Force LWJGL to use the Freetype library intended for it, instead of using the one
        // that we ship with Java (since it may be older than what's needed).
        // NOTE: this must point at the per-version natives cache dir (where the bundled
        // LWJGL natives aar gets extracted to, see ensureLwjglNativesExtracted() in
        // LaunchGame.kt) rather than DIR_NATIVE_LIB — libfreetype.so is not a jniLib,
        // it only ships inside the natives aar's assets.
        if (gameVersion != null) {
            File versionNativesDir = new File(PathManager.DIR_CACHE, "natives/" + gameVersion.getVersionName());
            userArgs.add("-Dorg.lwjgl.freetype.libname=" + versionNativesDir.getAbsolutePath() + "/libfreetype.so");
        }
        userArgs.add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared");

        // Some phones are not using the right number of cores, fix that
        userArgs.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());

        userArgs.addAll(JVMArgs);
        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.autoram_info_msg, AllSettings.getRamAllocation().getValue().getValue()), Toast.LENGTH_SHORT).show());
        System.out.println(JVMArgs);
        for (int i = 0; i < userArgs.size(); i++) {
            String arg = userArgs.get(i);
            if (arg.startsWith("--accessToken")) {
                i += 1;
            }
            Logger.appendToLog("JVMArg: " + arg);
        }

        setupExitMethod(activity.getApplication());
        initializeGameExitHook();
        chdir(gameVersion == null ? ProfilePathHome.getGameHome() : gameVersion.getGameDir().getAbsolutePath());
        userArgs.add(0,"java"); //argv[0] is the program name according to C standard.

        final int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
        Logger.appendToLog("Java Exit code: " + exitCode);
        if (exitCode != 0) {
            ErrorActivity.showExitMessage(activity, exitCode, false);
        }
        EventBus.getDefault().post(new JvmExitEvent(exitCode));
    }

    /**
     * NOTE: parameter widened from AppCompatActivity to Activity -- see the
     * comment on launchJavaVM above. This lets the SDL3 launch path (which
     * hosts the game on a plain Activity) call into the same launch
     * pipeline as the existing AppCompat-based path.
     */
    public static void launchWithUtils(
            final Activity activity,
            final Runtime runtime,
            Version gameVersion,
            final List<String> JVMArgs,
            final String userArgsString
    ) throws Throwable {
        String runtimeHome = MultiRTUtils.getRuntimeHome(runtime.name).getAbsolutePath();

        installGlibcShims(activity);

        relocateLibPath(runtime, runtimeHome);

        initLdLibraryPath(runtimeHome);

        setEnv(runtimeHome, runtime, gameVersion);

        initJavaRuntime(runtimeHome);

        initGraphicAndSoundEngine(gameVersion != null);

        launchJavaVM(activity, runtimeHome, gameVersion, JVMArgs, userArgsString);
    }

    /**
     *  Gives an argument list filled with both the user args
     *  and the auto-generated ones (eg. the window resolution).
     * @return A list filled with args.
     */
    public static List<String> getJavaArgs(String runtimeHome, String userArgumentsString) {
        List<String> userArguments = parseJavaArguments(userArgumentsString);
        String resolvFile;
        resolvFile = new File(PathManager.DIR_DATA,"resolv.conf").getAbsolutePath();

        ArrayList<String> overridableArguments = new ArrayList<>(Arrays.asList(
                "-Djava.home=" + runtimeHome,
                "-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath(),
                "-Djna.boot.library.path=" + DIR_NATIVE_LIB,
                "-Duser.home=" + ProfilePathManager.INSTANCE.getCurrentPath(),
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dpojav.path.minecraft=" + ProfilePathHome.getGameHome(),
                "-Dpojav.path.private.account=" + PathManager.DIR_ACCOUNT_NEW,
                "-Duser.timezone=" + TimeZone.getDefault().getID(),

                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
                //LWJGL 3 DEBUG FLAGS
                //"-Dorg.lwjgl.util.Debug=true",
                //"-Dorg.lwjgl.util.DebugFunctions=true",
                //"-Dorg.lwjgl.util.DebugLoader=true",
                // GLFW Stub width height
                "-Dglfwstub.windowWidth=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, AllSettings.getResolutionRatio().getValue() / 100F),
                "-Dglfwstub.windowHeight=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, AllSettings.getResolutionRatio().getValue() / 100F),
                "-Dglfwstub.initEgl=false",
                "-Dext.net.resolvPath=" +resolvFile,
                "-Dlog4j2.formatMsgNoLookups=true", //Log4j RCE mitigation

                "-Dnet.minecraft.clientmodname=" + InfoDistributor.LAUNCHER_NAME,
                "-Dfml.earlyprogresswindow=false", //Forge 1.14+ workaround
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK", // Default is POSIX_SPAWN which requires starting jspawnhelper, which doesn't work on Android
                "-Dsodium.checks.issue2561=false"
        ));

        List<String> additionalArguments = new ArrayList<>();
        for (String arg : overridableArguments) {
            String strippedArg = arg.substring(0,arg.indexOf('='));
            boolean add = true;
            for (String uarg : userArguments) {
                if (uarg.startsWith(strippedArg)) {
                    add = false;
                    break;
                }
            }
            if (add)
                additionalArguments.add(arg);
            else
                Logging.i("ArgProcessor","Arg skipped: "+arg);
        }

        //Add all the arguments
        userArguments.addAll(additionalArguments);
        return userArguments;
    }

    /**
     * Parse and separate java arguments in a user friendly fashion
     * It supports multi line and absence of spaces between arguments
     * The function also supports auto-removal of improper arguments, although it may miss some.
     *
     * @param args The un-parsed argument list.
     * @return Parsed args as an ArrayList
     */
    public static ArrayList<String> parseJavaArguments(String args){
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        //For each prefixes, we separate args.
        String[] separators = new String[]{"-XX:-","-XX:+", "-XX:","--", "-D", "-X", "-javaagent:", "-verbose"};
        for (String prefix : separators){
            while (true) {
                int start = args.indexOf(prefix);
                if (start == -1) break;
                //Get the end of the current argument by checking the nearest separator
                int end = -1;
                for (String separator: separators) {
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if (tempEnd == -1) continue;
                    if (end == -1) {
                        end = tempEnd;
                        continue;
                    }
                    end = Math.min(end, tempEnd);
                }
                //Fallback
                if (end == -1) end = args.length();

                //Extract it
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                //Check if two args aren't bundled together by mistake
                if (parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if (arraySize > 0) {
                        String lastString = parsedArguments.get(arraySize - 1);
                        // Looking for list elements
                        if (lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")) {
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                }
                else Logging.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    /**
     * Open the render library in accordance to the settings.
     * It will fallback if it fails to load the library.
     * @return The name of the loaded library
     */
    public static String loadGraphicsLibrary() {
        if (!Renderers.INSTANCE.isCurrentRendererValid()) return null;
        else {
            RendererPlugin rendererPlugin = RendererPluginManager.getSelectedRendererPlugin();
            if (rendererPlugin != null) {
                return rendererPlugin.getPath() + "/" + rendererPlugin.getGlName();
            } else {
                return Renderers.INSTANCE.getCurrentRenderer().getRendererLibrary();
            }
        }
    }

    /**
     * Remove the argument from the list, if it exists
     * If the argument exists multiple times, they will all be removed.
     * @param argList The argument list to purge
     * @param argStart The argument to purge from the list.
     */
    private static void purgeArg(List<String> argList, String argStart) {
        argList.removeIf(arg -> arg.startsWith(argStart));
    }
    private static final int EGL_OPENGL_ES_BIT = 0x0001;
    private static final int EGL_OPENGL_ES2_BIT = 0x0004;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    @SuppressWarnings("SameParameterValue")
    private static boolean hasExtension(String extensions, String name) {
        int start = extensions.indexOf(name);
        while (start >= 0) {
            // check that we didn't find a prefix of a longer extension name
            int end = start + name.length();
            if (end == extensions.length() || extensions.charAt(end) == ' ') {
                return true;
            }
            start = extensions.indexOf(name, end);
        }
        return false;
    }

    public static int getDetectedVersion() {
        /*
         * Get all the device configurations and check the EGL_RENDERABLE_TYPE attribute
         * to determine the highest ES version supported by any config. The
         * EGL_KHR_create_context extension is required to check for ES3 support; if the
         * extension is not present this test will fail to detect ES3 support. This
         * effectively makes the extension mandatory for ES3-capable devices.
         */
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] numConfigs = new int[1];
        if (egl.eglInitialize(display, null)) {
            try {
                boolean checkES3 = hasExtension(egl.eglQueryString(display, EGL10.EGL_EXTENSIONS),
                        "EGL_KHR_create_context");
                if (egl.eglGetConfigs(display, null, 0, numConfigs)) {
                    EGLConfig[] configs = new EGLConfig[numConfigs[0]];
                    if (egl.eglGetConfigs(display, configs, numConfigs[0], numConfigs)) {
                        int highestEsVersion = 0;
                        int[] value = new int[1];
                        for (int i = 0; i < numConfigs[0]; i++) {
                            if (egl.eglGetConfigAttrib(display, configs[i],
                                    EGL10.EGL_RENDERABLE_TYPE, value)) {
                                if (checkES3 && ((value[0] & EGL_OPENGL_ES3_BIT_KHR) ==
                                        EGL_OPENGL_ES3_BIT_KHR)) {
                                    if (highestEsVersion < 3) highestEsVersion = 3;
                                } else if ((value[0] & EGL_OPENGL_ES2_BIT) == EGL_OPENGL_ES2_BIT) {
                                    if (highestEsVersion < 2) highestEsVersion = 2;
                                } else if ((value[0] & EGL_OPENGL_ES_BIT) == EGL_OPENGL_ES_BIT) {
                                    if (highestEsVersion < 1) highestEsVersion = 1;
                                }
                            } else {
                                Logging.w("glesDetect", "Getting config attribute with "
                                        + "EGL10#eglGetConfigAttrib failed "
                                        + "(" + i + "/" + numConfigs[0] + "): "
                                        + egl.eglGetError());
                            }
                        }
                        return highestEsVersion;
                    } else {
                        Logging.e("glesDetect", "Getting configs with EGL10#eglGetConfigs failed: "
                                + egl.eglGetError());
                        return -1;
                    }
                } else {
                    Logging.e("glesDetect", "Getting number of configs with EGL10#eglGetConfigs failed: "
                            + egl.eglGetError());
                    return -2;
                }
            } finally {
                egl.eglTerminate(display);
            }
        } else {
            Logging.e("glesDetect", "Couldn't initialize EGL.");
            return -3;
        }
    }
    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeGameExitHook();
    public static native void setupExitMethod(Context context);
    // Obtain AWT screen pixels to render on Android SurfaceView
    public static native int[] renderAWTScreenFrame(/* Object canvas, int width, int height */);
    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
    }

