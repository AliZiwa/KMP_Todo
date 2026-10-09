@file:OptIn(ExperimentalForeignApi::class)

package dev.aliziwa.todo.platform.ios

import dev.aliziwa.todo.platform.AppPaths
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS [AppPaths]: everything lives under `Application Support/<appName>` inside the app
 * container (or inside the shared App Group container when [appGroupId] is given, so the
 * WidgetKit extension can read the same files).
 *
 * All directories are created eagerly; the constructor never throws.
 */
class IosAppPaths(
    appName: String = "PersonalTodo",
    appGroupId: String? = null,
) : AppPaths {

    override val dataDir: String
    override val modelsDir: String
    override val databasePath: String

    init {
        val fm = NSFileManager.defaultManager
        val base: NSURL = appGroupId
            ?.let { fm.containerURLForSecurityApplicationGroupIdentifier(it) }
            ?: (fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as? NSURL)
            ?: NSURL.fileURLWithPath(NSTemporaryDirectory())

        val root = base.URLByAppendingPathComponent(appName)?.path
            ?: (NSTemporaryDirectory() + appName)
        val models = "$root/models"

        ensureDirectory(root)
        ensureDirectory(models)

        dataDir = root
        modelsDir = models
        databasePath = "$root/todo.db"
    }

    private fun ensureDirectory(path: String) {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) {
            fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }
}
