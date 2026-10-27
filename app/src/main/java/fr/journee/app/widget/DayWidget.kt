package fr.journee.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import fr.journee.app.MainActivity
import fr.journee.app.R
import fr.journee.app.Store
import fr.journee.app.Sync
import java.text.DateFormat
import java.time.ZonedDateTime
import java.util.Date

class DayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onEnabled(context: Context) {
        if (Store.hasAccount(context)) Sync.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH && Store.hasAccount(context)) {
            Sync.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "fr.journee.app.REFRESH"

        fun updateAll(context: Context) {
            val ctx = context.applicationContext
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, DayWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, manager, ids)
        }

        private fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            for (id in ids) manager.updateAppWidget(id, build(ctx, id))
            manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        private fun immutable() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun mutable() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        @Suppress("DEPRECATION")
        private fun build(ctx: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_day)
            val snap = Store.snapshot(ctx)
            val loggedIn = Store.hasAccount(ctx)
            val header = Rows.header(snap, ZonedDateTime.now())

            views.setTextViewText(R.id.title, header.title)
            views.setTextViewText(R.id.subtitle, header.subtitle)
            val unread = header.unread ?: 0
            if (loggedIn && unread > 0) {
                views.setTextViewText(R.id.unread, "✉ $unread")
                views.setViewVisibility(R.id.unread, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.unread, View.GONE)
            }

            val footer = when {
                !loggedIn -> ""
                Store.needsLogin(ctx) -> ctx.getString(R.string.widget_needs_login)
                Store.syncing(ctx) -> ctx.getString(R.string.updating)
                else -> {
                    val last = Store.lastSync(ctx)
                    val time = if (last > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)) else null
                    val base = time?.let { ctx.getString(R.string.updated_at, it) } ?: ctx.getString(R.string.never_updated)
                    Store.error(ctx)?.let { "$base · $it" } ?: base
                }
            }
            views.setTextViewText(R.id.footer, footer)
            views.setViewVisibility(R.id.footer, if (footer.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(
                R.id.empty,
                if (loggedIn) ctx.getString(R.string.updating) else ctx.getString(R.string.widget_tap_to_connect),
            )

            // List of rows, served by DayWidgetService.
            val serviceIntent = Intent(ctx, DayWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            serviceIntent.data = Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.list, serviceIntent)
            views.setEmptyView(R.id.list, R.id.empty)

            val openApp = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                immutable(),
            )
            views.setOnClickPendingIntent(R.id.header, openApp)
            views.setOnClickPendingIntent(R.id.empty, openApp)
            views.setOnClickPendingIntent(R.id.footer, openApp)
            val template = PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                mutable(),
            )
            views.setPendingIntentTemplate(R.id.list, template)

            // Not connected (or reconnection needed): the button opens the app instead,
            // because a broadcast receiver may not start an activity from the background.
            val refresh = if (loggedIn && !Store.needsLogin(ctx)) {
                PendingIntent.getBroadcast(
                    ctx, 2,
                    Intent(ctx, DayWidget::class.java).setAction(ACTION_REFRESH),
                    immutable(),
                )
            } else {
                openApp
            }
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            return views
        }
    }
}
