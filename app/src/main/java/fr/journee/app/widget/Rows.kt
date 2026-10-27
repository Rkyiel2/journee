package fr.journee.app.widget

import fr.journee.app.Snapshot
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What the widget (and the in-app preview) displays, independent of how it is drawn. */
sealed class Row {
    data class Section(val label: String) : Row()

    data class Lesson(
        val start: String,
        val end: String,
        val title: String,
        val detail: String,
        val status: String?,
        val canceled: Boolean,
        val now: Boolean,
        val color: Int?,
    ) : Row()

    data class Entry(
        val title: String,
        val trailing: String,
        val detail: String,
        val color: Int?,
        val maxTitleLines: Int = 1,
    ) : Row()

    data class Message(val text: String) : Row()
}

data class Header(val title: String, val subtitle: String, val unread: Int?)

object Rows {
    private val FR = Locale.FRENCH
    private val HM = DateTimeFormatter.ofPattern("HH:mm", FR)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)
    private val D_MONTH = DateTimeFormatter.ofPattern("d MMMM", FR)
    private val SHORT = DateTimeFormatter.ofPattern("d MMM", FR)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", FR)
    private val WEEKDAY_SHORT = DateTimeFormatter.ofPattern("EEE", FR)

    fun header(snap: Snapshot?, now: ZonedDateTime): Header {
        val today = now.toLocalDate()
        val day = snap?.targetDay(now) ?: today
        val title = when (day) {
            today -> "Aujourd'hui"
            today.plusDays(1) -> "Demain"
            else -> cap(day.format(WEEKDAY))
        }
        val subtitle = if (day == today || day == today.plusDays(1)) day.format(DAY_MONTH) else day.format(D_MONTH)
        return Header(title, subtitle, snap?.unread)
    }

    fun build(snap: Snapshot, now: ZonedDateTime): List<Row> {
        val rows = ArrayList<Row>()
        val today = now.toLocalDate()
        val day = snap.targetDay(now)

        // Classes
        val lessons = snap.lessonsOn(day)
        if (lessons.isEmpty()) {
            rows += Row.Message(if (snap.errors.containsKey("timetable")) "Emploi du temps indisponible" else "Pas de cours")
        } else {
            for (l in lessons) {
                val status = when {
                    !l.status.isNullOrBlank() -> l.status
                    l.canceled -> "Annulé"
                    l.test -> "Contrôle"
                    else -> null
                }
                rows += Row.Lesson(
                    start = l.start.format(HM),
                    end = l.end.format(HM),
                    title = pretty(l.title),
                    detail = listOf(l.room, pretty(l.teacher)).filter { it.isNotBlank() }.joinToString(" · "),
                    status = status,
                    canceled = l.canceled,
                    now = day == today && !l.canceled && !now.isBefore(l.start) && now.isBefore(l.end),
                    color = l.color,
                )
            }
        }

        // Homework still to do, due on the shown day or later
        val todo = snap.homework
            .filter { !it.done && !it.due.toLocalDate().isBefore(day) }
            .sortedBy { it.due }
            .take(6)
        rows += Row.Section("Devoirs")
        if (todo.isEmpty()) {
            rows += Row.Message(if (snap.errors.containsKey("homework")) "Devoirs indisponibles" else "Rien à faire")
        } else {
            for (h in todo) {
                rows += Row.Entry(
                    title = pretty(h.subject),
                    trailing = dueLabel(h.due.toLocalDate(), today),
                    detail = h.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    color = h.color,
                )
            }
        }

        // Canteen
        snap.menus[day]?.takeIf { it.isNotEmpty() }?.let { items ->
            rows += Row.Section("Cantine")
            rows += Row.Entry(title = items.joinToString(" · "), trailing = "", detail = "", color = null, maxTitleLines = 3)
        }

        // Latest grades
        if (snap.grades.isNotEmpty()) {
            rows += Row.Section("Notes")
            for (g in snap.grades.take(3)) {
                val value = if (g.outOf.isNotBlank() && g.value.any { it.isDigit() }) "${g.value}/${g.outOf}" else g.value
                val detail = buildList {
                    g.date?.let { add(it.toLocalDate().format(SHORT)) }
                    if (g.average.isNotBlank()) add("moy. ${g.average}")
                }.joinToString(" · ")
                rows += Row.Entry(title = pretty(g.subject), trailing = value, detail = detail, color = null)
            }
        }
        return rows
    }

    private fun dueLabel(due: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days <= 0L -> "aujourd'hui"
            days == 1L -> "demain"
            days < 7L -> due.format(WEEKDAY_SHORT)
            else -> due.format(SHORT)
        }
    }

    private fun cap(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(FR) else it.toString() }

    /** Pronote writes subjects and teachers in CAPITALS; tone that down. */
    fun pretty(s: String): String {
        if (s.isBlank()) return s
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) return s
        val lower = s.lowercase(FR)
        val sb = StringBuilder(lower.length)
        var upperNext = true
        for (c in lower) {
            if (upperNext && c.isLetter()) {
                sb.append(c.titlecase(FR))
                upperNext = false
            } else {
                sb.append(c)
            }
            if (c == ' ' || c == '-' || c == '\'' || c == '(' || c == '/') upperNext = true
        }
        return sb.toString()
    }
}
