package com.example.mykeyboard

import android.util.Log
import kotlin.math.max
import kotlin.math.min

// 🌟 修正：startIndex を追加し、単語の開始位置を記録
class ViterbiPath(
    val word: String,
    val cost: Int,
    val rid: Int,
    val startIndex: Int, // ← NEW! どこから始まった単語か
    val prev: ViterbiPath?
)

// 🌟 部分変換(先頭文節だけを確定して残りは変換待ちに残す)対応。
// firstSegmentWord/firstSegmentHiraganaLength は、この候補が2文節以上に
// 分かれる時だけ非null。1文節だけの候補(=text全体で1語)では常にnull。
data class ViterbiResult(
    val text: String,
    val firstSegmentWord: String?,
    val firstSegmentHiraganaLength: Int,
    val cost: Int = 0 // 通常の変換と予測変換の候補を混ぜて並べる時に使う
)

class JapaneseConverter(
    private val db: DictionaryDatabaseHelper,
    private val matrix: MatrixManager
) {
    private val queryCache = HashMap<String, List<DictionaryDatabaseHelper.DictEntry>>()
    private val BEAM_WIDTH = 50
    private val UNK_ID = 2999
    private val UNK_COST = 8000

    // 🌟 予測変換(engine.py の PRED_* と同じ値)。最後の語の読みが PRED_MIN_HEAD 文字以上ある時だけ、
    // その読みで始まるより長い語で補う。補った候補には PRED_BASE と1文字ごとの PRED_PER_CHAR を足す
    private val PRED_MIN_HEAD = 2
    private val PRED_MAX_MISSING = 4
    private val PRED_ROWS = 30
    private val PRED_BASE = 2000
    private val PRED_PER_CHAR = 150

    // ==========================================
    // 🌟 新規：前回の計算状態（DP表）を保持するキャッシュ
    // ==========================================
    private var lastInput = ""
    private var cachedDp = Array<MutableList<ViterbiPath>>(0) { mutableListOf() }

    // 🌟 変換はKeyboardControllerの変換専用スレッドで走り、resetCacheはメインスレッドから
    // 呼ばれる。直接消すと計算中の表を壊すので、印だけ付けて次のconvertの冒頭で消す。
    @Volatile private var resetRequested = false

    // 外部からキャッシュを強制リセットしたい時（確定時やバックスペース時など）に呼ぶ
    fun resetCache() {
        resetRequested = true
    }

    private fun clearCacheNow() {
        resetRequested = false
        lastInput = ""
        cachedDp = Array(0) { mutableListOf() }
        queryCache.clear()
    }

    // 🌟 予測変換。直前のconvert(同じ読み)で作った格子を使い、最後の語だけ読みの続きを補う
    fun predict(hiragana: String, limit: Int = 10): List<ViterbiResult> {
        val len = hiragana.length
        if (hiragana != lastInput || cachedDp.size != len + 1) return emptyList()
        val ends = mutableListOf<ViterbiPath>()
        for (i in kotlin.math.max(0, len - 15)..(len - PRED_MIN_HEAD)) {
            val prevPaths = cachedDp[i]
            if (prevPaths.isEmpty()) continue
            val head = hiragana.substring(i)
            val matches = queryCache.getOrPut("PRED_$head") { db.getPredictionMatches(head, PRED_ROWS) }
            for (match in matches) {
                val missing = match.yomi.length - head.length
                if (missing > PRED_MAX_MISSING) continue
                val extra = match.weight + PRED_BASE + PRED_PER_CHAR * missing
                for (prevPath in prevPaths) {
                    val cost = prevPath.cost + matrix.getConnectionCost(prevPath.rid, match.lid) + extra
                    ends.add(ViterbiPath(match.word, cost, match.rid, i, prevPath))
                }
            }
        }
        ends.sortBy { it.cost }
        val seen = HashSet<String>()
        val results = mutableListOf<ViterbiResult>()
        for (end in ends) {
            val words = mutableListOf<String>()
            var curr: ViterbiPath? = end
            while (curr != null && curr.word != "BOS") {
                words.add(curr.word)
                curr = curr.prev
            }
            val text = words.reversed().joinToString("")
            if (!seen.add(text)) continue
            results.add(ViterbiResult(text, null, 0, end.cost))
            if (results.size >= limit) break
        }
        return results
    }

    // 🌟 trailingRomaji: まだ母音が確定していない末尾の子音1文字。指定時は最後の
    // 位置(j==len)の辞書検索がこの文字も加味した予測変換になる（詳細は
    // DictionaryDatabaseHelper.getPrefixMatchesForViterbi 参照）。
    fun convert(hiragana: String, trailingRomaji: String = "", limit: Int = 5): List<ViterbiResult> {
        if (resetRequested) clearCacheNow()
        if (hiragana.isEmpty()) {
            clearCacheNow()
            return emptyList()
        }

        val len = hiragana.length
        val dp = Array(len + 1) { mutableListOf<ViterbiPath>() }

        // ==========================================
        // 🌟 1. 差分探索：前回入力との共通部分を計算
        // ==========================================
        var prefixLen = 0
        while (prefixLen < len && prefixLen < lastInput.length && hiragana[prefixLen] == lastInput[prefixLen]) {
            prefixLen++
        }

        // 末尾のノードは「予測(Prefix)」から「完全一致(Exact)」に性質が変わるため、
        // キャッシュを安全に再利用できるのは (共通長 - 1) まで！
        val safeCacheLen = kotlin.math.max(0, prefixLen - 1)

        // ==========================================
        // 🌟 2. キャッシュの復元（重いDB検索と計算のスキップ）
        // ==========================================
        for (i in 0..safeCacheLen) {
            if (i < cachedDp.size) {
                dp[i] = cachedDp[i] // ViterbiPathインスタンスをそのまま使い回す
            }
        }

        // 🌟 修正：safeCacheLen==0 は「2文字目」や「たまたま1文字目が一致」でも毎回成立するため、
        // 旧コードのままだと dp[0]（cachedDpと共有参照）に重複BOSが際限なく積み上がってしまう。
        // dp[0] が本当に空（初回、またはresetCache直後）の時だけBOSを追加する。
        if (dp[0].isEmpty()) {
            dp[0].add(ViterbiPath("BOS", 0, 0, 0, null)) // startIndex = 0
        }

        // ==========================================
        // 🌟 3. 足りない部分（追加された文字）だけをViterbi計算
        // ==========================================
        val startCalculationIndex = safeCacheLen + 1

        // j のループは途中(startCalculationIndex)から始める！
        for (j in startCalculationIndex..len) {
            val candidatesAtJ = mutableListOf<ViterbiPath>()
            val startI = kotlin.math.max(0, j - 15)

            for (i in startI until j) {
                if (dp[i].isEmpty()) continue

                val sub = hiragana.substring(i, j)

                // 🌟 v2: 前方一致(予測)は、末尾に母音待ちの子音がある時だけ使う。読みが確定している
                // 時に予測を混ぜると、長いまれな語(かんばせ→顔 等)が短い読みに紛れ込む。予測候補は
                // CandidateManager が dbHelper.getCandidates で別に後ろへ並べている。
                var dictMatches = if (j == len && trailingRomaji.isNotEmpty()) {
                    // 🌟 trailingRomaji が変わると同じ sub でも検索結果が変わるので
                    // キャッシュキーに含める（含めないと保留中の子音を変えた時に
                    // 古い候補が返ってしまう）。
                    queryCache.getOrPut("PREFIX_${trailingRomaji}_$sub") {
                        db.getPrefixMatchesForViterbi(sub, trailingRomaji)
                    }
                } else {
                    queryCache.getOrPut(sub) {
                        db.getExactMatches(sub)
                    }
                }

                if (dictMatches.isEmpty() && sub.length == 1) {
                    // 🌟 v2: 未知語は専用ID(行列の2999)。v1はID 0(文頭と同じ)で、前後の接続が無料だった
                    dictMatches = listOf(DictionaryDatabaseHelper.DictEntry(sub, sub, UNK_COST, UNK_ID, UNK_ID))
                }

                for (match in dictMatches) {
                    for (prevPath in dp[i]) {
                        val connectCost = matrix.getConnectionCost(prevPath.rid, match.lid)
                        val totalCost = prevPath.cost + connectCost + match.weight
                        // 🌟 ここで i を startIndex として渡す
                        candidatesAtJ.add(ViterbiPath(match.word, totalCost, match.rid, i, prevPath))
                    }
                }
            }
            candidatesAtJ.sortBy { it.cost } // 内部でインプレースソート（新リストを作らない）
            if (candidatesAtJ.size > BEAM_WIDTH) {
                // BEAM_WIDTH 以降の要素をズバッと削除（新リストを作らない）
                candidatesAtJ.subList(BEAM_WIDTH, candidatesAtJ.size).clear()
            }
            val DIVERSITY_COUNT = 3 // 各切り方(i)ごとに最低限確保する数

            candidatesAtJ.sortBy { it.cost } // まず全体をコスト順にソート

            // インプレースで処理してメモリ割り当て(GC)を防ぐ
            val nextDp = ArrayList<ViterbiPath>(BEAM_WIDTH)
            // j - i は最大15なので、長さ16の軽量配列で各開始位置の確保数をカウント
            val countsByOffset = IntArray(16)
            val added = BooleanArray(candidatesAtJ.size)

            // 🌟 パス1: 多様性の確保（各開始位置 i ごとにトップ2個を強制確保）
            for (idx in candidatesAtJ.indices) {
                val cand = candidatesAtJ[idx]
                val offset = j - cand.startIndex // どれくらい前から始まったか(1〜15)

                if (offset in 1..15 && countsByOffset[offset] < DIVERSITY_COUNT) {
                    nextDp.add(cand)
                    countsByOffset[offset]++
                    added[idx] = true
                }
            }

            // 🌟 パス2: 残りの枠を、純粋に全体でコストが低い順に埋めていく
            for (idx in candidatesAtJ.indices) {
                if (nextDp.size >= BEAM_WIDTH) break
                if (!added[idx]) {
                    nextDp.add(candidatesAtJ[idx])
                    added[idx] = true
                }
            }

            // 最後に再度コスト順に整列して確定
            nextDp.sortBy { it.cost }
            dp[j] = nextDp
        } // <- j のループ終わり

        // ==========================================
        // 🌟 4. 次回の入力のために状態を保存
        // ==========================================
        lastInput = hiragana
        cachedDp = dp

        // 5. ゴール (EOS) 処理
        val finalPaths = mutableListOf<ViterbiPath>()
        for (path in dp[len]) {
            val connectCost = matrix.getConnectionCost(path.rid, 0)
            val totalCost = path.cost + connectCost
            // EOSにも startIndex (len) を渡す
            finalPaths.add(ViterbiPath("EOS", totalCost, 0, len, path))
        }

        val topPaths = finalPaths.sortedBy { it.cost }

        val seenTexts = HashSet<String>()
        val results = mutableListOf<ViterbiResult>()

        for (path in topPaths) {
            val words = mutableListOf<String>()
            val nodes = mutableListOf<ViterbiPath>()
            var curr: ViterbiPath? = path.prev

            while (curr != null && curr.word != "BOS") {
                words.add(curr.word)
                nodes.add(curr)
                curr = curr.prev
            }

            val candidateString = words.reversed().joinToString("")
            if (!seenTexts.add(candidateString)) continue // 既出ならスキップ(重複排除)

            // 🌟 部分変換用: nodesは[EOS側の最後の文節, ..., 先頭文節]の順。
            // 2文節以上に分かれている時だけ、先頭文節(nodes.last)の単語と、
            // それが消費したひらがな文字数(=2番目の文節の開始位置)を記録する。
            var firstSegmentWord: String? = null
            var firstSegmentLen = 0
            if (nodes.size >= 2) {
                val firstNode = nodes.last()
                val secondNode = nodes[nodes.size - 2]
                firstSegmentWord = firstNode.word
                firstSegmentLen = secondNode.startIndex
            }

            results.add(ViterbiResult(candidateString, firstSegmentWord, firstSegmentLen, path.cost))

            if (results.size >= limit) break
        }

        return results
    }
}