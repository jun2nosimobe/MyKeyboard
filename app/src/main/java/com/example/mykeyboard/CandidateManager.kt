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

        if (state.isDirectRomajiMode) {
            finalCandidates.add(rawStr)
            if (rawStr.startsWith("\\") && rawStr.length > 1) {
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

        // 💡 Viterbiにはあえて trailingRomaji を渡しません。
        // これにより、Viterbiは純粋に「微分法」を返し、それに「t」がくっついて「微分法t」となります。
        val viterbiResults = if (cleanHiragana.isNotEmpty()) viterbiConverter.convert(cleanHiragana, limit = 5) else emptyList()
        val viterbiCandidates = viterbiResults.map { it + trailingRomaji }

        val prevRid = state.lastConfirmedWord.takeIf { it.isNotEmpty() }?.let {
            dbHelper.getRidForWord(it)
        } ?: 0

        // 取得した prevRid と matrix を渡して文脈リランキングを発動させる
        // ==========================================
        // 🌟 修正箇所1：trailingRomaji を渡して「先読みGLOB予測」を発動させる！
        // ==========================================
        val dbCandidates = if (cleanHiragana.isNotEmpty() || trailingRomaji.isNotEmpty()) {
            dbHelper.getCandidates(
                hiragana = cleanHiragana,
                trailingRomaji = trailingRomaji, // ← 🌟ここが抜けていました！
                prevRid = prevRid,
                matrix = matrix,
                limit = 10
            )
        } else {
            emptyList()
        }

        // ==========================================
        // 🌟 修正箇所2：打ちかけ状態（tなど）なら「予測」を優先し、そうでないなら「Viterbi」を優先！
        // ==========================================
        if (trailingRomaji.isNotEmpty()) {
            // 【パターンA: 打ちかけ（例：bibunhout）】
            // ユーザーは「微分方程式」などを探しているので、予測候補をトップに持ってくる
            for (cand in dbCandidates) {
                if (!finalCandidates.contains(cand)) {
                    finalCandidates.add(cand)
                }
            }
            // 「微分法t」などのローマ字混じりViterbi候補は、予測の下に降格させる
            for (cand in viterbiCandidates) {
                if (!finalCandidates.contains(cand)) {
                    finalCandidates.add(cand)
                }
            }
        } else {
            // 【パターンB: 打ち終わり（例：bibunhou）】
            // ユーザーは「微分法」という確定文字を探しているので、Viterbiをトップに持ってくる
            for (cand in viterbiCandidates) {
                if (!finalCandidates.contains(cand)) {
                    finalCandidates.add(cand)
                }
            }
            for (cand in dbCandidates) {
                if (!finalCandidates.contains(cand)) {
                    finalCandidates.add(cand)
                }
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