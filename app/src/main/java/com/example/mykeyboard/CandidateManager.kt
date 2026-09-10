package com.example.mykeyboard

class CandidateManager(
    private val dbHelper: DictionaryDatabaseHelper,
    private val engDbHelper: EnglishDictionaryHelper,
    private val viterbiConverter: JapaneseConverter,
    private val composer: Composer,
    private val matrix: MatrixManager
) {
    // 🌟 「ひらがな二文字以下は候補から除外」への対応。
    // 変換候補(辞書完全一致・Viterbi結果)の中に、漢字化されていない生のひらがなが
    // 1〜2文字だけ混じっていると、意味のある変換候補に紛れて選びにくくなる
    // (ユーザーが本当にひらがなのまま確定したい場合は、常に末尾に追加される
    // 「入力そのまま」の候補(hiraganaStr等、下のfallback群)で対応できるので、
    // このフィルタで除外しても入力の自由度は失われない)。
    private fun isShortHiraganaNoise(word: String): Boolean {
        if (word.isEmpty() || word.length > 2) return false
        return word.all { it in 'ぁ'..'ん' || it == 'ー' }
    }

    // 🌟 「フリック入力時は。、？！を変換候補として.,?!を出したい」への対応
    private val punctuationHalfWidth = mapOf('。' to '.', '、' to ',', '？' to '?', '！' to '!')

    // 🌟 修正: 戻り値を List<Pair<String, String>> に変更
    // isFlickInputMode: フリック入力が有効な時だけtrue。「最後に入力された文字に
    // 濁点の処理が加えられる可能性を考慮して候補を表示したい」への対応で使う
    // (詳細は末尾のappendDakutenLookaheadCandidatesを参照)。
    fun generateCandidates(state: KeyboardState, isFlickInputMode: Boolean = false): List<Pair<String, String>> {

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
                    finalCandidates.addAll(predictions.filterNot { isShortHiraganaNoise(it.first) })
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
            val isAllUpperCase = rawStr.length > 1 && rawStr.any { it.isLetter() } && rawStr.filter { it.isLetter() }.all { it.isUpperCase() }

            // 1文字以上の入力で、先頭が大文字の場合（1文字だけ大文字の場合もここに入る）
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

            val exactMatchExists = engSuggestions.any { it.equals(rawStr, ignoreCase = true) }

            if (finalCandidates.none { it.first == rawStr }) {
                if (exactMatchExists) {
                    // 辞書に完全一致する単語がある場合は、安心して一番左（先頭）に確定候補として置く
                    finalCandidates.add(0, Pair(rawStr, rawStr))
                } else {
                    // 入力途中など、完全一致しない場合はサジェストの邪魔にならないよう一番右（最後尾）に置く
                    finalCandidates.add(Pair(rawStr, rawStr))
                }
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
        // trailingRomaji (保留中の子音) がある時は Viterbi 自身にそれを渡し、辞書側の
        // 文字クラスGLOBで正しく予測変換させる。以前は Viterbi の結果に生の romaji 文字
        // をそのまま連結していたため「局所t」のような文字化けした候補が出ていた。
        val viterbiResults = if (cleanHiragana.isNotEmpty()) viterbiConverter.convert(
            cleanHiragana,
            trailingRomaji = trailingRomaji,
            limit = 10
        ) else emptyList()
        val viterbiCandidates = viterbiResults.map {
            Pair(it, cleanHiragana + trailingRomaji)
        }.filterNot { isShortHiraganaNoise(it.first) }

        val prevRid = state.lastConfirmedWord.takeIf { it.isNotEmpty() }?.let {
            dbHelper.getRidForWord(it)
        } ?: 0

        val dbCandidates = (if (cleanHiragana.isNotEmpty() || trailingRomaji.isNotEmpty()) {
            dbHelper.getCandidates(
                hiragana = cleanHiragana,
                trailingRomaji = trailingRomaji,
                prevRid = prevRid,
                matrix = matrix,
                limit = 10
            )
        } else emptyList()).filterNot { isShortHiraganaNoise(it.first) }

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

        // 🌟 「変換候補で最後に入力された文字に濁点の処理が加えられる可能性を
        // 考慮して候補を表示したい（フリック入力時限定）」への対応。
        // フリック入力では、かな1文字を打った直後に「゛゜」キーで濁点/半濁点/
        // 小文字化することがよくある(か→が等)。その操作をする前から、した後の
        // 変換候補も先読みして混ぜておくことで、わざわざ濁点キーを押さなくても
        // 目的の候補を選べるようにする。
        if (isFlickInputMode) {
            appendDakutenLookaheadCandidates(hiraganaStr, prevRid, finalCandidates)
            appendPunctuationHalfWidthCandidate(hiraganaStr, finalCandidates)
        }

        return finalCandidates
    }

    // 🌟 「フリック入力時は。、？！を変換候補として.,?!を出したい」への対応。
    // 句読点キーはcomposingバッファへの追記になったので(KeyboardController.
    // handlePunctuationTapped参照)、末尾が。、？！のいずれかであれば、同じ位置の
    // 半角記号版も候補として追加する。
    private fun appendPunctuationHalfWidthCandidate(
        hiraganaStr: String,
        finalCandidates: MutableList<Pair<String, String>>
    ) {
        if (hiraganaStr.isEmpty()) return
        val lastChar = hiraganaStr.last()
        val halfWidth = punctuationHalfWidth[lastChar] ?: return
        val altStr = hiraganaStr.dropLast(1) + halfWidth
        if (finalCandidates.none { it.first == altStr }) finalCandidates.add(Pair(altStr, altStr))
    }

    private fun appendDakutenLookaheadCandidates(
        hiraganaStr: String,
        prevRid: Int,
        finalCandidates: MutableList<Pair<String, String>>
    ) {
        if (hiraganaStr.isEmpty()) return
        val lastChar = hiraganaStr.last()
        val prefix = hiraganaStr.dropLast(1)

        var variant = FlickKeyDatabase.dakutenCycle[lastChar]
        var steps = 0
        while (variant != null && variant != lastChar && steps < 3) {
            val altHiragana = prefix + variant

            // 🌟 注意: ここでviterbiConverter.convert()を呼んではいけない。convert()は
            // 呼ぶたびに内部の差分計算用キャッシュ(lastInput/cachedDp)をこの引数の
            // 文字列で上書きしてしまうため、先読み用に別のかな文字列を渡すと、
            // 次の本当のキー入力時にキャッシュの基準がズレて変換が狂う
            // (「濁点先読みが機能していなさそう」の原因はこれだった)。
            // dbHelper.getCandidates()は状態を持たない単純なDB検索なので安全に呼べる。
            val altDbCandidates = dbHelper.getCandidates(
                hiragana = altHiragana,
                trailingRomaji = "",
                prevRid = prevRid,
                matrix = matrix,
                limit = 5
            ).filterNot { isShortHiraganaNoise(it.first) }
            for (cand in altDbCandidates) {
                if (finalCandidates.none { it.first == cand.first }) finalCandidates.add(cand)
            }

            if (finalCandidates.none { it.first == altHiragana }) finalCandidates.add(Pair(altHiragana, altHiragana))

            variant = FlickKeyDatabase.dakutenCycle[variant]
            steps++
        }
    }
}