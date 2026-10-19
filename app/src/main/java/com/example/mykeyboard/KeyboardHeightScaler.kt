package com.example.mykeyboard

import android.content.SharedPreferences
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup

// keyboard_view.xml の全キー行(+候補欄)は @dimen/mk_row_height_normal / _flick の
// どちらかを高さに使っている。生成直後(=まだ拡縮前)にその基準pxを持つViewを記録し、
// apply(scale)のたびに「基準px × scale」を設定し直す。基準値を保持するので、
// スライダーを動かして何度呼んでも倍率が累積しない。
class KeyboardHeightScaler(root: View, resources: Resources) {
    companion object {
        // 高さの倍率は日本語モード用と、それ以外(英語・数学文字の各モード)用で別に持つ
        const val PREF_JAPANESE = "keyboardHeightScaleJa"
        const val PREF_OTHER = "keyboardHeightScaleEn"
        private const val PREF_OLD = "keyboardHeightScale" // 分ける前の共通の値。未設定時の初期値に使う

        fun load(prefs: SharedPreferences, japanese: Boolean): Float =
            prefs.getFloat(if (japanese) PREF_JAPANESE else PREF_OTHER, prefs.getFloat(PREF_OLD, 1.0f))
    }

    private val baseHeights = HashMap<View, Int>()

    init {
        val normalPx = resources.getDimensionPixelSize(R.dimen.mk_row_height_normal)
        val flickPx = resources.getDimensionPixelSize(R.dimen.mk_row_height_flick)

        fun collect(view: View) {
            val h = view.layoutParams?.height
            if (h != null && (h == normalPx || h == flickPx)) baseHeights[view] = h
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(root)
    }

    fun apply(scale: Float) {
        for ((view, base) in baseHeights) {
            val lp = view.layoutParams ?: continue
            lp.height = (base * scale).toInt()
            view.layoutParams = lp
        }
    }
}
