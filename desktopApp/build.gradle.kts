import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.shared)
            implementation(compose.desktop.currentOs)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            // java-llama.cpp: the jar bundles de/kherud/llama/Mac/aarch64/libjllama.dylib and
            // LlamaLoader extracts it at runtime, so no java.library.path wiring is required.
            implementation(libs.java.llama.cpp)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Integration tests build a real TodoApp graph with the real ports; headless keeps AWT from
// putting a Dock icon up for the test JVM (and exercises the Taskbar guard in DesktopAlertPresenter).
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    testLogging { showStandardStreams = true }
    // ScreenshotTest renders App() into PNGs; -Ptodo.screenshotDir=<dir> says where they land.
    (project.findProperty("todo.screenshotDir") as String?)?.let {
        systemProperty("todo.screenshotDir", it)
    }
}

compose.desktop {
    application {
        mainClass = "dev.aliziwa.todo.desktop.MainKt"

        // Compose's packaging task refuses Homebrew JDKs (compose-multiplatform#3107), and
        // `org.gradle.java.home` (supervisor-owned) is Homebrew's openjdk@21. Point jpackage at the
        // first non-Homebrew JDK on the machine when one exists; otherwise leave the default and let
        // checkRuntime explain itself.
        providers.exec {
            commandLine("/usr/libexec/java_home", "-V")
        }.standardError.asText.orNull
            ?.lines()
            ?.mapNotNull { line -> line.substringAfter(" /", "").takeIf { it.isNotBlank() }?.let { "/" + it.trim() } }
            ?.firstOrNull { it.contains("/JavaVirtualMachines/") && !it.contains("/homebrew/") }
            ?.let { packagingJdk ->
                logger.lifecycle("Packaging with JDK at $packagingJdk")
                javaHome = packagingJdk
            }

        // Menu-bar/Dock name and a sane AWT tray experience.
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Personal Todo",
            "-Dapple.laf.useScreenMenuBar=true",
            // Room's bundled SQLite and java-llama.cpp both System.loadLibrary(); JDK 24+ warns
            // today and will block it without this.
            "--enable-native-access=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "PersonalTodo"
            packageVersion = "1.0.0"
            description = "Personal to-do with due alerts and an offline assistant"
            vendor = "aliziwa"
            // Room's bundled SQLite, java-llama.cpp's native extraction and AWT all reach outside
            // the Compose default module set; bundling the full JDK keeps the DMG self-contained.
            includeAllModules = true

            macOS {
                bundleID = "dev.aliziwa.todo"
                dockName = "Personal Todo"
                iconFile.set(project.file("icons/appicon.icns"))
                infoPlist {
                    // LSUIElement false => normal Dock app (we also want a Dock badge);
                    // the tray lives in the menu bar regardless.
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <false/>
                        <key>NSHighResolutionCapable</key>
                        <true/>
                        <key>LSApplicationCategoryType</key>
                        <string>public.app-category.productivity</string>
                        <key>NSSupportsAutomaticGraphicsSwitching</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}

// Convenience for headless verification of the LaunchAgent CLI hooks.
tasks.register("printRuntimeClasspath") {
    val cp = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles
    val out = layout.buildDirectory.file("runtime-classpath.txt")
    outputs.file(out)
    doLast {
        val jar = tasks.named("jvmJar").get().outputs.files.singleFile
        out.get().asFile.writeText((listOf(jar) + (cp?.files ?: emptySet())).joinToString(":") { it.absolutePath })
    }
}
