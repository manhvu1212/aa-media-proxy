package io.github.manhvu1212.aamediaproxy

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Watches every active platform MediaSession and:
 *
 *  1. Publishes a snapshot of the currently selected source ([MediaInfo]) to in-process
 *     observers (the launcher [MainActivity] reads this directly — it never binds to
 *     [PlaybackService]). This is always live, with or without Android Auto.
 *  2. When Android Auto is connected, also forwards the underlying [MediaController] to
 *     [PlaybackService] so a Media3 MediaLibrarySession can proxy it to the head unit.
 *     When AA disconnects, [PlaybackService] is torn down entirely so the proxy doesn't
 *     show up in the phone's media chip / notification area at times when there is no
 *     AA on the other end to consume it.
 */
class MediaNotificationListener : NotificationListenerService() {

    private val sessionManager by lazy {
        getSystemService(MediaSessionManager::class.java)
    }

    // Cached set of packages that declare themselves Android Auto compatible — bridging
    // those would just duplicate the now-playing widget. Refresh occasionally to pick up
    // newly installed apps without re-querying on every session change.
    // Volatile because the cache is populated on [pmExecutor] and read on the main thread.
    @Volatile private var aaNativeCache: Set<String> = emptySet()
    @Volatile private var aaNativeCacheAt: Long = 0L

    // PackageManager queries are slow on cold start (~150ms in practice) — they must not
    // run on the main thread or they drop frames during launcher startup.
    private val pmExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AaProxy-PM").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // AA connection state. Read on main only; mirrored to the static [isAaConnected] so
    // PlaybackService can consult it from other threads.
    private var aaConnected = false
    private var carConnectionLive: LiveData<Int>? = null
    private val carConnectionObserver = Observer<Int> { type ->
        // CarConnection emits NOT_CONNECTED (0), NATIVE (1, AAOS), or PROJECTION (2, phone+AA).
        val nowConnected = type == CarConnection.CONNECTION_TYPE_NATIVE ||
            type == CarConnection.CONNECTION_TYPE_PROJECTION
        if (nowConnected == aaConnected) return@Observer
        aaConnected = nowConnected
        isAaConnected = nowConnected
        Log.i(TAG, "AA connection changed: connected=$aaConnected (type=$type)")
        if (aaConnected) {
            // AA just connected → spin PlaybackService up against whatever is already
            // playing, without waiting for the next session-change event.
            forwardCurrentToPlaybackService()
        } else {
            // AA went away → tear PlaybackService (and its MediaSession / notification)
            // down. The in-app MediaInfo snapshot stays live for MainActivity to display.
            PlaybackService.requestStop(this)
        }
    }

    // The currently selected source controller.
    private var selectedController: MediaController? = null

    // Keep track of the active candidate controllers and their callbacks.
    private val monitoredControllers = mutableMapOf<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateBridgedController(controllers ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        instance = this
        val component = ComponentName(this, javaClass)
        // Warm the AA-native package cache off the main thread before we touch the
        // session manager, so the first updateBridgedController() call has hot data.
        pmExecutor.execute {
            refreshAaNativeCache()
            mainHandler.post {
                try {
                    sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
                    // observeForever fires synchronously with the current value, which
                    // initializes [aaConnected] before the first updateBridgedController()
                    // runs, so cold-start state is correct.
                    val live = CarConnection(this).type
                    carConnectionLive = live
                    live.observeForever(carConnectionObserver)
                    updateBridgedController(sessionManager.getActiveSessions(component))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register sessions listener", t)
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Listener disconnected")
        mainHandler.post {
            carConnectionLive?.removeObserver(carConnectionObserver)
            carConnectionLive = null
            aaConnected = false
            isAaConnected = false
            clearMonitoredControllers()
            publishMediaInfo()
        }
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Throwable) {
        }
        instance = null
        PlaybackService.requestStop(this)
    }

    override fun onDestroy() {
        pmExecutor.shutdownNow()
        clearMonitoredControllers()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        val isMedia = sbn.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (isMedia) {
            Log.d(TAG, "Media notification posted/updated by $pkg, refreshing active sessions")
            mainHandler.post {
                refreshFromActiveSessions()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        val isMedia = sbn.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (isMedia) {
            Log.d(TAG, "Media notification removed by $pkg, refreshing active sessions")
            mainHandler.post {
                refreshFromActiveSessions()
            }
        }
    }

    private fun refreshFromActiveSessions() {
        try {
            val component = ComponentName(this, javaClass)
            updateBridgedController(sessionManager.getActiveSessions(component))
        } catch (t: Throwable) {
            Log.e(TAG, "refreshFromActiveSessions failed", t)
        }
    }

    private fun updateBridgedController(controllers: List<MediaController>) {
        val skip = aaNativePackages() + packageName
        val candidates = controllers.filterNot { it.packageName in skip }

        // Update the callbacks on all candidates so we hear about their state/metadata changes.
        updateMonitoredControllers(candidates)

        // getActiveSessions() is documented as priority-ordered, but a paused session can
        // still be listed first. Prefer one that's actively playing/buffering so we don't
        // surface a stale session when a fresh one is playing.
        val selected = candidates.firstOrNull { it.isLive() }
            ?: candidates.firstOrNull()

        if (selected != null) {
            Log.i(TAG, "Selected source: ${selected.packageName} (tag=${selected.tag}, " +
                "aaConnected=$aaConnected)")
        } else {
            Log.i(TAG, "No bridgeable session " +
                "(total=${controllers.size}, skipped=${controllers.size - candidates.size})")
        }

        selectController(selected)

        // Only spin up the proxy session when AA is on the other end. Without AA there's
        // no consumer, and creating a MediaSession just clutters the phone's media UI.
        if (aaConnected) {
            PlaybackService.onSourceControllerChanged(this, selected)
        }
    }

    private fun selectController(newController: MediaController?) {
        val current = selectedController
        if (current?.sessionToken == newController?.sessionToken) {
            // Same underlying session — publish metadata updates.
            publishMediaInfo()
            return
        }
        selectedController = newController
        publishMediaInfo()
    }

    private fun updateMonitoredControllers(candidates: List<MediaController>) {
        val newTokens = candidates.map { it.sessionToken }.toSet()

        // 1. Unregister and remove controllers that are no longer in the candidates list
        val iterator = monitoredControllers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in newTokens) {
                val (controller, callback) = entry.value
                try {
                    controller.unregisterCallback(callback)
                    Log.d(TAG, "Unregistered callback for ${controller.packageName}")
                } catch (_: Throwable) {
                }
                iterator.remove()
            }
        }

        // 2. Register and add new controllers
        for (controller in candidates) {
            val token = controller.sessionToken
            if (token !in monitoredControllers) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        mainHandler.post {
                            Log.d(TAG, "Playback state changed for ${controller.packageName}: ${state?.state}")
                            // Re-evaluate session selection when any candidate's playback state changes.
                            // E.g., if a paused/idle session starts playing, we want to switch to it.
                            refreshFromActiveSessions()
                        }
                    }

                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                        mainHandler.post {
                            // If this is the selected controller, publish updated media info
                            if (selectedController?.sessionToken == token) {
                                Log.d(TAG, "Metadata changed for selected controller ${controller.packageName}")
                                publishMediaInfo()
                            }
                        }
                    }

                    override fun onSessionDestroyed() {
                        mainHandler.post {
                            Log.d(TAG, "Session destroyed for ${controller.packageName}")
                            // Re-evaluate in case another session is still alive
                            refreshFromActiveSessions()
                        }
                    }
                }
                try {
                    controller.registerCallback(callback, mainHandler)
                    monitoredControllers[token] = Pair(controller, callback)
                    Log.d(TAG, "Registered callback for ${controller.packageName}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register callback for ${controller.packageName}", t)
                }
            }
        }
    }

    private fun clearMonitoredControllers() {
        for ((controller, callback) in monitoredControllers.values) {
            try {
                controller.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }
        monitoredControllers.clear()
        selectedController = null
    }

    private fun publishMediaInfo() {
        val c = selectedController
        val info = c?.let { MediaInfo.fromController(it) }
        currentMediaInfo = info
        infoListeners.forEach {
            try {
                it(info)
            } catch (t: Throwable) {
                Log.e(TAG, "MediaInfo listener threw", t)
            }
        }
    }

    private fun forwardCurrentToPlaybackService() {
        // Always re-derive from getActiveSessions() so we don't rely on a possibly-stale
        // [selectedController] reference at the moment AA flips on.
        refreshFromActiveSessions()
    }

    private fun MediaController.isLive(): Boolean {
        val state = playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    private fun aaNativePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        // Stale-while-revalidate: never block the main thread on PackageManager.
        if (now - aaNativeCacheAt >= CACHE_TTL_MS) {
            pmExecutor.execute { refreshAaNativeCache() }
        }
        return aaNativeCache
    }

    @WorkerThread
    private fun refreshAaNativeCache() {
        val now = SystemClock.elapsedRealtime()
        if (now - aaNativeCacheAt < CACHE_TTL_MS) return
        // Just having a MediaBrowserService isn't enough — YouTube, for instance, ships
        // one for system media controls but does NOT support Android Auto. The actual AA
        // opt-in signal is the `com.google.android.gms.car.application` meta-data on the
        // <application>. Require both to declare a package "AA-native" and skip it.
        val resolved = try {
            packageManager.queryIntentServices(
                Intent("android.media.browse.MediaBrowserService"),
                0
            )
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentServices failed", t)
            emptyList()
        }
        val out = mutableSetOf<String>()
        for (ri in resolved) {
            val pkg = ri.serviceInfo?.packageName ?: continue
            val hasAaMeta = try {
                val appInfo = packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                appInfo.metaData?.containsKey(AA_APP_META) == true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (t: Throwable) {
                Log.w(TAG, "getApplicationInfo failed for $pkg", t)
                false
            }
            if (hasAaMeta) out.add(pkg)
        }
        aaNativeCache = out
        aaNativeCacheAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "AA-native packages refreshed: ${out.size} of ${resolved.size} " +
            "MediaBrowserService candidates")
    }

    /**
     * Snapshot of the currently selected source — what MainActivity renders. Deliberately
     * value-type so it can be safely handed across threads without leaking the underlying
     * platform MediaController.
     */
    data class MediaInfo(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val artwork: Bitmap?,
        val state: Int,
    ) {
        companion object {
            fun fromController(c: MediaController): MediaInfo {
                val meta = c.metadata
                return MediaInfo(
                    packageName = c.packageName,
                    title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                        ?: meta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                        ?: meta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    artwork = meta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                        ?: meta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: meta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                    state = c.playbackState?.state ?: PlaybackState.STATE_NONE,
                )
            }
        }
    }

    companion object {
        private const val TAG = "AaMediaProxy.Listener"
        private const val CACHE_TTL_MS = 30_000L
        private const val AA_APP_META = "com.google.android.gms.car.application"

        /**
         * Globally-readable AA connection state. [PlaybackService] reads this to belt-
         * and-suspenders block Media3's automatic foreground promotion outside AA, since
         * `requestStop` only fires on actual disconnects.
         */
        @Volatile var isAaConnected: Boolean = false
            private set

        @Volatile var currentMediaInfo: MediaInfo? = null
            private set

        @Volatile private var instance: MediaNotificationListener? = null
        private val infoListeners = CopyOnWriteArrayList<(MediaInfo?) -> Unit>()

        /**
         * Register a listener that fires on the main thread whenever the selected source
         * (or its metadata / state) changes. Fires immediately with the current snapshot.
         */
        fun addMediaInfoListener(listener: (MediaInfo?) -> Unit) {
            infoListeners += listener
            listener(currentMediaInfo)
        }

        fun removeMediaInfoListener(listener: (MediaInfo?) -> Unit) {
            infoListeners -= listener
        }
    }
}
