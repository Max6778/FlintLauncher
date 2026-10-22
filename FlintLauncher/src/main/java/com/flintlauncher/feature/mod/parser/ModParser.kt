package com.flintlauncher.feature.mod.parser

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.moandjiezana.toml.Toml
import com.flintlauncher.feature.log.Logging
import com.flintlauncher.feature.version.Version
import com.flintlauncher.task.Task
import com.flintlauncher.utils.file.FileTools
import com.flintlauncher.utils.path.PathManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.jar.JarInputStream
import kotlin.jvm.Throws

class ModParser {
    companion object {
        /**
         * 获取当前版本的所有模组的模组信息
         */
        @JvmStatic
        fun checkAllMods(minecraftVersion: Version, parserListener: ModParserListener) {
            File(minecraftVersion.getGameDir(), "mods").apply {
                if (exists() && isDirectory && (listFiles()?.isNotEmpty() == true)) {
                    ModParser().parseAllMods(this, parserListener)
                    return
                }
            }
            parserListener.onParseEnded(emptyList())
        }
    }

    /**
     * 异步解析模组文件夹中的所有模组
     * @param modsFolder 模组文件夹
     * @param listener 解析过程监听器
     */
    fun parseAllMods(modsFolder: File, listener: ModParserListener) {
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val cacheFileName = "${modsFolder.absolutePath.replace(File.separator, "-")}.cache"
        val infoCacheFile = File(PathManager.DIR_ADDONS_INFO_CACHE, cacheFileName)
        val modInfoQueue = ConcurrentLinkedQueue<ModInfo>()
        val newCacheMap = ConcurrentHashMap<String, ModInfoCache>()

        Task.runTask {
            if (modsFolder.isDirectory) {
                val files = modsFolder.listFiles()
                    ?.filter { it.extension.equals("jar", true) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@runTask

                val existingCache = loadCache(gson, infoCacheFile)

                runBlocking {
                    val optimalThreads = calculateOptimalThreads(files.size)
                    val semaphore = Semaphore(optimalThreads)

                    files.chunked(calculateChunkSize(files.size)).forEach { batch ->
                        val deferredList = batch.map { file ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    parseModFile(file, files.size, existingCache, newCacheMap, modInfoQueue, listener)
                                }
                            }
                        }
                        //等待所有解析任务完成
                        deferredList.awaitAll()
                    }
                }
            }
        }.onThrowable { e ->
            Logging.e("ModParser", "An exception occurred while parsing all mods!", e)
        }.finallyTask {
            persistCache(gson, infoCacheFile, newCacheMap.values.toList())
            listener.onParseEnded(modInfoQueue.toList())
        }.execute()
    }

    private fun parseModFile(
        modFile: File,
        totalCount: Int,
        existingCache: Map<String, ModInfoCache>,
        newCache: MutableMap<String, ModInfoCache>,
        modQueue: ConcurrentLinkedQueue<ModInfo>,
        listener: ModParserListener
    ) {
        val fileHash = FileTools.calculateFileHash(modFile)

        existingCache[fileHash]?.let { cached ->
            val modInfo = cached.modInfo.apply { file = modFile }
            modQueue.add(modInfo)
            listener.onProgress(modInfo, totalCount)
            newCache[fileHash] = cached
            return
        }

        parseModContents(modFile)?.let { modInfo ->
            modQueue.add(modInfo)
            listener.onProgress(modInfo, totalCount)
            newCache[fileHash] = ModInfoCache(fileHash, modInfo)
        }
    }

    private fun parseModContents(modFile: File): ModInfo? {
        return try {
            JarInputStream(FileInputStream(modFile)).use { jarStream ->
                locateModDescriptor(jarStream)?.let { entry ->
                    parseDescriptorContent(modFile, jarStream, entry.name)
                }
            }
        } catch (e: Exception) {
            Logging.e("ModParser", "Error parsing ${modFile.name}", e)
            null
        }
    }

    private fun locateModDescriptor(jarStream: JarInputStream): java.util.jar.JarEntry? {
        val targetFiles = setOf(
            "fabric.mod.json",
            "quilt.mod.json",
            "META-INF/neoforge.mods.toml",
            "META-INF/mods.toml",
            "mcmod.info"
        )

        return generateSequence { jarStream.nextJarEntry }
            .firstOrNull { it.name in targetFiles }
    }

    private fun parseDescriptorContent(modFile: File, jarStream: JarInputStream, fileName: String): ModInfo? {
        return when (fileName) {
            "fabric.mod.json" -> parseFabricMod(modFile, jarStream)
            "quilt.mod.json" -> parseQuiltMod(modFile, jarStream)
            "META-INF/neoforge.mods.toml", "META-INF/mods.toml" -> parseForgeMod(modFile, jarStream)
            "mcmod.info" -> parseLegacyForgeMod(modFile, jarStream)
            else -> null
        }
    }

    @Throws(Exception::class)
    private fun parseFabricMod(modFile: File, jarStream: JarInputStream): ModInfo? {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val jsonObject = JsonParser.parseString(content).asJsonObject
        val modId = jsonObject.stringOrNull("id") ?: return null
        return ModInfo(
            modId,
            jsonObject.stringOrNull("version") ?: "unknown",
            jsonObject.stringOrNull("name") ?: modId,
            jsonObject.stringOrNull("description") ?: "",
            jsonObject["authors"]?.takeIf { it.isJsonArray }?.asJsonArray?.let { authorsArray ->
                authorsArray.mapNotNull { authorElement ->
                    if (authorElement.isJsonObject) authorElement.asJsonObject.stringOrNull("name")
                    else authorElement.primitiveStringOrNull()
                }.toTypedArray()
            } ?: emptyArray()
        ).apply { file = modFile }
    }

    @Throws(Exception::class)
    private fun parseQuiltMod(modFile: File, jarStream: JarInputStream): ModInfo? {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val quiltLoader = JsonParser.parseString(content).asJsonObject["quilt_loader"].asJsonObject
        val modId = quiltLoader.stringOrNull("id") ?: return null
        val metadata = quiltLoader["metadata"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return ModInfo(
            modId,
            quiltLoader.stringOrNull("version") ?: "unknown",
            metadata.stringOrNull("name") ?: modId,
            metadata.stringOrNull("description") ?: "",
            metadata["contributors"]?.takeIf { it.isJsonObject }?.asJsonObject?.keySet()?.toTypedArray() ?: emptyArray()
        ).apply { file = modFile }
    }

    @Throws(Exception::class)
    private fun parseForgeMod(modFile: File, jarStream: JarInputStream): ModInfo? {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val toml = Toml().read(content)
        val modEntry = toml.getTables("mods").firstOrNull() ?: return null

        return ModInfo(
            modEntry.getString("modId") ?: return null,
            modEntry.getString("version") ?: "unknown",
            modEntry.getString("displayName") ?: modEntry.getString("modId") ?: return null,
            modEntry.getString("description") ?: "",
            parseForgeAuthors(modEntry)
        ).apply { file = modFile }
    }

    private fun parseForgeAuthors(modEntry: Toml): Array<String> = runCatching {
        modEntry.getString("authors")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toTypedArray()
    }.getOrNull() ?: emptyArray()

    @Throws(Exception::class)
    private fun parseLegacyForgeMod(modFile: File, jarStream: JarInputStream): ModInfo? {
        val content = jarStream.bufferedReader().use(BufferedReader::readText)
        val jsonArray = JsonParser.parseString(content).asJsonArray
        val mainEntry = jsonArray[0].asJsonObject

        val modId = mainEntry.stringOrNull("modid") ?: return null
        return ModInfo(
            modId,
            mainEntry.stringOrNull("version") ?: "unknown",
            mainEntry.stringOrNull("name") ?: modId,
            mainEntry.stringOrNull("description") ?: "",
            parseLegacyForgeAuthors(mainEntry)
        ).apply { file = modFile }
    }

    private fun parseLegacyForgeAuthors(entry: com.google.gson.JsonObject): Array<String> {
        val authors = entry["authorList"] ?: entry["authors"] ?: return emptyArray()
        return authors.takeIf { it.isJsonArray }?.asJsonArray?.toStringArray() ?: emptyArray()
    }

    private fun JsonArray.toStringArray(): Array<String> {
        return this.mapNotNull { it.primitiveStringOrNull() }.toTypedArray()
    }

    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.primitiveStringOrNull()

    private fun JsonElement.primitiveStringOrNull(): String? = runCatching {
        if (isJsonNull || !isJsonPrimitive || !asJsonPrimitive.isString) null else asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun loadCache(gson: Gson, cacheFile: File): Map<String, ModInfoCache> {
        return cacheFile.takeIf { it.exists() }?.let {
            runCatching {
                gson.fromJson<List<ModInfoCache>>(
                    Tools.read(it),
                    object : TypeToken<List<ModInfoCache>>() {}.type
                )?.associateBy { it.fileHash }
            }.getOrElse { e ->
                Logging.e("ModParser", "Cache load failed: ${it.absolutePath}", e)
                emptyMap()
            }
        } ?: emptyMap()
    }

    private fun persistCache(gson: Gson, cacheFile: File, data: List<ModInfoCache>) {
        Task.runTask {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                FileUtils.write(cacheFile, gson.toJson(data), Charsets.UTF_8)
            }.onFailure { e ->
                Logging.e("ModParser", "Cache save failed", e)
            }
        }.execute()
    }

    private fun calculateOptimalThreads(fileCount: Int): Int {
        val coreCount = Runtime.getRuntime().availableProcessors()
        return (fileCount.coerceAtMost(coreCount * 8))
            .coerceAtLeast(4)
    }

    private fun calculateChunkSize(totalFiles: Int): Int {
        return when {
            totalFiles < 50 -> 4
            totalFiles < 200 -> 32
            else -> 64
        }
    }

    private suspend fun <T> Semaphore.withPermit(action: suspend () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            release()
        }
    }
}
