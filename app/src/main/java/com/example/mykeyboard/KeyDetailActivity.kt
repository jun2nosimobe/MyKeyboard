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

        val prefs = getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // ヘッダー
        container.addView(TextView(this).apply { text = "キー詳細編集: ${data.normal}"; textSize = 22f; setPadding(0,0,0,40) })

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
        val editNormal = createEditField("通常時 (Normal)", "key_${keyId}_normal", data.normal)
        val editShift  = createEditField("シフト時 (Shift)", "key_${keyId}_normalShift", data.normalShift)
        val editBB     = createEditField("黒板太字 (Blackboard)", "key_${keyId}_blackboard", data.blackboard)
        val editBBShift = createEditField("黒板太字・シフト", "key_${keyId}_blackboardShift", data.blackboardShift)
        val editGreek  = createEditField("ギリシャ文字 (Greek)", "key_${keyId}_greek", data.greek)
        val editGreekShift = createEditField("ギリシャ文字・シフト", "key_${keyId}_greekShift", data.greekShift)
        val editFraktur = createEditField("ドイツ文字 (Fraktur)", "key_${keyId}_fraktur", data.fraktur)
        val editFrakturShift = createEditField("ドイツ文字・シフト", "key_${keyId}_frakturShift", data.frakturShift)
        val editScript = createEditField("筆記体 (Script)", "key_${keyId}_script", data.mathscript)
        val editScriptShift = createEditField("筆記体・シフト", "key_${keyId}_scriptShift", data.mathscriptShift)
        val editSymbol = createEditField("記号 (MathSymbol)", "key_${keyId}_symbol", data.mathsymbol) // 🌟 追加
        val editSymbolShift = createEditField("記号・シフト時", "key_${keyId}_symbolShift", data.mathsymbolShift)// 変更後 🌟
        val editLPNormal = createEditField("長押し・通常 (スペース区切り)", "key_${keyId}_longPressNormal", data.longPressNormal.joinToString(" "))
        val editLPShift  = createEditField("長押し・シフト (スペース区切り)", "key_${keyId}_longPressShift", data.longPressShift.joinToString(" "))
        // 保存ボタン
        val saveBtn = Button(this).apply {
            text = "このキーの設定を保存"
            setOnClickListener {
                prefs.edit().apply {
                    putString("key_${keyId}_normal", editNormal.text.toString())
                    putString("key_${keyId}_normalShift", editShift.text.toString())
                    putString("key_${keyId}_blackboard", editBB.text.toString())
                    putString("key_${keyId}_blackboardShift", editBBShift.text.toString())
                    putString("key_${keyId}_greek", editGreek.text.toString())
                    putString("key_${keyId}_greekShift", editGreekShift.text.toString())
                    putString("key_${keyId}_fraktur", editFraktur.text.toString())
                    putString("key_${keyId}_frakturShift", editFrakturShift.text.toString())
                    putString("key_${keyId}_script", editScript.text.toString())
                    putString("key_${keyId}_scriptShift", editScriptShift.text.toString())
                    putString("key_${keyId}_symbol", editSymbol.text.toString())
                    putString("key_${keyId}_symbolShift", editSymbolShift.text.toString())
                    putString("key_${keyId}_longPressNormal", editLPNormal.text.toString())
                    putString("key_${keyId}_longPressShift", editLPShift.text.toString())
                    apply()
                }
                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                finish() // 前の画面に戻る
            }
        }
        container.addView(saveBtn)

        root.addView(container)
        setContentView(root)
    }
}