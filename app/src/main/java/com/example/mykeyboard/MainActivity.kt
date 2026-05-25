package com.example.mykeyboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mykeyboard.ui.theme.MyKeyboardTheme

class MainActivity : ComponentActivity() {

    // 🌟 マネージャーのインスタンス（本来は DI や Singleton で IME サービスと共有すべきです）
    private lateinit var matrixManager: MatrixManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        matrixManager = MatrixManager(this)

        setContent {
            MyKeyboardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        onReloadRequested = {
                            // 1. マトリクスの再読み込み
                            matrixManager.reloadMatrix()

                            // 2. 辞書データベースの再読み込み
                            // （DictionaryDatabaseHelper のインスタンスを作成して呼び出す）
                            val dictHelper = DictionaryDatabaseHelper(this@MainActivity)
                            dictHelper.reloadDatabase()

                            Toast.makeText(this@MainActivity, "辞書とマトリクスを再読み込みしました！", Toast.LENGTH_SHORT).show()
                        },
                        onOpenKeyEditor = {
                            // 🌟 キー設定一覧画面へ遷移
                            startActivity(Intent(this, KeyEditorActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onReloadRequested: () -> Unit,
    onOpenKeyEditor: () -> Unit
) {
    val context = LocalContext.current
    // 外部ファイルのデプロイ先パスを取得
    val deployPath = context.getExternalFilesDir(null)?.absolutePath ?: "不明"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "IME 管理パネル",
            style = MaterialTheme.typography.headlineMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("PCからのファイル転送先 (adb push):", style = MaterialTheme.typography.titleSmall)
                Text(deployPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        Button(
            onClick = onReloadRequested,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("最新の辞書・マトリクスを再読込")
        }

        Button(
            onClick = onOpenKeyEditor,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("キーレイアウト詳細設定を開く")
        }
    }
}