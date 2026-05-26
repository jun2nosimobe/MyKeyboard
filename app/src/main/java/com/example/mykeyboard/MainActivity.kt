package com.example.mykeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mykeyboard.ui.theme.MyKeyboardTheme
import java.io.File
import java.io.FileOutputStream
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri

class MainActivity : ComponentActivity() {

    private lateinit var matrixManager: MatrixManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        matrixManager = MatrixManager(this)

        setContent {
            MyKeyboardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UnifiedSettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        onReloadRequested = {
                            // 1. マトリクスの再読み込み
                            matrixManager.reloadMatrix()

                            // 2. 日本語辞書の再読み込み
                            val dictHelper = DictionaryDatabaseHelper(this@MainActivity)
                            dictHelper.reloadDatabase()

                            // 3. 🌟 英語辞書の再読み込み
                            val engDictHelper = EnglishDictionaryHelper(this@MainActivity)
                            engDictHelper.reloadDatabase()

                            Toast.makeText(this@MainActivity, "すべての辞書・マトリクスを再読み込みしました！", Toast.LENGTH_SHORT).show()
                        },
                        onOpenKeyEditor = {
                            startActivity(Intent(this, KeyEditorActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UnifiedSettingsScreen(
    modifier: Modifier = Modifier,
    onReloadRequested: () -> Unit,
    onOpenKeyEditor: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)

    // --- 💾 状態管理 (SharedPreferences と同期) ---
    var bgImagePath by remember { mutableStateOf(prefs.getString("bgImagePath", null)) }
    var keyTextColor by remember { mutableIntStateOf(prefs.getInt("keyTextColor", AndroidColor.BLACK)) }
    var bgAlpha by remember { mutableFloatStateOf(prefs.getFloat("bgAlpha", 0.4f)) }
    var bgColorPacked by remember { mutableIntStateOf(prefs.getInt("bgColorPacked", AndroidColor.parseColor("#ECECEC"))) }

    // --- 🖼️ 画像ピッカーの設定 ---
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val outFile = File(context.filesDir, "custom_bg.jpg")
                val outputStream = FileOutputStream(outFile)

                inputStream?.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val path = outFile.absolutePath
                prefs.edit().putString("bgImagePath", path).apply()
                bgImagePath = path
                Toast.makeText(context, "背景画像を保存しました", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "保存エラー: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("キーボード設定", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ==========================================
        // 🌟 NEW: リアルタイム・プレビュー領域
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "プレビュー (実際の入力はできません)",
                    style = MaterialTheme.typography.labelMedium,
                    color = ComposeColor.Gray,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                // 🌟 ここで先ほど作った Composable を呼び出す
                KeyboardPreviewView(
                    modifier = Modifier.fillMaxWidth().height(250.dp), // 実際のキーボードの高さに合わせて調整
                    keyTextColor = keyTextColor,
                    bgAlpha = bgAlpha,
                    bgColorPacked = bgColorPacked,
                    bgImagePath = bgImagePath
                )
            }
        }

        // ==========================================
        // 🎨 1. 見た目の設定 (Appearance)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("🎨 見た目のカスタマイズ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                // 🌑 文字色の反転
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("キーの文字色")
                    Button(onClick = {
                        val newColor = if (keyTextColor == AndroidColor.BLACK) AndroidColor.WHITE else AndroidColor.BLACK
                        keyTextColor = newColor
                        prefs.edit().putInt("keyTextColor", newColor).apply()
                    }) {
                        Text(if (keyTextColor == AndroidColor.BLACK) "黒 (タップで白に変更)" else "白 (タップで黒に変更)")
                    }
                }

                Divider()

                // 🖼️ 背景画像
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("背景画像")
                        Text(if (bgImagePath != null) "設定済み" else "未設定", style = MaterialTheme.typography.bodySmall, color = ComposeColor.Gray)
                    }
                    Button(onClick = {
                        imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text("画像を選択")
                    }
                }

                // 🌫️ 透過率スライダー
                Column {
                    Text("画像の透過率: ${(bgAlpha * 100).toInt()}%")
                    Slider(
                        value = bgAlpha,
                        onValueChange = {
                            bgAlpha = it
                            prefs.edit().putFloat("bgAlpha", it).apply()
                        },
                        valueRange = 0f..1f
                    )
                }

                Divider()

                // 🔆 背景の明るさ（土台の色）スライダー
                Column {
                    val currentBrightness = AndroidColor.red(bgColorPacked)
                    Text("背景の明るさ (土台の色): $currentBrightness / 255")

                    // 色のプレビューボックス
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ComposeColor(currentBrightness, currentBrightness, currentBrightness))
                    )

                    Slider(
                        value = currentBrightness.toFloat(),
                        onValueChange = {
                            val v = it.toInt()
                            val newColor = AndroidColor.rgb(v, v, v)
                            bgColorPacked = newColor
                            prefs.edit().putInt("bgColorPacked", newColor).apply()
                        },
                        valueRange = 0f..255f
                    )
                }

                // ♻️ リセットボタン
                OutlinedButton(
                    onClick = {
                        prefs.edit().remove("bgImagePath").apply()
                        val defaultColor = AndroidColor.parseColor("#ECECEC")
                        prefs.edit().putInt("bgColorPacked", defaultColor).apply()

                        bgImagePath = null
                        bgColorPacked = defaultColor
                        Toast.makeText(context, "背景設定をリセットしました", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("見た目をデフォルトにリセット")
                }
            }
        }

        // ==========================================
        // ⌨️ 2. キー配列設定 (Layout)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("⌨️ キーレイアウト", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("各キーの入力文字や長押し時の候補を編集します。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = onOpenKeyEditor,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("キー配列詳細設定を開く")
                }
            }
        }

        // ==========================================
        // 🛠️ 3. 開発者メニュー (Developer)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🛠️ 開発者メニュー", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                val deployPath = context.getExternalFilesDir(null)?.absolutePath ?: "不明"
                Text("PCからのファイル転送先 (adb push):", style = MaterialTheme.typography.titleSmall)
                Text(deployPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)

                Button(
                    onClick = onReloadRequested,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("最新の辞書・マトリクスを再読込")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}


@Composable
fun KeyboardPreviewView(
    modifier: Modifier = Modifier,
    keyTextColor: Int,
    bgAlpha: Float,
    bgColorPacked: Int,
    bgImagePath: String?
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // 🌟 修正：R.layout.keyboard_layout を R.layout.keyboard_view に変更
            val view = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null)

            // プレビューなので、誤操作を防ぐためにタッチイベントを無効化
            view.setOnTouchListener { _, _ -> true }

            view
        },
        update = { view ->
            // 🌟 スライダーなどの状態（State）が変わるたびにここが自動で呼ばれ、見た目が更新される

            // 1. 背景の明るさ（土台の色）を適用
            val rootLayout = view.findViewById<RelativeLayout>(R.id.keyboard_root_layout)
            rootLayout?.setBackgroundColor(bgColorPacked)

            // 2. 背景画像と透過率を適用
            val bgImage = view.findViewById<ImageView>(R.id.keyboard_bg)
            if (bgImagePath != null && File(bgImagePath).exists()) {
                bgImage?.setImageURI(Uri.fromFile(File(bgImagePath)))
                bgImage?.visibility = android.view.View.VISIBLE
            } else {
                bgImage?.setImageDrawable(null)
            }
            bgImage?.alpha = bgAlpha

            // 3. 文字色をすべてのキーに適用
            // （※KeyDatabaseのIDリストを使って一括更新するか、主要なキーIDを直接指定します）
            val controlKeys = listOf(R.id.btn_shift, R.id.btn_space, R.id.btn_delete, R.id.btn_enter, R.id.btn_mode, R.id.btn_comma, R.id.btn_period)
            (KeyDatabase.keys.keys + controlKeys).forEach { id ->
                view.findViewById<TextView>(id)?.setTextColor(keyTextColor)
            }
        }
    )
}