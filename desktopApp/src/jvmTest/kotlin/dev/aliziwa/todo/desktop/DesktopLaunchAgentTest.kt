package dev.aliziwa.todo.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Covers the pure parts of [DesktopCapabilities]: the generated dev run script and the plist
 * payload. Installing/removing the real LaunchAgent is verified manually with `launchctl print`
 * (it mutates the user's login items, so it is not a unit test).
 */
class DesktopLaunchAgentTest {

    @Test
    fun `dev run script points at this checkout and starts hidden`() {
        val caps = DesktopCapabilities(DesktopPaths(), DesktopUiController())
        val script = caps.writeDevRunScript()
        assertTrue(script.canExecute(), "run-dev.sh must be executable")
        val text = script.readText()
        assertTrue(text.contains(":desktopApp:run"), "must launch the desktop app: $text")
        assertTrue(text.contains("TODO_START_HIDDEN=1"), "login start must not pop a window: $text")
        assertTrue(text.contains("DEVELOPER_DIR="), "Gradle needs DEVELOPER_DIR on this machine: $text")
        val cdLine = text.lines().first { it.startsWith("cd ") }
        val repo = cdLine.removePrefix("cd ").trim().trim('"').removeSuffix(" || exit 1").trim('"')
        assertTrue(File(repo, "gradlew").exists(), "resolved repo root has no gradlew: $repo")
    }

    @Test
    fun `program arguments target the dev script when not packaged`() {
        val caps = DesktopCapabilities(DesktopPaths(), DesktopUiController())
        val args = caps.resolveProgramArguments()
        assertEquals("/bin/sh", args.first())
        assertTrue(args[1].endsWith("run-dev.sh"), "unexpected arguments: $args")
    }

    @Test
    fun `plist path is the documented LaunchAgents location`() {
        val caps = DesktopCapabilities(DesktopPaths(), DesktopUiController())
        assertTrue(
            caps.plistFile.absolutePath.endsWith("Library/LaunchAgents/dev.aliziwa.todo.plist"),
            caps.plistFile.absolutePath,
        )
    }
}
