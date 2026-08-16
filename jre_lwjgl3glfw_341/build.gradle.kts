plugins {
    java
}

group = "org.lwjgl.glfw"

configurations.getByName("default").isCanBeResolved = true

// This module used to share Java source with jre_lwjgl3glfw via a sourceSets
// override pointing at "../jre_lwjgl3glfw/src/main/java". That broke down once
// Minecraft 26.2 needed GLFW.glfwSetPreeditCallback/IME methods, which only
// exist as real classes (GLFWPreeditCallback etc.) in the 3.4.1 lwjgl-glfw jar
// -- referencing them from shared source would fail to compile against 3.3.3's
// jar, which doesn't ship those classes at all. So this module now keeps its
// own full copy of the patch source under its own src/main/java (default
// convention, no override needed) -- same pattern Amethyst uses for its two
// lwjgl-glfw modules. Any shared, version-independent fix still needs to be
// applied to both copies by hand; only genuinely 3.4.1-only API (Preedit/IME)
// belongs solely here.
//
// Note: this module's @Nullable annotations use javax.annotation (JSR-305,
// same as jre_lwjgl3glfw), not org.jspecify -- jspecify's TYPE_USE-targeted
// @Nullable triggers an unfixed javac bug (JNIWriter$TypeSignature$SignatureException)
// when generating native headers for classes with native methods.

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-classes")
    destinationDirectory.set(file("../ZalithLauncher/src/main/assets/components/lwjgl3/3.4.1/"))
    doLast {
        val versionFile = file("../ZalithLauncher/src/main/assets/components/lwjgl3/3.4.1/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
    from({
        configurations.getByName("default").map {
            if (it.isDirectory) it else zipTree(it)
        }
    }) {
        // Only exclude the exact classes our patch source (jre_lwjgl3glfw/src)
        // replaces -- NOT the whole org/lwjgl/glfw package. The stock jar still
        // needs to supply everything we don't patch (GLFWErrorCallbackI,
        // GLFWKeyCallbackI, GLFWWindowSizeCallbackI, and the rest of the
        // callback interfaces GLFW.java's own method signatures depend on).
        exclude("org/lwjgl/glfw/GLFW.class", "org/lwjgl/glfw/GLFW\$*.class")
        exclude("org/lwjgl/glfw/CallbackBridge.class", "org/lwjgl/glfw/CallbackBridge\$*.class")
        exclude("org/lwjgl/glfw/Callbacks.class", "org/lwjgl/glfw/Callbacks\$*.class")
        exclude("org/lwjgl/glfw/GLFWNativeCocoa.class")
        exclude("org/lwjgl/glfw/GLFWNativeEGL.class")
        exclude("org/lwjgl/glfw/GLFWNativeNSGL.class")
        exclude("org/lwjgl/glfw/GLFWNativeOSMesa.class")
        exclude("org/lwjgl/glfw/GLFWNativeWGL.class")
        exclude("org/lwjgl/glfw/GLFWNativeWayland.class")
        exclude("org/lwjgl/glfw/GLFWNativeWin32.class")
        exclude("org/lwjgl/glfw/GLFWNativeX11.class")
        exclude("org/lwjgl/glfw/GLFWWindowProperties.class", "org/lwjgl/glfw/GLFWWindowProperties\$*.class")
        exclude("org/lwjgl/opengl/GLCapabilities.class", "org/lwjgl/opengl/GLCapabilities\$*.class")
        exclude("org/lwjgl/opengl/PojavRendererInit.class", "org/lwjgl/opengl/PojavRendererInit\$*.class")
    }
    exclude("net/java/openjdk/cacio/ctc/**")
    manifest {
        attributes("Manifest-Version" to "3.4.1")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    // This module's OWN libs/ folder -- put LWJGL 3.4.1's real release jars
    // here, separate from jre_lwjgl3glfw/libs/ (which stays on 3.3.3).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
