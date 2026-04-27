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

class JapaneseConverter(
    private val db: DictionaryDatabaseHelper,
    private val matrix: MatrixManager
) {
    private val queryCache = HashMap<String, List<DictionaryDatabaseHelper.DictEntry>>()
    private val BEAM_WIDTH = 50

    // ==========================================
    // 🌟 新規：前回の計算状態（DP表）を保持するキャッシュ
    // ==========================================
    private var lastInput = ""
    private var cachedDp = Array<MutableList<ViterbiPath>>(0) { mutableListOf() }

    // 外部からキャッシュを強制リセットしたい時（確定時やバックスペース時など）に呼ぶ
    fun resetCache() {
        lastInput = ""
        cachedDp = Array(0) { mutableListOf() }
        queryCache.clear()
    }

    fun convert(hiragana: String, limit: Int = 5): List<String> {
        if (hiragana.isEmpty()) {
            resetCache()
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

        // キャッシュが0（初回または完全に違う文字）の場合はBOSをセット
        if (safeCacheLen == 0) {
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

                var dictMatches = if (j == len) {
                    queryCache.getOrPut("PREFIX_$sub") {
                        db.getPrefixMatchesForViterbi(sub)
                    }
                } else {
                    queryCache.getOrPut(sub) {
                        db.getExactMatches(sub)
                    }
                }

                if (dictMatches.isEmpty() && sub.length == 1) {
                    dictMatches = listOf(DictionaryDatabaseHelper.DictEntry(sub, sub, 8000, 0, 0))
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

        // 🌟 重複チェックによる $O(N)$ の遅延を防ぐため LinkedHashSet に変更
        val resultCandidates = java.util.LinkedHashSet<String>()

        for (path in topPaths) {
            val words = mutableListOf<String>()
            var curr: ViterbiPath? = path.prev

            while (curr != null && curr.word != "BOS") {
                words.add(curr.word)
                curr = curr.prev
            }

            val candidateString = words.reversed().joinToString("")
            // contains判定不要、そのままaddするだけで順序を保って重複排除される
            resultCandidates.add(candidateString)

            if (resultCandidates.size >= limit) break
        }

        return resultCandidates.toList()
    }
}