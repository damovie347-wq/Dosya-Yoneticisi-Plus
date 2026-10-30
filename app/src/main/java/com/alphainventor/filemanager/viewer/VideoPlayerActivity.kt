package com.alphainventor.filemanager.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Video Oynatıcı (VideoPlayerActivity & viewer/f):
 * 1) Orijinal arayüz düzeni:
 *    - Sağ altta dairesel yüzen butonlar (@drawable/btn_circular): Ses İzi (🎵), Altyazı (💬), Kilit (🔒), Tam Ekran / En-Boy (⛶).
 *    - Ana alt satır (control_buttons_line2): En-Boy | Önceki (|◀) | Oynat/Duraklat | Sonraki (▶|) | Diğer Kontroller (▲/▼) | Ekran Döndür (🔄).
 *    - Açılır ek kontrol satırı (control_buttons_line1): Karıştır (🔀) | 10sn Geri (↺10) | Oynatma Hızı (1.0x) | 10sn İleri (↻10) | Tekrar Modu (1 / 🔁 / 🔂).
 * 2) Orijinal açılır menüler ve diyaloglar:
 *    - Ses izi, Altyazı ve Oynatma hızı (0.25x..2.0x) butonların hemen üzerinde koyu açılır PopupWindow olarak açılır.
 *    - Sağ üst üç nokta menüsü: Altyazı seç (.srt/.vtt), Altyazı boyutu (SeekBar diyaloğu), Çözücü değiştir, Şununla aç, Özellikler.
 * 3) Orijinal dokunmatik hareketler (Gestures):
 *    - Sol dikey kaydırma: Parlaklık (`vertical_progress_bar_brightness.xml` + `ic_player_brightness_high`).
 *    - Sağ dikey kaydırma: Ses (`vertical_progress_bar.xml` + `ic_vp_volume_on`).
 *    - Yatay kaydırma: 46sp gölgeli süre göstergesiyle anlık video sarma.
 *    - İki parmakla yakınlaştırma/uzaklaştırma (Pinch-to-Zoom 0.5x - 3.0x).
 *    - Uzun basma: 2X hızlı oynatma (`bg_player_control_popup`).
 *    - Çift dokunma: Sol 1/3 -> -10sn, Sağ 1/3 -> +10sn, Orta -> Oynat/Duraklat (`iconSplash` animasyonu).
 * 4) Sıfır bekleme (0 ms yerel başlangıç):
 *    - Yerel videolarda bloklayıcı `MediaMetadataRetriever` ön taraması kaldırıldı ve `pbVideoBuffering` yalnızca ağ akışlarında gösterilir.
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
    // 0 = Tek Oynat (ic_play_one), 1 = Tümünü Tekrarla (ic_vp_repeat_off), 2 = Biri Tekrarla (ic_vp_repeat_one)
    private var repeatModeState: Int = 0
    private var isShuffleEnabled: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var subtitleTextSizeSp: Float = 18f
    private var externalSubtitleUri: Uri? = null
    private var videoZoomScale: Float = 1.0f
    private var videoTransX: Float = 0f
    private var videoTransY: Float = 0f

    private var preferSoftwareDecoder: Boolean = false
    private var usingPlatformFallback: Boolean = false
    private var isDraggingSeekBar: Boolean = false
    private var isHorizontalScrubbing: Boolean = false

    private var knownDurationMs: Long = 0L
    private var pendingScrubTargetMs: Long = -1L
    private var deferredSeekOnReadyMs: Long = -1L
    private var lastLiveSeekRealtimeMs: Long = 0L
    private var lastSeekTargetMs: Long = -1L
    private var lastSeekTimestampMs: Long = 0L
    private var errorRecoveryCount: Int = 0

    private val externalSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            externalSubtitleUri = uri
            val pos = exoPlayer?.currentPosition ?: 0L
            initializePlayer(startPositionMs = pos, isErrorRecovery = false)
            Toast.makeText(this, "Altyazı yüklendi.", Toast.LENGTH_SHORT).show()
        }
    }

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
                        val now = SystemClock.elapsedRealtime()
                        val rawPos = player.currentPosition.coerceAtLeast(0L)
                        val pos = if (lastSeekTargetMs >= 0L && (now - lastSeekTimestampMs) < 750L && player.playbackState == Player.STATE_BUFFERING) {
                            lastSeekTargetMs
                        } else {
                            if (player.playbackState == Player.STATE_READY) {
                                lastSeekTargetMs = -1L
                            }
                            rawPos
                        }
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

        // Orijinal APK viewer/c & viewer/e: 0 ms oturum listesi aktarımı
        val sessionKey = intent.getStringExtra("VIEWER_SESSION_KEY")
        val session = LocalFileRepository.getViewerSession(sessionKey)
        if (session != null && session.paths.isNotEmpty()) {
            playlistUrls = ArrayList(session.paths)
            playlistTitles = if (session.titles.size == session.paths.size) {
                ArrayList(session.titles)
            } else {
                ArrayList(session.paths.map { it.substringAfterLast('/') })
            }
            if (authToken.isBlank() && session.authToken.isNotBlank()) {
                authToken = session.authToken
            }
            playlistIndex = session.initialIndex.coerceIn(0, (playlistUrls.size - 1).coerceAtLeast(0))
        } else {
            playlistUrls = intent.getStringArrayListExtra("PLAYLIST_URLS") ?: arrayListOf(videoUrlOrPath)
            playlistTitles = intent.getStringArrayListExtra("PLAYLIST_TITLES") ?: arrayListOf(videoTitle)
            playlistIndex = intent.getIntExtra("PLAYLIST_INDEX", 0).coerceIn(0, (playlistUrls.size - 1).coerceAtLeast(0))
        }

        binding.tvVideoTitle.text = videoTitle
        binding.tvCodecBadge.text = "HW+"
        binding.exoPlayerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)

        setupOverlayControls()
        setupTouchAndScrubGestures()
        initializePlayer(startPositionMs = 0L, isErrorRecovery = false)
    }

    private fun isNetworkStream(): Boolean {
        return videoUrlOrPath.startsWith("http://") || videoUrlOrPath.startsWith("https://")
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
        binding.exoPlayerView.clipChildren = false
        binding.exoPlayerView.clipToPadding = false
        binding.exoPlayerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.apply {
            clipChildren = false
            clipToPadding = false
        }

        exoPlayer?.release()

        // AV1 (10-bit Main10 / AV1Core) ve HEVC videolar için akıllı çözücü sıralaması:
        // - HW+ modunda: Donanım çözücüleri -> hızlı çok çekirdekli dav1d -> diğer çözücüler (yavaş tek çekirdekli libgav1 en sonda).
        // - SW modunda: dav1d -> yazılım çözücüleri -> donanım çözücüleri.
        val smartCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val rawList = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling).ifEmpty {
                if (!requiresSecure) MediaCodecUtil.getDecoderInfos(mimeType, false, false) else emptyList()
            }
            if (rawList.size <= 1) {
                rawList
            } else {
                rawList.sortedBy { info ->
                    val nameLower = info.name.lowercase(Locale.ROOT)
                    val isDav1d = nameLower.contains("dav1d")
                    val isSw = info.softwareOnly || nameLower.startsWith("c2.android.") || nameLower.startsWith("omx.google.")
                    if (preferSoftwareDecoder) {
                        when {
                            isDav1d -> 0
                            isSw -> 1
                            else -> 2
                        }
                    } else {
                        when {
                            !isSw -> 0
                            isDav1d -> 1
                            else -> 2
                        }
                    }
                }
            }
        }

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setMediaCodecSelector(smartCodecSelector)
            setExtensionRendererMode(
                if (preferSoftwareDecoder) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            setEnableDecoderFallback(true)
        }

        // Orijinal APK (ExoPlayer 2.18 ProgressiveMediaSource) ile birebir:
        // MKV Cues anahtar kare tablosunu bozan CBR bayrağı kapalı ve Media3 1.3.1'in MKV sarma sırasında
        // takılmaya yol açan SubtitleTranscodingExtractor katmanı devre dışı.
        @Suppress("DEPRECATION")
        val extractorsFactory = DefaultExtractorsFactory()
            .setTextTrackTranscodingEnabled(false)

        val okHttpFactory = OkHttpDataSource.Factory(CloudManager.httpClient).apply {
            if (authToken.isNotBlank() && !authToken.startsWith("DEMO_")) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val trackSelector = DefaultTrackSelector(this).apply {
            val langTag = Locale.getDefault().toLanguageTag()
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(langTag)
                    .setPreferredTextLanguage(langTag)
                    .setSelectUndeterminedTextLanguage(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                150,
                400
            )
            .setBackBuffer(10_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().also { player ->
                // AV1 ve yüksek çözünürlüklü MKV'lerde ileri/geri sarmanın anında anahtar kareye (keyframe)
                // oturması ve yüzlerce ara kareyi çözmeye çalışıp takılmaması için CLOSEST_SYNC:
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                binding.exoPlayerView.player = player
                loadMediaIntoExoPlayer(player, resetRecoveryState = !isErrorRecovery, initialPosMs = startPositionMs)

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        binding.pbVideoBuffering.visibility =
                            if (playbackState == Player.STATE_BUFFERING && isNetworkStream()) View.VISIBLE else View.GONE

                        if (playbackState == Player.STATE_READY) {
                            binding.pbVideoBuffering.visibility = View.GONE
                            val dur = resolveEffectiveDuration(player)
                            if (dur > 0L) {
                                binding.tvTotalDuration.text = FormatUtils.formatDuration(dur)
                            }
                            if (deferredSeekOnReadyMs >= 0L) {
                                val target = deferredSeekOnReadyMs
                                deferredSeekOnReadyMs = -1L
                                lastSeekTargetMs = target
                                lastSeekTimestampMs = SystemClock.elapsedRealtime()
                                player.seekTo(target)
                            }
                            updateSubtitleButtonIcon(player)
                        } else if (playbackState == Player.STATE_ENDED) {
                            handleVideoEnded()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateSubtitleButtonIcon(player)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        binding.btnPlayPause.setImageResource(
                            if (isPlaying) R.drawable.ic_vp_pause else R.drawable.ic_vp_play
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val resumePos = (
                            lastSeekTargetMs.takeIf { it >= 0L }
                                ?: pendingScrubTargetMs.takeIf { it >= 0L }
                                ?: deferredSeekOnReadyMs.takeIf { it >= 0L }
                                ?: player.currentPosition
                            ).coerceAtLeast(0L)
                        pendingScrubTargetMs = -1L

                        when (errorRecoveryCount) {
                            0 -> {
                                errorRecoveryCount = 1
                                // Eğer oynatma sırasında (seek vb.) geçici bir hata oluştuysa oynatıcıyı yıkmadan yerinde kurtar
                                if (knownDurationMs > 0L) {
                                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                        .build()
                                    player.prepare()
                                    if (resumePos > 0L) {
                                        player.seekTo(resumePos)
                                    }
                                    player.playWhenReady = true
                                } else {
                                    preferSoftwareDecoder = !preferSoftwareDecoder
                                    binding.tvCodecBadge.text = if (preferSoftwareDecoder) "SW" else "HW+"
                                    initializePlayer(startPositionMs = resumePos, isErrorRecovery = true)
                                }
                            }
                            1 -> {
                                errorRecoveryCount = 2
                                preferSoftwareDecoder = !preferSoftwareDecoder
                                binding.tvCodecBadge.text = if (preferSoftwareDecoder) "SW" else "HW+"
                                initializePlayer(startPositionMs = resumePos, isErrorRecovery = true)
                            }
                            else -> {
                                if (!isNetworkStream()) {
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

    private fun updateSubtitleButtonIcon(player: ExoPlayer) {
        val isDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val hasSelectedText = !isDisabled && player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        binding.btnQuickSubtitleTrack.setImageResource(
            if (hasSelectedText) R.drawable.ic_vp_subtitles else R.drawable.ic_subtitles_off
        )
    }

    private fun handleVideoEnded() {
        when (repeatModeState) {
            2 -> {
                // Birini tekrarla
                exoPlayer?.seekTo(0L)
                exoPlayer?.play()
            }
            1 -> {
                // Tüm klasörü tekrarla / sıradakine geç
                if (playlistUrls.size > 1) {
                    playNextInPlaylist()
                } else {
                    exoPlayer?.seekTo(0L)
                    exoPlayer?.play()
                }
            }
            else -> {
                // Tek oynat
                binding.btnPlayPause.setImageResource(R.drawable.ic_vp_play)
                binding.playerControlsOverlay.visibility = View.VISIBLE
            }
        }
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

        // Aynı klasörde aynı isimli .srt / .vtt varsa veya kullanıcı harici altyazı seçtiyse otomatik bağla
        val subtitleConfigs = ArrayList<MediaItem.SubtitleConfiguration>()
        val extSub = externalSubtitleUri ?: findMatchingLocalSubtitle(videoUrlOrPath)
        if (extSub != null) {
            val subPath = extSub.toString().lowercase(Locale.ROOT)
            val subMime = when {
                subPath.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                subPath.endsWith(".ass") || subPath.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            subtitleConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(extSub)
                    .setMimeType(subMime)
                    .setLanguage("tr")
                    .setLabel("Harici Altyazı")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        }

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        if (initialPosMs > 0L) {
            player.setMediaItem(mediaItem, initialPosMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.setPlaybackSpeed(currentSpeed)
        player.playWhenReady = true
        player.prepare()
    }

    private fun findMatchingLocalSubtitle(videoPath: String): Uri? {
        if (videoPath.startsWith("http") || videoPath.startsWith("content://")) return null
        return try {
            val vf = File(videoPath)
            val parent = vf.parentFile ?: return null
            val base = vf.nameWithoutExtension
            for (ext in listOf("srt", "vtt", "ass", "ssa")) {
                val cand = File(parent, "$base.$ext")
                if (cand.exists() && cand.length() > 0L) {
                    return Uri.fromFile(cand)
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun executeSmartSeek(targetMs: Long, forceImmediate: Boolean) {
        val dur = resolveEffectiveDuration(exoPlayer)
        val clampedTarget = if (dur > 0L) targetMs.coerceIn(0L, dur) else targetMs.coerceAtLeast(0L)

        if (usingPlatformFallback) {
            if (forceImmediate) {
                pendingScrubTargetMs = -1L
                binding.platformVideoView.seekTo(clampedTarget.toInt())
            } else {
                pendingScrubTargetMs = clampedTarget
            }
            return
        }

        val player = exoPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)

        if (forceImmediate) {
            pendingScrubTargetMs = -1L
            lastSeekTargetMs = clampedTarget
            lastSeekTimestampMs = now
            lastLiveSeekRealtimeMs = now
            if (player.playbackState == Player.STATE_IDLE) {
                deferredSeekOnReadyMs = clampedTarget
                player.prepare()
            } else {
                deferredSeekOnReadyMs = -1L
                player.seekTo(clampedTarget)
            }
        } else {
            pendingScrubTargetMs = clampedTarget
            // Sürükleme esnasında yalnızca çözücü hazır (STATE_READY) ise önizleme sarması yap;
            // çözücü STATE_BUFFERING içindeyken üst üste seek yığılmasını önle
            if (player.playbackState == Player.STATE_READY && (now - lastLiveSeekRealtimeMs >= 250L)) {
                lastLiveSeekRealtimeMs = now
                lastSeekTargetMs = clampedTarget
                lastSeekTimestampMs = now
                player.seekTo(clampedTarget)
            }
        }
    }

    private fun playPreviousInPlaylist() {
        val curPos = if (usingPlatformFallback) binding.platformVideoView.currentPosition.toLong() else (exoPlayer?.currentPosition ?: 0L)
        if (curPos > 4_000L || playlistUrls.size <= 1) {
            seekByDelta(-10_000L)
        } else {
            externalSubtitleUri = null
            playlistIndex = if (playlistIndex > 0) playlistIndex - 1 else playlistUrls.lastIndex
            videoUrlOrPath = playlistUrls[playlistIndex]
            videoTitle = playlistTitles.getOrElse(playlistIndex) { videoUrlOrPath.substringAfterLast('/') }
            initializePlayer(startPositionMs = 0L, isErrorRecovery = false)
        }
    }

    private fun playNextInPlaylist() {
        if (playlistUrls.size <= 1) {
            seekByDelta(10_000L)
        } else {
            externalSubtitleUri = null
            playlistIndex = if (isShuffleEnabled && playlistUrls.size > 2) {
                (playlistUrls.indices - playlistIndex).random()
            } else {
                (playlistIndex + 1) % playlistUrls.size
            }
            videoUrlOrPath = playlistUrls[playlistIndex]
            videoTitle = playlistTitles.getOrElse(playlistIndex) { videoUrlOrPath.substringAfterLast('/') }
            initializePlayer(startPositionMs = 0L, isErrorRecovery = false)
        }
    }

    private fun setupOverlayControls() {
        binding.btnVideoBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause(showSplash = false)
        }

        binding.tvCodecBadge.setOnClickListener {
            toggleHardwareSoftwareDecoder()
        }

        // Orijinal APK viewer/f: Dairesel yüzen Ses İzi ve Altyazı butonları kendi üzerinde PopupWindow açar
        binding.btnQuickAudioTrack.setOnClickListener { anchor ->
            showAudioTrackPopupWindow(anchor)
        }

        binding.btnQuickSubtitleTrack.setOnClickListener { anchor ->
            showSubtitleTrackPopupWindow(anchor)
        }

        binding.btnFloatingExpand.setOnClickListener {
            cycleAspectRatio()
        }

        // Ana Satır: Önceki Video (|◀) ve Sonraki Video (▶|)
        binding.btnRewind10.setOnClickListener {
            playPreviousInPlaylist()
        }

        binding.btnForward10.setOnClickListener {
            playNextInPlaylist()
        }

        // Açılır Satır 1: -10sn (↺10) ve +10sn (↻10)
        binding.btnExtraRewind10.setOnClickListener {
            seekByDelta(-10_000L)
        }

        binding.btnExtraForward10.setOnClickListener {
            seekByDelta(10_000L)
        }

        // Orijinal APK @id/more & @id/less: Açılır Satır 1'i (control_buttons_line1) aç/kapat
        binding.btnSubtitlesAndAudio.setOnClickListener {
            val isVisible = binding.llExtraVideoControls.visibility == View.VISIBLE
            binding.llExtraVideoControls.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnSubtitlesAndAudio.rotation = if (isVisible) 180f else 0f
        }

        binding.btnMuteToggle.setOnClickListener {
            isShuffleEnabled = !isShuffleEnabled
            exoPlayer?.shuffleModeEnabled = isShuffleEnabled
            binding.btnMuteToggle.alpha = if (isShuffleEnabled) 1.0f else 0.65f
            Toast.makeText(
                this,
                if (isShuffleEnabled) "Karışık oynatma açık" else "Karışık oynatma kapalı",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Orijinal APK 3 Kademeli Tekrar Modu: Tek Oynat (ic_play_one) -> Tümünü Tekrarla (ic_vp_repeat_off) -> Birini Tekrarla (ic_vp_repeat_one)
        binding.btnRepeatToggle.setOnClickListener {
            repeatModeState = (repeatModeState + 1) % 3
            when (repeatModeState) {
                0 -> {
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
                    binding.btnRepeatToggle.setImageResource(R.drawable.ic_play_one)
                    Toast.makeText(this, "Tek video oynat", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_ALL
                    binding.btnRepeatToggle.setImageResource(R.drawable.ic_vp_repeat_off)
                    Toast.makeText(this, "Tümünü tekrarla", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
                    binding.btnRepeatToggle.setImageResource(R.drawable.ic_vp_repeat_one)
                    Toast.makeText(this, "Bu videoyu tekrarla", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Orijinal APK @id/speed: Tıklanınca 0.25x..2.0x açılır hız seçici PopupWindow açar
        binding.btnPlaybackSpeed.setOnClickListener { anchor ->
            showPlaybackSpeedPopupWindow(anchor)
        }

        binding.btnTrackSelector.setOnClickListener { anchor ->
            showSubtitleTrackPopupWindow(anchor)
        }

        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
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

        // Orijinal APK Sağ Üst 3 Nokta Menüsü: Altyazı Seç | Altyazı Boyutu | Çözücü | Şununla Aç | Özellikler
        binding.btnVideoMore.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Altyazı dosyası seç (.srt / .vtt)")
            popup.menu.add(0, 2, 1, "Altyazı boyutu")
            popup.menu.add(0, 3, 2, "Ses İzi ve Altyazı Seçimi")
            popup.menu.add(0, 4, 3, "Çözücü Değiştir (HW+ / SW)")
            popup.menu.add(0, 5, 4, "Şununla aç")
            popup.menu.add(0, 6, 5, "Özellikler")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> externalSubtitleLauncher.launch(arrayOf("*/*"))
                    2 -> showSubtitleSizeDialog()
                    3 -> showInteractiveTrackSelectorDialog(initialFocusAudio = false)
                    4 -> toggleHardwareSoftwareDecoder()
                    5 -> {
                        val fi = FileItem(
                            id = videoUrlOrPath,
                            name = videoTitle,
                            path = videoUrlOrPath,
                            isDirectory = false,
                            size = 0L,
                            lastModified = System.currentTimeMillis(),
                            mimeType = "video/*",
                            sourceType = if (isNetworkStream()) FileSourceType.CLOUD else FileSourceType.LOCAL,
                            downloadUrl = videoUrlOrPath
                        )
                        LocalFileRepository.openWithExternalChooser(this, fi, authToken)
                    }
                    6 -> showVideoPropertiesDialog()
                }
                true
            }
            popup.show()
        }
    }

    private fun getZoomTargetView(): View {
        return if (usingPlatformFallback) {
            binding.platformVideoView
        } else {
            binding.exoPlayerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                ?: binding.exoPlayerView
        }
    }

    private fun resetVideoZoomAndPan() {
        videoZoomScale = 1.0f
        videoTransX = 0f
        videoTransY = 0f
        val target = getZoomTargetView()
        target.scaleX = 1.0f
        target.scaleY = 1.0f
        target.translationX = 0f
        target.translationY = 0f
        binding.exoPlayerView.videoSurfaceView?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            translationX = 0f
            translationY = 0f
        }
    }

    private fun applyVideoZoomAndPan(focusDeltaX: Float = 0f, focusDeltaY: Float = 0f) {
        if (videoZoomScale <= 1.01f) {
            resetVideoZoomAndPan()
            return
        }
        val target = getZoomTargetView()
        val containerW = binding.videoPlayerRoot.width.coerceAtLeast(1).toFloat()
        val containerH = binding.videoPlayerRoot.height.coerceAtLeast(1).toFloat()
        val baseW = target.width.takeIf { it > 0 }?.toFloat() ?: containerW
        val baseH = target.height.takeIf { it > 0 }?.toFloat() ?: containerH

        val scaledW = baseW * videoZoomScale
        val scaledH = baseH * videoZoomScale
        val maxTransX = ((scaledW - containerW) * 0.5f).coerceAtLeast((baseW * (videoZoomScale - 1f)) * 0.5f)
        val maxTransY = ((scaledH - containerH) * 0.5f).coerceAtLeast((baseH * (videoZoomScale - 1f)) * 0.5f)

        videoTransX = (videoTransX + focusDeltaX).coerceIn(-maxTransX, maxTransX)
        videoTransY = (videoTransY + focusDeltaY).coerceIn(-maxTransY, maxTransY)

        target.scaleX = videoZoomScale
        target.scaleY = videoZoomScale
        target.translationX = videoTransX
        target.translationY = videoTransY
    }

    private fun cycleAspectRatio() {
        resetVideoZoomAndPan()
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

    /**
     * Orijinal APK viewer/f: Ses İzi (🎵) dairesel butonuna tıklanınca hemen üstünde açılan koyu PopupWindow
     */
    private fun showAudioTrackPopupWindow(anchor: View) {
        val p = exoPlayer ?: return
        val density = resources.displayMetrics.density
        val audioGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_control_popup)
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        var popup: PopupWindow? = null

        val header = TextView(this).apply {
            text = "Ses İzi"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        }
        container.addView(header)

        var trackCounter = 0
        audioGroups.forEach { group ->
            for (tIdx in 0 until group.length) {
                trackCounter++
                val fmt = group.getTrackFormat(tIdx)
                val lang = fmt.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() } ?: "Ses $trackCounter"
                val title = fmt.label?.takeIf { it.isNotBlank() } ?: ""
                val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                val label = listOf(lang, title, ch).filter { it.isNotBlank() }.joinToString(" • ")
                val selected = group.isTrackSelected(tIdx)

                val row = TextView(this).apply {
                    text = if (selected) "✓  $label" else "    $label"
                    textSize = 14f
                    setTextColor(if (selected) Color.parseColor("#80CBC4") else Color.WHITE)
                    val hPad = (12 * density).toInt()
                    val vPad = (9 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(tIdx)))
                            .build()
                        popup?.dismiss()
                    }
                }
                container.addView(row)
            }
        }

        if (trackCounter == 0) {
            container.addView(TextView(this).apply {
                text = "Alternatif ses izi yok"
                textSize = 13.5f
                setTextColor(Color.LTGRAY)
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
            })
        }

        showAnchoredControlPopup(anchor, container).also { popup = it }
    }

    /**
     * Orijinal APK viewer/f: Altyazı (💬) dairesel butonuna tıklanınca hemen üstünde açılan koyu PopupWindow
     */
    private fun showSubtitleTrackPopupWindow(anchor: View) {
        val p = exoPlayer ?: return
        val density = resources.displayMetrics.density
        val textGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val isTextDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val anySubSelected = !isTextDisabled && textGroups.any { it.isSelected }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_control_popup)
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        var popup: PopupWindow? = null

        val header = TextView(this).apply {
            text = "Altyazı"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        }
        container.addView(header)

        // 1) Altyazı Kapalı
        container.addView(TextView(this).apply {
            text = if (!anySubSelected) "✓  Altyazı kapalı" else "    Altyazı kapalı"
            textSize = 14f
            setTextColor(if (!anySubSelected) Color.parseColor("#80CBC4") else Color.WHITE)
            val hPad = (12 * density).toInt()
            val vPad = (9 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                updateSubtitleButtonIcon(p)
                popup?.dismiss()
            }
        })

        var subCount = 0
        textGroups.forEach { group ->
            for (tIdx in 0 until group.length) {
                subCount++
                val fmt = group.getTrackFormat(tIdx)
                val lang = fmt.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() } ?: "Altyazı $subCount"
                val title = fmt.label?.takeIf { it.isNotBlank() } ?: ""
                val label = listOf(lang, title).filter { it.isNotBlank() }.joinToString(" • ")
                val selected = !isTextDisabled && group.isTrackSelected(tIdx)

                container.addView(TextView(this).apply {
                    text = if (selected) "✓  $label" else "    $label"
                    textSize = 14f
                    setTextColor(if (selected) Color.parseColor("#80CBC4") else Color.WHITE)
                    val hPad = (12 * density).toInt()
                    val vPad = (9 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(tIdx)))
                            .build()
                        updateSubtitleButtonIcon(p)
                        popup?.dismiss()
                    }
                })
            }
        }

        // Harici altyazı seç (.srt / .vtt) ve Altyazı boyutu
        container.addView(TextView(this).apply {
            text = "＋  Altyazı dosyası seç..."
            textSize = 13.5f
            setTextColor(Color.parseColor("#80CBC4"))
            val hPad = (12 * density).toInt()
            val vPad = (9 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                popup?.dismiss()
                externalSubtitleLauncher.launch(arrayOf("*/*"))
            }
        })

        container.addView(TextView(this).apply {
            text = "Aa  Altyazı boyutu"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            val hPad = (12 * density).toInt()
            val vPad = (9 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                popup?.dismiss()
                showSubtitleSizeDialog()
            }
        })

        showAnchoredControlPopup(anchor, container).also { popup = it }
    }

    /**
     * Orijinal APK viewer/f: Oynatma Hızı (1.0x) butonuna tıklanınca üstünde açılan hız listesi (0.25x..2.0x)
     */
    private fun showPlaybackSpeedPopupWindow(anchor: View) {
        val density = resources.displayMetrics.density
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_player_control_popup)
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        var popup: PopupWindow? = null

        for (spd in speeds) {
            val selected = abs(currentSpeed - spd) < 0.01f
            val label = "${spd}x"
            container.addView(TextView(this).apply {
                text = if (selected) "✓  $label" else "    $label"
                textSize = 14f
                setTextColor(if (selected) Color.parseColor("#80CBC4") else Color.WHITE)
                val hPad = (16 * density).toInt()
                val vPad = (7 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    currentSpeed = spd
                    exoPlayer?.setPlaybackSpeed(spd)
                    binding.btnPlaybackSpeed.text = label
                    popup?.dismiss()
                }
            })
        }

        showAnchoredControlPopup(anchor, container).also { popup = it }
    }

    private fun showAnchoredControlPopup(anchor: View, content: View): PopupWindow {
        val density = resources.displayMetrics.density
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popW = content.measuredWidth.coerceAtLeast((170 * density).toInt())
        val popH = content.measuredHeight

        val popup = PopupWindow(
            content,
            popW,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 12f * density
        }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width / 2 - popW / 2).coerceIn((8 * density).toInt(), (screenW - popW - (8 * density).toInt()).coerceAtLeast(0))
        val y = (loc[1] - popH - (8 * density).toInt()).coerceAtLeast((24 * density).toInt())
        popup.showAtLocation(binding.videoPlayerRoot, Gravity.NO_GRAVITY, x, y)
        return popup
    }

    /**
     * Orijinal APK res/layout/dialog_video_subtitle.xml: Altyazı Boyutu (SeekBar) Diyaloğu
     */
    private fun showSubtitleSizeDialog() {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (24 * density).toInt()
            val padV = (18 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val tvPreview = TextView(this).apply {
            text = "Örnek Altyazı Metni (${subtitleTextSizeSp.toInt()} sp)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, (14 * density).toInt())
        }

        val seekBar = SeekBar(this).apply {
            max = 24 // 12sp .. 36sp
            progress = (subtitleTextSizeSp.toInt() - 12).coerceIn(0, 24)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val newSp = (12 + p).toFloat()
                    subtitleTextSizeSp = newSp
                    tvPreview.text = "Örnek Altyazı Metni (${newSp.toInt()} sp)"
                    tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, newSp)
                    binding.exoPlayerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, newSp)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        layout.addView(tvPreview)
        layout.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle("Altyazı boyutu")
            .setView(layout)
            .setPositiveButton("TAMAM", null)
            .show()
    }

    private fun showVideoPropertiesDialog() {
        val p = exoPlayer
        val vf = p?.videoFormat
        val af = p?.audioFormat
        val f = File(videoUrlOrPath)
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
        val details = buildString {
            append("Dosya: $videoTitle\n")
            if (f.exists()) {
                append("Boyut: ${FormatUtils.formatSizeDetailed(f.length())}\n")
                append("Tarih: ${sdf.format(Date(f.lastModified()))}\n")
                append("Konum: ${f.absolutePath}\n")
            }
            val dur = resolveEffectiveDuration(p)
            if (dur > 0L) {
                append("Süre: ${FormatUtils.formatDuration(dur)}\n")
            }
            if (vf != null) {
                append("Video: ${vf.sampleMimeType ?: "-"} (${vf.width}x${vf.height})\n")
            }
            if (af != null) {
                append("Ses: ${af.sampleMimeType ?: "-"} (${af.channelCount} kanal, ${af.sampleRate} Hz)")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Özellikler")
            .setMessage(details.trim())
            .setPositiveButton("TAMAM", null)
            .show()
    }

    private fun toggleHardwareSoftwareDecoder() {
        val pos = if (usingPlatformFallback) {
            binding.platformVideoView.currentPosition.toLong()
        } else {
            exoPlayer?.currentPosition ?: 0L
        }
        preferSoftwareDecoder = !preferSoftwareDecoder
        binding.tvCodecBadge.text = if (preferSoftwareDecoder) "SW" else "HW+"
        initializePlayer(startPositionMs = pos.coerceAtLeast(0L), isErrorRecovery = false)
    }

    private fun togglePlayPause(showSplash: Boolean = true) {
        if (usingPlatformFallback) {
            val vv = binding.platformVideoView
            if (vv.isPlaying) {
                vv.pause()
                binding.btnPlayPause.setImageResource(R.drawable.ic_vp_play)
                if (showSplash) triggerCenterPlayPauseSplash(isNowPlaying = false)
            } else {
                vv.start()
                binding.btnPlayPause.setImageResource(R.drawable.ic_vp_pause)
                if (showSplash) triggerCenterPlayPauseSplash(isNowPlaying = true)
            }
            return
        }
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            if (showSplash) triggerCenterPlayPauseSplash(isNowPlaying = false)
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            }
            p.play()
            if (showSplash) triggerCenterPlayPauseSplash(isNowPlaying = true)
        }
    }

    private fun triggerCenterPlayPauseSplash(isNowPlaying: Boolean) {
        binding.iconSplash.setImageResource(if (isNowPlaying) R.drawable.ic_vp_play else R.drawable.ic_vp_pause)
        binding.iconSplash.alpha = 1f
        binding.iconSplash.scaleX = 0.85f
        binding.iconSplash.scaleY = 0.85f
        binding.iconSplash.visibility = View.VISIBLE
        binding.iconSplash.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .alpha(0f)
            .setDuration(420L)
            .withEndAction {
                binding.iconSplash.visibility = View.GONE
            }
            .start()
    }

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
            updateSubtitleButtonIcon(p)
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
        var gestureMode = 0 // 0 = Yok, 1 = Yatay Video Sarma, 2 = Dikey Ses/Parlaklık, 3 = İki Parmak Zoom
        var isLongPress2x = false
        var startVolume = 0
        var startBrightness = 0.5f

        var prevScaleFocusX = 0f
        var prevScaleFocusY = 0f

        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (isControlsLocked) return false
                gestureMode = 3
                prevScaleFocusX = detector.focusX
                prevScaleFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isControlsLocked) return false
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor <= 0f) return false
                // Orijinal APK X5(FFFFF)V: Zoom ölçeği [1.0f, 3.0f] (%100 - %300) aralığındadır, %100'ün altına küçülmez!
                val oldScale = videoZoomScale
                videoZoomScale = (videoZoomScale * factor).coerceIn(1.0f, 3.0f)
                val panDx = detector.focusX - prevScaleFocusX
                val panDy = detector.focusY - prevScaleFocusY
                prevScaleFocusX = detector.focusX
                prevScaleFocusY = detector.focusY

                val centerX = binding.videoPlayerRoot.width * 0.5f
                val centerY = binding.videoPlayerRoot.height * 0.5f
                val scaleRatio = if (oldScale > 0f) videoZoomScale / oldScale else 1f
                videoTransX = (videoTransX - (detector.focusX - centerX) * (scaleRatio - 1f))
                videoTransY = (videoTransY - (detector.focusY - centerY) * (scaleRatio - 1f))

                applyVideoZoomAndPan(panDx, panDy)
                showCenterOverlayLabel("${(videoZoomScale * 100).toInt()}%")
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (videoZoomScale <= 1.02f) {
                    resetVideoZoomAndPan()
                }
                hideCenterOverlayLabelDelayed(500L)
            }
        })

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
                        togglePlayPause(showSplash = true)
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
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
            }
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

                MotionEvent.ACTION_POINTER_DOWN -> {
                    gestureMode = 3
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isLongPress2x || gestureMode == 3 || event.pointerCount > 1) return@OnTouchListener true
                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (gestureMode == 0) {
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                            gestureMode = 1
                            isHorizontalScrubbing = true
                        } else if (abs(dy) > touchSlop * 1.4f && abs(dy) > abs(dx) * 1.3f) {
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
                        val deltaRatio = -dy / (height * 0.72f)
                        if (downX < width / 2f) {
                            val newBr = (startBrightness + deltaRatio).coerceIn(0.02f, 1.0f)
                            window.attributes = window.attributes.apply { screenBrightness = newBr }
                            val pct = (newBr * 100).toInt().coerceIn(0, 100)
                            showVerticalGestureBar(isBrightness = true, percent = pct)
                        } else if (audioManager != null) {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val targetVol = (startVolume + (deltaRatio * maxVol).toInt()).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val pct = ((targetVol * 100) / maxVol).coerceIn(0, 100)
                            showVerticalGestureBar(isBrightness = false, percent = pct)
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
                        hideVerticalGestureBarDelayed(500L)
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

    /**
     * Orijinal APK @id/center_overlay: Dikey Ses / Parlaklık Çubuğunu (`vertical_progress_bar` / `vertical_progress_bar_brightness`) gösterir
     */
    private fun showVerticalGestureBar(isBrightness: Boolean, percent: Int) {
        binding.tvCenterSeekOverlay.visibility = View.GONE
        binding.verticalProgressBar.progressDrawable = ContextCompat.getDrawable(
            this,
            if (isBrightness) R.drawable.vertical_progress_bar_brightness else R.drawable.vertical_progress_bar
        )
        binding.verticalProgressBar.progress = percent
        binding.ivGestureIcon.setImageResource(
            if (isBrightness) R.drawable.ic_player_brightness_high else R.drawable.ic_vp_volume_on
        )
        binding.centerGestureOverlay.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages("HIDE_GESTURE_BAR")
    }

    private fun hideVerticalGestureBarDelayed(delayMs: Long) {
        uiHandler.removeCallbacksAndMessages("HIDE_GESTURE_BAR")
        uiHandler.postAtTime(
            { binding.centerGestureOverlay.visibility = View.GONE },
            "HIDE_GESTURE_BAR",
            SystemClock.uptimeMillis() + delayMs
        )
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
        binding.centerGestureOverlay.visibility = View.GONE
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
