package io.github.manhvu1212.aamediaproxy

import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.ByteArrayOutputStream

/**
 * SimpleBasePlayer that forwards every command from Android Auto down to whatever
 * platform MediaController the NotificationListenerService picked as the bridged source,
 * and mirrors that session's state back up so AA shows live metadata.
 */
class MediaProxyPlayer(applicationLooper: Looper) : SimpleBasePlayer(applicationLooper) {

    private val mainHandler = Handler(applicationLooper)

    private var sourceController: MediaController? = null
    private var sourcePackage: String? = null

    // Cache PNG-encoded artwork keyed by the source Bitmap reference. invalidateState()
    // re-runs getState() on every play/pause toggle, so encoding on each call would burn
    // CPU. The platform hands us the same Bitmap instance until metadata actually changes.
    private var lastArtBitmap: Bitmap? = null
    private var lastArtBytes: ByteArray? = null

    // Optimistic override for the source's PlaybackState. transport.play()/pause() is
    // async via Binder IPC — the source may not publish the new state for tens to
    // hundreds of milliseconds. While we wait, getState() reports this pending intent
    // (and handleSetPlayWhenReady holds its returned future open) so SimpleBasePlayer
    // doesn't read the stale source state and revert AA's optimistic UI flip back to
    // the previous play/pause icon.
    private var pendingIntent: Boolean? = null
    private var pendingFuture: SettableFuture<Any?>? = null
    private val pendingTimeoutRunnable = Runnable {
        Log.w(TAG, "Source did not reflect intent=$pendingIntent within ${PENDING_INTENT_TIMEOUT_MS}ms; releasing")
        clearPendingIntent()
        // Source never caught up — let AA see the source's real state now.
        invalidateState()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            maybeCompletePendingIntent(state)
            invalidateState()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            invalidateState()
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "Source session destroyed (pkg=$sourcePackage)")
            detachController()
        }
    }

    @MainThread
    fun attachController(controller: MediaController) {
        // Compare by sessionToken — the platform hands us a fresh MediaController instance
        // every time OnActiveSessionsChangedListener fires, even when it points at the same
        // underlying session. Reference equality would churn attach/detach pointlessly.
        if (sourceController?.sessionToken == controller.sessionToken) return
        detachController()
        Log.i(TAG, "Attaching source controller for ${controller.packageName}")
        sourceController = controller
        sourcePackage = controller.packageName
        controller.registerCallback(controllerCallback)
        invalidateState()
    }

    @MainThread
    fun detachController() {
        val existing = sourceController ?: return
        Log.i(TAG, "Detaching source controller (pkg=$sourcePackage)")
        try {
            existing.unregisterCallback(controllerCallback)
        } catch (_: Throwable) {
        }
        sourceController = null
        sourcePackage = null
        clearPendingIntent()
        invalidateState()
    }

    override fun getState(): State {
        val controller = sourceController
        val commandsBuilder = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_TIMELINE)

        // No source attached: return a truly empty playlist so the underlying platform
        // MediaSession clears its metadata. If we kept a placeholder MediaItem here,
        // Media3 in STATE_IDLE wouldn't push a metadata refresh and the system would
        // keep showing whatever the last attached source published (e.g. the previous
        // YouTube video) in the notification shade.
        if (controller == null) {
            // Drop the artwork cache so we don't hold a strong reference to the bitmap
            // from the now-detached source.
            encodeArtwork(null)
            return State.Builder()
                .setAvailableCommands(commandsBuilder.build())
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setPlaylist(emptyList())
                .build()
        }

        val pbState = controller.playbackState
        val sourceMeta = controller.metadata
        val actions = pbState?.actions ?: 0L
        val sourceState = pbState?.state ?: PlaybackState.STATE_NONE

        // Only advertise transport commands the source actually supports. AA renders
        // the skip / seek buttons as enabled iff we add them here; if we add them
        // unconditionally, the user can press skip on something like a podcast app that
        // doesn't expose it and the press is silently swallowed by the platform side.
        if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
            commandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT)
            commandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
            commandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
            commandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        if (actions and PlaybackState.ACTION_SEEK_TO != 0L) {
            commandsBuilder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }

        // Map platform PlaybackState → Media3 (playWhenReady, playbackState).
        //
        // playWhenReady is *intent to play* — BUFFERING/CONNECTING/FF/REW/SKIPPING all
        // mean the user wants playback, even though no audio is coming out right now.
        // Conflating them with "paused" (the old behavior, which only counted
        // STATE_PLAYING) caused AA to flash the pause button every time the source hit
        // a transitional state — most visibly right after we called transport.play(),
        // before the source had published STATE_PLAYING yet.
        val sourceWantsToPlay = sourceState.isPlayingIntent()
        // While a play/pause command we just issued hasn't been reflected by the source
        // yet, honor the user's intent. Otherwise SimpleBasePlayer's post-handle
        // getState() call reads the stale state and reverts AA.
        val playWhenReady = pendingIntent ?: sourceWantsToPlay

        val mediaPlaybackState = when (sourceState) {
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> Player.STATE_BUFFERING
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> Player.STATE_IDLE
            // STATE_STOPPED is treated as ready+paused so AA keeps the current item on
            // screen rather than wiping back to "no media". The source can publish
            // STATE_NONE explicitly if it wants the UI cleared.
            else -> Player.STATE_READY
        }

        val title = sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: PLACEHOLDER_TITLE
        val artist = sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val artBitmap = sourceMeta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: sourceMeta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: sourceMeta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artBytes = encodeArtwork(artBitmap)

        // Duration: platform METADATA_KEY_DURATION is in ms; Media3 wants μs. A non-
        // positive value means "unknown" (live streams report 0 or -1) — represent
        // that as TIME_UNSET so AA doesn't render a 0:00 timeline.
        val durationMs = sourceMeta?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val durationUs = if (durationMs > 0L) durationMs * 1_000L else C.TIME_UNSET

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        // Stash the source package so the in-app UI can surface "Nguồn: <pkg>". Using
        // station is a benign carrier — AA doesn't render it prominently.
        sourcePackage?.let { metadataBuilder.setStation(it) }
        if (artBytes != null) {
            metadataBuilder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(ACTIVE_MEDIA_ID)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        val mediaItemDataBuilder = MediaItemData.Builder(ACTIVE_MEDIA_ID)
            .setMediaItem(mediaItem)
            .setDurationUs(durationUs)

        // The position extrapolator should only advance when the source is actually
        // emitting audio — BUFFERING/CONNECTING freezes the clock at the source.
        val isAdvancing = sourceState == PlaybackState.STATE_PLAYING ||
            sourceState == PlaybackState.STATE_FAST_FORWARDING ||
            sourceState == PlaybackState.STATE_REWINDING
        val basePositionMs = pbState?.position ?: 0L
        val baseUpdateTime = pbState?.lastPositionUpdateTime ?: 0L
        val playbackSpeed = pbState?.playbackSpeed ?: 1f
        val positionSupplier = PositionSupplier {
            if (isAdvancing && baseUpdateTime > 0L) {
                val elapsed = SystemClock.elapsedRealtime() - baseUpdateTime
                basePositionMs + (elapsed * playbackSpeed).toLong()
            } else {
                basePositionMs
            }
        }

        return State.Builder()
            .setAvailableCommands(commandsBuilder.build())
            .setPlaybackState(mediaPlaybackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(listOf(mediaItemDataBuilder.build()))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionSupplier)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val transport = sourceController?.transportControls
        Log.i(TAG, "handleSetPlayWhenReady($playWhenReady) transport=${transport != null}")
        if (transport == null) {
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady) transport.play() else transport.pause()
        return startPendingIntent(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
    ): ListenableFuture<*> {
        val transport = sourceController?.transportControls
            ?: return Futures.immediateVoidFuture()
        Log.i(TAG, "handleSeek(cmd=$seekCommand, pos=$positionMs)")
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> transport.skipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> transport.skipToPrevious()
            else -> transport.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        detachController()
        return Futures.immediateVoidFuture()
    }

    @MainThread
    private fun startPendingIntent(playWhenReady: Boolean): ListenableFuture<*> {
        // Replace any older pending intent — only the latest one matters. Completing
        // the previous future lets SimpleBasePlayer move past it without waiting.
        pendingFuture?.set(null)
        mainHandler.removeCallbacks(pendingTimeoutRunnable)

        pendingIntent = playWhenReady
        val future = SettableFuture.create<Any?>()
        pendingFuture = future
        // Belt-and-braces timeout: if the source never publishes the matching state
        // (it died silently, or it doesn't update PlaybackState in response to play()),
        // we still have to release the future or AA's controller hangs forever.
        mainHandler.postDelayed(pendingTimeoutRunnable, PENDING_INTENT_TIMEOUT_MS)
        return future
    }

    @MainThread
    private fun maybeCompletePendingIntent(state: PlaybackState?) {
        val pending = pendingIntent ?: return
        val sourceWantsToPlay = state?.state?.isPlayingIntent() == true
        if (sourceWantsToPlay == pending) {
            clearPendingIntent()
        }
    }

    @MainThread
    private fun clearPendingIntent() {
        mainHandler.removeCallbacks(pendingTimeoutRunnable)
        pendingIntent = null
        pendingFuture?.set(null)
        pendingFuture = null
    }

    private fun Int.isPlayingIntent(): Boolean = when (this) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun encodeArtwork(bitmap: Bitmap?): ByteArray? {
        if (bitmap === lastArtBitmap) return lastArtBytes
        lastArtBitmap = bitmap
        lastArtBytes = bitmap?.let {
            val baos = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.PNG, 100, baos)
            baos.toByteArray()
        }
        return lastArtBytes
    }

    companion object {
        private const val TAG = "AaMediaProxy.Player"
        const val ACTIVE_MEDIA_ID = "bridge-active"
        private const val PLACEHOLDER_TITLE = "AA Media Proxy"
        // 2s is comfortably longer than any reasonable Binder + source-app response,
        // but still bounded so AA never sees a frozen transport control button.
        private const val PENDING_INTENT_TIMEOUT_MS = 2_000L
    }
}
