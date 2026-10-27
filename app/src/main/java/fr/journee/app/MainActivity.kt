package fr.journee.app

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.journee.app.widget.DayWidget
import fr.journee.app.widget.RowViews
import fr.journee.app.widget.Rows
import java.text.DateFormat
import java.time.ZonedDateTime
import java.util.Date

class MainActivity : Activity() {

    private lateinit var who: TextView
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var actions: View
    private lateinit var url: EditText
    private lateinit var logout: Button
    private lateinit var previewLabel: View
    private lateinit var previewCard: View
    private lateinit var previewTitle: TextView
    private lateinit var previewSubtitle: TextView
    private lateinit var previewRows: LinearLayout

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        who = findViewById(R.id.who)
        status = findViewById(R.id.status)
        connect = findViewById(R.id.connect)
        actions = findViewById(R.id.actions)
        url = findViewById(R.id.url)
        logout = findViewById(R.id.logout)
        previewLabel = findViewById(R.id.preview_label)
        previewCard = findViewById(R.id.preview_card)
        previewTitle = findViewById(R.id.preview_title)
        previewSubtitle = findViewById(R.id.preview_subtitle)
        previewRows = findViewById(R.id.preview_rows)

        url.setText(Store.urlHint(this).orEmpty())

        connect.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            url.text.toString().trim().takeIf { it.isNotEmpty() }?.let { intent.putExtra(LoginActivity.EXTRA_URL, it) }
            startActivity(intent)
        }
        findViewById<Button>(R.id.refresh).setOnClickListener {
            Sync.refreshNow(this)
            status.text = getString(R.string.updating)
        }
        findViewById<Button>(R.id.add_widget).setOnClickListener { pinWidget() }
        findViewById<Button>(R.id.qr_import).setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_MODE, LoginActivity.MODE_QR_IMAGE)
            url.text.toString().trim().takeIf { it.isNotEmpty() }?.let { intent.putExtra(LoginActivity.EXTRA_URL, it) }
            startActivity(intent)
        }
        logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.logout_confirm)
                .setPositiveButton(R.string.logout) { _, _ ->
                    Sync.cancelAll(this)
                    Store.logout(this)
                    DayWidget.updateAll(this)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        render()
        // Opening the app is a good moment for a fresh update.
        if (Store.hasAccount(this) && !Store.needsLogin(this) &&
            System.currentTimeMillis() - Store.lastSync(this) > 10 * 60 * 1000
        ) {
            Sync.refreshNow(this)
        }
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    private fun render() {
        val logged = Store.hasAccount(this)
        val needsLogin = Store.needsLogin(this)
        val snap = Store.snapshot(this)

        who.text = when {
            !logged -> getString(R.string.not_connected)
            snap != null -> listOf(snap.userName, snap.className, snap.school).filter { it.isNotBlank() }.joinToString(" · ")
            else -> ""
        }
        status.text = when {
            !logged -> ""
            Store.syncing(this) -> getString(R.string.updating)
            else -> {
                val last = Store.lastSync(this)
                val base = if (last > 0) {
                    getString(R.string.updated_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)))
                } else {
                    getString(R.string.never_updated)
                }
                Store.error(this)?.let { "$base · $it" } ?: base
            }
        }

        connect.visibility = if (!logged || needsLogin) View.VISIBLE else View.GONE
        connect.text = getString(if (logged) R.string.reconnect else R.string.connect)
        actions.visibility = if (logged) View.VISIBLE else View.GONE
        logout.visibility = if (logged) View.VISIBLE else View.GONE

        val showPreview = logged && snap != null
        previewLabel.visibility = if (showPreview) View.VISIBLE else View.GONE
        previewCard.visibility = if (showPreview) View.VISIBLE else View.GONE
        previewRows.removeAllViews()
        if (snap != null && showPreview) {
            val now = ZonedDateTime.now()
            val header = Rows.header(snap, now)
            previewTitle.text = header.title
            previewSubtitle.text = header.subtitle + (header.unread?.takeIf { it > 0 }?.let { "  ·  ✉ $it" } ?: "")
            for (row in Rows.build(snap, now)) {
                val view = RowViews.bind(this, row, clickable = false).apply(this, previewRows)
                previewRows.addView(view)
            }
        }
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, DayWidget::class.java), null, null)
        } else {
            Toast.makeText(this, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }
}
