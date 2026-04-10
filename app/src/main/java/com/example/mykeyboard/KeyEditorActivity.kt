package com.example.mykeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.view.View // 🌟 これが必要

class KeyEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val header = TextView(this).apply {
            text = "キー設定一覧"
            textSize = 20f
            setPadding(40, 40, 40, 40)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            elevation = 4f
        }
        root.addView(header)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val prefs = getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

        // 全キーをループして一覧を生成
        for ((id, data) in KeyDatabase.keys) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(30, 40, 30, 40)
                setBackgroundResource(android.R.drawable.list_selector_background)
                gravity = Gravity.CENTER_VERTICAL

                // 🌟 タップしたら詳細編集画面へ
                setOnClickListener {
                    val intent = Intent(this@KeyEditorActivity, KeyDetailActivity::class.java)
                    intent.putExtra("key_id", id) // どのキーを編集するかIDを渡す
                    startActivity(intent)
                }
            }

            // メインの文字 (a, b, 0など)
            val mainChar = TextView(this).apply {
                // 保存された値があればそれを表示、なければデフォルト
                text = prefs.getString("key_${id}_normal", data.normal)
                textSize = 24f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            // プレビュー（薄く並ぶ記号類）
            val previewText = TextView(this).apply {
                val s = prefs.getString("key_${id}_normalShift", data.normalShift)
                val bb = prefs.getString("key_${id}_blackboard", data.blackboard)
                val lp = prefs.getString("key_${id}_longPress", data.longPressOptions.joinToString(" "))
                text = "Shift: $s  BB: $bb  長押し: $lp"
                textSize = 12f
                setTextColor(Color.GRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            itemLayout.addView(mainChar)
            itemLayout.addView(previewText)
            container.addView(itemLayout)

            // 区切り線
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(Color.LTGRAY)
            })
        }

        scrollView.addView(container)
        root.addView(scrollView)
        setContentView(root)
    }

    // 🌟 戻ってきた時に一覧を更新する
    override fun onResume() {
        super.onResume()
        // 本来はRecyclerViewを使うべきですが、今回はシンプルに再描画を促すためにonCreateを再度呼ぶか、画面を閉じて開き直す形になります。
        // ここでは一番簡単な「画面を開き直した時に最新になる」挙動になります。
    }
}