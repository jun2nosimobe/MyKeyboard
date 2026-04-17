package com.example.mykeyboard

class CandidateManager(
    private val dbHelper: DictionaryDatabaseHelper,
    private val viterbiConverter: JapaneseConverter,
    private val composer: Composer,
    private val matrix: MatrixManager
) {
    fun generateCandidates(state: KeyboardState): List<String> {

        val finalCandidates = mutableListOf<String>()
        val rawStr = state.composingText

        // ==========================================
        // 🌟 新規：入力文字がない場合は「次単語予測モード」
        // ==========================================
        if (rawStr.isEmpty()) {
            val lastWord = state.lastConfirmedWord
            if (!lastWord.isNullOrEmpty()) {
                // 1. 直前の単語の rid を取得
                val prevRid = dbHelper.getRidForWord(lastWord)
                if (prevRid != null) {
                    // 2. 相性の良い lid トップ5を取得
                    val bestLids = matrix.getTopConnectingLids(prevRid, limit = 5)
                    // 3. その lid を持つ頻出単語を取得
                    val predictions = dbHelper.getPredictionsByLids(bestLids, limit = 15)
                    finalCandidates.addAll(predictions)
                }
            }
            return finalCandidates
        }

        // ==========================================
        // 以下、通常の変換・予測処理（前回修正した

        if (state.isDirectRomajiMode) {
            finalCandidates.add(rawStr)
            if (rawStr.startsWith("\\") && rawStr.length > 1) {
                // 🌟 修正：limitを明示的に指定（prevRidとの混同を避ける）
                val dbCandidates = dbHelper.getCandidates(rawStr.substring(1), limit = 10)
                for (word in dbCandidates) {
                    if (!finalCandidates.contains(word)) finalCandidates.add(word)
                }
            }
            return finalCandidates
        }

        val hiraganaStr = composer.convertRomajiToHiragana(rawStr)
        val trailingRomajiMatch = Regex("[a-zA-Z-]+$").find(hiraganaStr)
        val trailingRomaji = trailingRomajiMatch?.value ?: ""
        val cleanHiragana = if (trailingRomaji.isNotEmpty()) hiraganaStr.dropLast(trailingRomaji.length) else hiraganaStr

        // 🌟 修正1：Viterbiの戻り値は List<String> になるため、空の場合は emptyList() を返す
        val viterbiResults = if (cleanHiragana.isNotEmpty()) viterbiConverter.convert(cleanHiragana, limit = 5) else emptyList()

        // 🌟 修正2：リストの全ての候補に対して、末尾の未確定ローマ字を結合する
        val viterbiCandidates = viterbiResults.map { it + trailingRomaji }

        // ==========================================
        // 🌟 修正3：通常の予測変換にもマトリックス（文脈）を適用する！
        // ==========================================
        // 直前の単語から rid を取得（無ければ 0）
        val prevRid = state.lastConfirmedWord.takeIf { it.isNotEmpty() }?.let {
            dbHelper.getRidForWord(it)
        } ?: 0

        // 取得した prevRid と matrix を渡して文脈リランキングを発動させる
        val dbCandidates = if (cleanHiragana.isNotEmpty()) {
            dbHelper.getCandidates(cleanHiragana, prevRid = prevRid, matrix = matrix, limit = 10)
        } else {
            emptyList()
        }

        // 🌟 修正4：Viterbiの結果（文節変換）をトップに持ってくる
        for (cand in viterbiCandidates) {
            if (!finalCandidates.contains(cand)) {
                finalCandidates.add(cand)
            }
        }

        // 次に予測候補を追加
        for (cand in dbCandidates) {
            if (!finalCandidates.contains(cand)) {
                finalCandidates.add(cand)
            }
        }

        // 最後に無変換の平仮名、カタカナ、ローマ字をフォールバックとして追加
        if (!finalCandidates.contains(hiraganaStr)) finalCandidates.add(hiraganaStr)

        val katakanaStr = hiraganaStr.map { if (it in 'ぁ'..'ん') it + 0x60 else it }.joinToString("")
        if (katakanaStr != hiraganaStr && !finalCandidates.contains(katakanaStr)) finalCandidates.add(katakanaStr)

        if (rawStr != hiraganaStr && !finalCandidates.contains(rawStr)) finalCandidates.add(rawStr)

        return finalCandidates
    }
}