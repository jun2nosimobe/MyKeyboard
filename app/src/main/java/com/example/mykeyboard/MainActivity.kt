package com.example.mykeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mykeyboard.ui.theme.MyKeyboardTheme
import java.io.File
import java.io.FileOutputStream

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
                            matrixManager.reloadMatrix()

                            val dictHelper = DictionaryDatabaseHelper(this@MainActivity)
                            dictHelper.reloadDatabase()

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

    var bgImagePath by remember { mutableStateOf(prefs.getString("bgImagePath", null)) }
    var keyTextColor by remember { mutableStateOf(prefs.getInt("keyTextColor", AndroidColor.BLACK)) }
    var bgAlpha by remember { mutableStateOf(prefs.getFloat("bgAlpha", 0.4f)) }
    var bgColorPacked by remember { mutableStateOf(prefs.getInt("bgColorPacked", AndroidColor.parseColor("#ECECEC"))) }
    var enableLearningBeta by remember { mutableStateOf(prefs.getBoolean("enableLearningBeta", false)) }

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

        // リアルタイム・プレビュー領域
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

                KeyboardButtonPreviewView(
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                    keyTextColor = keyTextColor,
                    bgAlpha = bgAlpha,
                    bgColorPacked = bgColorPacked,
                    bgImagePath = bgImagePath
                )
            }
        }

        // 1. 見た目の設定 (Appearance)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("🎨 見た目のカスタマイズ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

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

                HorizontalDivider()

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

                HorizontalDivider()

                Column {
                    val currentBrightness = AndroidColor.red(bgColorPacked)
                    Text("背景の明るさ (土台の色): $currentBrightness / 255")

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

        // 2. キー配列設定 (Layout)
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

        // 3. 開発者メニュー (Developer)
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

        // ==========================================
        // 🌟 NEW: アプリ説明・サポート・各種リンク集
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("💬 アプリについて & サポート", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Text(
                    text = "数式環境や論文執筆、高度な計算をスムーズに行うための数学特化型IMEです。英語の数学用語サジェスト、TeXコマンド補完、日本語のかな漢字変換パイプラインを搭載しています。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // 大型の外部ウェブリンクボタン
                OutlinedButton(
                    onClick = { openWebUrl(context, "https://forms.gle/XXXXXX") }, // 🌟 実際のGoogleフォームURLへ
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🐛 不具合報告・要望フォームを開く")
                }

                OutlinedButton(
                    onClick = { openWebUrl(context, "https://docs.google.com/spreadsheets/d/XXXXXX") }, // 🌟 実際のスプレッドシートURLへ
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📋 不具合対応・進捗リストを見る")
                }

                HorizontalDivider()

                Text("各種連絡先・各種リンク(一応書いてるだけです. 基本的には上のフォームで報告してください)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 📧 Gmail（メールアプリ起動リンク）
                    ContactLinkItem(
                        iconLabel = "✉️",
                        title = "Gmail",
                        value = "jun2nosimobe57lte@gmail.com", // 🌟 ご自身のメールアドレスへ
                        onClick = { openWebUrl(context, "jun2nosimobe57lte@gmail.com") }
                    )

                    // 🐦 Twitter / X
                    ContactLinkItem(
                        iconLabel = "🐦",
                        title = "Twitter",
                        value = "@jun2nosimobe", // 🌟 アカウント名へ
                        onClick = { openWebUrl(context, "https://x.com/jun2nosimobe") }
                    )

                    ContactLinkItem(
                        iconLabel = "\uD83E\uDD6D",
                        title = "mixi2",
                        value = "@jun2nosimobe", // 🌟 アカウント名へ
                        onClick = { openWebUrl(context, "https://mixi.social/@jun2nosimobe") }
                    )

                    // 💻 GitHub
                    ContactLinkItem(
                        iconLabel = "💻",
                        title = "GitHub Repository",
                        value = "jun2nosimobe/MyKeyboard", // 🌟 リポジトリURLへ
                        onClick = { openWebUrl(context, "https://github.com/jun2nosimobe/MyKeyboard") }
                    )
                }
            }
        }


        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🧪 実験的機能 (Beta)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("入力学習機能 (日本語)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "確定した変換を記憶し、次回の予測候補の優先度を上げます。予期せぬ動作をする可能性があるためβ版として提供しています。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Switch(
                        checked = enableLearningBeta,
                        onCheckedChange = { isChecked ->
                            enableLearningBeta = isChecked
                            prefs.edit().putBoolean("enableLearningBeta", isChecked).apply()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

// 🌟 NEW: 各種リンク用のリストスタイルUIコンポーネント
@Composable
fun ContactLinkItem(
    iconLabel: String,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(iconLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
        Column(modifier = Modifier.weight(1.0f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = ComposeColor.Gray)
        }
        Text("🔗", style = MaterialTheme.typography.bodySmall, color = ComposeColor.LightGray)
    }
}

@Composable
fun KeyboardButtonPreviewView(
    modifier: Modifier = Modifier,
    keyTextColor: Int,
    bgAlpha: Float,
    bgColorPacked: Int,
    bgImagePath: String?
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null)
            view.setOnTouchListener { _, _ -> true }
            view
        },
        update = { view ->
            val rootLayout = view.findViewById<RelativeLayout>(R.id.keyboard_root_layout)
            rootLayout?.setBackgroundColor(bgColorPacked)

            val bgImage = view.findViewById<ImageView>(R.id.keyboard_bg)
            if (bgImagePath != null && File(bgImagePath).exists()) {
                bgImage?.setImageURI(Uri.fromFile(File(bgImagePath)))
                bgImage?.visibility = android.view.View.VISIBLE
            } else {
                bgImage?.setImageDrawable(null)
            }
            bgImage?.alpha = bgAlpha

            val controlKeys = listOf(R.id.btn_shift, R.id.btn_space, R.id.btn_delete, R.id.btn_enter, R.id.btn_mode, R.id.btn_comma, R.id.btn_period)
            (KeyDatabase.keys.keys + controlKeys).forEach { id ->
                view.findViewById<TextView>(id)?.setTextColor(keyTextColor)
            }
        }
    )
}

// 🌟 修正: Webや外部アプリへのインテントを安全に発行する関数
// 📄 MainActivity.kt の一番下にある関数を修正
fun openWebUrl(context: Context, url: String) {
    try {
        // 🌟 mailto: から始まる場合は ACTION_SENDTO を使用し、それ以外は ACTION_VIEW を使う
        val intent = if (url.startsWith("mailto:")) {
            Intent(Intent.ACTION_SENDTO, Uri.parse(url))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        val errorMsg = if (url.startsWith("mailto:")) "メールアプリを開けませんでした" else "ブラウザを開けませんでした"
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
    }
}