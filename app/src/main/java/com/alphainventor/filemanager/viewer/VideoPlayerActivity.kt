package com.alphainventor.filemanager.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityVideoPlayerBinding
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.theme.ThemeManager
import com.alphainventor.filemanager.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 MPV Sınıfı Yüksek Performanslı Video Oynatıcı:
 * 1) MPV tarzı anlık önbellekleme (bufferForPlaybackMs = 250ms, 10 sn geri tamponu) ve Matroska (.mkv)
 *    Cues anahtar kare tablosunu bozmayan temiz DefaultExtractorsFactory yapılandırması.
 * 2) AV1 (10-bit / AV1Core) ve DDP5.1 (E-AC3) .mkv dosyalarında:
 *    - Önce donanım çözücüsü (EXTENSION_RENDERER_MODE_ON + tüm sistem MediaCodec listesi yedeklemesi) kullanılır.
 *    - onPlayerError içinde playCurrentVideoItem'ın errorRecoveryCount değerini sıfırlaması nedeniyle oluşan
 *      sonsuz 00:00 döngüsü tamamen giderildi; hata durumunda konum (resumePos) korunarak kurtarma yapılır
 *      ve gerekirse yerleşik donanım VideoView yüzeyine kesintisiz geçilir!
 * 3) Etkileşimli Ses İzi (🎵) ve Altyazı (💬) Seçici:
 *    - Film içindeki tüm gömülü ses izlerini (dil, kanal, kodek) ve altyazıları listeler, tek dokunuşla
 *      TrackSelectionOverride üzerinden anında değiştirir!
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var videoUrlOrPath: String = ""
    private var videoTitle: String = ""
    private var authToken: String = ""
    private var playlistUrls: ArrayList<String> = ArrayList()
    private var playlistTitles: ArrayList<String> = ArrayList()
    private var playlistIndex: Int = 0

    private var isControlsLocked: Boolean = false
    private var isRepeatOne: Boolean = false
    private var isShuffleEnabled: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var preferSoftwareDecoder: Boolean = false
    private var usingPlatformFallback: Boolean = false
    private var isDraggingSeekBar: Boolean = false
    private var isHorizontalScrubbing: Boolean = false

    private var knownDurationMs: Long = 0L
    private var pendingScrubTargetMs: Long = -1L
    private var deferredSeekOnReadyMs: Long = -1L
    private var lastLiveSeekRealtimeMs: Long = 0L
    private var errorRecoveryCount: Int = 0

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!isDraggingSeekBar && !isHorizontalScrubbing) {
                if (usingPlatformFallback) {
                    val vv = binding.platformVideoView
                    val pos = vv.currentPosition.toLong().coerceAtLeast(0L)
                    val dur = vv.duration.toLong().takeIf { it > 0L } ?: knownDurationMs
                    binding.tvCurrentTime.text = FormatUtils.formatDuration(pos)
                    if (dur > 0L) {
                        binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                        binding.sbVideoProgress.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    }
                } else {
                    val player = exoPlayer
                    if (player != null) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val dur = resolveEffectiveDuration(player)
                        binding.tvCurrentTime.text = FormatUtils.formatDuration(pos)
                        if (dur > 0L) {
                            binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                            binding.sbVideoProgress.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                        }
                    }
                }
            }
            uiHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        val incomingUri = intent.data
        videoUrlOrPath = incomingUri?.toString() ?: (intent.getStringExtra("VIDEO_URL") ?: "")
        videoTitle = intent.getStringExtra("VIDEO_TITLE")
            ?: resolveDisplayNameFromUri(incomingUri)
            ?: Uri.decode(videoUrlOrPath.substringAfterLast('/').substringBefore('?'))
        authToken = intent.getStringExtra("AUTH_TOKEN") ?: ""

        playlistUrls = intent.getStringArrayListExtra("PLAYLIST_URLS") ?: arrayListOf(videoUrlOrPath)
        playlistTitles = intent.getStringArrayListExtra("PLAYLIST_TITLES") ?: arrayListOf(videoTitle)
        playlistIndex = intent.getIntExtra("PLAYLIST_INDEX", 0).coerceIn(0, (playlistUrls.size - 1).coerceAtLeast(0))

        binding.tvVideoTitle.text = videoTitle
        binding.tvCodecBadge.text = "HW+FFmpeg"

        setupOverlayControls()
        setupTouchAndScrubGestures()
        initializePlayer(startPositionMs = 0L, isErrorRecovery = false)
    }

    private fun resolveEffectiveDuration(player: ExoPlayer?): Long {
        if (usingPlatformFallback) {
            val vDur = binding.platformVideoView.duration.toLong()
            if (vDur > 0L) {
                knownDurationMs = vDur
                return vDur
            }
            return knownDurationMs.coerceAtLeast(0L)
        }
        val pDur = player?.duration ?: C.TIME_UNSET
        if (pDur > 0L && pDur != C.TIME_UNSET) {
            knownDurationMs = pDur
            return pDur
        }
        return knownDurationMs.coerceAtLeast(0L)
    }

    private fun resolveDisplayNameFromUri(uri: Uri?): String? {
        if (uri == null) return null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun preloadLocalVideoDuration(pathOrUri: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dur = try {
                val retriever = MediaMetadataRetriever()
                try {
                    when {
                        pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://") -> 0L
                        pathOrUri.startsWith("content://") -> {
                            retriever.setDataSource(this@VideoPlayerActivity, Uri.parse(pathOrUri))
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                        pathOrUri.startsWith("file://") -> {
                            val f = File(Uri.parse(pathOrUri).path ?: "")
                            if (f.exists()) {
                                retriever.setDataSource(f.absolutePath)
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            } else 0L
                        }
                        else -> {
                            val f = File(pathOrUri)
                            if (f.exists()) {
                                retriever.setDataSource(f.absolutePath)
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            } else 0L
                        }
                    }
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
                0L
            }

            if (dur > 0L) {
                withContext(Dispatchers.Main) {
                    if (knownDurationMs <= 0L) {
                        knownDurationMs = dur
                        binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                    }
                }
            }
        }
    }

    private fun resolveMediaUri(): Uri {
        return when {
            videoUrlOrPath.startsWith("http://") ||
            videoUrlOrPath.startsWith("https://") ||
            videoUrlOrPath.startsWith("content://") ||
            videoUrlOrPath.startsWith("file://") -> Uri.parse(videoUrlOrPath)
            else -> Uri.fromFile(File(videoUrlOrPath))
        }
    }

    private fun initializePlayer(startPositionMs: Long = 0L, isErrorRecovery: Boolean = false) {
        usingPlatformFallback = false
        binding.platformVideoView.stopPlayback()
        binding.platformVideoView.visibility = View.GONE
        binding.exoPlayerView.visibility = View.VISIBLE

        exoPlayer?.release()

        // Tüm donanım + yazılım (dav1d / c2.android.av1) çözücülerini sırayla deneyen esnek MediaCodecSelector
        val permissiveSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val defaultList = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            if (defaultList.isNotEmpty() || requiresSecure) {
                defaultList
            } else {
                MediaCodecUtil.getDecoderInfos(mimeType, false, false)
            }
        }

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setMediaCodecSelector(permissiveSelector)
            setExtensionRendererMode(
                if (preferSoftwareDecoder) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            setEnableDecoderFallback(true)
        }

        // Matroska (.mkv) Cues indeksini bozmayan temiz DefaultExtractorsFactory
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val okHttpFactory = OkHttpDataSource.Factory(CloudManager.httpClient).apply {
            if (authToken.isNotBlank() && !authToken.startsWith("DEMO_")) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // MPV sınıfı hızlı başlangıç ve sarma tamponu (250ms ilk kare, 10sn geri tampon)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                250,
                800
            )
            .setBackBuffer(10_000, true)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().also { player ->
                binding.exoPlayerView.player = player
                if (startPositionMs > 0L) {
                    deferredSeekOnReadyMs = startPositionMs
                }
                loadMediaIntoExoPlayer(player, resetRecoveryState = !isErrorRecovery, initialPosMs = startPositionMs)

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        binding.pbVideoBuffering.visibility =
                            if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (playbackState == Player.STATE_READY) {
                            val dur = resolveEffectiveDuration(player)
                            if (dur > 0L) {
                                binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                            }
                            if (deferredSeekOnReadyMs > 0L) {
                                val target = deferredSeekOnReadyMs
                                deferredSeekOnReadyMs = -1L
                                player.seekTo(target)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_vp_pause else R.drawable.ic_vp_play
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val resumePos = (
                            pendingScrubTargetMs.takeIf { it >= 0L }
                                ?: deferredSeekOnReadyMs.takeIf { it >= 0L }
                                ?: player.currentPosition
                            ).coerceAtLeast(0L)

                        when (errorRecoveryCount) {
                            0 -> {
                                errorRecoveryCount = 1
                                preferSoftwareDecoder = !preferSoftwareDecoder
                                binding.tvCodecBadge.text = if (preferSoftwareDecoder) "SW-FFmpeg" else "HW+FFmpeg"
                                initializePlayer(startPositionMs = resumePos, isErrorRecovery = true)
                            }
                            1 -> {
                                errorRecoveryCount = 2
                                // İkincil metin/altyazı izi hatasıysa devre dışı bırakıp aynı konumdan devam et
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                player.prepare()
                                if (resumePos > 0L) {
                                    player.seekTo(resumePos)
                                }
                                player.playWhenReady = true
                            }
                            else -> {
                                // Yerel dosyada özel AV1/DDP5.1 donanım uyuşmazlığı varsa doğrudan yerleşik Platform VideoView'a geç
                                if (!videoUrlOrPath.startsWith("http")) {
                                    switchToPlatformVideoViewFallback(resumePos)
                                } else {
                                    Toast.makeText(
                                        this@VideoPlayerActivity,
                                        "Video akış hatası: ${error.errorCodeName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                })
            }

        uiHandler.removeCallbacks(progressUpdater)
        uiHandler.post(progressUpdater)
    }

    private fun switchToPlatformVideoViewFallback(startPosMs: Long) {
        try {
            exoPlayer?.release()
            exoPlayer = null
            usingPlatformFallback = true
            binding.tvCodecBadge.text = "HW-Sistem"
            binding.pbVideoBuffering.visibility = View.GONE
            binding.exoPlayerView.visibility = View.GONE
            binding.platformVideoView.visibility = View.VISIBLE

            val uri = resolveMediaUri()
            binding.platformVideoView.setVideoURI(uri)
            binding.platformVideoView.setOnPreparedListener { mp ->
                val dur = mp.duration.toLong()
                if (dur > 0L) {
                    knownDurationMs = dur
                    binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                }
                if (startPosMs > 0L) {
                    binding.platformVideoView.seekTo(startPosMs.toInt())
                }
                binding.platformVideoView.start()
                binding.btnPlayPause.setImageResource(R.drawable.ic_vp_pause)
            }
            binding.platformVideoView.setOnErrorListener { _, _, _ ->
                false
            }
        } catch (_: Throwable) {
        }
    }

    private fun loadMediaIntoExoPlayer(player: ExoPlayer, resetRecoveryState: Boolean, initialPosMs: Long = 0L) {
        if (resetRecoveryState) {
            knownDurationMs = 0L
            pendingScrubTargetMs = -1L
            errorRecoveryCount = 0
        }
        val uri = resolveMediaUri()
        binding.tvVideoTitle.text = videoTitle
        preloadLocalVideoDuration(videoUrlOrPath)
        if (initialPosMs > 0L) {
            player.setMediaItem(MediaItem.fromUri(uri), initialPosMs)
        } else {
            player.setMediaItem(MediaItem.fromUri(uri))
        }
        player.playWhenReady = true
        player.prepare()
    }

    private fun executeSmartSeek(targetMs: Long, forceImmediate: Boolean) {
        val dur = resolveEffectiveDuration(exoPlayer)
        val clampedTarget = if (dur > 0L) targetMs.coerceIn(0L, dur) else targetMs.coerceAtLeast(0L)
        pendingScrubTargetMs = clampedTarget

        if (usingPlatformFallback) {
            if (forceImmediate) {
                binding.platformVideoView.seekTo(clampedTarget.toInt())
            }
            return
        }

        val player = exoPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        if (player.playbackState == Player.STATE_READY && player.duration > 0L) {
            if (forceImmediate || (now - lastLiveSeekRealtimeMs >= 320L)) {
                lastLiveSeekRealtimeMs = now
                player.seekTo(clampedTarget)
            }
        } else {
            deferredSeekOnReadyMs = clampedTarget
            if (forceImmediate && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        }
    }

    private fun setupOverlayControls() {
        binding.btnVideoBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.tvCodecBadge.setOnClickListener {
            toggleHardwareSoftwareDecoder()
        }

        binding.btnQuickAudioTrack.setOnClickListener {
            showInteractiveTrackSelectorDialog(initialFocusAudio = true)
        }

        binding.btnQuickSubtitleTrack.setOnClickListener {
            showInteractiveTrackSelectorDialog(initialFocusAudio = false)
        }

        // |◀ Önceki Video veya 10 sn Geri
        binding.btnRewind10.setOnClickListener {
            val curPos = if (usingPlatformFallback) binding.platformVideoView.currentPosition.toLong() else (exoPlayer?.currentPosition ?: 0L)
            if (binding.llExtraVideoControls.visibility == View.VISIBLE) {
                seekByDelta(-10_000L)
            } else if (curPos > 4_000L || playlistUrls.size <= 1) {
                seekByDelta(-10_000L)
            } else {
                playlistIndex = if (playlistIndex > 0) playlistIndex - 1 else playlistUrls.lastIndex
                videoUrlOrPath = playlistUrls[playlistIndex]
                videoTitle = playlistTitles.getOrElse(playlistIndex) { videoUrlOrPath.substringAfterLast('/') }
                initializePlayer(startPositionMs = 0L, isErrorRecovery = false)
            }
        }

        // ▶| Sonraki Video veya 10 sn İleri
        binding.btnForward10.setOnClickListener {
            if (binding.llExtraVideoControls.visibility == View.VISIBLE || playlistUrls.size <= 1) {
                seekByDelta(10_000L)
            } else {
                playlistIndex = (playlistIndex + 1) % playlistUrls.size
                videoUrlOrPath = playlistUrls[playlistIndex]
                videoTitle = playlistTitles.getOrElse(playlistIndex) { videoUrlOrPath.substringAfterLast('/') }
                initializePlayer(startPositionMs = 0L, isErrorRecovery = false)
            }
        }

        binding.btnSubtitlesAndAudio.setOnClickListener {
            val isVisible = binding.llExtraVideoControls.visibility == View.VISIBLE
            binding.llExtraVideoControls.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.btnRewind10.setImageResource(R.drawable.ic_vp_rewind_10)
                binding.btnForward10.setImageResource(R.drawable.ic_vp_forward_10)
                binding.btnSubtitlesAndAudio.setImageResource(R.drawable.ic_vp_expand_down)
            } else {
                binding.btnRewind10.setImageResource(R.drawable.ic_vp_prev)
                binding.btnForward10.setImageResource(R.drawable.ic_vp_next)
                binding.btnSubtitlesAndAudio.setImageResource(R.drawable.ic_vp_rotate_ccw)
            }
        }

        binding.btnMuteToggle.setOnClickListener {
            isShuffleEnabled = !isShuffleEnabled
            exoPlayer?.shuffleModeEnabled = isShuffleEnabled
            binding.btnMuteToggle.alpha = if (isShuffleEnabled) 1.0f else 0.65f
        }

        binding.btnRepeatToggle.setOnClickListener {
            isRepeatOne = !isRepeatOne
            exoPlayer?.repeatMode = if (isRepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            binding.btnRepeatToggle.setImageResource(
                if (isRepeatOne) R.drawable.ic_vp_repeat_one else R.drawable.ic_vp_repeat_off
            )
            binding.btnRepeatToggle.alpha = if (isRepeatOne) 1.0f else 0.65f
        }

        binding.btnPlaybackSpeed.setOnClickListener {
            val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            val nextIdx = (speeds.indexOfFirst { it == currentSpeed } + 1) % speeds.size
            currentSpeed = speeds[nextIdx]
            exoPlayer?.setPlaybackSpeed(currentSpeed)
            val label = if (currentSpeed == 1.0f) "1X" else "${currentSpeed}X"
            binding.btnPlaybackSpeed.text = label
        }

        binding.btnTrackSelector.setOnClickListener {
            showInteractiveTrackSelectorDialog(initialFocusAudio = false)
        }

        binding.btnAspectRatio.setOnClickListener {
            val nextMode = when (binding.exoPlayerView.resizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                    binding.btnAspectRatio.setImageResource(R.drawable.ic_vp_resize_crop)
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                    binding.btnAspectRatio.setImageResource(R.drawable.ic_vp_resize_stretch)
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                else -> {
                    binding.btnAspectRatio.setImageResource(R.drawable.ic_vp_resize_fit)
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
            binding.exoPlayerView.resizeMode = nextMode
        }

        binding.btnRotateScreen.setOnClickListener {
            requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        binding.btnLockControls.setOnClickListener {
            isControlsLocked = true
            binding.playerControlsOverlay.visibility = View.GONE
            binding.btnUnlockOverlay.visibility = View.VISIBLE
        }

        binding.btnUnlockOverlay.setOnClickListener {
            isControlsLocked = false
            binding.btnUnlockOverlay.visibility = View.GONE
            binding.playerControlsOverlay.visibility = View.VISIBLE
        }

        binding.sbVideoProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dur = resolveEffectiveDuration(exoPlayer)
                if (fromUser && dur > 0L) {
                    val target = (dur * progress) / 1000L
                    pendingScrubTargetMs = target
                    val formatted = FormatUtils.formatDuration(target)
                    binding.tvCurrentTime.text = formatted
                    showCenterOverlayLabel(formatted)
                    executeSmartSeek(target, forceImmediate = false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingSeekBar = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDraggingSeekBar = false
                if (pendingScrubTargetMs >= 0L) {
                    executeSmartSeek(pendingScrubTargetMs, forceImmediate = true)
                }
                hideCenterOverlayLabelDelayed(500L)
            }
        })

        binding.btnVideoMore.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Ses İzi ve Altyazı Seçimi")
            popup.menu.add(0, 2, 1, "Çözücü Değiştir (Donanım HW / Yazılım FFmpeg)")
            popup.menu.add(0, 3, 2, "Şununla aç (Harici Video Oynatıcı)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showInteractiveTrackSelectorDialog(initialFocusAudio = false)
                    2 -> toggleHardwareSoftwareDecoder()
                    3 -> {
                        val fi = FileItem(
                            id = videoUrlOrPath,
                            name = videoTitle,
                            path = videoUrlOrPath,
                            isDirectory = false,
                            size = 0L,
                            lastModified = System.currentTimeMillis(),
                            mimeType = "video/*",
                            sourceType = if (videoUrlOrPath.startsWith("http")) FileSourceType.CLOUD else FileSourceType.LOCAL,
                            downloadUrl = videoUrlOrPath
                        )
                        LocalFileRepository.openWithExternalChooser(this, fi, authToken)
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun toggleHardwareSoftwareDecoder() {
        val pos = if (usingPlatformFallback) {
            binding.platformVideoView.currentPosition.toLong()
        } else {
            exoPlayer?.currentPosition ?: 0L
        }
        preferSoftwareDecoder = !preferSoftwareDecoder
        binding.tvCodecBadge.text = if (preferSoftwareDecoder) "SW-FFmpeg" else "HW+FFmpeg"
        initializePlayer(startPositionMs = pos.coerceAtLeast(0L), isErrorRecovery = false)
    }

    private fun togglePlayPause() {
        if (usingPlatformFallback) {
            val vv = binding.platformVideoView
            if (vv.isPlaying) {
                vv.pause()
                binding.btnPlayPause.setImageResource(R.drawable.ic_vp_play)
            } else {
                vv.start()
                binding.btnPlayPause.setImageResource(R.drawable.ic_vp_pause)
            }
            return
        }
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            }
            p.play()
        }
    }

    /**
     * Video 01:08 - 01:14'te Kullanıcının İstediği Etkileşimli Ses İzi ve Altyazı Seçim Diyaloğu:
     * - Film içindeki tüm gömülü Ses İzlerini (C.TRACK_TYPE_AUDIO: Dil, Etiket, Kanal, Kodek) radyo butonlarıyla listeler ve seçtirir.
     * - Film içindeki tüm gömülü Altyazıları (C.TRACK_TYPE_TEXT: "Altyazı Kapalı" + Tüm diller/formatlar) radyo butonlarıyla listeler ve seçtirir.
     */
    private fun showInteractiveTrackSelectorDialog(initialFocusAudio: Boolean) {
        val p = exoPlayer
        if (p == null) {
            Toast.makeText(this, "Sistem donanım oynatıcısında varsayılan ses izi aktif.", Toast.LENGTH_SHORT).show()
            return
        }

        val density = resources.displayMetrics.density
        val cardColor = ThemeManager.getCardColor(this)
        val primaryColor = ThemeManager.getPrimaryTextColor(this)
        val secondaryColor = ThemeManager.getSecondaryTextColor(this)
        val tealColor = Color.parseColor("#008578")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(cardColor)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (20 * density).toInt()
            val padV = (16 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(cardColor)
        }
        scroll.addView(root)

        val tracks: Tracks = p.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        // 1. BÖLÜM: SES İZİ SEÇİMİ
        val tvAudioHeader = TextView(this).apply {
            text = "🎵 Ses İzi Seçimi (${audioGroups.sumOf { it.length }})"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(tealColor)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        root.addView(tvAudioHeader)

        val rgAudio = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        data class TrackOption(
            val radioId: Int,
            val groupIndex: Int,
            val trackIndex: Int,
            val group: Tracks.Group?,
            val label: String,
            val isSelected: Boolean
        )

        val audioOptions = ArrayList<TrackOption>()
        var nextRadioId = 5000

        audioGroups.forEachIndexed { gIdx, group ->
            for (tIdx in 0 until group.length) {
                val fmt = group.getTrackFormat(tIdx)
                val lang = fmt.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() } ?: "Ses ${audioOptions.size + 1}"
                val title = fmt.label?.takeIf { it.isNotBlank() } ?: ""
                val mime = (fmt.sampleMimeType ?: "").substringAfter('/')
                val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                val fullLabel = listOf(lang, title, mime.uppercase(Locale.US), ch)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                audioOptions.add(
                    TrackOption(
                        radioId = nextRadioId++,
                        groupIndex = gIdx,
                        trackIndex = tIdx,
                        group = group,
                        label = fullLabel,
                        isSelected = group.isTrackSelected(tIdx)
                    )
                )
            }
        }

        if (audioOptions.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Gömülü alternatif ses izi bulunamadı."
                textSize = 13.5f
                setTextColor(secondaryColor)
                setPadding(0, 0, 0, (10 * density).toInt())
            })
        } else {
            for (opt in audioOptions) {
                val rb = RadioButton(this).apply {
                    id = opt.radioId
                    text = opt.label
                    textSize = 14f
                    setTextColor(primaryColor)
                    isChecked = opt.isSelected
                    setPadding((8 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
                }
                rgAudio.addView(rb)
            }
            root.addView(rgAudio)
        }

        // 2. BÖLÜM: ALTYAZI SEÇİMİ
        val tvSubHeader = TextView(this).apply {
            text = "💬 Altyazı Seçimi (${textGroups.sumOf { it.length }})"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(tealColor)
            setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
        }
        root.addView(tvSubHeader)

        val rgSub = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val subOptions = ArrayList<TrackOption>()
        val isTextDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val anySubSelected = !isTextDisabled && textGroups.any { it.isSelected }

        val offOptionId = nextRadioId++
        subOptions.add(
            TrackOption(
                radioId = offOptionId,
                groupIndex = -1,
                trackIndex = -1,
                group = null,
                label = "Altyazı Kapalı (Yok)",
                isSelected = !anySubSelected
            )
        )

        textGroups.forEachIndexed { gIdx, group ->
            for (tIdx in 0 until group.length) {
                val fmt = group.getTrackFormat(tIdx)
                val lang = fmt.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() } ?: "Altyazı ${subOptions.size}"
                val title = fmt.label?.takeIf { it.isNotBlank() } ?: ""
                val mime = (fmt.sampleMimeType ?: "").substringAfter('/')
                val fullLabel = listOf(lang, title, mime.uppercase(Locale.US))
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                subOptions.add(
                    TrackOption(
                        radioId = nextRadioId++,
                        groupIndex = gIdx,
                        trackIndex = tIdx,
                        group = group,
                        label = fullLabel,
                        isSelected = !isTextDisabled && group.isTrackSelected(tIdx)
                    )
                )
            }
        }

        for (opt in subOptions) {
            val rb = RadioButton(this).apply {
                id = opt.radioId
                text = opt.label
                textSize = 14f
                setTextColor(primaryColor)
                isChecked = opt.isSelected
                setPadding((8 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
            }
            rgSub.addView(rb)
        }
        root.addView(rgSub)

        // 3. BÖLÜM: KODEK & ÇÖZÜCÜ BİLGİSİ
        val vf = p.videoFormat
        val af = p.audioFormat
        val infoText = buildString {
            append("\nAktif Çözücü: ${binding.tvCodecBadge.text}\n")
            if (vf != null) {
                append("Video: ${vf.sampleMimeType ?: "-"} (${vf.width}x${vf.height})\n")
            }
            if (af != null) {
                append("Ses: ${af.sampleMimeType ?: "-"} (${af.channelCount} kanal, ${af.sampleRate} Hz)")
            }
        }
        root.addView(TextView(this).apply {
            text = infoText
            textSize = 12.5f
            setTextColor(secondaryColor)
        })

        var dialogRef: AlertDialog? = null

        rgAudio.setOnCheckedChangeListener { _, checkedId ->
            val chosen = audioOptions.firstOrNull { it.radioId == checkedId } ?: return@setOnCheckedChangeListener
            val grp = chosen.group ?: return@setOnCheckedChangeListener
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, listOf(chosen.trackIndex)))
                .build()
            Toast.makeText(this, "Ses izi: ${chosen.label}", Toast.LENGTH_SHORT).show()
            dialogRef?.dismiss()
        }

        rgSub.setOnCheckedChangeListener { _, checkedId ->
            val chosen = subOptions.firstOrNull { it.radioId == checkedId } ?: return@setOnCheckedChangeListener
            if (chosen.group == null) {
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                Toast.makeText(this, "Altyazı kapatıldı", Toast.LENGTH_SHORT).show()
            } else {
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(chosen.group.mediaTrackGroup, listOf(chosen.trackIndex)))
                    .build()
                Toast.makeText(this, "Altyazı: ${chosen.label}", Toast.LENGTH_SHORT).show()
            }
            dialogRef?.dismiss()
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle(if (initialFocusAudio) "Ses İzi ve Altyazı Seçimi" else "Altyazı ve Ses İzi Seçimi")
            .setView(scroll)
            .setNeutralButton("ÇÖZÜCÜ DEĞİŞTİR (HW/SW)") { _, _ ->
                toggleHardwareSoftwareDecoder()
            }
            .setPositiveButton("TAMAM", null)
            .create()
        dialogRef.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndScrubGestures() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var downX = 0f
        var downY = 0f
        var scrubStartPosMs = 0L
        var gestureMode = 0 // 0 = Yok, 1 = Yatay Video Sarma, 2 = Dikey Ses/Parlaklık
        var isLongPress2x = false
        var startVolume = 0
        var startBrightness = 0.5f

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    binding.btnUnlockOverlay.visibility =
                        if (binding.btnUnlockOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    binding.playerControlsOverlay.visibility =
                        if (binding.playerControlsOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isControlsLocked) return true
                val width = binding.videoPlayerRoot.width.coerceAtLeast(1).toFloat()
                val leftBoundary = width / 3f
                val rightBoundary = (width * 2f) / 3f

                when {
                    e.x < leftBoundary -> seekByDelta(-10_000L)
                    e.x > rightBoundary -> seekByDelta(10_000L)
                    else -> {
                        togglePlayPause()
                        binding.playerControlsOverlay.visibility = View.VISIBLE
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isControlsLocked || gestureMode != 0) return
                val p = exoPlayer ?: return
                if (p.isPlaying) {
                    isLongPress2x = true
                    p.setPlaybackSpeed(2.0f)
                    binding.tvFastPlay2xBadge.visibility = View.VISIBLE
                }
            }
        })

        val touchHandler = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (isControlsLocked) return@OnTouchListener true

            val width = binding.videoPlayerRoot.width.coerceAtLeast(1).toFloat()
            val height = binding.videoPlayerRoot.height.coerceAtLeast(1).toFloat()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    val curPos = if (usingPlatformFallback) {
                        binding.platformVideoView.currentPosition.toLong()
                    } else {
                        exoPlayer?.currentPosition ?: 0L
                    }
                    scrubStartPosMs = (pendingScrubTargetMs.takeIf { it >= 0L } ?: curPos).coerceAtLeast(0L)
                    gestureMode = 0
                    startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val curBr = window.attributes.screenBrightness
                    startBrightness = if (curBr in 0.01f..1.0f) curBr else 0.5f
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isLongPress2x) return@OnTouchListener true
                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (gestureMode == 0) {
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                            gestureMode = 1
                            isHorizontalScrubbing = true
                        } else if (abs(dy) > touchSlop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                            gestureMode = 2
                        }
                    }

                    if (gestureMode == 1) {
                        val effectiveDur = resolveEffectiveDuration(exoPlayer)
                        val maxDur = if (effectiveDur > 0L) effectiveDur else 7_200_000L
                        val fullSwipeSpanMs = (maxDur / 6L).coerceIn(90_000L, 600_000L)
                        val deltaMs = ((dx / width) * fullSwipeSpanMs).toLong()
                        val targetPos = (scrubStartPosMs + deltaMs).coerceIn(0L, maxDur)

                        pendingScrubTargetMs = targetPos
                        val formatted = FormatUtils.formatDuration(targetPos)
                        binding.tvCurrentTime.text = formatted
                        if (effectiveDur > 0L) {
                            binding.sbVideoProgress.progress = ((targetPos * 1000L) / effectiveDur).toInt().coerceIn(0, 1000)
                        }
                        showCenterOverlayLabel(formatted)
                        executeSmartSeek(targetPos, forceImmediate = false)
                    } else if (gestureMode == 2) {
                        val deltaRatio = -dy / (height * 0.75f)
                        if (downX < width / 2f) {
                            val newBr = (startBrightness + deltaRatio).coerceIn(0.05f, 1.0f)
                            window.attributes = window.attributes.apply { screenBrightness = newBr }
                            showCenterOverlayLabel("☀ ${(newBr * 100).toInt()}%")
                        } else if (audioManager != null) {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val targetVol = (startVolume + (deltaRatio * maxVol).toInt()).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val pct = ((targetVol * 100) / maxVol)
                            showCenterOverlayLabel("🔊 $pct%")
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPress2x) {
                        isLongPress2x = false
                        exoPlayer?.setPlaybackSpeed(currentSpeed)
                        binding.tvFastPlay2xBadge.visibility = View.GONE
                    }
                    if (isHorizontalScrubbing) {
                        isHorizontalScrubbing = false
                        if (pendingScrubTargetMs >= 0L) {
                            executeSmartSeek(pendingScrubTargetMs, forceImmediate = true)
                        }
                        hideCenterOverlayLabelDelayed(500L)
                    } else if (gestureMode == 2) {
                        hideCenterOverlayLabelDelayed(500L)
                    }
                    gestureMode = 0
                }
            }
            true
        }

        binding.exoPlayerView.setOnTouchListener(touchHandler)
        binding.platformVideoView.setOnTouchListener(touchHandler)
        binding.playerControlsOverlay.setOnTouchListener(touchHandler)
    }

    private fun seekByDelta(deltaMs: Long) {
        val dur = resolveEffectiveDuration(exoPlayer)
        val curPos = if (usingPlatformFallback) {
            binding.platformVideoView.currentPosition.toLong()
        } else {
            exoPlayer?.currentPosition ?: 0L
        }
        val basePos = (pendingScrubTargetMs.takeIf { it >= 0L } ?: curPos).coerceAtLeast(0L)
        val target = (basePos + deltaMs).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
        executeSmartSeek(target, forceImmediate = true)
        val formatted = FormatUtils.formatDuration(target)
        binding.tvCurrentTime.text = formatted
        if (dur > 0L) {
            binding.sbVideoProgress.progress = ((target * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
        showCenterOverlayLabel(formatted)
        hideCenterOverlayLabelDelayed(750L)
    }

    private fun showCenterOverlayLabel(text: String) {
        binding.tvCenterSeekOverlay.text = text
        binding.tvCenterSeekOverlay.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages("HIDE_SEEK_LABEL")
    }

    private fun hideCenterOverlayLabelDelayed(delayMs: Long) {
        uiHandler.removeCallbacksAndMessages("HIDE_SEEK_LABEL")
        uiHandler.postAtTime(
            { binding.tvCenterSeekOverlay.visibility = View.GONE },
            "HIDE_SEEK_LABEL",
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.videoPlayerRoot).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
        if (usingPlatformFallback && binding.platformVideoView.isPlaying) {
            binding.platformVideoView.pause()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        binding.platformVideoView.stopPlayback()
        super.onDestroy()
    }
}
