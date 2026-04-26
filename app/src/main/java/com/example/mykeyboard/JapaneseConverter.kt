package com.example.mykeyboard

import android.util.Log
import kotlin.math.max
import kotlin.math.min

// 🌟 修正1：1つのノードではなく「これまでの経路（パス）」全体を記録するクラスに変更
class ViterbiPath(
    val word: String,
    val cost: Int,
    val rid: Int,
    val prev: ViterbiPath? // 直前の経路へのポインタ（リンクドリスト形式）
)

class JapaneseConverter(
    private val db: DictionaryDatabaseHelper,
    private val matrix: MatrixManager
) {
    private val queryCache = HashMap<String, List<DictionaryDatabaseHelper.DictEntry>>()
    private val BEAM_WIDTH = 10

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
            dp[0].add(ViterbiPath("BOS", 0, 0, null))
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
                        candidatesAtJ.add(ViterbiPath(match.word, totalCost, match.rid, prevPath))
                    }
                }
            }
            dp[j] = candidatesAtJ.sortedBy { it.cost }.take(BEAM_WIDTH).toMutableList()
        }

        // ==========================================
        // 🌟 4. 次回の入力のために状態を保存
        // ==========================================
        lastInput = hiragana
        cachedDp = dp

        // ==========================================
        // 5. ゴール (EOS) 処理とバックトラック
        // ==========================================
        // （EOS処理と文字列組み立ては既存のコードそのまま）
        val finalPaths = mutableListOf<ViterbiPath>()
        for (path in dp[len]) {
            val connectCost = matrix.getConnectionCost(path.rid, 0)
            val totalCost = path.cost + connectCost
            finalPaths.add(ViterbiPath("EOS", totalCost, 0, path))
        }

        val topPaths = finalPaths.sortedBy { it.cost }
        val resultCandidates = mutableListOf<String>()

        for (path in topPaths) {
            val words = mutableListOf<String>()
            var curr: ViterbiPath? = path.prev

            while (curr != null && curr.word != "BOS") {
                words.add(curr.word)
                curr = curr.prev
            }

            val candidateString = words.reversed().joinToString("")
            if (!resultCandidates.contains(candidateString)) {
                resultCandidates.add(candidateString)
            }
            if (resultCandidates.size >= limit) break
        }

        return resultCandidates
    }
}