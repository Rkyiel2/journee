package fr.journee.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import fr.journee.app.R

/** Turns a [Row] into RemoteViews. Used by the widget list and by the in-app preview. */
object RowViews {
    const val VIEW_TYPES = 4

    fun bind(ctx: Context, row: Row, clickable: Boolean = true): RemoteViews {
        val pkg = ctx.packageName
        val accent = ctx.getColor(R.color.accent)
        val now = ctx.getColor(R.color.now)
        val tertiary = ctx.getColor(R.color.text_tertiary)
        val views: RemoteViews
        val clickId: Int
        when (row) {
            is Row.Section -> {
                views = RemoteViews(pkg, R.layout.widget_row_section)
                views.setTextViewText(R.id.text, row.label)
                clickId = R.id.text
            }
            is Row.Message -> {
                views = RemoteViews(pkg, R.layout.widget_row_message)
                views.setTextViewText(R.id.text, row.text)
                clickId = R.id.text
            }
            is Row.Lesson -> {
                views = RemoteViews(pkg, R.layout.widget_row_lesson)
                val start = SpannableString(row.start)
                if (row.now) {
                    start.setSpan(ForegroundColorSpan(now), 0, start.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                views.setTextViewText(R.id.start, start)
                views.setTextViewText(R.id.end, row.end)

                val title = SpannableString(row.title)
                if (row.canceled) {
                    title.setSpan(StrikethroughSpan(), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    title.setSpan(ForegroundColorSpan(tertiary), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (row.now) {
                    title.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                views.setTextViewText(R.id.title, title)

                val detail = SpannableStringBuilder()
                if (!row.status.isNullOrBlank()) {
                    detail.append(row.status)
                    detail.setSpan(ForegroundColorSpan(accent), 0, detail.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (row.detail.isNotBlank()) detail.append(" · ")
                }
                detail.append(row.detail)
                views.setTextViewText(R.id.detail, detail)
                views.setViewVisibility(R.id.detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)

                val barColor = if (row.canceled) tertiary else (row.color ?: tertiary)
                views.setInt(R.id.bar, "setColorFilter", barColor)
                clickId = R.id.row
            }
            is Row.Entry -> {
                views = RemoteViews(pkg, R.layout.widget_row_entry)
                views.setTextViewText(R.id.title, row.title)
                views.setInt(R.id.title, "setMaxLines", row.maxTitleLines)
                views.setTextViewText(R.id.detail, row.detail)
                views.setViewVisibility(R.id.detail, if (row.detail.isBlank()) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.trailing, row.trailing)
                views.setViewVisibility(R.id.trailing, if (row.trailing.isBlank()) View.GONE else View.VISIBLE)
                val c = row.color
                if (c == null) {
                    views.setViewVisibility(R.id.dot, View.GONE)
                } else {
                    views.setViewVisibility(R.id.dot, View.VISIBLE)
                    views.setInt(R.id.dot, "setColorFilter", c)
                }
                clickId = R.id.row
            }
        }
        if (clickable) views.setOnClickFillInIntent(clickId, Intent())
        return views
    }
}
