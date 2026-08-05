plugins {
    java
}

group = "org.lwjgl.glfw"

configurations.getByName("default").isCanBeResolved = true

// Same patch source files as jre_lwjgl3glfw (CallbackBridge, GLFW*, etc.),
// not duplicated -- this module differs only in which LWJGL jars sit in ITS
// OWN libs/ and where the output jar lands. Keeps the patch code a single
// source of truth; any API difference between 3.3.3 and 3.4.1 the patches
// don't compile against surfaces as a build error here, not a runtime crash.
sourceSets {
    main {
        java.srcDirs("../jre_lwjgl3glfw/src/main/java")
    }
}

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
