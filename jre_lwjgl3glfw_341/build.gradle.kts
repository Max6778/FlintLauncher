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
        // The stock 3.4.1 lwjgl-glfw.jar ships its own unpatched GLFW/CallbackBridge
        // classes. Our patched sources (compiled from jre_lwjgl3glfw/src) must always
        // win for this package -- never rely on from()-ordering/duplicatesStrategy
        // to sort that out implicitly, since that's exactly what silently shipped
        // the stock class before and caused the JNI_OnLoad SIGSEGV.
        exclude("org/lwjgl/glfw/**")
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
