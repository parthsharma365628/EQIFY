package com.liteplane.eqify

import android.content.Context
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Sentry wrapper. Off when the build has no DSN or the user opted out.
 * Never sends song titles, artists, headphone names, API keys, IP or user IDs.
 */
object CrashReporting {
    private const val TAG = "CrashReporting"
    private const val PREFS = "eqify_crash_reporting"
    private const val KEY_ENABLED = "enabled"

    /** Handled-error categories; each is reported at most once per process. */
    enum class Category {
        EQUALIZER_ATTACH, SESSION_ZERO_FALLBACK, APPLY_GAINS, LIMITER_FAILED,
        GENRE_HISTORY_WRITE, RECEIVER_REGISTRATION
    }

    private val reported = ConcurrentHashMap.newKeySet<Category>()
    private val _active = MutableStateFlow(false)
    /** True while Sentry is initialized and sending. */
    val active = _active.asStateFlow()

    val isConfigured: Boolean get() = BuildConfig.SENTRY_DSN.isNotBlank()

    fun isOptedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Call first in Application.onCreate. Safe to call again after opting in. */
    fun start(context: Context) {
        if (!isConfigured || !isOptedIn(context) || Sentry.isEnabled()) {
            _active.value = Sentry.isEnabled()
            return
        }
        try {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.environment = BuildConfig.BUILD_TYPE
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.isSendDefaultPii = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.isEnableUserInteractionBreadcrumbs = false
                options.isEnableUserInteractionTracing = false
                options.tracesSampleRate = null
                options.isEnableAutoSessionTracking = true // crash-free session rate
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrubEvent(event) }
                options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> scrubBreadcrumb(crumb) }
            }
            _active.value = Sentry.isEnabled()
        } catch (e: Exception) {
            Log.e(TAG, "Crash reporting could not start: ${e.message}")
            _active.value = false
        }
    }

    fun setOptedIn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop()
    }

    private fun stop() {
        try { Sentry.close() } catch (e: Exception) { Log.w(TAG, "Sentry close failed: ${e.message}") }
        _active.value = false
    }

    /** Problems that don't crash but break EQ. Local logging stays as it is. */
    fun recordHandled(category: Category, error: Throwable? = null, detail: String? = null) {
        if (!_active.value || !reported.add(category)) return
        try {
            val tagScope = io.sentry.ScopeCallback { scope ->
                scope.setTag("handled_category", category.name)
                scope.level = SentryLevel.WARNING
            }
            if (error != null) Sentry.captureException(error, tagScope)
            else Sentry.captureMessage("${category.name}: ${detail ?: "no detail"}", SentryLevel.WARNING, tagScope)
        } catch (e: Exception) {
            Log.w(TAG, "Could not record handled error: ${e.message}")
        }
    }

    /** Debug builds only: verifies the dashboard receives events. Returns false when inactive. */
    fun sendTestEvent(): Boolean {
        if (!_active.value) return false
        Sentry.captureException(IllegalStateException("EQify test error (debug build)"))
        Sentry.flush(5_000)
        return true
    }

    // ── Scrubbing ─────────────────────────────────────────────────────

    /** Mirrored from DataStore by EqifyApplication (it can't be read synchronously). */
    @Volatile private var lastFmApiKey: String = ""

    fun updateApiKey(key: String) { lastFmApiKey = key }

    /** Current values that must never leave the device. Read at send time. */
    private fun secrets(): List<String> = listOf(
        lastFmApiKey,
        NowPlayingState.track.value.takeUnless { it == "Waiting for music…" }.orEmpty(),
        NowPlayingState.artist.value,
        NowPlayingState.observation.value?.title.orEmpty(),
        NowPlayingState.observation.value?.artist.orEmpty(),
        NowPlayingState.selectedHeadphone.value
    )

    private fun scrub(text: String?): String? = CrashScrubber.scrub(text, secrets())

    private fun scrubEvent(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.message?.let { m ->
            m.message = scrub(m.message)
            m.formatted = scrub(m.formatted)
        }
        event.exceptions?.forEach { it.value = scrub(it.value) }
        event.breadcrumbs?.forEach { scrubBreadcrumb(it) }
        event.extras?.keys?.toList()?.forEach { key ->
            (event.getExtra(key) as? String)?.let { event.setExtra(key, scrub(it) ?: "") }
        }
        return event
    }

    private fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
        crumb.message = scrub(crumb.message)
        crumb.data.keys.toList().forEach { key ->
            (crumb.getData(key) as? String)?.let { crumb.setData(key, scrub(it) ?: "") }
        }
        return crumb
    }
}
