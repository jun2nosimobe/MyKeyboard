package com.example.mykeyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "mykanji.db"
        private const val DB_VERSION = 1
    }


    // 🌟 Dictionaryの戻り値に yomi も含めるように拡張（ペナルティ計算のため）
    data class DictEntry(val word: String, val yomi: String, val weight: Int, val lid: Int, val rid: Int)

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

    // ==========================================
    // 🌟 修正版：未確定ローマ字から、精緻なGLOBパターンへの完全マッピング
    // ==========================================
    private val ROMAJI_GLOB_MAP = mapOf(
        // 1文字の未確定子音
        "k" to "[かきくけこ]",
        "s" to "[さしすせそ]",
        "t" to "[たちつてとっ]",
        "n" to "[なにぬねのん]",
        "h" to "[はひふへほ]",
        "m" to "[まみむめも]",
        "y" to "[やゆよ]",
        "r" to "[らりるれろ]",
        "w" to "[わをう]",
        "g" to "[がぎぐげご]",
        "z" to "[ざじずぜぞ]",
        "d" to "[だぢづでど]",
        "b" to "[ばびぶべぼ]",
        "p" to "[ぱぴぷぺぽ]",
        "c" to "[ち]", // chi, cha用
        "f" to "[ふ]",
        "j" to "[じじゃじゅじょ]",
        "v" to "[ヴ]",

        // 🌟 2文字の未確定子音（拗音など）
        "ky" to "き[ゃゅょ]",  // 必ず「き」の次に「ゃ,ゅ,ょ」が来ることを保証！
        "sy" to "し[ゃゅょ]",
        "sh" to "し[ゃゅょ]",
        "ty" to "ち[ゃゅょ]",
        "ch" to "ち[ゃゅょ]",
        "ny" to "に[ゃゅょ]",
        "hy" to "ひ[ゃゅょ]",
        "my" to "み[ゃゅょ]",
        "ry" to "り[ゃゅょ]",
        "gy" to "ぎ[ゃゅょ]",
        "zy" to "じ[ゃゅょ]",
        "dy" to "ぢ[ゃゅょ]",
        "by" to "び[ゃゅょ]",
        "py" to "ぴ[ゃゅょ]"
    )

    private fun getGlobPrefixes(hiragana: String, trailingRomaji: String): List<String> {
        if (trailingRomaji.isEmpty()) return listOf("$hiragana*")

        val lowerRomaji = trailingRomaji.lowercase()
        var globStr = ROMAJI_GLOB_MAP[lowerRomaji]

        // 促音（ttなど）の処理
        if (globStr == null && lowerRomaji.length > 1 && lowerRomaji[0] == lowerRomaji[1]) {
            val subGlob = ROMAJI_GLOB_MAP[lowerRomaji.substring(1)] ?: ""
            if (subGlob.isNotEmpty()) globStr = "っ$subGlob"
        }

        if (globStr != null && globStr.contains("[")) {
            // 例: "き[ゃゅょ]" -> prefix="き", chars="ゃゅょ"
            val prefix = globStr.substringBefore("[")
            val chars = globStr.substringAfter("[").substringBefore("]")

            // 🌟 ここで「き* OR きゅ* OR きょ*」のように確実にインデックスが効くリストに展開！
            return chars.map { "$hiragana$prefix$it*" }
        }

        return listOf("$hiragana*")
    }
    override fun onCreate(db: SQLiteDatabase?) {
        // 🌟 新規：ユーザーの入力履歴を保存するテーブルを作成
        // ON CONFLICT(word) で簡単にカウントアップできるように word を PRIMARY KEY にします
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
        // 🌟 DBを開くたびに確認し、無ければインデックスを作成する（超高速化）
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_dictionary_yomi ON dictionary(yomi)")
        db?.execSQL("CREATE INDEX IF NOT EXISTS idx_history_yomi ON user_history(yomi)")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // 既存のDBがある場合、アップグレード時にもテーブルを作るように保証
        onCreate(db)
    }
    // ==========================================
    // 🌟 学習機能（ユーザー履歴の記録 ＋ 自動忘却）
    // ==========================================
    fun learnWord(word: String, yomi: String) {
        // 短すぎる単語（1文字）や、明らかにおかしい文字列（英数字混じり等）は最初から学習させない（タイポ防止）
        if (yomi.length <= 1 || !yomi.matches(Regex("^[ぁ-んー]+$"))) return

        val db = writableDatabase
        val currentTime = System.currentTimeMillis()

        try {
            // 1. 通常の学習（UPSERT）
            db.execSQL("""
                INSERT INTO user_history (word, yomi, use_count, last_used_time)
                VALUES (?, ?, 1, ?)
                ON CONFLICT(word) DO UPDATE SET 
                    use_count = use_count + 1,
                    last_used_time = ?
            """, arrayOf(word, yomi, currentTime, currentTime))

            // ==========================================
            // 🌟 2. 自動忘却（ガベージコレクション）
            // ==========================================
            // レコードが 5000件 を超えたら、古い・使われていないデータから消去する
            // (※毎回 COUNT すると重いので、乱数を使って 1/10 の確率でたまに掃除を実行する程度で十分です)
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

    // ==========================================
    // 1. 完全一致検索（Viterbiが毎文字呼ぶので超重要）
    // ==========================================
    fun getExactMatches(yomi: String): List<DictEntry> {
        val list = java.util.ArrayList<DictEntry>(10)
        val db = readableDatabase

        // 🌟 修正：SELECT に yomi を追加し、抽出順を DictEntry に合わせる
        db.rawQuery("SELECT word, yomi, weight, lid, rid FROM dictionary WHERE yomi = ?", arrayOf(yomi)).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(DictEntry(
                    cursor.getString(0), // word (String)
                    cursor.getString(1), // yomi (String)  <-- 🌟 新規追加
                    cursor.getInt(2),    // weight (Int)
                    cursor.getInt(3),    // lid (Int)
                    cursor.getInt(4)     // rid (Int)
                ))
            }
        }
        return list
    }
    // ==========================================
    // 2. 予測変換（学習履歴 ＋ 予測ペナルティ ＋ マトリックス文脈リランキング）
    // ==========================================
    // 🌟 修正1：引数に matrix を追加し、接続コストを計算できるようにする
    // ==========================================
    // 🌟 修正: 戻り値を List<Pair<String, String>> (単語と読みのペア) に変更
    // ==========================================
    fun getCandidates(
        hiragana: String,
        trailingRomaji: String = "",
        prevRid: Int = 0,
        matrix: MatrixManager? = null,
        limit: Int = 40
    ): List<Pair<String, String>> {

        val finalCandidates = mutableListOf<Pair<String, String>>()
        val db = readableDatabase

        // 🌟 NEW: 展開されたパターンのリストと、WHERE句を生成
        val prefixes = getGlobPrefixes(hiragana, trailingRomaji)
        val whereClause = prefixes.joinToString(" OR ") { "yomi GLOB ?" }

        // Step 1: ユーザーの学習履歴
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

        // Step 2: ベース辞書
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

                // 既に履歴にあるかチェック
                if (finalCandidates.any { it.first == word }) continue

                val expectedMinLen = hiragana.length + (if (trailingRomaji.isNotEmpty()) 1 else 0)
                val missingCharCount = kotlin.math.max(0, yomi.length - expectedMinLen)
                val predictionPenalty = missingCharCount * 100

                val connectionCost = if (matrix != null && prevRid != 0) {
                    matrix.getConnectionCost(prevRid, lid)
                } else {
                    0
                }

                val finalCost = weight + predictionPenalty + connectionCost
                baseDictCandidates.add(Pair(Pair(word, yomi), finalCost))
            }
        }

        baseDictCandidates.sortBy { it.second }
        // コスト順にソートして、重複を省きながら追加
        val sortedNewCands = baseDictCandidates.map { it.first }.distinctBy { it.first }.take(remainingLimit)
        finalCandidates.addAll(sortedNewCands)

        return finalCandidates
    }

    // 🌟 ついでに、getPredictionsByLids も yomi を返すように修正
    fun getPredictionsByLids(lids: List<Int>, limit: Int = 15): List<Pair<String, String>> {
        if (lids.isEmpty()) return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        val placeholders = lids.joinToString(",") { "?" }
        val args = lids.map { it.toString() }.toTypedArray()

        // SELECT に yomi を追加
        db.rawQuery(
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
    // ==========================================
    // 3. 次単語予測用のヘルパー関数
    // ==========================================

    // 確定した単語から、代表的な（一番コストが低い）rid を取得する
    fun getRidForWord(word: String): Int? {
        readableDatabase.rawQuery(
            "SELECT rid FROM dictionary WHERE word = ? ORDER BY weight ASC LIMIT 1",
            arrayOf(word)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return null
    }

    // ==========================================
    // 🌟 Viterbiエンジン専用・前方一致予測検索
    // ==========================================
    fun getPrefixMatchesForViterbi(hiraganaPrefix: String): List<DictEntry> {
        val list = mutableListOf<DictEntry>()
        val db = readableDatabase

        // GLOB で前方一致検索を行う（LIMIT は50程度にしてViterbiの爆発を防ぐ）
        db.rawQuery(
            "SELECT word, yomi, weight, lid, rid FROM dictionary WHERE yomi GLOB ? ORDER BY weight ASC LIMIT 50",
            arrayOf("$hiraganaPrefix*")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getString(0)
                val yomi = cursor.getString(1)
                val baseWeight = cursor.getInt(2)
                val lid = cursor.getInt(3)
                val rid = cursor.getInt(4)

                // 🌟 超重要：未入力文字に対するペナルティを weight に加算する！
                // これがないと、常に超長い単語が勝ってしまい変換が壊れます
                val missingCharCount = yomi.length - hiraganaPrefix.length
                val predictionPenalty = missingCharCount * 150 // ※要調整。少し厳しめが良い

                list.add(DictEntry(
                    word,
                    yomi,
                    baseWeight + predictionPenalty, // ペナルティ込みのコストに偽装してViterbiに渡す
                    lid,
                    rid
                ))
            }
        }
        return list
    }
}