package com.starmap.app.events.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.starmap.app.MainActivity
import com.starmap.app.events.EventIds
import com.starmap.app.events.SkyEvent
import com.starmap.app.events.SkyEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Carries "the thing you were just told about" from a notification back into the sky view. */
object DeepLink {

    const val ACTION = "com.starmap.app.action.OPEN_EVENT"
    const val EXTRA_EVENT_ID = "starmap.event.id"
    const val EXTRA_TARGET = "starmap.event.target"
    const val EXTRA_TIME_MILLIS = "starmap.event.time"

    fun intent(context: Context, event: SkyEvent): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION
            // The id has to go in the data URI, not just the extras: PendingIntent reuse
            // compares intents with filterEquals, which ignores extras entirely, so two
            // events would otherwise look identical and share one PendingIntent.
            data = Uri.parse("starmap://event/${Uri.encode(event.id)}")
            putExtra(EXTRA_EVENT_ID, event.id)
            putExtra(EXTRA_TARGET, searchTarget(event))
            putExtra(EXTRA_TIME_MILLIS, event.peakMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun pending(context: Context, event: SkyEvent): PendingIntent =
        PendingIntent.getActivity(
            context,
            EventIds.notificationId(event.id),
            intent(context, event),
            // Mandatory from API 31: a PendingIntent with neither immutable nor mutable
            // specified throws at runtime.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** What the sky view should go and look at when the notification is tapped. */
    private fun searchTarget(event: SkyEvent): String = when (event.kind) {
        SkyEventKind.MeteorPeak -> event.subject
        SkyEventKind.LunarEclipse, SkyEventKind.FullMoon, SkyEventKind.NewMoon -> "Moon"
        SkyEventKind.SolarEclipse -> "Sun"
        SkyEventKind.Opposition, SkyEventKind.GreatestElongation -> event.subject
        SkyEventKind.PlanetConjunction, SkyEventKind.MoonConjunction ->
            event.subject.substringBefore('|')
        SkyEventKind.IssPass -> "ISS"
        else -> ""
    }
}

/**
 * Where a tapped notification waits until the UI is ready to act on it.
 *
 * Process-scoped rather than passed down through the composition, because the intent can
 * arrive before there is any composition to hand it to — including on the launch that
 * follows a crash.
 */
object EventDeepLink {
    private val _pending = MutableStateFlow<String?>(null)

    /**
     * Observable rather than a plain field: when the app is already open, a notification
     * tap arrives through onNewIntent with nothing else changing, so the UI has to be
     * watching this to notice.
     */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun consume(intent: Intent?) {
        if (intent?.action != DeepLink.ACTION) return
        val target = intent.getStringExtra(DeepLink.EXTRA_TARGET)
        if (!target.isNullOrBlank()) _pending.value = target
    }

    /** Takes the pending target, leaving nothing behind, so it is acted on exactly once. */
    fun take(): String? = _pending.getAndUpdate { null }
}
