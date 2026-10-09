package dev.aliziwa.todo.util

import kotlin.random.Random

/** Random identifiers without depending on experimental `kotlin.uuid`. */
object Ids {
    private const val HEX = "0123456789abcdef"

    /** RFC-4122-shaped random id (version 4 layout), good enough as a primary key. */
    fun newId(): String {
        val chars = CharArray(36)
        for (i in chars.indices) {
            chars[i] = when (i) {
                8, 13, 18, 23 -> '-'
                14 -> '4'
                19 -> HEX[8 + Random.nextInt(4)]
                else -> HEX[Random.nextInt(16)]
            }
        }
        return chars.concatToString()
    }
}
