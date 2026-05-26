package com.example.mykeyboard

class CandidateManager(
    private val dbHelper: DictionaryDatabaseHelper,
    private val engDbHelper: EnglishDictionaryHelper,
    private val viterbiConverter: JapaneseConverter,
    private val composer: Composer,
    private val matrix: MatrixManager
) {
    // 🌟 修正: 戻り値を List<Pair<String, String>> に変更
    fun generateCandidates(state: KeyboardState): List<Pair<String, String>> {

        val finalCandidates = mutableListOf<Pair<String, String>>()
        val rawStr = state.composingText

        // 次単語予測モード
        if (rawStr.isEmpty()) {
            val lastWord = state.lastConfirmedWord
            if (!lastWord.isNullOrEmpty()) {
                val prevRid = dbHelper.getRidForWord(lastWord)
                if (prevRid != null) {
                    val bestLids = matrix.getTopConnectingLids(prevRid, limit = 5)
                    val predictions = dbHelper.getPredictionsByLids(bestLids, limit = 15)
                    finalCandidates.addAll(predictions)
                }
            }
            return finalCandidates
        }

        // ==========================================
        // 🌟 NEW: 英語モード または TeXコマンド (\始まり) の処理を最優先でインターセプト！
        // ==========================================
        if (state.currentMode == MathKeyboardService.InputMode.NORMAL || rawStr.startsWith("\\")) {

            // 英語辞書からサジェストを取得 (例: "homo" -> ["homomorphism", "homology"...])
            val engSuggestions = engDbHelper.getEnglishSuggestions(rawStr, limit = 15)

            // 戻り値の型に合わせて Pair(word, word) に変換
            val engPairs = engSuggestions.map { Pair(it, it) }
            finalCandidates.addAll(engPairs)

            // IMEのお約束：入力中の生文字列(rawStr)は、一番左(先頭)に確定候補として置いておく
            if (finalCandidates.none { it.first == rawStr }) {
                finalCandidates.add(0, Pair(rawStr, rawStr))
            }

            // 🌟 英語モードの時はここでリターンし、重い日本語処理（Viterbi等）をスキップする！
            return finalCandidates
        }

        // Direct Romaji Mode
        if (state.isDirectRomajiMode) {
            finalCandidates.add(Pair(rawStr, rawStr))
            if (rawStr.startsWith("\\") && rawStr.length > 1) {
                val dbCandidates = dbHelper.getCandidates(rawStr.substring(1), limit = 10)
                for (cand in dbCandidates) {
                    if (finalCandidates.none { it.first == cand.first }) finalCandidates.add(cand)
                }
            }
            return finalCandidates
        }

        val hiraganaStr = composer.convertRomajiToHiragana(rawStr)
        val trailingRomajiMatch = Regex("[a-zA-Z-]+$").find(hiraganaStr)
        val trailingRomaji = trailingRomajiMatch?.value ?: ""
        val cleanHiragana =
            if (trailingRomaji.isNotEmpty()) hiraganaStr.dropLast(trailingRomaji.length) else hiraganaStr

        // 🌟 Viterbiの結果を Pair にする (Viterbiの読みは、渡した cleanHiragana そのもの！)
        val viterbiResults = if (cleanHiragana.isNotEmpty()) viterbiConverter.convert(
            cleanHiragana,
            limit = 10
        ) else emptyList()
        val viterbiCandidates = viterbiResults.map {
            Pair(it + trailingRomaji, cleanHiragana + trailingRomaji)
        }

        val prevRid = state.lastConfirmedWord.takeIf { it.isNotEmpty() }?.let {
            dbHelper.getRidForWord(it)
        } ?: 0

        val dbCandidates = if (cleanHiragana.isNotEmpty() || trailingRomaji.isNotEmpty()) {
            dbHelper.getCandidates(
                hiragana = cleanHiragana,
                trailingRomaji = trailingRomaji,
                prevRid = prevRid,
                matrix = matrix,
                limit = 10
            )
        } else emptyList()

        // 統合処理 (重複は first で判定)
        if (trailingRomaji.isNotEmpty()) {
            for (cand in dbCandidates) if (finalCandidates.none { it.first == cand.first }) finalCandidates.add(
                cand
            )
            for (cand in viterbiCandidates) if (finalCandidates.none { it.first == cand.first }) finalCandidates.add(
                cand
            )
        } else {
            for (cand in viterbiCandidates) if (finalCandidates.none { it.first == cand.first }) finalCandidates.add(
                cand
            )
            for (cand in dbCandidates) if (finalCandidates.none { it.first == cand.first }) finalCandidates.add(
                cand
            )
        }

        if (finalCandidates.none { it.first == hiraganaStr }) finalCandidates.add(
            Pair(
                hiraganaStr,
                hiraganaStr
            )
        )

        val katakanaStr =
            hiraganaStr.map { if (it in 'ぁ'..'ん') it + 0x60 else it }.joinToString("")
        if (katakanaStr != hiraganaStr && finalCandidates.none { it.first == katakanaStr }) finalCandidates.add(
            Pair(katakanaStr, katakanaStr)
        )
        if (rawStr != hiraganaStr && finalCandidates.none { it.first == rawStr }) finalCandidates.add(
            Pair(rawStr, rawStr)
        )

        return finalCandidates
    }
}