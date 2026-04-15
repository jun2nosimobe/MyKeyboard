package com.example.mykeyboard

import kotlin.math.min

// ラティス（探索グラフ）のノード
data class ViterbiNode(
    val word: String,
    val weight: Int,
    val lid: Int,
    val rid: Int,
    val start: Int,
    val end: Int
) {
    var minCost: Int = Int.MAX_VALUE
    var bestPrev: ViterbiNode? = null
}

class JapaneseConverter(
    private val db: DictionaryDatabaseHelper,
    private val matrix: MatrixManager
) {
    // 🌟 爆速化のコア：DB検索結果を一時的に保存するキャッシュ
    private val queryCache = HashMap<String, List<DictionaryDatabaseHelper.DictEntry>>()

    fun convert(hiragana: String): String {
        if (hiragana.isEmpty()) return ""

        // 🌟 変換処理の開始時にキャッシュをクリア（古いメモリを溜め込まない）
        queryCache.clear()

        val len = hiragana.length
        val nodesAt = Array(len + 1) { mutableListOf<ViterbiNode>() }

        val bos = ViterbiNode("BOS", 0, 0, 0, 0, 0).apply { minCost = 0 }
        nodesAt[0].add(bos)

        // 1. ラティス構築
        for (i in 0 until len) {
            if (nodesAt[i].isEmpty()) continue

            // 処理を軽くするため、最大10文字先までを検索
            val maxSearchLen = min(len, i + 10)

            for (j in i + 1..maxSearchLen) {
                val sub = hiragana.substring(i, j)

                // 🌟 劇的な最適化：キャッシュにあればそれを使い、無ければDBにアクセスして保存する
                val dictMatches = queryCache.getOrPut(sub) { db.getExactMatches(sub) }

                for (match in dictMatches) {
                    nodesAt[j].add(ViterbiNode(match.word, match.weight, match.lid, match.rid, i, j))
                }
            }

            // 🌟 修正1：foundMatchに関わらず、必ず1文字のフォールバックノードを置く
            // これにより、どんな入力でもグラフが途切れることなく最短経路を計算できます
            val fallbackChar = hiragana.substring(i, i + 1)
            nodesAt[i + 1].add(ViterbiNode(fallbackChar, 8000, 0, 0, i, i + 1))
        }

        // 2. Viterbi アルゴリズム
        for (j in 1..len) {
            for (node in nodesAt[j]) {
                for (prev in nodesAt[node.start]) {
                    // 🌟 修正2：オーバーフロー（Int.MAX_VALUE + costがマイナスになるバグ）を防止
                    if (prev.minCost == Int.MAX_VALUE) continue

                    val connectCost = matrix.getConnectionCost(prev.rid, node.lid)
                    val totalCost = prev.minCost + connectCost + node.weight

                    if (totalCost < node.minCost) {
                        node.minCost = totalCost
                        node.bestPrev = prev
                    }
                }
            }
        }

        val eos = ViterbiNode("EOS", 0, 0, 0, len, len)
        for (prev in nodesAt[len]) {
            if (prev.minCost == Int.MAX_VALUE) continue
            val connectCost = matrix.getConnectionCost(prev.rid, eos.lid)
            val totalCost = prev.minCost + connectCost

            if (totalCost < eos.minCost) {
                eos.minCost = totalCost
                eos.bestPrev = prev
            }
        }

        // 3. バックトラック
        val result = mutableListOf<String>()
        var curr: ViterbiNode? = eos.bestPrev
        while (curr != null && curr != bos) {
            result.add(curr.word)
            curr = curr.bestPrev
        }

        return result.reversed().joinToString("")
    }
}