package com.example.mykeyboard

import android.content.Context
import android.content.Intent // 🌟 画面遷移に必要
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val prefs = getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

        // --- 🖼️ 背景画像選択の設定 ---
        val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                try {
                    // 選択された画像をアプリ専用フォルダにコピーして永続化
                    val inputStream = contentResolver.openInputStream(uri)
                    val outFile = File(filesDir, "custom_bg.jpg")
                    val outputStream = FileOutputStream(outFile)

                    inputStream?.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }

                    // ファイルのパスを保存
                    prefs.edit().putString("bgImagePath", outFile.absolutePath).apply()
                    Toast.makeText(this, "背景画像を保存しました", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存エラー: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 画像選択ボタン
        findViewById<Button>(R.id.btn_select_image).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // --- 🌑 背景の明るさ（土台の色）の設定 ---
        val colorPreview = findViewById<View>(R.id.view_color_preview)
        val colorBar = findViewById<SeekBar>(R.id.seekbar_bg_color)

        val savedColor = prefs.getInt("bgColorPacked", Color.parseColor("#ECECEC"))
        colorPreview?.setBackgroundColor(savedColor)
        colorBar?.progress = Color.red(savedColor)

        colorBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newColor = Color.rgb(progress, progress, progress)
                colorPreview?.setBackgroundColor(newColor)
                prefs.edit().putInt("bgColorPacked", newColor).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- ♻️ リセットボタンの設定 ---
        findViewById<Button>(R.id.btn_reset_bg).setOnClickListener {
            prefs.edit().remove("bgImagePath").apply()
            val defaultColor = Color.parseColor("#ECECEC")
            prefs.edit().putInt("bgColorPacked", defaultColor).apply()
            colorBar?.progress = Color.red(defaultColor)
            colorPreview?.setBackgroundColor(defaultColor)

            Toast.makeText(this, "背景設定をリセットしました", Toast.LENGTH_SHORT).show()
        }

        val btnKeyEditor = findViewById<Button>(R.id.btn_open_key_editor)
        btnKeyEditor.setOnClickListener {
            // キー一覧画面（KeyEditorActivity）へ遷移
            val intent = Intent(this, KeyEditorActivity::class.java)
            startActivity(intent)
        }
    }
}