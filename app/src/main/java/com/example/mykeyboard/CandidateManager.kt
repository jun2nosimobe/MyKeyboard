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
        // 英語モード または TeXコマンド (\始まり) の処理
        // ==========================================
        if (state.currentMode == MathKeyboardService.InputMode.NORMAL || rawStr.startsWith("\\")) {

            // 英語辞書からサジェストを取得
            val engSuggestions = engDbHelper.getEnglishSuggestions(rawStr, limit = 15)

            // 🌟 入力文字列のケース（大文字・小文字）パターンを判定
            // ※英数字以外の文字（ハイフンなど）が含まれるケースを考慮し、文字が存在し、かつすべての英文字が大文字かをチェック
            val isAllUpperCase = rawStr.isNotEmpty() && rawStr.any { it.isLetter() } && rawStr.filter { it.isLetter() }.all { it.isUpperCase() }
            val isFirstCharUpperCase = rawStr.isNotEmpty() && rawStr[0].isUpperCase()

            // 戻り値の型に合わせて Pair(word, word) に変換しつつ、ケースを反映
            val engPairs = engSuggestions.map { word ->
                val displayWord = when {
                    // TeXコマンド(\始まり)の場合はケース変換をスキップ
                    word.startsWith("\\") -> word

                    // 🌟 パターン1: 全文字大文字の場合
                    isAllUpperCase -> word.uppercase()

                    // 🌟 パターン2: 先頭のみ大文字の場合
                    isFirstCharUpperCase -> word.replaceFirstChar { it.uppercaseChar() }

                    // パターン3: すべて小文字の場合
                    else -> word
                }
                Pair(displayWord, displayWord)
            }
            finalCandidates.addAll(engPairs)

            // 入力中の生文字列(rawStr)は一番左(先頭)に確定候補として置いておく
            if (finalCandidates.none { it.first == rawStr }) {
                finalCandidates.add(0, Pair(rawStr, rawStr))
            }

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