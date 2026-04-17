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
    // DB検索結果のキャッシュ
    private val queryCache = HashMap<String, List<DictionaryDatabaseHelper.DictEntry>>()

    // 🌟 ビーム幅（各文字の区切りで保持する上位パスの数）
    // 大きくすると精度・候補数が増えるが重くなる。スマホなら10〜20が最適。
    private val BEAM_WIDTH = 10

    // 🌟 戻り値を String から List<String> に変更し、複数の変換候補を返せるようにする
    fun convert(hiragana: String, limit: Int = 5): List<String> {
        if (hiragana.isEmpty()) return emptyList()
        queryCache.clear()

        val len = hiragana.length

        // dp[i] は「i文字目までを変換した際の、スコアが良い上位 N 個の経路(ViterbiPath)」を保持する
        val dp = Array(len + 1) { mutableListOf<ViterbiPath>() }

        // 初期状態 (BOS: 始まり)
        dp[0].add(ViterbiPath("BOS", 0, 0, null))

        // 1. ビームサーチ（N-Best Viterbi）による探索
        for (j in 1..len) {
            val candidatesAtJ = mutableListOf<ViterbiPath>()

            // 処理を軽くするため、直近の最大15文字分までを遡って検索
            val startI = max(0, j - 15)
            for (i in startI until j) {
                if (dp[i].isEmpty()) continue

                val sub = hiragana.substring(i, j)

                // ==========================================
                // 🌟 ここが究極の進化ポイント！
                // j が len (入力の最後尾) に到達した時だけ、前方一致(予測)を解禁する
                // ==========================================
                var dictMatches = if (j == len) {
                    // キャッシュキーが被らないように "PREFIX_" をつける
                    queryCache.getOrPut("PREFIX_$sub") {
                        // 予測用メソッド（後述）を呼ぶ
                        db.getPrefixMatchesForViterbi(sub)
                    }
                } else {
                    queryCache.getOrPut(sub) {
                        db.getExactMatches(sub)
                    }
                }

                // 🌟 未知語(OOV)救済ロジック
                if (dictMatches.isEmpty() && sub.length == 1) {
                    dictMatches = listOf(DictionaryDatabaseHelper.DictEntry(sub, sub, 8000, 0, 0))
                }

                for (match in dictMatches) {
                    // (以下、既存の Viterbi パス結合ロジックそのまま)
                    for (prevPath in dp[i]) {
                        val connectCost = matrix.getConnectionCost(prevPath.rid, match.lid)
                        val totalCost = prevPath.cost + connectCost + match.weight
                        candidatesAtJ.add(ViterbiPath(match.word, totalCost, match.rid, prevPath))
                    }
                }
            }

            // 🌟 ビームサーチの要：枝刈り（Pruning）
            // 全ての組み合わせを残すとメモリが爆発するので、スコア(cost)が低い上位10個だけを dp[j] に残す
            dp[j] = candidatesAtJ.sortedBy { it.cost }.take(BEAM_WIDTH).toMutableList()
        }

        // 2. ゴール (EOS: 終わり) 処理
        val finalPaths = mutableListOf<ViterbiPath>()
        for (path in dp[len]) {
            val connectCost = matrix.getConnectionCost(path.rid, 0) // EOSのlidは一般的に0
            val totalCost = path.cost + connectCost
            finalPaths.add(ViterbiPath("EOS", totalCost, 0, path))
        }

        // 3. バックトラックと結果文字列の組み立て
        // 最終的にゴールまで到達した経路をスコア順に並べる
        val topPaths = finalPaths.sortedBy { it.cost }

        // 1位のデバッグログ出力（任意）
        topPaths.firstOrNull()?.let { best ->
            Log.d("ViterbiDebug", "Best Path Cost: ${best.cost}")
        }

        val resultCandidates = mutableListOf<String>()

        for (path in topPaths) {
            val words = mutableListOf<String>()
            var curr: ViterbiPath? = path.prev // EOS自体は文字列に含まないためスキップ

            while (curr != null && curr.word != "BOS") {
                words.add(curr.word)
                curr = curr.prev
            }

            // 逆から辿っているので反転して結合
            val candidateString = words.reversed().joinToString("")

            // 異なる経路でも、最終的な文字列が同じになる場合があるため重複を防ぐ
            if (!resultCandidates.contains(candidateString)) {
                resultCandidates.add(candidateString)
            }

            // 指定された数(limit)の候補が集まったら終了
            if (resultCandidates.size >= limit) break
        }

        return resultCandidates
    }
}