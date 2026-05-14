package io.github.manhvu1212.aamediaproxy

import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

/**
 * SimpleBasePlayer that forwards every command from Android Auto down to whatever
 * platform MediaController the NotificationListenerService picked as the bridged source,
 * and mirrors that session's state back up so AA shows live metadata.
 */
class MediaProxyPlayer(applicationLooper: Looper) : SimpleBasePlayer(applicationLooper) {

    private var sourceController: MediaController? = null
    private var sourcePackage: String? = null

    // Cache PNG-encoded artwork keyed by the source Bitmap reference. invalidateState()
    // re-runs getState() on every play/pause toggle, so encoding on each call would burn
    // CPU. The platform hands us the same Bitmap instance until metadata actually changes.
    private var lastArtBitmap: Bitmap? = null
    private var lastArtBytes: ByteArray? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
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
        invalidateState()
    }

    override fun getState(): State {
        val controller = sourceController
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_TIMELINE)
            .build()

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
                .setAvailableCommands(commands)
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setPlaylist(emptyList())
                .build()
        }

        val pbState = controller.playbackState
        val sourceMeta = controller.metadata

        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING
        val title = sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: PLACEHOLDER_TITLE
        val artist = sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: sourceMeta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val artBitmap = sourceMeta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: sourceMeta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: sourceMeta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artBytes = encodeArtwork(artBitmap)

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

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(ACTIVE_MEDIA_ID)
                        .setMediaItem(mediaItem)
                        .build()
                )
            )
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val transport = sourceController?.transportControls
        Log.i(TAG, "handleSetPlayWhenReady($playWhenReady) transport=${transport != null}")
        if (transport != null) {
            if (playWhenReady) transport.play() else transport.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        detachController()
        return Futures.immediateVoidFuture()
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
    }
}
