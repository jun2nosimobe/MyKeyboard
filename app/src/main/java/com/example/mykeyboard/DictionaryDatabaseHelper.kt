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
    // ==========================================
    // 🌟 修正：既存のDBを読み込んだ際にも、確実にテーブルを作る
    // ==========================================
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // IF NOT EXISTS がついているので、既に存在する場合は一瞬でスキップされ負荷はかかりません
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_history (
                word TEXT PRIMARY KEY,
                yomi TEXT,
                use_count INTEGER DEFAULT 1,
                last_used_time INTEGER
            )
        """)
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
    fun getCandidates(hiragana: String, prevRid: Int = 0, matrix: MatrixManager? = null, limit: Int = 40): List<String> {
        // 返り値は純粋な文字列のリスト
        val finalCandidates = mutableListOf<String>()
        val db = readableDatabase

        // 🌟 Step 1: ユーザーが過去に使った単語（学習履歴）を優先して取得
        try {
            db.rawQuery("""
                SELECT word FROM user_history 
                WHERE yomi GLOB ? 
                ORDER BY last_used_time DESC, use_count DESC 
                LIMIT ?
            """, arrayOf("$hiragana*", limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    finalCandidates.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // もし学習データだけで上限に達したらそのまま返す
        if (finalCandidates.size >= limit) return finalCandidates

        // 🌟 Step 2: ベース辞書から取得し、ペナルティと「遷移コスト」でリランキング
        val remainingLimit = limit - finalCandidates.size
        val baseDictCandidates = mutableListOf<Pair<String, Int>>() // Pair(word, 最終コスト)

        db.rawQuery(
            "SELECT word, yomi, weight, lid, rid FROM dictionary WHERE yomi GLOB ? ORDER BY weight ASC LIMIT 100",
            arrayOf("$hiragana*")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getString(0)
                val yomi = cursor.getString(1)
                val weight = cursor.getInt(2)
                val lid = cursor.getInt(3)

                // すでに学習履歴から追加されている単語は、重複して出さないようスキップ
                if (finalCandidates.contains(word)) continue

                // A. 未入力ペナルティ
                val missingCharCount = yomi.length - hiragana.length
                val predictionPenalty = missingCharCount * 100 // ※100くらいが適正

                // B. マトリックスを用いた遷移コスト（🌟 ここで実装！）
                // 直前に確定した単語(prevRid)があり、matrixが渡されていれば計算
                val connectionCost = if (matrix != null && prevRid != 0) {
                    matrix.getConnectionCost(prevRid, lid)
                } else {
                    0
                }

                // 最終コスト（低いほど良い）
                val finalCost = weight + predictionPenalty + connectionCost
                baseDictCandidates.add(Pair(word, finalCost))
            }
        }

        // スコア順に並べ替えて、枠が余っている分だけ追加
        baseDictCandidates.sortBy { it.second }
        finalCandidates.addAll(baseDictCandidates.map { it.first }.distinct().take(remainingLimit))

        return finalCandidates
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

    // 繋がりやすい lid のリストを受け取り、それに合致する高頻度な単語を返す
    fun getPredictionsByLids(lids: List<Int>, limit: Int = 15): List<String> {
        if (lids.isEmpty()) return emptyList()

        // "?, ?, ?" のようなプレースホルダーを作る
        val placeholders = lids.joinToString(",") { "?" }
        val args = lids.map { it.toString() }.toTypedArray()
        val list = mutableListOf<String>()

        // 指定された lid の中から、単独コスト(weight)が低い順に取得
        readableDatabase.rawQuery(
            "SELECT word FROM dictionary WHERE lid IN ($placeholders) ORDER BY weight ASC LIMIT ?",
            arrayOf(*args, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val w = cursor.getString(0)
                if (!list.contains(w)) list.add(w)
            }
        }
        return list
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