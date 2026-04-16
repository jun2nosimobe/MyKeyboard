package com.example.mykeyboard

class CandidateManager(
    private val dbHelper: DictionaryDatabaseHelper,
    private val viterbiConverter: JapaneseConverter,
    private val composer: Composer
) {
    fun generateCandidates(state: KeyboardState): List<String> {
        val finalCandidates = mutableListOf<String>()
        val rawStr = state.composingText

        if (rawStr.isEmpty()) return finalCandidates

        if (state.isDirectRomajiMode) {
            finalCandidates.add(rawStr)
            if (rawStr.startsWith("\\") && rawStr.length > 1) {
                val dbCandidates = dbHelper.getCandidates(rawStr.substring(1), 10)
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

        val viterbiResult = if (cleanHiragana.isNotEmpty()) viterbiConverter.convert(cleanHiragana) else ""
        val exactViterbi = if (viterbiResult.isNotEmpty()) viterbiResult + trailingRomaji else ""

        val dbCandidates = if (cleanHiragana.isNotEmpty()) dbHelper.getCandidates(cleanHiragana, 10) else emptyList()

        finalCandidates.addAll(dbCandidates)

        if (exactViterbi.isNotEmpty() && !finalCandidates.contains(exactViterbi)) {
            finalCandidates.add(exactViterbi)
        }

        if (!finalCandidates.contains(hiraganaStr)) finalCandidates.add(hiraganaStr)
        val katakanaStr = hiraganaStr.map { if (it in 'ぁ'..'ん') it + 0x60 else it }.joinToString("")
        if (katakanaStr != hiraganaStr && !finalCandidates.contains(katakanaStr)) finalCandidates.add(katakanaStr)
        if (rawStr != hiraganaStr && !finalCandidates.contains(rawStr)) finalCandidates.add(rawStr)

        return finalCandidates
    }
}