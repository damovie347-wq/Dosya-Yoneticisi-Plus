package com.alphainventor.filemanager.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.alphainventor.filemanager.R
import com.alphainventor.filemanager.cloud.CloudManager
import com.alphainventor.filemanager.core.LocalFileRepository
import com.alphainventor.filemanager.databinding.ActivityImageViewerBinding
import com.alphainventor.filemanager.model.FileItem
import com.alphainventor.filemanager.util.AppSettings
import com.alphainventor.filemanager.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 Resim Görüntüleyici (Galeri):
 * 1) Klasördeki tüm görseller arasında sağa-sola kaydırarak geçiş (PagerSnapHelper + RecyclerView).
 * 2) İki parmakla yakınlaştırma/uzaklaştırma (Pinch-to-Zoom 1.0x - 5.0x), çift dokunuşla yakınlaştırma (Double-Tap Zoom)
 *    ve yakınlaştırılmış görsel üzerinde sürükleme (Pan).
 * 3) Üst Bar: "1 / 30" sayaç + dosya adı + sağ üstte ikonlu "Slayt gösterisi", "Şu şekilde ayarla:", "Şununla aç" menüsü.
 * 4) Alt Bar: Paylaş | Döndür | Sil | Bilgi (sol altta tarih/yol/çözünürlük/boyut kutusu) | Daha fazla.
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private lateinit var pagerAdapter: ImagePagerAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private val imagePaths = ArrayList<String>()
    private val imageTitles = ArrayList<String>()
    private val imageRotations = HashMap<Int, Float>()
    private var currentIndex: Int = 0
    private var authToken: String = ""
    private var barsVisible: Boolean = true
    private var infoOverlayVisible: Boolean = false

    private var slideshowRunning: Boolean = false
    private val slideshowHandler = Handler(Looper.getMainLooper())
    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (!slideshowRunning || imagePaths.isEmpty()) return
            val nextIdx = (currentIndex + 1) % imagePaths.size
            binding.rvImagePager.smoothScrollToPosition(nextIdx)
            onPageSelected(nextIdx)
            slideshowHandler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authToken = intent.getStringExtra("AUTH_TOKEN") ?: ""
        val singlePath = intent.data?.path ?: (intent.getStringExtra("IMAGE_PATH") ?: "")
        val singleTitle = intent.getStringExtra("IMAGE_TITLE") ?: singlePath.substringAfterLast('/')

        val passedPaths = intent.getStringArrayListExtra("IMAGE_LIST_PATHS")
        val passedTitles = intent.getStringArrayListExtra("IMAGE_LIST_TITLES")
        val passedIndex = intent.getIntExtra("IMAGE_INDEX", -1)

        if (!passedPaths.isNullOrEmpty()) {
            imagePaths.addAll(passedPaths)
            if (!passedTitles.isNullOrEmpty() && passedTitles.size == passedPaths.size) {
                imageTitles.addAll(passedTitles)
            } else {
                imageTitles.addAll(passedPaths.map { it.substringAfterLast('/') })
            }
            currentIndex = if (passedIndex in imagePaths.indices) {
                passedIndex
            } else {
                imagePaths.indexOf(singlePath).coerceAtLeast(0)
            }
        } else if (singlePath.isNotBlank()) {
            loadSiblingImagesFromParentDirectory(singlePath, singleTitle)
        }

        setupPager()
        setupTopAndBottomActions()
        updateCurrentPageUi()
    }

    override fun onDestroy() {
        stopSlideshow()
        super.onDestroy()
    }

    private fun loadSiblingImagesFromParentDirectory(targetPath: String, targetTitle: String) {
        val file = File(targetPath)
        val parent = file.parentFile
        if (parent != null && parent.exists() && parent.isDirectory) {
            val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
            val siblings = parent.listFiles()?.filter { f ->
                f.isFile && (AppSettings.showHiddenFiles || !f.name.startsWith(".")) &&
                    f.extension.lowercase(Locale.ROOT) in imageExts
            } ?: emptyList()

            val sorted = when (AppSettings.sortBy) {
                0 -> if (AppSettings.sortAscending) siblings.sortedBy { it.name.lowercase(Locale.ROOT) }
                else siblings.sortedByDescending { it.name.lowercase(Locale.ROOT) }
                1 -> if (AppSettings.sortAscending) siblings.sortedBy { it.length() }
                else siblings.sortedByDescending { it.length() }
                else -> if (AppSettings.sortAscending) siblings.sortedBy { it.lastModified() }
                else siblings.sortedByDescending { it.lastModified() }
            }

            if (sorted.isNotEmpty()) {
                for (f in sorted) {
                    imagePaths.add(f.absolutePath)
                    imageTitles.add(f.name)
                }
                currentIndex = imagePaths.indexOf(file.absolutePath).coerceAtLeast(0)
                return
            }
        }
        imagePaths.add(targetPath)
        imageTitles.add(targetTitle)
        currentIndex = 0
    }

    private fun setupPager() {
        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvImagePager.layoutManager = layoutManager
        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(binding.rvImagePager)

        pagerAdapter = ImagePagerAdapter()
        binding.rvImagePager.adapter = pagerAdapter
        binding.rvImagePager.setItemViewCacheSize(4)

        if (currentIndex in imagePaths.indices) {
            binding.rvImagePager.scrollToPosition(currentIndex)
        }

        binding.rvImagePager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val snapView = snapHelper.findSnapView(layoutManager) ?: return
                    val pos = layoutManager.getPosition(snapView)
                    if (pos != RecyclerView.NO_POSITION && pos != currentIndex) {
                        onPageSelected(pos)
                    }
                }
            }
        })
    }

    private fun onPageSelected(newIndex: Int) {
        if (newIndex !in imagePaths.indices) return
        currentIndex = newIndex
        updateCurrentPageUi()
    }

    private fun updateCurrentPageUi() {
        if (imagePaths.isEmpty()) {
            finish()
            return
        }
        currentIndex = currentIndex.coerceIn(0, imagePaths.lastIndex)
        binding.tvImageCounter.text = "${currentIndex + 1} / ${imagePaths.size}"
        binding.tvImageTitle.text = imageTitles.getOrElse(currentIndex) {
            imagePaths[currentIndex].substringAfterLast('/')
        }
        if (infoOverlayVisible) {
            updateFloatingInfoOverlay()
        }
    }

    private fun setupTopAndBottomActions() {
        binding.btnImageBack.setOnClickListener {
            finish()
        }

        binding.btnImageShare.setOnClickListener { anchor ->
            showOriginalOverflowPopup(anchor)
        }

        binding.btnBottomShare.setOnClickListener {
            val item = getCurrentFileItem() ?: return@setOnClickListener
            LocalFileRepository.shareFile(this, item)
        }

        binding.btnBottomRotate.setOnClickListener {
            val nextRot = ((imageRotations[currentIndex] ?: 0f) - 90f) % 360f
            imageRotations[currentIndex] = nextRot
            pagerAdapter.notifyItemChanged(currentIndex)
        }

        binding.btnBottomDelete.setOnClickListener {
            confirmAndDeleteCurrentImage()
        }

        binding.btnBottomInfo.setOnClickListener {
            infoOverlayVisible = !infoOverlayVisible
            if (infoOverlayVisible) {
                updateFloatingInfoOverlay()
                binding.llFloatingImageInfo.visibility = View.VISIBLE
            } else {
                binding.llFloatingImageInfo.visibility = View.GONE
            }
        }

        binding.btnBottomMore.setOnClickListener { anchor ->
            showOriginalOverflowPopup(anchor)
        }
    }

    private fun toggleOverlayBars() {
        if (slideshowRunning) {
            stopSlideshow()
        }
        barsVisible = !barsVisible
        binding.topBarOverlay.visibility = if (barsVisible) View.VISIBLE else View.GONE
        binding.bottomBarOverlay.visibility = if (barsVisible) View.VISIBLE else View.GONE
        if (!barsVisible) {
            binding.llFloatingImageInfo.visibility = View.GONE
        } else if (infoOverlayVisible) {
            binding.llFloatingImageInfo.visibility = View.VISIBLE
        }
    }

    private fun updateFloatingInfoOverlay() {
        val path = imagePaths.getOrNull(currentIndex) ?: return
        val file = File(path)
        val sdf = SimpleDateFormat("dd MMMM yyyy HH:mm", Locale("tr", "TR"))
        val dateText = if (file.exists()) sdf.format(Date(file.lastModified())) else sdf.format(Date())

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
        val resPart = if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth} x ${opts.outHeight}    " else ""
        val sizePart = if (file.exists()) FormatUtils.formatSize(file.length()) else ""

        binding.tvFloatingInfoDate.text = dateText
        binding.tvFloatingInfoPath.text = path
        binding.tvFloatingInfoResolutionSize.text = "$resPart$sizePart"
    }

    /**
     * Orijinal APK Sağ Üst / Sağ Alt İkonlu Açılır Menü (Video 3 00:08 - 00:16):
     * - ▶ Slayt gösterisi
     * - 🖼 Şu şekilde ayarla: (Duvar kağıdı / WhatsApp Profil fotoğrafı / Kişi resmi)
     * - ↗ Şununla aç
     */
    private fun showOriginalOverflowPopup(anchor: View) {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#303030"))
            val vPad = (6 * density).toInt()
            setPadding(0, vPad, 0, vPad)
            elevation = 8f * density
        }

        var popupWindow: PopupWindow? = null

        fun addMenuRow(iconRes: Int, title: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (215 * density).toInt(),
                    (46 * density).toInt()
                )
                val hPad = (16 * density).toInt()
                setPadding(hPad, 0, hPad, 0)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popupWindow?.dismiss()
                    onClick()
                }
            }
            val iv = ImageView(this).apply {
                val sz = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (14 * density).toInt()
                }
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                isSingleLine = true
            }
            row.addView(iv)
            row.addView(tv)
            container.addView(row)
        }

        addMenuRow(
            iconRes = R.drawable.ic_vp_play,
            title = if (slideshowRunning) "Slayt gösterisini durdur" else "Slayt gösterisi"
        ) {
            if (slideshowRunning) stopSlideshow() else startSlideshow()
        }

        addMenuRow(
            iconRes = R.drawable.v_shape_image,
            title = "Şu şekilde ayarla:"
        ) {
            launchSetAsIntent()
        }

        addMenuRow(
            iconRes = R.drawable.ic_menu_share,
            title = "Şununla aç"
        ) {
            val item = getCurrentFileItem() ?: return@addMenuRow
            LocalFileRepository.openWithExternalChooser(this, item, authToken)
        }

        popupWindow = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 10f * density
        }

        popupWindow.showAsDropDown(anchor, -(140 * density).toInt(), 0)
    }

    private fun startSlideshow() {
        if (imagePaths.size <= 1) {
            Toast.makeText(this, "Slayt gösterisi için klasörde birden fazla görsel olmalı.", Toast.LENGTH_SHORT).show()
            return
        }
        slideshowRunning = true
        barsVisible = false
        binding.topBarOverlay.visibility = View.GONE
        binding.bottomBarOverlay.visibility = View.GONE
        binding.llFloatingImageInfo.visibility = View.GONE
        Toast.makeText(this, "Slayt gösterisi başlatıldı", Toast.LENGTH_SHORT).show()
        slideshowHandler.removeCallbacks(slideshowRunnable)
        slideshowHandler.postDelayed(slideshowRunnable, 3000L)
    }

    private fun stopSlideshow() {
        slideshowRunning = false
        slideshowHandler.removeCallbacks(slideshowRunnable)
    }

    private fun launchSetAsIntent() {
        val path = imagePaths.getOrNull(currentIndex) ?: return
        val file = File(path)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "image/*")
                putExtra("mimeType", "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Şu şekilde ayarla:"))
        } catch (e: Exception) {
            Toast.makeText(this, "İşlem başlatılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmAndDeleteCurrentImage() {
        val path = imagePaths.getOrNull(currentIndex) ?: return
        val title = imageTitles.getOrElse(currentIndex) { path.substringAfterLast('/') }
        val file = File(path)
        if (!file.exists()) return

        AlertDialog.Builder(this)
            .setTitle("Sil")
            .setMessage("\"$title\" görselini silmek istediğinizden emin misiniz?")
            .setPositiveButton("TAMAM") { _, _ ->
                lifecycleScope.launch {
                    val item = getCurrentFileItem()
                    val deleted = if (item != null) {
                        LocalFileRepository.deleteFiles(items = listOf(item), moveToRecycleBin = AppSettings.useRecycleBin, context = this@ImageViewerActivity) > 0
                    } else {
                        file.delete()
                    }
                    if (deleted) {
                        val removedIdx = currentIndex
                        imagePaths.removeAt(removedIdx)
                        if (removedIdx in imageTitles.indices) {
                            imageTitles.removeAt(removedIdx)
                        }
                        pagerAdapter.notifyItemRemoved(removedIdx)
                        if (imagePaths.isEmpty()) {
                            finish()
                        } else {
                            currentIndex = removedIdx.coerceAtMost(imagePaths.lastIndex)
                            updateCurrentPageUi()
                        }
                    }
                }
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun getCurrentFileItem(): FileItem? {
        val path = imagePaths.getOrNull(currentIndex) ?: return null
        val title = imageTitles.getOrElse(currentIndex) { path.substringAfterLast('/') }
        val file = File(path)
        return FileItem(
            id = path,
            name = title,
            path = path,
            isDirectory = false,
            size = if (file.exists()) file.length() else 0L,
            lastModified = if (file.exists()) file.lastModified() else System.currentTimeMillis(),
            mimeType = "image/*"
        )
    }

    // ==================== PAGER ADAPTER & ZOOMABLE IMAGE VIEW ====================

    private inner class ImagePagerAdapter : RecyclerView.Adapter<ImagePagerAdapter.PageViewHolder>() {

        inner class PageViewHolder(
            val container: FrameLayout,
            val zoomImageView: ZoomableImageView,
            val progressBar: ProgressBar
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
            }

            val zoomView = ZoomableImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onSingleTapListener = {
                    toggleOverlayBars()
                }
            }

            val pb = ProgressBar(parent.context).apply {
                val sz = (36 * resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
                visibility = View.GONE
            }

            container.addView(zoomView)
            container.addView(pb)
            return PageViewHolder(container, zoomView, pb)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val pathOrUrl = imagePaths[position]
            val rot = imageRotations[position] ?: 0f
            holder.zoomImageView.rotation = rot
            holder.zoomImageView.setImageBitmap(null)
            holder.progressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    decodeImageSafely(pathOrUrl)
                }
                holder.progressBar.visibility = View.GONE
                if (bmp != null) {
                    holder.zoomImageView.setZoomBitmap(bmp)
                }
            }
        }

        override fun getItemCount(): Int = imagePaths.size

        private fun decodeImageSafely(pathOrUrl: String): Bitmap? {
            return try {
                if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                    val reqBuilder = Request.Builder().url(pathOrUrl)
                    if (authToken.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer $authToken")
                    }
                    CloudManager.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val bytes = resp.body?.bytes() ?: return null
                        decodeSampledBytes(bytes, 2560, 2560)
                    }
                } else {
                    val file = File(pathOrUrl)
                    if (!file.exists()) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    val maxDim = 2560
                    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                        sample *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun decodeSampledBytes(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > reqW || bounds.outHeight / sample > reqH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}

/**
 * İki parmakla yakınlaştırma (Pinch-to-Zoom), çift tıklamayla yakınlaştırma (Double-Tap Zoom)
 * ve yakınlaştırılmış görselde kaydırma (Pan) destekleyen özel ImageView.
 * Normal boyuttayken (1.0x) yatay kaydırmayı doğrudan RecyclerView/PagerSnapHelper'a devreder.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val displayRect = RectF()

    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private val lastTouch = PointF()
    private var isDragging = false

    var onSingleTapListener: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val newScale = (currentScale * factor).coerceIn(minScale, maxScale)
            val actualFactor = newScale / currentScale
            currentScale = newScale
            drawMatrix.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
            constrainMatrixBounds()
            imageMatrix = drawMatrix
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (currentScale < 1.02f) {
                resetToFitCenter()
            }
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapListener?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.15f) {
                resetToFitCenter()
            } else {
                val targetScale = 2.8f
                val factor = targetScale / currentScale
                currentScale = targetScale
                drawMatrix.postScale(factor, factor, e.x, e.y)
                constrainMatrixBounds()
                imageMatrix = drawMatrix
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setZoomBitmap(bmp: Bitmap) {
        super.setImageBitmap(bmp)
        post {
            resetToFitCenter()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && drawable != null) {
            resetToFitCenter()
        }
    }

    fun resetToFitCenter() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return

        baseMatrix.reset()
        val scale = min(vw / dw, vh / dh)
        val dx = (vw - dw * scale) * 0.5f
        val dy = (vh - dh * scale) * 0.5f
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        drawMatrix.set(baseMatrix)
        currentScale = 1f
        imageMatrix = drawMatrix
    }

    private fun constrainMatrixBounds() {
        val d = drawable ?: return
        displayRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(displayRect)

        val vw = width.toFloat()
        val vh = height.toFloat()
        var deltaX = 0f
        var deltaY = 0f

        if (displayRect.width() <= vw) {
            deltaX = (vw - displayRect.width()) * 0.5f - displayRect.left
        } else if (displayRect.left > 0f) {
            deltaX = -displayRect.left
        } else if (displayRect.right < vw) {
            deltaX = vw - displayRect.right
        }

        if (displayRect.height() <= vh) {
            deltaY = (vh - displayRect.height()) * 0.5f - displayRect.top
        } else if (displayRect.top > 0f) {
            deltaY = -displayRect.top
        } else if (displayRect.bottom < vh) {
            deltaY = vh - displayRect.bottom
        }

        drawMatrix.postTranslate(deltaX, deltaY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = false
                if (currentScale > 1.03f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && currentScale > 1.03f) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    if (abs(dx) > 2f || abs(dy) > 2f) {
                        isDragging = true
                        drawMatrix.postTranslate(dx, dy)
                        constrainMatrixBounds()
                        imageMatrix = drawMatrix
                        lastTouch.set(event.x, event.y)

                        // Eğer yakınlaştırılmış görüntünün en sol/sağ kenarına ulaşıldıysa yatay kaydırmada Pager'a izin ver
                        val atLeftEdge = displayRect.left >= -2f && dx > 6f
                        val atRightEdge = displayRect.right <= width + 2f && dx < -6f
                        if ((atLeftEdge || atRightEdge) && abs(dx) > abs(dy) * 1.5f) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        } else {
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                } else if (event.pointerCount >= 2) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (currentScale <= 1.03f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }
}
