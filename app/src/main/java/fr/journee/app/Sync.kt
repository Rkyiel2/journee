package fr.journee.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.journee.app.widget.DayWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Sync {
    // Pronote issues a new token on every login, so two logins must never run at the same time.
    private val mutex = Mutex()

    enum class Outcome { OK, NOT_LOGGED_IN, NEEDS_LOGIN, NETWORK, ERROR }

    suspend fun refresh(context: Context): Outcome {
        val ctx = context.applicationContext
        return mutex.withLock {
            val creds = Store.creds(ctx) ?: return@withLock Outcome.NOT_LOGGED_IN
            Store.setSyncing(ctx, true)
            DayWidget.updateAll(ctx)
            val engine = Engine(ctx)
            try {
                val args = JSONObject()
                    .put("url", creds.url)
                    .put("username", creds.username)
                    .put("token", creds.token)
                    .put("kind", creds.kind)
                    .put("deviceUUID", Store.deviceUUID(ctx))
                creds.navigator?.let { args.put("navigatorIdentifier", it) }
                applyResult(ctx, engine.call("loginToken", args))
            } catch (e: Exception) {
                Store.setError(ctx, "Pas de réponse de Pronote (${e.javaClass.simpleName})")
                Outcome.NETWORK
            } finally {
                engine.destroy()
                Store.setSyncing(ctx, false)
                DayWidget.updateAll(ctx)
            }
        }
    }

    /** Stores the result of a login call (token, QR or double-auth step). */
    fun applyResult(ctx: Context, res: JSONObject): Outcome {
        if (res.optBoolean("ok")) {
            res.optJSONObject("snapshot")?.let { Store.saveSnapshot(ctx, it) }
            schedule(ctx)
            return Outcome.OK
        }
        if (res.has("security")) {
            Store.setError(ctx, "Pronote demande une vérification : ouvre l'app pour te reconnecter.", needsLogin = true)
            return Outcome.NEEDS_LOGIN
        }
        val err = res.optJSONObject("error") ?: JSONObject()
        val type = err.optString("type")
        val message = err.optString("message")
        return when (type) {
            "auth" -> {
                Store.setError(ctx, "Session expirée : reconnecte-toi.", needsLogin = true)
                Outcome.NEEDS_LOGIN
            }
            "network" -> {
                Store.setError(ctx, "Hors ligne")
                Outcome.NETWORK
            }
            else -> {
                Store.setError(ctx, message.ifBlank { "Erreur Pronote" })
                Outcome.ERROR
            }
        }
    }

    private val networkConstraint: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Hourly background refresh. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork("journee-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Refresh right now (widget button, app button). */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("journee-now", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWork()
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return when (Sync.refresh(applicationContext)) {
            Sync.Outcome.NETWORK -> if (runAttemptCount < 2) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
