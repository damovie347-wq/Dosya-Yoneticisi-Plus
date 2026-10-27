package com.alphainventor.filemanager.viewer

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityVideoPlayerBinding
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.model.FileSourceType
import com.alphainventor.filemanager.util.FormatUtils

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var videoUrlOrPath: String = ""
    private var videoTitle: String = ""
    private var authToken: String = ""
    private var isControlsLocked: Boolean = false
    private var isRepeatOne: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var preferSoftwareDecoder: Boolean = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player != null && player.isPlaying) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.coerceAtLeast(1L)
                binding.tvCurrentTime.text = FormatUtils.formatDuration(pos)
                if (player.duration > 0) {
                    binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                    binding.sbVideoProgress.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                }
            }
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        videoUrlOrPath = intent.data?.toString() ?: (intent.getStringExtra("VIDEO_URL") ?: "")
        videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: videoUrlOrPath.substringAfterLast('/')
        authToken = intent.getStringExtra("AUTH_TOKEN") ?: ""

        binding.tvVideoTitle.text = videoTitle

        setupOverlayControls()
        setupDoubleTapSeekGestures()
        initializePlayer()
    }

    private fun initializePlayer() {
        exoPlayer?.release()

        // 1. Tüm Konteyner ve Kodekleri (MKV, WebM, MP4, AVI, MOV, FLV, TS, AV1, HEVC/H.265, VP9, AC3, EAC3, DTS, Opus, FLAC)
        // hem donanım (MediaCodec) hem de FFmpeg yazılım çözücü ile açan RenderersFactory:
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(
                if (preferSoftwareDecoder) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            setEnableDecoderFallback(true)
        }

        // 2. Tüm video uzantıları ve bozuk indeksli MKV/TS/MP4 akışları için gelişmiş ExtractorsFactory:
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        // 3. Google Drive / Dropbox / OneDrive / WebDAV akışları için Bearer Token destekli HTTP DataSource:
        val okHttpFactory = OkHttpDataSource.Factory(CloudManager.httpClient).apply {
            if (authToken.isNotBlank() && !authToken.startsWith("DEMO_")) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // 4. Düşük RAM ve akıcı önbellek yönetimi (cihazı ısıtmaz, bellek şişirmez):
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().also { player ->
                binding.exoPlayerView.player = player
                val uri = if (videoUrlOrPath.startsWith("http") || videoUrlOrPath.startsWith("content://")) {
                    Uri.parse(videoUrlOrPath)
                } else {
                    Uri.fromFile(java.io.File(videoUrlOrPath))
                }
                player.setMediaItem(MediaItem.fromUri(uri))
                player.playWhenReady = true
                player.prepare()

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        binding.pbVideoBuffering.visibility =
                            if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (playbackState == Player.STATE_READY && player.duration > 0) {
                            binding.tvTotalDuration.text = FormatUtils.formatDuration(player.duration)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.text = if (isPlaying) "⏸" else "▶"
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Donanım kodek hata verirse otomatik olarak FFmpeg yazılım çözücüye geç
                        if (!preferSoftwareDecoder) {
                            preferSoftwareDecoder = true
                            binding.tvCodecBadge.text = "SW-FFmpeg"
                            initializePlayer()
                        }
                    }
                })
            }

        uiHandler.post(progressUpdater)
    }

    private fun setupOverlayControls() {
        binding.btnVideoBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            val p = exoPlayer ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
        }

        binding.btnRewind10.setOnClickListener {
            seekByDelta(-10_000L, "-00:10")
        }

        binding.btnForward10.setOnClickListener {
            seekByDelta(10_000L, "+00:10")
        }

        binding.btnRepeatToggle.setOnClickListener {
            isRepeatOne = !isRepeatOne
            exoPlayer?.repeatMode = if (isRepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            binding.btnRepeatToggle.alpha = if (isRepeatOne) 1.0f else 0.5f
        }

        binding.btnPlaybackSpeed.setOnClickListener {
            val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            val nextIdx = (speeds.indexOfFirst { it == currentSpeed } + 1) % speeds.size
            currentSpeed = speeds[nextIdx]
            exoPlayer?.setPlaybackSpeed(currentSpeed)
            binding.btnPlaybackSpeed.text = "${currentSpeed}x"
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
                val p = exoPlayer ?: return
                if (fromUser && p.duration > 0) {
                    val target = (p.duration * progress) / 1000L
                    p.seekTo(target)
                    binding.tvCurrentTime.text = FormatUtils.formatDuration(target)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnVideoMore.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Çözücü Değiştir (Donanım HW / Yazılım FFmpeg)")
            popup.menu.add(0, 2, 1, "Ses İzi / Altyazı Seç")
            popup.menu.add(0, 3, 2, "Şununla aç (Harici Video Oynatıcı)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        preferSoftwareDecoder = !preferSoftwareDecoder
                        binding.tvCodecBadge.text = if (preferSoftwareDecoder) "SW-FFmpeg" else "HW+FFmpeg"
                        val pos = exoPlayer?.currentPosition ?: 0L
                        initializePlayer()
                        exoPlayer?.seekTo(pos)
                    }
                    2 -> showTrackInfoDialog()
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

    /**
     * Videodaki (01:01, 01:12, 01:14) sol/sağ çift dokunma ile "-00:10" ve "+00:10" sarma hareketi:
     */
    private fun setupDoubleTapSeekGestures() {
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
                val width = binding.videoPlayerRoot.width.coerceAtLeast(1)
                if (e.x < width / 2f) {
                    seekByDelta(-10_000L, "-00:10")
                } else {
                    seekByDelta(10_000L, "+00:10")
                }
                return true
            }
        })

        binding.exoPlayerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun seekByDelta(deltaMs: Long, label: String) {
        val player = exoPlayer ?: return
        val target = (player.currentPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(target)
        binding.tvCurrentTime.text = FormatUtils.formatDuration(target)
        binding.tvCenterSeekOverlay.text = label
        binding.tvCenterSeekOverlay.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages("HIDE_SEEK_LABEL")
        uiHandler.postAtTime(
            { binding.tvCenterSeekOverlay.visibility = View.GONE },
            "HIDE_SEEK_LABEL",
            android.os.SystemClock.uptimeMillis() + 750L
        )
    }

    private fun showTrackInfoDialog() {
        val p = exoPlayer ?: return
        val formatInfo = buildString {
            append("Video Başlığı: $videoTitle
")
            append("Aktif Çözücü Modu: ${binding.tvCodecBadge.text}
")
            p.videoFormat?.let { vf ->
                append("Video Kodek: ${vf.sampleMimeType ?: "Bilinmiyor"} (${vf.width}x${vf.height})
")
            }
            p.audioFormat?.let { af ->
                append("Ses Kodek: ${af.sampleMimeType ?: "Bilinmiyor"} (${af.channelCount} kanal, ${af.sampleRate} Hz)
")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Kodek ve Akış Bilgisi")
            .setMessage(formatInfo)
            .setPositiveButton("Tamam", null)
            .show()
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
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
