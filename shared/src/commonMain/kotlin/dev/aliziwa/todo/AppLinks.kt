package dev.aliziwa.todo

/**
 * The only outbound URLs the app knows about. They are opened in the system browser by the user's
 * own tap (Settings → About); the app itself never fetches them, so this does not weaken the
 * offline-first rule.
 */
object AppLinks {
    const val PRIVACY = "https://aliziwa.github.io/personal-todo-kmp/privacy.html"
    const val SUPPORT = "https://aliziwa.github.io/personal-todo-kmp/support.html"
}

/** Store/product name, shown in Settings → About. */
const val APP_DISPLAY_NAME = "Doorbell"
