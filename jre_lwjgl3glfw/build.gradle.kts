plugins {
    java
}

group = "org.lwjgl.glfw"

configurations.getByName("default").isCanBeResolved = true

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-classes")
    // Versioned destination now -- this module owns only the 3.3.3 build;
    // jre_lwjgl3glfw_341 (new sibling) owns 3.4.1. Tools.generateLibClasspath()/
    // getLWJGL3ClassPath() on the app side pick between "lwjgl3/3.3.3" and
    // "lwjgl3/3.4.1" at runtime from the org.lwjgl:lwjgl: version in the
    // launched version's JSON, same detection Amethyst uses.
    destinationDirectory.set(file("../ZalithLauncher/src/main/assets/components/lwjgl3/3.3.3/"))
    doLast {
        val versionFile = file("../ZalithLauncher/src/main/assets/components/lwjgl3/3.3.3/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
    from({
        configurations.getByName("default").map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
    exclude("net/java/openjdk/cacio/ctc/**")
    manifest {
        attributes("Manifest-Version" to "3.3.3")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    // Swap the jars under libs/ to LWJGL 3.3.3's real release jars
    // (lwjgl, lwjgl-glfw, lwjgl-opengl, lwjgl-openal, lwjgl-stb, lwjgl-jemalloc
    // + their android-natives jars) -- these are currently 3.3.6.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
