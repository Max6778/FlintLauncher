package com.flintlauncher.renderer.renderers

import com.flintlauncher.renderer.RendererInterface
import com.flintlauncher.utils.path.PathManager
import java.io.File

class VirGLRenderer : RendererInterface {
    override fun getRendererId(): String = "gallium_virgl"

    override fun getUniqueIdentifier(): String = "a3ccc1fe-de3f-4a81-8c45-2485181b63b3"

    override fun getRendererName(): String = "VirGLRenderer"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            "VTEST_SOCKET_NAME" to File(PathManager.DIR_CACHE, ".virgl_test").absolutePath
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libOSMesa_2121.so"
}