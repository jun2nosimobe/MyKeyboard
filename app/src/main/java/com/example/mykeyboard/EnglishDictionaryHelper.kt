package com.example.mykeyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream

class EnglishDictionaryHelper(private val context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "eng_dict.db" // 🌟 完全に独立したファイル名
        private const val DB_VERSION = 1

        // 🌟 【重要】assets/eng_dict.db を更新したらこちらも+1すること
        // (DictionaryDatabaseHelper.ASSET_DICT_VERSIONと同じ理由・同じ仕組み)。
        private const val ASSET_DICT_VERSION = 2
        private const val PREFS_NAME = "KeyboardSettings"
        private const val PREF_KEY_DEPLOYED_VERSION = "deployedEngDictVersion"
    }

    private val dbFile: File = context.getDatabasePath(DB_NAME)

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deployedVersion = prefs.getInt(PREF_KEY_DEPLOYED_VERSION, 0)
        if (!dbFile.exists() || deployedVersion < ASSET_DICT_VERSION) {
            close()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            deployFromExternalOrAssets()
            prefs.edit().putInt(PREF_KEY_DEPLOYED_VERSION, ASSET_DICT_VERSION).apply()
        }
    }

    private fun deployFromExternalOrAssets() {
        dbFile.parentFile?.mkdirs()
        val externalFile = File(context.getExternalFilesDir(null), DB_NAME)

        if (externalFile.exists()) {
            externalFile.copyTo(dbFile, overwrite = true)
        } else {
            // assets にデフォルトを置く場合はここを通る
            try {
                context.assets.open(DB_NAME).use { inputStream ->
                    FileOutputStream(dbFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                // assetsに無くても、空のDBが onCreate で作られるので問題なし
            }
        }
    }

    // 🌟 英語辞書専用の爆速リロード機能
    fun reloadDatabase() {
        // 1. まず現在開いているデータベースの接続を完全に閉じる（超重要！）
        close()

        // 2. PCから adb push された外部ストレージのファイルパス
        val externalFile = File(context.getExternalFilesDir(null), "eng_dict.db")

        // 3. Androidアプリが実際に読み込む内部データベースのパス
        val internalDbFile = context.getDatabasePath("eng_dict.db")

        if (externalFile.exists()) {
            try {
                // 内部データベースを上書きコピー
                externalFile.copyTo(internalDbFile, overwrite = true)
                // コピー成功後、次に検索が走ったときに新しいDBで開かれるようになります
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase?) {
        // 英語サジェストに必要な最小限のテーブル構造
        db?.execSQL("""
            CREATE TABLE IF NOT EXISTS english_math_dictionary (
                word TEXT PRIMARY KEY,
                type INTEGER, -- 0: 英単語, 1: TeXコマンド
                weight INTEGER
            )
        """)
    }

    override fun onOpen(db: SQLiteDatabase?) {
        super.onOpen(db)
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_english_word ON english_math_dictionary(word)")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

    // ==========================================
    // 🌟 前方一致サジェストクエリ
    // ==========================================
    fun getEnglishSuggestions(prefix: String, limit: Int = 10): List<String> {
        if (prefix.isEmpty()) return emptyList()

        val list = mutableListOf<String>()
        val db = readableDatabase

        // バックスラッシュで始まる場合はTeXコマンド(type=1)、それ以外は英単語(type=0)
        val isTexCommand = prefix.startsWith("\\")
        val targetType = if (isTexCommand) 1 else 0

        // 🌟 TeXは大文字小文字を区別する(\Ga と打ったら \gamma ではなく \Gamma)のでGLOB。
        // 英単語は区別しない(表示は打ち方に合わせてCandidateManagerが変える)のでLIKE
        val query = if (isTexCommand)
            "SELECT word FROM english_math_dictionary WHERE type = ? AND word GLOB ? ORDER BY weight ASC LIMIT ?"
        else
            "SELECT word FROM english_math_dictionary WHERE type = ? AND word LIKE ? ORDER BY weight ASC LIMIT ?"
        val pattern = if (isTexCommand) "$prefix*" else "$prefix%"

        db.rawQuery(query, arrayOf(targetType.toString(), pattern, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0))
            }
        }
        // 🌟 打った文字列と一致する語が上位limit件の外にあっても候補に入れる(先頭に置くのはCandidateManager)
        if (list.none { it.equals(prefix, ignoreCase = !isTexCommand) }) {
            val exactQuery = if (isTexCommand)
                "SELECT word FROM english_math_dictionary WHERE type = ? AND word = ? LIMIT 1"
            else
                "SELECT word FROM english_math_dictionary WHERE type = ? AND word = ? COLLATE NOCASE LIMIT 1"
            db.rawQuery(exactQuery, arrayOf(targetType.toString(), prefix)).use { cursor ->
                if (cursor.moveToFirst()) list.add(0, cursor.getString(0))
            }
        }
        return list
    }
}