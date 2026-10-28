package com.alphainventor.filemanager.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 (file_media_grid_item.xml) içindeki
 * com.alphainventor.filemanager.widget.SquareImageView karşılığı 1:1 kare kapsayıcı.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
