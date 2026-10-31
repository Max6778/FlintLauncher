package com.flintlauncher.launch

import androidx.collection.ArrayMap
import com.flintlauncher.InfoDistributor
import com.flintlauncher.feature.accounts.AccountUtils
import com.flintlauncher.feature.customprofilepath.ProfilePathHome
import com.flintlauncher.feature.customprofilepath.ProfilePathHome.Companion.getLibrariesHome
import com.flintlauncher.feature.version.Version
import com.flintlauncher.utils.ZHTools
import com.flintlauncher.utils.path.LibPath
import com.flintlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.AWTCanvasView
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.Runtime
import net.kdt.pojavlaunch.utils.JSONUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.io.File

class LaunchArgs(
    private val account: MinecraftAccount,
    private val gameDirPath: File,
    private val minecraftVersion: Version,
    private val versionInfo: JMinecraftVersionList.Version,
    private val versionFileName: String,
    private val runtime: Runtime,
    private val launchClassPath: String
) {
    fun getAllArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        // NOTE: getMinecraftJVMArgs() is added first and getJavaArgs() last, on purpose.
        // The downloaded version manifest (e.g. 26.x) can define its own
        // -Djava.library.path=${natives_directory}/java, which points at a subfolder
        // that doesn't actually exist (natives are extracted flat into natives_directory).
        // Since the JVM keeps the LAST -D for a given key, our own correctly-computed
        // path from getJavaArgs() must come after the manifest's args so it always wins.
        argsList.addAll(getMinecraftJVMArgs())
        argsList.addAll(getJavaArgs())
        argsList.add("-cp")
        argsList.add("${Tools.getLWJGL3ClassPath(versionInfo)}:$launchClassPath")
        argsList.add(versionInfo.mainClass)
        argsList.addAll(getMinecraftClientArgs())

        return argsList
    }

    /**
     * Returns the writable, per-version directory used to hold extracted native
     * libraries (LWJGL/JNA/Netty natives, etc). This must NEVER point at
     * context.applicationInfo.nativeLibraryDir (PathManager.DIR_NATIVE_LIB),
     * since that directory is read-only at runtime on Android and libraries
     * that try to mkdir/extract into it will crash with AccessDeniedException.
     */
    private fun getVersionSpecificNativesDir(): File =
        File(PathManager.DIR_CACHE, "natives/${minecraftVersion.getVersionName()}")

    private fun getJavaArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        if (AccountUtils.isOtherLoginAccount(account)) {
            if (account.otherBaseUrl.contains("auth.mc-user.com")) {
                argsList.add("-javaagent:${LibPath.NIDE_8_AUTH.absolutePath}=${account.otherBaseUrl.replace("https://auth.mc-user.com:233/", "")}")
                argsList.add("-Dnide8auth.client=true")
            } else {
                argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=${account.otherBaseUrl}")
            }
        }

        argsList.addAll(getCacioJavaArgs(runtime.javaVersion == 8))

        val is7 = VersionNumber.compare(VersionNumber.asVersion(versionInfo.id ?: "0.0").canonical, "1.12") < 0
        val configFilePath = if (is7) LibPath.LOG4J_XML_1_7 else LibPath.LOG4J_XML_1_12
        argsList.add("-Dlog4j.configurationFile=${configFilePath.absolutePath}")

        val versionSpecificNativesDir = getVersionSpecificNativesDir()
        if (versionSpecificNativesDir.exists()) {
            val dirPath = versionSpecificNativesDir.absolutePath
            argsList.add("-Djava.library.path=$dirPath:${PathManager.DIR_NATIVE_LIB}")
            argsList.add("-Djna.boot.library.path=$dirPath")
        }

        return argsList
    }

    private fun getMinecraftJVMArgs(): Array<String> {
        val versionInfo = Tools.getVersionInfo(minecraftVersion, true)

//        // Parse Forge 1.17+ additional JVM Arguments
//        if (versionInfo.inheritsFrom == null || versionInfo.arguments == null || versionInfo.arguments.jvm == null) {
//            return emptyArray()
//        }

        // FIX: use the writable, version-specific natives cache dir here instead of
        // PathManager.DIR_NATIVE_LIB. Newer version JSONs (e.g. 26.x) embed JVM args
        // like -Djna.tmpdir=${natives_directory},
        // -Dorg.lwjgl.system.SharedLibraryExtractPath=${natives_directory}, and
        // -Dio.netty.native.workdir=${natives_directory}. Substituting those
        // placeholders with the read-only APK native lib dir causes LWJGL/JNA/Netty
        // to crash with AccessDeniedException when they try to extract/mkdir there.
        val versionSpecificNativesDir = getVersionSpecificNativesDir()
        versionSpecificNativesDir.mkdirs()

        val varArgMap: MutableMap<String, String?> = android.util.ArrayMap()
        varArgMap["classpath_separator"] = ":"
        varArgMap["library_directory"] = getLibrariesHome()
        varArgMap["version_name"] = versionInfo.id
        varArgMap["natives_directory"] = versionSpecificNativesDir.absolutePath

        val minecraftArgs = manifestArguments(versionInfo.arguments?.jvm)
            .map { if (it.startsWith("-DignoreList=")) "$it,$versionFileName.jar" else it }
        return JSONUtils.insertJSONValueList(minecraftArgs.toTypedArray<String>(), varArgMap)
    }

    private fun getMinecraftClientArgs(): Array<String> {
        val verArgMap: MutableMap<String, String> = ArrayMap()
        verArgMap["auth_session"] = account.accessToken
        verArgMap["auth_access_token"] = account.accessToken
        verArgMap["auth_player_name"] = account.username
        verArgMap["auth_uuid"] = account.profileId.replace("-", "")
        verArgMap["auth_xuid"] = account.xuid
        verArgMap["assets_root"] = ProfilePathHome.getAssetsHome()
        verArgMap["assets_index_name"] = versionInfo.assets
        verArgMap["game_assets"] = ProfilePathHome.getAssetsHome()
        verArgMap["game_directory"] = gameDirPath.absolutePath
        verArgMap["user_properties"] = "{}"
        verArgMap["user_type"] = "msa"
        verArgMap["version_name"] = versionInfo.inheritsFrom ?: versionInfo.id

        setLauncherInfo(verArgMap)

        val manifestGameArgs = versionInfo.arguments?.game
        val minecraftArgs = if (manifestGameArgs != null) {
            manifestArguments(manifestGameArgs).toTypedArray()
        } else {
            splitAndFilterEmpty(versionInfo.minecraftArguments ?: "")
        }
        return JSONUtils.insertJSONValueList(minecraftArgs, verArgMap)
    }

    /** Reads both string arguments and the rule/value objects used by Mojang manifests. */
    private fun manifestArguments(arguments: Array<*>?): List<String> {
        if (arguments == null) return emptyList()
        val result = ArrayList<String>()
        for (argument in arguments) {
            when (argument) {
                is String -> result.add(argument)
                is Map<*, *> -> {
                    if (!manifestRulesAllow(argument["rules"])) continue
                    val value = argument["value"] ?: argument["values"]
                    when (value) {
                        is String -> result.add(value)
                        is Iterable<*> -> value.forEach { if (it is String) result.add(it) }
                        is Array<*> -> value.forEach { if (it is String) result.add(it) }
                    }
                }
            }
        }
        return result
    }

    /** Mojang applies matching rules in order; the last matching rule wins. */
    private fun manifestRulesAllow(rawRules: Any?): Boolean {
        if (rawRules == null) return true
        val rules = when (rawRules) {
            is Iterable<*> -> rawRules
            is Array<*> -> rawRules.asIterable()
            else -> return false
        }
        var allowed = false
        for (rawRule in rules) {
            val rule = rawRule as? Map<*, *> ?: continue
            if (!manifestRuleMatches(rule)) continue
            when (rule["action"] as? String) {
                "allow" -> allowed = true
                "disallow" -> allowed = false
            }
        }
        return allowed
    }

    private fun manifestRuleMatches(rule: Map<*, *>): Boolean {
        val os = rule["os"] as? Map<*, *>
        if (os != null) {
            val expectedName = os["name"] as? String
            if (expectedName != null && !isCurrentOs(expectedName)) return false
            val expectedVersion = os["version"] as? String
            if (expectedVersion != null && !matchesRegex(expectedVersion, System.getProperty("os.version").orEmpty())) return false
            val expectedArch = os["arch"] as? String
            if (expectedArch != null && !matchesRegex(expectedArch, System.getProperty("os.arch").orEmpty())) return false
        }
        val features = rule["features"] as? Map<*, *> ?: return true
        // Launcher features not explicitly provided by this launch path are false.
        return features.all { (name, expected) -> name is String && expected is Boolean && !expected }
    }

    private fun isCurrentOs(expected: String): Boolean {
        val actual = System.getProperty("os.name").orEmpty().lowercase()
        return when (expected.lowercase()) {
            "linux" -> actual.contains("linux") || actual.contains("android")
            "windows" -> actual.startsWith("windows")
            "osx" -> actual.contains("mac") || actual.contains("darwin")
            else -> false
        }
    }

    private fun matchesRegex(pattern: String, value: String): Boolean =
        try { Regex(pattern).containsMatchIn(value) } catch (_: IllegalArgumentException) { false }

    private fun setLauncherInfo(verArgMap: MutableMap<String, String>) {
        verArgMap["launcher_name"] = InfoDistributor.LAUNCHER_NAME
        verArgMap["launcher_version"] = ZHTools.getVersionName()
        verArgMap["version_type"] = minecraftVersion.getCustomInfo()
            .takeIf { it.isNotEmpty() && it.isNotBlank() }
            ?: versionInfo.type
    }

    private fun splitAndFilterEmpty(arg: String): Array<String> {
        val list: MutableList<String> = ArrayList()
        arg.split(" ").forEach {
            if (it.isNotEmpty()) list.add(it)
        }
        return list.toTypedArray()
    }

    companion object {
        @JvmStatic
        fun getCacioJavaArgs(isJava8: Boolean): List<String> {
            val argsList: MutableList<String> = ArrayList()

            // Caciocavallo config AWT-enabled version
            argsList.add("-Djava.awt.headless=false")
            argsList.add("-Dcacio.managed.screensize=" + AWTCanvasView.AWT_CANVAS_WIDTH + "x" + AWTCanvasView.AWT_CANVAS_HEIGHT)
            argsList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
            argsList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
            argsList.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
            if (isJava8) {
                argsList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
            } else {
                argsList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
                argsList.add("-javaagent:" + LibPath.CACIO_17_AGENT.getAbsolutePath())
                argsList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.util=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")

                // Opens the java.net package to Arc DNS injector on Java 9+
                argsList.add("--add-opens=java.base/java.net=ALL-UNNAMED")
            }

            val cacioClassPath = StringBuilder()
            cacioClassPath.append("-Xbootclasspath/").append(if (isJava8) "p" else "a")
            val cacioFiles = if (isJava8) LibPath.CACIO_8 else LibPath.CACIO_17
            cacioFiles.listFiles()?.onEach {
                if (it.name.endsWith(".jar")) cacioClassPath.append(":").append(it.absolutePath)
            }

            argsList.add(cacioClassPath.toString())

            return argsList
        }
    }
}
