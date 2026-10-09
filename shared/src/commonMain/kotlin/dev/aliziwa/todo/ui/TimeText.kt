package dev.aliziwa.todo.ui

/**
 * Pure time-of-day text helpers for the time picker. No Compose here on purpose: every rule is
 * unit-tested in `commonTest/ui/TimeTextTest.kt`.
 *
 * Minutes are always "minutes since local midnight", 0..1439 — the same unit
 * [dev.aliziwa.todo.domain.TaskDraft.timeMinutes] uses.
 */
object TimeText {

    const val MINUTES_PER_DAY = 24 * 60

    private val words = mapOf(
        "noon" to 12 * 60,
        "midday" to 12 * 60,
        "midnight" to 0,
        "morning" to 9 * 60,
        "lunch" to 12 * 60 + 30,
        "afternoon" to 15 * 60,
        "evening" to 18 * 60,
        "tonight" to 20 * 60,
        "night" to 21 * 60,
    )

    /**
     * Parses anything a human types into a time field:
     * `"3pm"`, `"3 PM"`, `"15:30"`, `"7.45"`, `"7h45"`, `"930"`, `"0930"`, `"9"`, `"noon"`,
     * `"midnight"`, `"12am"`. Returns null when it cannot be read as a time of day.
     */
    fun parse(raw: String?): Int? {
        if (raw == null) return null
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null

        words[text]?.let { return it }

        // "at 5", "@ 5" and a trailing "o'clock" are noise around a normal time.
        var core = text
            .removePrefix("at ")
            .removePrefix("@")
            .replace("o'clock", "")
            .replace("oclock", "")
            .trim()

        var pm = false
        var am = false
        for (suffix in listOf("p.m.", "a.m.", "pm", "am", "p", "a")) {
            if (core.endsWith(suffix)) {
                if (suffix.startsWith("p")) pm = true else am = true
                core = core.removeSuffix(suffix).trim()
                break
            }
        }
        if (core.isEmpty()) return null

        // Any of ':' '.' 'h' ' ' may separate hours from minutes.
        val normalized = core.replace('.', ':').replace('h', ':').replace(' ', ':')
        val hourPart: String
        val minutePart: String
        if (normalized.contains(':')) {
            val pieces = normalized.split(':').filter { it.isNotBlank() }
            if (pieces.size > 2) return null
            hourPart = pieces.getOrNull(0) ?: return null
            minutePart = pieces.getOrNull(1) ?: "0"
        } else {
            if (!normalized.all { it.isDigit() }) return null
            when (normalized.length) {
                1, 2 -> {
                    hourPart = normalized
                    minutePart = "0"
                }
                3, 4 -> {
                    hourPart = normalized.dropLast(2)
                    minutePart = normalized.takeLast(2)
                }
                else -> return null
            }
        }

        if (!hourPart.all { it.isDigit() } || !minutePart.all { it.isDigit() }) return null
        if (minutePart.length > 2) return null
        var hour = hourPart.toIntOrNull() ?: return null
        val minute = minutePart.toIntOrNull() ?: return null
        if (minute !in 0..59) return null

        if (pm || am) {
            if (hour !in 1..12) return null
            if (pm && hour < 12) hour += 12
            if (am && hour == 12) hour = 0
        }
        if (hour !in 0..23) return null
        return hour * 60 + minute
    }

    /** "15:30" when [use24Hour], otherwise "3:30 PM". */
    fun format(minutesOfDay: Int, use24Hour: Boolean = false): String {
        val m = wrap(minutesOfDay)
        val hour = m / 60
        val minute = m % 60
        return if (use24Hour) {
            "${pad(hour)}:${pad(minute)}"
        } else {
            "${displayHour12(hour)}:${pad(minute)} ${if (hour < 12) "AM" else "PM"}"
        }
    }

    /** Same as [format] but "No time" for null — what the picker's pill shows. */
    fun formatOrNone(minutesOfDay: Int?, use24Hour: Boolean = false, noneLabel: String = "No time"): String =
        minutesOfDay?.let { format(it, use24Hour) } ?: noneLabel

    /** Short form for dense rows: "15:30" / "3:30pm", and "3pm" on the hour. */
    fun formatCompact(minutesOfDay: Int, use24Hour: Boolean = false): String {
        val m = wrap(minutesOfDay)
        val hour = m / 60
        val minute = m % 60
        if (use24Hour) return "${pad(hour)}:${pad(minute)}"
        val suffix = if (hour < 12) "am" else "pm"
        return if (minute == 0) "${displayHour12(hour)}$suffix" else "${displayHour12(hour)}:${pad(minute)}$suffix"
    }

    /** 0 -> 12, 13 -> 1. */
    fun displayHour12(hour24: Int): Int {
        val h = ((hour24 % 24) + 24) % 24 % 12
        return if (h == 0) 12 else h
    }

    /** The hour labels a wheel shows: 0..23 for 24h, 12,1..11 for 12h. */
    fun hourWheelValues(use24Hour: Boolean): List<Int> =
        if (use24Hour) (0..23).toList() else listOf(12) + (1..11).toList()

    fun hourWheelLabel(hour: Int, use24Hour: Boolean): String = if (use24Hour) pad(hour) else hour.toString()

    /** Rebuilds minutes-of-day from the wheel + AM/PM state. */
    fun fromParts(wheelHour: Int, minute: Int, use24Hour: Boolean, pm: Boolean): Int {
        val hour = when {
            use24Hour -> wheelHour
            pm -> if (wheelHour == 12) 12 else wheelHour + 12
            else -> if (wheelHour == 12) 0 else wheelHour
        }
        return wrap(hour * 60 + minute)
    }

    fun isPm(minutesOfDay: Int): Boolean = wrap(minutesOfDay) >= 12 * 60

    /** Shifts a time by [deltaMinutes], wrapping around midnight. */
    fun shift(minutesOfDay: Int, deltaMinutes: Int): Int = wrap(minutesOfDay + deltaMinutes)

    /** Rounds up to the next [step] boundary — used by the "+15m" quick chip. */
    fun roundUpTo(minutesOfDay: Int, step: Int): Int {
        if (step <= 1) return wrap(minutesOfDay)
        val m = wrap(minutesOfDay)
        val remainder = m % step
        return wrap(if (remainder == 0) m else m + (step - remainder))
    }

    fun wrap(minutes: Int): Int = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()
}
