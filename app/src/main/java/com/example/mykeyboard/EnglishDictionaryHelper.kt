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
    }

    private val dbFile: File = context.getDatabasePath(DB_NAME)

    init {
        if (!dbFile.exists()) {
            deployFromExternalOrAssets()
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
        close()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val externalFile = File(context.getExternalFilesDir(null), DB_NAME)
        if (externalFile.exists()) {
            externalFile.copyTo(dbFile, overwrite = true)
        }
        writableDatabase
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

        val query = "SELECT word FROM english_math_dictionary WHERE type = ? AND word LIKE ? ORDER BY weight ASC LIMIT ?"
        val likePrefix = "$prefix%"

        db.rawQuery(query, arrayOf(targetType.toString(), likePrefix, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0))
            }
        }
        return list
    }
}