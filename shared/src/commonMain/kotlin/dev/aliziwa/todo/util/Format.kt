package dev.aliziwa.todo.util

/** Human-readable countdown / overdue text used by chips, tray tooltips and notifications. */
object Format {

    /**
     * Formats the signed distance to a due instant.
     *  - future  -> "in 2d 3h", "in 3h 12m", "in 12m", "in 45s"
     *  - now     -> "due now"
     *  - past    -> "overdue by 3m"
     */
    fun countdown(deltaMillis: Long): String = when {
        deltaMillis in -999..999 -> "due now"
        deltaMillis > 0 -> "in " + span(deltaMillis)
        else -> "overdue by " + span(-deltaMillis)
    }

    /** Just the magnitude, e.g. "2d 3h". */
    fun span(millis: Long): String {
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** `minutesOfDay` -> "08:05". */
    fun timeOfDay(minutesOfDay: Int): String {
        val m = ((minutesOfDay % 1440) + 1440) % 1440
        return pad2(m / 60) + ":" + pad2(m % 60)
    }

    fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()
}
