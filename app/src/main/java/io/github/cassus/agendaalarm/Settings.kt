package io.github.cassus.agendaalarm

import android.content.Context

/** App settings and alarm bookkeeping in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var filter: FilterSettings
        get() = FilterSettings(
            calendars = prefs.getStringSet("calendars", emptySet())!!.mapNotNull(::parseRule).toMap(),
            skipAllDay = prefs.getBoolean("skipAllDay", true),
            accepted = prefs.getBoolean("accepted", true),
            tentative = prefs.getBoolean("tentative", true),
            declined = prefs.getBoolean("declined", false),
            notReplied = prefs.getBoolean("notReplied", true),
            owned = prefs.getBoolean("owned", true),
        )
        set(s) {
            prefs.edit()
                .putStringSet("calendars", s.calendars.map { (id, r) -> "$id:${if (r.events) "E" else ""}${if (r.todos) "T" else ""}" }.toSet())
                .putBoolean("skipAllDay", s.skipAllDay)
                .putBoolean("accepted", s.accepted)
                .putBoolean("tentative", s.tentative)
                .putBoolean("declined", s.declined)
                .putBoolean("notReplied", s.notReplied)
                .putBoolean("owned", s.owned)
                .apply()
        }

    var snoozeMinutes: List<Int>
        get() = prefs.getString("snooze", "5,10,15")!!.split(",").mapNotNull { it.trim().toIntOrNull() }
        set(v) = prefs.edit().putString("snooze", v.joinToString(",")).apply()

    var fadeInSeconds: Int
        get() = prefs.getInt("fadeIn", 90)
        set(v) = prefs.edit().putInt("fadeIn", v).apply()

    var autoStopSeconds: Int
        get() = prefs.getInt("autoStop", 180)
        set(v) = prefs.edit().putInt("autoStop", v).apply()

    /** Vibrate with the alarm (in normal and vibrate ringer modes). */
    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit().putBoolean("vibrate", v).apply()

    /** Ringtone URI string; null = default alarm sound. */
    var ringtone: String?
        get() = prefs.getString("ringtone", null)
        set(v) = prefs.edit().putString("ringtone", v).apply()

    /** Custom alarm volume in percent; null = keep the system alarm volume. */
    var volumePercent: Int?
        get() = prefs.getInt("volume", -1).takeIf { it >= 0 }
        set(v) = prefs.edit().putInt("volume", v ?: -1).apply()

    /** Keys of reminders already fired, "key|alarmTime", pruned after a day. */
    fun fired(now: Long): Set<String> =
        prefs.getStringSet("fired", emptySet())!!
            .filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) > now - DAY }
            .map { it.substringBeforeLast('|') }.toSet()

    fun markFired(key: String, now: Long) {
        val kept = prefs.getStringSet("fired", emptySet())!!
            .filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) > now - DAY }
        prefs.edit().putStringSet("fired", (kept + "$key|$now").toSet()).commit()
    }

    private fun parseRule(s: String): Pair<Long, CalendarRule>? {
        val id = s.substringBefore(':').toLongOrNull() ?: return null
        val flags = s.substringAfter(':', "")
        return id to CalendarRule(events = 'E' in flags, todos = 'T' in flags)
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L

        /** "5, 10,15" → [5, 10, 15]; null if not 1–4 distinct values of 1–1440 minutes. */
        fun parseSnooze(text: String): List<Int>? {
            val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val mins = parts.map { it.toIntOrNull()?.takeIf { m -> m in 1..1440 } ?: return null }
            return mins.distinct().sorted().takeIf { it.size in 1..4 && it.size == mins.size }
        }

        /** Seconds field: whole number in range, else null. */
        fun parseSeconds(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }

        /** Percent of the stream's max → volume index; at least 1 so a custom level is never silent. */
        fun volumeIndex(percent: Int, max: Int): Int = ((percent.coerceIn(0, 100) * max + 50) / 100).coerceIn(1, max)
    }
}
