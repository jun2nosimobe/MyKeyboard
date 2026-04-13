package com.example.mykeyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import java.io.File

// 🌟 キーボードの見た目（テーマ、色、背景画像）と設定の保存を管理するオブジェクト
class KeyboardThemeManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

    var keyTextColor: Int = prefs.getInt("keyTextColor", Color.BLACK)
        private set

    val bgAlpha: Float
        get() = prefs.getFloat("bgAlpha", 0.4f)

    val bgColorPacked: Int
        get() = prefs.getInt("bgColorPacked", Color.parseColor("#ECECEC"))

    // カスタムキーのテキストを取得
    fun getCustomText(context: Context, buttonId: Int, suffix: String, defaultValue: String): String {
        val idName = try {
            context.resources.getResourceEntryName(buttonId)
        } catch (e: Exception) {
            buttonId.toString()
        }
        return prefs.getString("key_${idName}_$suffix", defaultValue) ?: defaultValue
    }

    // 文字色の反転と保存
    fun toggleKeyTextColor() {
        keyTextColor = if (keyTextColor == Color.BLACK) Color.WHITE else Color.BLACK
        prefs.edit().putInt("keyTextColor", keyTextColor).apply()
    }

    // 背景透過率の保存と適用
    fun setBgAlpha(alpha: Float, keyboardView: View) {
        prefs.edit().putFloat("bgAlpha", alpha).apply()
        keyboardView.findViewById<ImageView>(R.id.keyboard_bg)?.alpha = alpha
    }

    // 背景の明るさ（色）の保存と適用
    fun setBgColorPacked(colorPacked: Int, keyboardView: View) {
        prefs.edit().putInt("bgColorPacked", colorPacked).apply()
        keyboardView.findViewById<RelativeLayout>(R.id.keyboard_root_layout)?.setBackgroundColor(colorPacked)
    }

    // すべてのキーの文字色を更新
    fun updateAllTextColors(keyboardView: View) {
        val controlKeys = listOf(R.id.btn_shift, R.id.btn_space, R.id.btn_delete, R.id.btn_enter, R.id.btn_mode, R.id.btn_comma, R.id.btn_period)
        (KeyDatabase.keys.keys + controlKeys).forEach { id ->
            keyboardView.findViewById<TextView>(id)?.setTextColor(keyTextColor)
        }
    }

    // 背景画像と背景色を再読み込みして適用
    fun reloadBackground(keyboardView: View) {
        val rootLayout = keyboardView.findViewById<RelativeLayout>(R.id.keyboard_root_layout)
        rootLayout?.setBackgroundColor(bgColorPacked)

        val bgImage = keyboardView.findViewById<ImageView>(R.id.keyboard_bg)
        val bgPath = prefs.getString("bgImagePath", null)

        if (bgPath != null) {
            val file = File(bgPath)
            if (file.exists()) {
                bgImage?.setImageURI(Uri.fromFile(file))
                bgImage?.visibility = View.VISIBLE
            } else {
                bgImage?.setImageDrawable(null)
            }
        } else {
            bgImage?.setImageDrawable(null)
        }
        bgImage?.alpha = bgAlpha

        updateAllTextColors(keyboardView)
    }
}