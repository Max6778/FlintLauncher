package com.movtery.zalithlauncher.renderer.renderers

import com.movtery.zalithlauncher.renderer.RendererInterface

/**
 * Krypton Wrapper (org.angelauramc.krypton_wrapper) — a "next-gen GL4ES" fork,
 * bundled here from its upstream krypton_wrapper-release.aar (MIT licensed,
 * (c) 2016-2018 Sebastien Chevalier, (c) 2013-2016 Ryan Hileman, (c) 2025 BZLZHH).
 * Its native library is libng_gl4es.so.
 *
 * The "opengles2" prefix on getRendererId() is intentional: JREUtils.setRendererEnv()
 * pattern-matches on rendererId.startsWith("opengles2") to apply the GL4ES-family
 * tuning env vars (LIBGL_ES, LIBGL_MIPMAP, LIBGL_NOERROR, LIBGL_NOINTOVLHACK,
 * LIBGL_NORMALIZE), and separately requires rendererId.startsWith("opengles") to
 * skip the Zink/Mesa branch entirely. "opengles2_krypton" satisfies both while
 * staying distinct from GL4ESRenderer's own "opengles2" id.
 */
class KryptonRenderer : RendererInterface {
    override fun getRendererId(): String = "opengles2_krypton"

    override fun getUniqueIdentifier(): String = "c47a1e6d-3f92-4b8a-9e15-7d2c6a4f0b83"

    override fun getRendererName(): String = "Krypton Wrapper"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libng_gl4es.so"
}
