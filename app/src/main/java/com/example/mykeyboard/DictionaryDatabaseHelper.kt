package com.example.mykeyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "mykanji.db"
        private const val DB_VERSION = 1
    }

    // 🌟 修正：JapaneseConverter.kt と名前を合わせるため DictEntry に変更
    data class DictEntry(val word: String, val weight: Int, val lid: Int, val rid: Int)

    private val dbFile: File = context.getDatabasePath(DB_NAME)

    init {
        if (!dbFile.exists()) {
            copyDatabaseFromAssets()
        }
    }

    private fun copyDatabaseFromAssets() {
        dbFile.parentFile?.mkdirs()
        context.assets.open(DB_NAME).use { inputStream ->
            FileOutputStream(dbFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase?) {}
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

    // ==========================================
    // 1. 完全一致検索（Viterbiが毎文字呼ぶので超重要）
    // ==========================================
    fun getExactMatches(yomi: String): List<DictEntry> {
        // 🌟 高速化：ArrayListの初期容量を指定し、配列拡張のメモリアロケーションを防ぐ
        val list = java.util.ArrayList<DictEntry>(10)
        val db = readableDatabase

        // Viterbiは超高速に回るため、純粋な完全一致のみを抽出
        db.rawQuery("SELECT word, weight, lid, rid FROM dictionary WHERE yomi = ?", arrayOf(yomi)).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(DictEntry(
                    cursor.getString(0),
                    cursor.getInt(1),
                    cursor.getInt(2),
                    cursor.getInt(3)
                ))
            }
        }
        return list
    }

    // ==========================================
    // 2. 予測変換（サジェストバー用）
    // ==========================================
    fun getCandidates(hiragana: String, limit: Int = 40): List<String> {
        // 🌟 高速化1：containsチェックが O(1)（一瞬）で終わる LinkedHashSet に変更
        // 順序も保持されるため、予測変換の並び順が崩れません。
        val candidates = java.util.LinkedHashSet<String>(limit)
        val db = readableDatabase

        try {
            // 🌟 高速化2：重い CASE ソートを排除し、インデックスが100%効く「2段構え検索」に変更

            // ① まず「完全一致」だけを検索（一瞬で終わる）
            db.rawQuery(
                "SELECT word FROM dictionary WHERE yomi = ? ORDER BY weight ASC LIMIT ?",
                arrayOf(hiragana, limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates.add(cursor.getString(0))
                }
            }

            // ② リミットに達していなければ「前方一致（ただし完全一致を除く）」を追加検索
            if (candidates.size < limit) {
                val remainingLimit = limit - candidates.size
                db.rawQuery(
                    "SELECT word FROM dictionary WHERE yomi LIKE ? AND yomi != ? ORDER BY weight ASC LIMIT ?",
                    arrayOf("$hiragana%", hiragana, remainingLimit.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        candidates.add(cursor.getString(0))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return candidates.toList()
    }
}