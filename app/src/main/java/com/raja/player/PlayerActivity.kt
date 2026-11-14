package com.raja.player

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity(), Player.Listener {

    private lateinit var settings: Settings
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer
    private lateinit var statusView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var subtitleImage: ImageView
    private lateinit var enhanceButton: Button
    private lateinit var loading: View

    private val ui = Handler(Looper.getMainLooper())
    private val hideStatus = Runnable { statusView.visibility = View.GONE }
    private var player: ExoPlayer? = null
    private var mediaUri: Uri? = null
    private var videoSurface: Surface? = null
    private var videoSize: VideoSize? = null
    private var retries = 0
    private var overloadDisabled = false
    private var droppedTotal = 0L
    private var dropWindowCount = 0
    private var dropWindowStart = 0L
    private val backoffMs = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        glView = findViewById(R.id.videoView)
        statusView = findViewById(R.id.statusView)
        subtitleView = findViewById(R.id.subtitleView)
        subtitleImage = findViewById(R.id.subtitleImage)
        enhanceButton = findViewById(R.id.enhanceButton)
        loading = findViewById(R.id.loading)
        settings = Settings(this)

        // Only http and https links are accepted. Nothing from the link is logged or shown in errors.
        mediaUri = validUri(intent)
        if (mediaUri == null) {
            toast(R.string.invalid_link)
            finish()
            return
        }

        renderer = VideoRenderer(
            onSurface = { s ->
                runOnUiThread {
                    videoSurface = s
                    player?.setVideoSurface(s)
                    glView.requestRender()
                }
            },
            onOverload = { runOnUiThread { onOverload() } },
            onRequestFrame = { glView.requestRender() }
        )
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        enhanceButton.setOnClickListener { toggleEnhance() }
        applySettingsToRenderer()
        applySubtitleStyle()
        createPlayer()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val uri = validUri(newIntent)
        if (uri == null) {
            toast(R.string.invalid_link)
            return
        }
        mediaUri = uri
        retries = 0
        overloadDisabled = false
        ui.removeCallbacksAndMessages(null)
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        showStatus(getString(R.string.loading_new))
    }

    override fun onResume() {
        super.onResume()
        // GLSurfaceView recreates its context and calls onSurface again, which reattaches the video.
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        player?.clearVideoSurface()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        renderer.release()
        super.onDestroy()
    }

    private fun validUri(i: Intent?): Uri? =
        i?.data?.takeIf { it.scheme == "http" || it.scheme == "https" }

    private fun createPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        p.addListener(this)
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                onFramesDropped(droppedFrames)
            }
        })
        p.setVideoSurface(videoSurface)
        p.setMediaItem(MediaItem.fromUri(mediaUri!!))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    // ---- Player events -------------------------------------------------------

    override fun onPlaybackStateChanged(playbackState: Int) {
        loading.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        if (playbackState == Player.STATE_READY) {
            retries = 0
            statusView.visibility = View.GONE
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        // Drop enhancement first: it is the likeliest cause for DRM or pipeline errors.
        if (renderer.enhanced) {
            overloadDisabled = true
            applyEnhance()
            showStatus(getString(R.string.enh_off_stream))
            player?.prepare()
            return
        }
        if (retries < backoffMs.size) {
            val delay = backoffMs[retries++]
            showStatus(getString(R.string.retrying, (delay / 1000).toInt()), delay + 1_000L)
            ui.postDelayed({ player?.prepare() }, delay)
        } else {
            showStatus(getString(R.string.failed_retry), 0L)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        this.videoSize = videoSize
        renderer.videoW = videoSize.width
        renderer.videoH = videoSize.height
        applyEnhance()
    }

    override fun onCues(cueGroup: CueGroup) {
        // Image subtitles (PGS, VobSub, DVB) arrive as bitmaps; text subtitles arrive as text.
        val bitmap = cueGroup.cues.firstOrNull { it.bitmap != null }?.bitmap
        if (bitmap != null) {
            subtitleView.visibility = View.GONE
            subtitleImage.setImageBitmap(bitmap)
            subtitleImage.visibility = View.VISIBLE
            return
        }
        subtitleImage.visibility = View.GONE
        val text = cueGroup.cues.joinToString("\n") { it.text ?: "" }.trim()
        subtitleView.text = text
        subtitleView.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ---- Remote control -------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (p.playbackState == Player.STATE_IDLE && retries >= backoffMs.size) {
                    retries = 0
                    p.prepare()
                } else {
                    p.playWhenReady = !p.playWhenReady
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> p.playWhenReady = !p.playWhenReady
            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-10_000L)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(10_000L)
            KeyEvent.KEYCODE_PROG_RED -> toggleEnhance()
            KeyEvent.KEYCODE_INFO -> showInfo()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_UP -> showOptions()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (p.isCurrentMediaItemLive) {
            showStatus(getString(R.string.no_seek_live))
            return
        }
        val target = p.currentPosition + deltaMs
        val duration = p.duration
        p.seekTo(if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
    }

    // ---- Enhancement ----------------------------------------------------------

    private fun toggleEnhance() {
        settings.enhance = if (renderer.enhanced) EnhanceMode.OFF else EnhanceMode.ON
        overloadDisabled = false
        applyEnhance()
        val state = getString(if (renderer.enhanced) R.string.on_short else R.string.off)
        var message = getString(R.string.enh_btn, state)
        if (renderer.enhanced && !displayIs4k()) message += "\n" + getString(R.string.info_not4k)
        showStatus(message, 5_000L)
    }

    /** Auto turns enhancement on only for a 4K TV output playing a source of 1080p or lower. */
    private fun applyEnhance() {
        val wanted = !overloadDisabled && when (settings.enhance) {
            EnhanceMode.ON -> true
            EnhanceMode.OFF -> false
            EnhanceMode.AUTO -> displayIs4k() && (videoSize?.height ?: 0) in 1..1080
        }
        renderer.enhanced = wanted
        updateEnhanceButton()
        glView.requestRender()
    }

    /**
     * Called when the device starts dropping frames, or when draw time gets too high,
     * while enhancement is on. Enhancement is turned off for the session.
     */
    private fun onOverload() {
        if (!renderer.enhanced) return
        overloadDisabled = true
        applyEnhance()
        showStatus(getString(R.string.enh_off_overload), 5_000L)
    }

    private fun onFramesDropped(count: Int) {
        droppedTotal += count
        val now = SystemClock.elapsedRealtime()
        if (dropWindowStart == 0L) dropWindowStart = now
        dropWindowCount += count
        if (now - dropWindowStart >= 5_000L) {
            if (renderer.enhanced && dropWindowCount >= 30) onOverload()
            dropWindowCount = 0
            dropWindowStart = now
        }
    }

    private fun updateEnhanceButton() {
        val state = getString(if (renderer.enhanced) R.string.on_short else R.string.off)
        enhanceButton.text = getString(R.string.enh_btn, state)
    }

    private fun displayPixels(): Pair<Int, Int> {
        val mode = windowManager.defaultDisplay.mode
        return mode.physicalWidth to mode.physicalHeight
    }

    private fun displayIs4k(): Boolean {
        val (w, h) = displayPixels()
        return maxOf(w, h) >= 3840
    }

    private fun showInfo() {
        val vs = videoSize
        val src = if (vs != null && vs.width > 0) "${vs.width}x${vs.height}" else getString(R.string.unknown)
        val (dw, dh) = displayPixels()
        val lines = mutableListOf(
            getString(R.string.info_source, src),
            getString(R.string.info_output, "${renderer.viewW}x${renderer.viewH}"),
            getString(R.string.info_display, "${dw}x${dh}"),
            getString(R.string.enh_btn, getString(if (renderer.enhanced) R.string.on_short else R.string.off)),
            getString(R.string.info_dropped, droppedTotal.toInt())
        )
        if (!displayIs4k()) lines += getString(R.string.info_not4k)
        showStatus(lines.joinToString("\n"), 10_000L)
    }

    // ---- Options menu ---------------------------------------------------------

    private fun showOptions() {
        val items = arrayOf(
            getString(R.string.opt_mode),
            getString(R.string.opt_adjust),
            getString(R.string.opt_enhance),
            getString(R.string.opt_aspect),
            getString(R.string.opt_quality),
            getString(R.string.opt_audio),
            getString(R.string.opt_subs),
            getString(R.string.opt_subs_size)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickMode()
                    1 -> adjustPicture()
                    2 -> pickEnhance()
                    3 -> pickAspect()
                    4 -> pickTrack(C.TRACK_TYPE_VIDEO, R.string.opt_quality, includeAuto = true)
                    5 -> pickTrack(C.TRACK_TYPE_AUDIO, R.string.opt_audio, includeAuto = false)
                    6 -> pickSubtitles()
                    7 -> pickSubtitleSize()
                }
            }
            .show()
    }

    private fun choose(titleRes: Int, labels: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray<CharSequence>()) { _, which -> onPick(which) }
            .show()
    }

    private fun pickMode() {
        val modes = ViewMode.entries
        choose(R.string.opt_mode, modes.map { getString(it.labelRes) }) { i ->
            settings.applyMode(modes[i])
            applySettingsToRenderer()
        }
    }

    private fun pickEnhance() {
        val modes = EnhanceMode.entries
        choose(R.string.opt_enhance, modes.map { getString(it.labelRes) }) { i ->
            settings.enhance = modes[i]
            overloadDisabled = false
            applyEnhance()
        }
    }

    private fun pickAspect() {
        val modes = Aspect.entries
        choose(R.string.opt_aspect, modes.map { getString(it.labelRes) }) { i ->
            settings.aspect = modes[i]
            applySettingsToRenderer()
        }
    }

    private fun pickSubtitleSize() {
        val labels = listOf(R.string.size_small, R.string.size_medium, R.string.size_large).map { getString(it) }
        choose(R.string.opt_subs_size, labels) { i ->
            settings.subsSize = i
            applySubtitleStyle()
        }
    }

    private fun pickSubtitles() {
        val options = trackOptions(C.TRACK_TYPE_TEXT, includeAuto = false, includeOff = true)
        if (options.size <= 1) {
            showStatus(getString(R.string.no_extra_tracks))
            return
        }
        choose(R.string.opt_subs, options.map { it.first }) { options[it].second() }
    }

    private fun pickTrack(type: Int, titleRes: Int, includeAuto: Boolean) {
        val options = trackOptions(type, includeAuto, includeOff = false)
        if (options.isEmpty()) {
            showStatus(getString(R.string.no_extra_tracks))
            return
        }
        choose(titleRes, options.map { it.first }) { options[it].second() }
    }

    /** (label, action) pairs for one track type, built from the tracks the stream currently offers. */
    private fun trackOptions(type: Int, includeAuto: Boolean, includeOff: Boolean): List<Pair<String, () -> Unit>> {
        val p = player ?: return emptyList()
        val list = mutableListOf<Pair<String, () -> Unit>>()
        if (includeAuto) {
            list += getString(R.string.auto) to {
                updateParams { it.clearOverridesOfType(type).setTrackTypeDisabled(type, false) }
            }
        }
        if (includeOff) {
            list += getString(R.string.off) to { updateParams { it.setTrackTypeDisabled(type, true) } }
        }
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val label = trackLabel(type, group.getTrackFormat(i), list.size + 1)
                list += label to {
                    updateParams {
                        it.setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    }
                }
            }
        }
        return list
    }

    private fun trackLabel(type: Int, format: Format, index: Int): String {
        if (type == C.TRACK_TYPE_VIDEO && format.height > 0) return "${format.height}p"
        val parts = listOfNotNull(format.label, format.language?.takeIf { it != "und" })
        return if (parts.isEmpty()) getString(R.string.track_n, index) else parts.joinToString(" - ")
    }

    private fun updateParams(edit: (TrackSelectionParameters.Builder) -> TrackSelectionParameters.Builder) {
        val p = player ?: return
        p.trackSelectionParameters = edit(p.trackSelectionParameters.buildUpon()).build()
    }

    private fun adjustPicture() {
        val view = layoutInflater.inflate(R.layout.dialog_adjust, null)
        bindSlider(view, R.id.seekBrightness, R.id.valBrightness, -0.2f, 0.2f, settings.brightness) { settings.brightness = it }
        bindSlider(view, R.id.seekContrast, R.id.valContrast, 0.5f, 1.5f, settings.contrast) { settings.contrast = it }
        bindSlider(view, R.id.seekSaturation, R.id.valSaturation, 0f, 2f, settings.saturation) { settings.saturation = it }
        bindSlider(view, R.id.seekSharpness, R.id.valSharpness, 0f, 1f, settings.sharpness) { settings.sharpness = it }
        AlertDialog.Builder(this)
            .setTitle(R.string.opt_adjust)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun bindSlider(
        root: View,
        seekId: Int,
        valueId: Int,
        min: Float,
        max: Float,
        current: Float,
        save: (Float) -> Unit
    ) {
        val seek = root.findViewById<SeekBar>(seekId)
        val label = root.findViewById<TextView>(valueId)
        seek.max = 100
        seek.progress = ((current - min) / (max - min) * 100f).roundToInt().coerceIn(0, 100)
        label.text = String.format(Locale.US, "%.2f", current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + (max - min) * progress / 100f
                save(v)
                label.text = String.format(Locale.US, "%.2f", v)
                applySettingsToRenderer()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ---- Applying settings ----------------------------------------------------

    private fun applySettingsToRenderer() {
        renderer.brightness = settings.brightness
        renderer.contrast = settings.contrast
        renderer.saturation = settings.saturation
        renderer.sharpness = settings.sharpness
        renderer.aspect = settings.aspect
        applyEnhance()
    }

    private fun applySubtitleStyle() {
        val sp = listOf(16f, 22f, 28f)[settings.subsSize.coerceIn(0, 2)]
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        subtitleView.setTextColor(Color.WHITE)
    }

    /** durationMs = 0 keeps the message until the next status update. */
    private fun showStatus(message: String, durationMs: Long = 3_000L) {
        statusView.text = message
        statusView.visibility = View.VISIBLE
        ui.removeCallbacks(hideStatus)
        if (durationMs > 0) ui.postDelayed(hideStatus, durationMs)
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }
}
