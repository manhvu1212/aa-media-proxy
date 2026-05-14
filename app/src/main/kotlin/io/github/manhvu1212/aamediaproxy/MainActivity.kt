package io.github.manhvu1212.aamediaproxy

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusListener: TextView
    private lateinit var statusSession: TextView
    private lateinit var mediaArtwork: ImageView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaSubtitle: TextView
    private lateinit var mediaState: TextView
    private lateinit var mediaPackage: TextView

    // Fired on the main thread by MediaNotificationListener whenever the selected source
    // (or its metadata / playback state) changes. The listener also fires once with the
    // current snapshot when we subscribe in onStart.
    private val infoListener: (MediaNotificationListener.MediaInfo?) -> Unit = { info ->
        renderMediaInfo(info)
    }

    // POST_NOTIFICATIONS is runtime-granted on Android 13+. Without it the system silently
    // suppresses our foreground service notification (importance=NONE), which means AA
    // sees no media controls and the user has no indication the bridge is running.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15+ enforces edge-to-edge — without this the status bar / notch overlaps
        // the first row of content. Pad the scroll view by system bar + display cutout
        // insets on top, and gesture-nav insets on bottom.
        val root = findViewById<android.view.View>(R.id.rootScroll)
        val basePad = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                basePad + bars.left,
                basePad + bars.top,
                basePad + bars.right,
                basePad + bars.bottom
            )
            insets
        }

        statusListener = findViewById(R.id.statusListener)
        statusSession = findViewById(R.id.statusSession)
        mediaArtwork = findViewById(R.id.mediaArtwork)
        mediaTitle = findViewById(R.id.mediaTitle)
        mediaSubtitle = findViewById(R.id.mediaSubtitle)
        mediaState = findViewById(R.id.mediaState)
        mediaPackage = findViewById(R.id.mediaPackage)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshStatus() }

        ensureNotificationPermission()
        refreshStatus()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        // addMediaInfoListener fires once synchronously with the current snapshot so the UI
        // populates immediately without a separate refresh call.
        MediaNotificationListener.addMediaInfoListener(infoListener)
    }

    override fun onStop() {
        super.onStop()
        MediaNotificationListener.removeMediaInfoListener(infoListener)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isListenerEnabled()
        statusListener.text = HtmlCompat.fromHtml(
            if (enabled) getString(R.string.status_listener_ok)
            else "<b>${getString(R.string.status_listener_missing)}</b>",
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        statusSession.text =
            if (enabled) getString(R.string.status_active)
            else getString(R.string.status_no_session)
    }

    private fun renderMediaInfo(info: MediaNotificationListener.MediaInfo?) {
        if (info == null) {
            mediaTitle.text = getString(R.string.no_media_title)
            mediaSubtitle.text = getString(R.string.no_media_subtitle)
            mediaState.text = getString(R.string.state_idle)
            mediaArtwork.setImageResource(android.R.drawable.ic_media_play)
            mediaPackage.text = ""
            return
        }

        mediaTitle.text = info.title ?: getString(R.string.no_media_title)
        mediaSubtitle.text = info.artist.orEmpty()
        mediaState.text = when (info.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> getString(R.string.state_playing)
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> getString(R.string.state_buffering)
            PlaybackState.STATE_PAUSED -> getString(R.string.state_paused)
            else -> getString(R.string.state_idle)
        }
        mediaPackage.text = getString(R.string.source_package, info.packageName)

        val art = info.artwork
        if (art != null) {
            mediaArtwork.setImageBitmap(art)
        } else {
            mediaArtwork.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(this, MediaNotificationListener::class.java)
            .flattenToString()
        return flat.split(":").any { it.equals(expected, ignoreCase = true) }
    }
}
