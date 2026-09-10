package com.example.mykeyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        // 🌟 PC側の出力ファイル名に合わせて変更
        private const val DB_NAME = "mydict.db"
        private const val DB_VERSION = 1

        // 🌟 【重要】assets/mydict.db の中身(単語・weight・matrix等)を更新するたびに
        // このバージョンを+1すること！ これを怠ると、PC側で辞書を再ビルドして
        // APKに焼き直しても「端末に既にmydict.dbが存在する」という理由だけで
        // 古いDBがそのまま使われ続けてしまう（実際にこれが原因で、ある回の
        // セッションで辞書修正が何件も端末に反映されないまま放置される事故が
        // 発生した）。DB_VERSION(SQLiteOpenHelperのスキーマバージョン)とは別物で、
        // こちらは「assetsの中身が変わったかどうか」だけを追跡する。
        private const val ASSET_DICT_VERSION = 7
        private const val PREFS_NAME = "KeyboardSettings"
        private const val PREF_KEY_DEPLOYED_VERSION = "deployedDictVersion"
    }

    data class DictEntry(val word: String, val yomi: String, val weight: Int, val lid: Int, val rid: Int)

    private val dbFile: File = context.getDatabasePath(DB_NAME)

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deployedVersion = prefs.getInt(PREF_KEY_DEPLOYED_VERSION, 0)
        // 🌟 初回起動時、または assets の辞書バージョンが上がっている場合は再配置する。
        if (!dbFile.exists() || deployedVersion < ASSET_DICT_VERSION) {
            // 🌟 既存のDB/WAL/SHMを確実に消してから配置し直す(reloadDatabaseと同様の理由)。
            close()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            deployFromExternalOrAssets()
            prefs.edit().putInt(PREF_KEY_DEPLOYED_VERSION, ASSET_DICT_VERSION).apply()
        }
    }

    // 🌟 新規：外部ディレクトリ（adb push先）にファイルがあればそこからコピー、無ければassetsからコピー
    private fun deployFromExternalOrAssets() {
        dbFile.parentFile?.mkdirs()
        val externalFile = File(context.getExternalFilesDir(null), DB_NAME)

        if (externalFile.exists()) {
            externalFile.copyTo(dbFile, overwrite = true)
        } else {
            context.assets.open(DB_NAME).use { inputStream ->
                FileOutputStream(dbFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    // ==========================================
    // 🌟 新規：PCから転送された最新DBを安全に再読み込みする
    // ==========================================
    fun reloadDatabase() {
        // 1. 現在のデータベース接続を完全に閉じる
        close()

        // 2. AndroidのSQLite特有の一時ファイル(WAL/SHM)を削除（これを行わないとDBが壊れます）
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        // 3. 外部領域から内部領域へファイルを上書きコピー
        val externalFile = File(context.getExternalFilesDir(null), DB_NAME)
        if (externalFile.exists()) {
            externalFile.copyTo(dbFile, overwrite = true)
        }

        // 4. 再接続をトリガー（この時 onCreate / onOpen が呼ばれる）
        writableDatabase
    }

    // ==========================================
    // 以降のコードは元のまま（ROMAJI_GLOB_MAP, onCreate, onOpen, getCandidates など）
    // ==========================================
    private val ROMAJI_GLOB_MAP = mapOf(
        "k" to "[かきくけこ]", "s" to "[さしすせそ]", "t" to "[たちつてとっ]", "n" to "[なにぬねのん]",
        "h" to "[はひふへほ]", "m" to "[まみむめも]", "y" to "[やゆよ]", "r" to "[らりるれろ]",
        "w" to "[わをう]", "g" to "[がぎぐげご]", "z" to "[ざじずぜぞ]", "d" to "[だぢづでど]",
        "b" to "[ばびぶべぼ]", "p" to "[ぱぴぷぺぽ]", "c" to "[ち]", "f" to "[ふ]",
        "j" to "[じじゃじゅじょ]", "v" to "[ヴ]",
        "ky" to "き[ゃゅょ]", "sy" to "し[ゃゅょ]", "sh" to "し[ゃゅょ]", "ty" to "ち[ゃゅょ]",
        "ch" to "ち[ゃゅょ]", "ny" to "に[ゃゅょ]", "hy" to "ひ[ゃゅょ]", "my" to "み[ゃゅょ]",
        "ry" to "り[ゃゅょ]", "gy" to "ぎ[ゃゅょ]", "zy" to "じ[ゃゅょ]", "dy" to "ぢ[ゃゅょ]",
        "by" to "び[ゃゅょ]", "py" to "ぴ[ゃゅょ]"
    )

    private fun getGlobPrefixes(hiragana: String, trailingRomaji: String): List<String> {
        if (trailingRomaji.isEmpty()) return listOf("$hiragana*")
        val lowerRomaji = trailingRomaji.lowercase()
        var globStr = ROMAJI_GLOB_MAP[lowerRomaji]
        if (globStr == null && lowerRomaji.length > 1 && lowerRomaji[0] == lowerRomaji[1]) {
            val subGlob = ROMAJI_GLOB_MAP[lowerRomaji.substring(1)] ?: ""
            if (subGlob.isNotEmpty()) globStr = "っ$subGlob"
        }
        if (globStr != null && globStr.contains("[")) {
            val prefix = globStr.substringBefore("[")
            val chars = globStr.substringAfter("[").substringBefore("]")
            return chars.map { "$hiragana$prefix$it*" }
        }
        return listOf("$hiragana*")
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL("""
            CREATE TABLE IF NOT EXISTS user_history (
                word TEXT PRIMARY KEY,
                yomi TEXT,
                use_count INTEGER DEFAULT 1,
                last_used_time INTEGER
            )
        """)
    }

    override fun onOpen(db: SQLiteDatabase?) {
        super.onOpen(db)
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_dictionary_yomi ON dictionary(yomi)")
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_history_yomi ON user_history(yomi)")
        // 🌟 性能改善: 候補確定のたびに走る「次単語予測」(generateCandidates内)が
        // getRidForWord(word=?)とgetPredictionsByLids(lid IN (...))を呼ぶが、
        // word/lid列に索引が無く、確定するたびに辞書全体(約127万行)を
        // フルスキャンしていた。これが「選択を確定させたときの処理が
        // 気持ち遅い」の主因。IF NOT EXISTSなので既存ユーザーも次回起動時に
        // 自動で索引が作られる(DB_VERSIONを上げる必要はない)。
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_dictionary_word ON dictionary(word)")
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_dictionary_lid ON dictionary(lid)")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    fun learnWord(word: String, yomi: String) {
        if (yomi.length <= 1 || !yomi.matches(Regex("^[ぁ-んー]+$"))) return
        val db = writableDatabase
        val currentTime = System.currentTimeMillis()
        try {
            db.execSQL("""
                INSERT INTO user_history (word, yomi, use_count, last_used_time)
                VALUES (?, ?, 1, ?)
                ON CONFLICT(word) DO UPDATE SET 
                    use_count = use_count + 1,
                    last_used_time = ?
            """, arrayOf(word, yomi, currentTime, currentTime))

            if (Math.random() < 0.1) {
                db.execSQL("""
                    DELETE FROM user_history 
                    WHERE word IN (
                        SELECT word FROM user_history 
                        ORDER BY last_used_time ASC, use_count ASC 
                        LIMIT -1 OFFSET 5000
                    )
                """)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getExactMatches(yomi: String): List<DictEntry> {
        val list = java.util.ArrayList<DictEntry>(10)
        readableDatabase.rawQuery("SELECT word, yomi, weight, lid, rid FROM dictionary WHERE yomi = ?", arrayOf(yomi)).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(DictEntry(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4)))
            }
        }
        return list
    }

    fun getCandidates(
        hiragana: String, trailingRomaji: String = "", prevRid: Int = 0, matrix: MatrixManager? = null, limit: Int = 40
    ): List<Pair<String, String>> {
        val finalCandidates = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        val prefixes = getGlobPrefixes(hiragana, trailingRomaji)
        val whereClause = prefixes.joinToString(" OR ") { "yomi GLOB ?" }

        try {
            val historyArgs = (prefixes + limit.toString()).toTypedArray()
            db.rawQuery("""
                SELECT word, yomi FROM user_history 
                WHERE $whereClause 
                ORDER BY last_used_time DESC, use_count DESC 
                LIMIT ?
            """, historyArgs).use { cursor ->
                while (cursor.moveToNext()) {
                    finalCandidates.add(Pair(cursor.getString(0), cursor.getString(1)))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (finalCandidates.size >= limit) return finalCandidates

        val remainingLimit = limit - finalCandidates.size
        val baseDictCandidates = mutableListOf<Pair<Pair<String, String>, Int>>()
        val dictArgs = prefixes.toTypedArray()
        db.rawQuery(
            "SELECT word, yomi, weight, lid, rid FROM dictionary WHERE $whereClause ORDER BY weight ASC LIMIT 100",
            dictArgs
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getString(0)
                val yomi = cursor.getString(1)
                val weight = cursor.getInt(2)
                val lid = cursor.getInt(3)

                if (finalCandidates.any { it.first == word }) continue

                val expectedMinLen = hiragana.length + (if (trailingRomaji.isNotEmpty()) 1 else 0)
                val missingCharCount = kotlin.math.max(0, yomi.length - expectedMinLen)
                val predictionPenalty = missingCharCount * 100

                val connectionCost = if (matrix != null && prevRid != 0) matrix.getConnectionCost(prevRid, lid) else 0
                val finalCost = weight + predictionPenalty + connectionCost
                baseDictCandidates.add(Pair(Pair(word, yomi), finalCost))
            }
        }

        baseDictCandidates.sortBy { it.second }
        val sortedNewCands = baseDictCandidates.map { it.first }.distinctBy { it.first }.take(remainingLimit)
        finalCandidates.addAll(sortedNewCands)

        return finalCandidates
    }

    fun getPredictionsByLids(lids: List<Int>, limit: Int = 15): List<Pair<String, String>> {
        if (lids.isEmpty()) return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        val placeholders = lids.joinToString(",") { "?" }
        val args = lids.map { it.toString() }.toTypedArray()

        readableDatabase.rawQuery(
            "SELECT word, yomi FROM dictionary WHERE lid IN ($placeholders) ORDER BY weight ASC LIMIT $limit",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val w = cursor.getString(0)
                val y = cursor.getString(1)
                if (list.none { it.first == w }) list.add(Pair(w, y))
            }
        }
        return list
    }

    fun getRidForWord(word: String): Int? {
        readableDatabase.rawQuery("SELECT rid FROM dictionary WHERE word = ? ORDER BY weight ASC LIMIT 1", arrayOf(word)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return null
    }

    // 🌟 trailingRomaji: まだ母音が確定していない子音1文字（"きょくしょ" + "t" の "t" 等）。
    // 指定があれば getGlobPrefixes と同じ文字クラスGLOBで絞り込み、まだ入力されていない
    // 母音違いの可能性も含めて予測変換する（例: "きょくしょ"+"t" →「局所体」等）。
    // これにより CandidateManager 側で生の romaji 文字を変換結果の末尾に
    // そのまま連結する（"局所t" のような文字化けした候補になる）必要が無くなる。
    fun getPrefixMatchesForViterbi(hiraganaPrefix: String, trailingRomaji: String = ""): List<DictEntry> {
        val list = mutableListOf<DictEntry>()
        val prefixes = getGlobPrefixes(hiraganaPrefix, trailingRomaji)
        val whereClause = prefixes.joinToString(" OR ") { "yomi GLOB ?" }
        val expectedMinLen = hiraganaPrefix.length + (if (trailingRomaji.isNotEmpty()) 1 else 0)

        readableDatabase.rawQuery(
            "SELECT word, yomi, weight, lid, rid FROM dictionary WHERE $whereClause ORDER BY weight ASC LIMIT 50",
            prefixes.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getString(0)
                val yomi = cursor.getString(1)
                val baseWeight = cursor.getInt(2)
                val lid = cursor.getInt(3)
                val rid = cursor.getInt(4)

                val missingCharCount = kotlin.math.max(0, yomi.length - expectedMinLen)
                val predictionPenalty = missingCharCount * 150
                list.add(DictEntry(word, yomi, baseWeight + predictionPenalty, lid, rid))
            }
        }
        return list
    }
}