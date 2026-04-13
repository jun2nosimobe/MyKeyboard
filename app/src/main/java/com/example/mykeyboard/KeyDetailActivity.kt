package com.example.mykeyboard

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class KeyDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyId = intent.getIntExtra("key_id", -1)
        val data = KeyDatabase.keys[keyId] ?: return

        // 🌟 整数IDのズレバグ対策（ThemeManagerと同じように文字列名で保存する）
        val idName = try {
            resources.getResourceEntryName(keyId)
        } catch (e: Exception) {
            keyId.toString()
        }

        val prefs = getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // ヘッダー
        container.addView(TextView(this).apply {
            text = "キー詳細編集: ${data.normal}"
            textSize = 22f
            setPadding(0, 0, 0, 20)
        })

        // 🌟 ユーザーへの案内表示を追加
        container.addView(TextView(this).apply {
            text = "※ 黒板太字や筆記体、全角文字などの特殊フォントは、ここで設定した「通常時」の文字から自動的に計算・変換されます。"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0")) // 少し青っぽい色で目立たせる
            setPadding(0, 0, 0, 40)
        })

        // 各モードの入力欄を作成するヘルパー関数
        fun createEditField(label: String, prefKey: String, defaultValue: String): EditText {
            container.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.GRAY) })
            val et = EditText(this).apply {
                setText(prefs.getString(prefKey, defaultValue))
                setPadding(0, 10, 0, 30)
            }
            container.addView(et)
            return et
        }

        // 🌟 KeyData のスリム化に合わせて項目を削減
        val editNormal   = createEditField("通常時 (Normal)", "key_${idName}_normal", data.normal)
        val editShift    = createEditField("シフト時 (Shift)", "key_${idName}_normalShift", data.normalShift)
        val editLPNormal = createEditField("長押し・通常 (スペース区切り)", "key_${idName}_longPressNormal", data.longPressNormal.joinToString(" "))
        val editLPShift  = createEditField("長押し・シフト (スペース区切り)", "key_${idName}_longPressShift", data.longPressShift.joinToString(" "))

        // 保存ボタン
        val saveBtn = Button(this).apply {
            text = "このキーの設定を保存"
            setOnClickListener {
                prefs.edit().apply {
                    // 🌟 保存キーも idName を使う
                    putString("key_${idName}_normal", editNormal.text.toString())
                    putString("key_${idName}_normalShift", editShift.text.toString())
                    putString("key_${idName}_longPressNormal", editLPNormal.text.toString())
                    putString("key_${idName}_longPressShift", editLPShift.text.toString())
                    apply()
                }
                Toast.makeText(this@KeyDetailActivity, "保存しました", Toast.LENGTH_SHORT).show()
                finish() // 前の画面に戻る
            }
        }
        container.addView(saveBtn)

        root.addView(container)
        setContentView(root)
    }
}