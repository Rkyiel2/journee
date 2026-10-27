package fr.journee.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Parsed form of the JSON snapshot built by js/src/snapshot.js. */
data class Snapshot(
    val fetchedAt: Instant?,
    val userName: String,
    val className: String,
    val school: String,
    val lessons: List<Lesson>,
    val homework: List<Homework>,
    val grades: List<Grade>,
    val unread: Int?,
    val menus: Map<LocalDate, List<String>>,
    val errors: Map<String, String>,
) {
    data class Lesson(
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val kind: String,
        val title: String,
        val room: String,
        val teacher: String,
        val status: String?,
        val canceled: Boolean,
        val test: Boolean,
        val color: Int?,
    )

    data class Homework(
        val due: ZonedDateTime,
        val subject: String,
        val text: String,
        val done: Boolean,
        val color: Int?,
    )

    data class Grade(
        val date: ZonedDateTime?,
        val subject: String,
        val value: String,
        val outOf: String,
        val average: String,
    )

    /** The day the widget should show: today while classes remain, otherwise the next school day. */
    fun targetDay(now: ZonedDateTime): LocalDate {
        val today = now.toLocalDate()
        val todays = lessons.filter { it.start.toLocalDate() == today }
        if (todays.any { it.end.isAfter(now) }) return today
        val next = lessons.map { it.start.toLocalDate() }.filter { it.isAfter(today) }.minOrNull()
        return next ?: today
    }

    fun lessonsOn(day: LocalDate) = lessons.filter { it.start.toLocalDate() == day }.sortedBy { it.start }

    companion object {
        private val zone: ZoneId get() = ZoneId.systemDefault()

        private fun time(s: String?): ZonedDateTime? {
            if (s.isNullOrBlank() || s == "null") return null
            return runCatching { Instant.parse(s).atZone(zone) }.getOrNull()
        }

        private fun color(s: String?): Int? {
            if (s.isNullOrBlank() || s == "null") return null
            return runCatching { android.graphics.Color.parseColor(s.trim()) }.getOrNull()
        }

        private fun JSONObject.str(key: String): String =
            if (isNull(key)) "" else optString(key, "")

        private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> {
            if (this == null) return emptyList()
            val out = ArrayList<T>(length())
            for (i in 0 until length()) {
                val o = optJSONObject(i) ?: continue
                f(o)?.let { out.add(it) }
            }
            return out
        }

        fun parse(o: JSONObject): Snapshot {
            val user = o.optJSONObject("user") ?: JSONObject()
            val lessons = o.optJSONArray("lessons").mapObjects { l ->
                val start = time(l.str("start")) ?: return@mapObjects null
                val end = time(l.str("end")) ?: return@mapObjects null
                Lesson(
                    start = start,
                    end = end,
                    kind = l.str("kind"),
                    title = l.str("title"),
                    room = l.str("room"),
                    teacher = l.str("teacher"),
                    status = l.str("status").ifBlank { null },
                    canceled = l.optBoolean("canceled"),
                    test = l.optBoolean("test"),
                    color = color(l.str("color")),
                )
            }
            val homework = o.optJSONArray("homework").mapObjects { h ->
                val due = time(h.str("due")) ?: return@mapObjects null
                Homework(due, h.str("subject"), h.str("text"), h.optBoolean("done"), color(h.str("color")))
            }
            val grades = o.optJSONArray("grades").mapObjects { g ->
                Grade(time(g.str("date")), g.str("subject"), g.str("value"), g.str("outOf"), g.str("average"))
            }
            val menus = LinkedHashMap<LocalDate, List<String>>()
            o.optJSONArray("menus").mapObjects { m ->
                val day = runCatching { LocalDate.parse(m.str("day")) }.getOrNull() ?: return@mapObjects null
                val items = m.optJSONArray("lunch")
                val list = ArrayList<String>()
                if (items != null) for (i in 0 until items.length()) items.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
                menus[day] = list
                Unit
            }
            val errors = LinkedHashMap<String, String>()
            o.optJSONObject("errors")?.let { e ->
                val keys = e.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    errors[k] = e.optString(k)
                }
            }
            return Snapshot(
                fetchedAt = time(o.str("fetchedAt"))?.toInstant(),
                userName = user.str("name"),
                className = user.str("className"),
                school = user.str("school"),
                lessons = lessons,
                homework = homework,
                grades = grades,
                unread = if (o.isNull("unread") || !o.has("unread")) null else o.optInt("unread"),
                menus = menus,
                errors = errors,
            )
        }
    }
}
