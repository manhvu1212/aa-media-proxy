package io.github.manhvu1212.aamediaproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The Media3 service Android Auto talks to. It hosts a MediaLibrarySession backed by
 * [MediaProxyPlayer], which proxies a live platform MediaController obtained from any
 * AA-blind media app via [MediaNotificationListener].
 *
 * Lifecycle is owned by [MediaNotificationListener]: the service is created on AA
 * connect ([onSourceControllerChanged]) and torn down on AA disconnect ([requestStop]).
 * It is never started for in-app "now playing" display — that path reads source state
 * directly from the listener and doesn't touch this service at all.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: MediaProxyPlayer
    private var librarySession: MediaLibrarySession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService onCreate")
        player = MediaProxyPlayer(mainLooper)
        val session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        librarySession = session
        instance = this

        val refreshCommand = SessionCommand(CUSTOM_ACTION_REFRESH, android.os.Bundle.EMPTY)
        val refreshButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_refresh))
            .setSessionCommand(refreshCommand)
            .setIconResId(android.R.drawable.ic_popup_sync)
            .build()
        session.setCustomLayout(ImmutableList.of(refreshButton))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only on the cold start do we have to publish the bridge notification ourselves
        // to beat the FGS start-deadline; once Media3 has taken over with its MediaStyle
        // notification (same id), re-posting the bridge would clobber the media chip with
        // a plain "Bridging…" entry. The listener can deliver many onStartCommand pulses
        // in a row as the source's metadata changes — each one would clobber.
        if (!foregroundStarted) {
            publishBridgeNotification()
        }
        // Consume any controller queued by the listener while the service was cold.
        pendingController?.let {
            pendingController = null
            player.attachController(it)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return librarySession
    }

    /**
     * Pushes the foreground-service notification. We attach our MediaLibrarySession's
     * platform token via MediaStyle so the system surfaces this entry in the media
     * controls area (Quick Settings / lock screen) instead of the regular notification
     * shade. With MediaStyle + a valid session token, the system reads the current
     * metadata directly from the session — the title/text below are just placeholders
     * for when the proxied source has no metadata yet.
     */
    private fun publishBridgeNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Bridge",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AA Media Proxy")
            .setContentText("Bridging media ↔ Android Auto")
            .setOngoing(true)
        librarySession?.platformToken?.let { token ->
            builder.setStyle(Notification.MediaStyle().setMediaSession(token))
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
        foregroundStarted = true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the session alive when the launcher activity is swiped away — Android Auto
        // and the notification still need it.
    }

    override fun onDestroy() {
        Log.i(TAG, "PlaybackService onDestroy")
        librarySession?.run {
            player.release()
            release()
        }
        librarySession = null
        instance = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.i(
                TAG,
                "onConnect from pkg=${controller.packageName} uid=${controller.uid} " +
                    "controllerVersion=${controller.controllerVersion} isTrusted=${controller.isTrusted}"
            )
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CUSTOM_ACTION_REFRESH, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<SessionResult> {
            Log.i(TAG, "onCustomCommand customAction=${customCommand.customAction} from pkg=${controller.packageName}")
            if (customCommand.customAction == CUSTOM_ACTION_REFRESH) {
                MediaNotificationListener.requestRefresh()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.i(
                TAG,
                "onGetLibraryRoot from pkg=${browser.packageName} " +
                    "isRecent=${params?.isRecent} isSuggested=${params?.isSuggested} " +
                    "isOffline=${params?.isOffline}"
            )
            // We have nothing to surface for "recent/suggested/offline" roots — we don't
            // persist media items, we just proxy whatever the source app is playing live.
            if (params?.isRecent == true || params?.isSuggested == true ||
                params?.isOffline == true
            ) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("AA Media Proxy")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.i(TAG, "onGetChildren parentId=$parentId from pkg=${browser.packageName}")
            // MVP: no browseable catalog — AA only needs the now-playing widget.
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.of(), params)
            )
        }
    }

    companion object {
        private const val TAG = "AaMediaProxy.Service"
        private const val CUSTOM_ACTION_REFRESH = "action.REFRESH"
        private const val ROOT_ID = "root"
        // Match Media3 DefaultMediaNotificationProvider defaults so its notification
        // replaces ours rather than stacking.
        private const val NOTIF_CHANNEL_ID = "default_channel_id"
        private const val NOTIF_ID = 1001

        @Volatile private var instance: PlaybackService? = null
        @Volatile private var pendingController: MediaController? = null

        /**
         * Called by [MediaNotificationListener] while AA is connected. The non-null path
         * (re-)starts the service in foreground; the null path detaches.
         */
        fun onSourceControllerChanged(context: Context, controller: MediaController?) {
            if (controller != null) {
                pendingController = controller
                val intent = Intent(context, PlaybackService::class.java)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } catch (t: Throwable) {
                    // Android 12+ throws ForegroundServiceStartNotAllowedException when the
                    // caller is in background without an exemption — surface it instead of
                    // silently swallowing.
                    Log.e(TAG, "startForegroundService failed", t)
                    pendingController = null
                }
                return
            }
            pendingController = null
            val svc = instance ?: return
            svc.mainHandler.post { svc.player.detachController() }
        }

        /**
         * Called when Android Auto disconnects. Releases the MediaLibrarySession
         * synchronously so the system removes our entry from the media chip area without
         * waiting for the service process to actually die.
         */
        fun requestStop(context: Context) {
            val svc = instance ?: run {
                pendingController = null
                return
            }
            svc.mainHandler.post {
                Log.i(TAG, "requestStop — AA disconnected, tearing down")
                pendingController = null
                svc.librarySession?.run {
                    svc.player.release()
                    release()
                }
                svc.librarySession = null
                instance = null
                if (svc.foregroundStarted) {
                    svc.stopForeground(STOP_FOREGROUND_REMOVE)
                    svc.foregroundStarted = false
                }
                svc.stopSelf()
            }
        }
    }
}
