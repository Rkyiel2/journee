package fr.journee.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import fr.journee.app.Store
import java.time.ZonedDateTime

class DayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var rows: List<Row> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            val snap = Store.snapshot(ctx)
            rows = if (snap == null || !Store.hasAccount(ctx)) emptyList() else Rows.build(snap, ZonedDateTime.now())
        }

        override fun onDestroy() {
            rows = emptyList()
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews? {
            val row = rows.getOrNull(position) ?: return null
            return RowViews.bind(ctx, row)
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = RowViews.VIEW_TYPES

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false
    }
}
