package com.alphainventor.filemanager.viewer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.alphainventor.filemanager.databinding.ActivityMusicPlayerBinding
import com.alphainventor.filemanager.util.FormatUtils
import java.io.File

class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicPlayerBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updater = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.isPlaying && p.duration > 0) {
                    binding.tvMusicCurrent.text = FormatUtils.formatDuration(p.currentPosition)
                    binding.tvMusicDuration.text = FormatUtils.formatDuration(p.duration)
                    binding.sbMusicProgress.progress = ((p.currentPosition * 1000L) / p.duration).toInt().coerceIn(0, 1000)
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.data?.toString() ?: (intent.getStringExtra("AUDIO_URL") ?: "")
        val title = intent.getStringExtra("AUDIO_TITLE") ?: url.substringAfterLast('/')
        binding.tvTrackName.text = title

        player = ExoPlayer.Builder(this).build().also { exo ->
            val uri = if (url.startsWith("http") || url.startsWith("content://")) Uri.parse(url) else Uri.fromFile(File(url))
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.playWhenReady = true
            exo.prepare()
        }

        binding.btnMusicBack.setOnClickListener { finish() }
        binding.btnMusicPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                binding.btnMusicPlayPause.text = "OYNAT"
            } else {
                p.play()
                binding.btnMusicPlayPause.text = "DURAKLAT"
            }
        }

        binding.sbMusicProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = player ?: return
                if (fromUser && p.duration > 0) {
                    p.seekTo((p.duration * progress) / 1000L)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        handler.post(updater)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
