package com.example.mykeyboard

class Composer {
    companion object {
        // 🌟 1. マップを Companion Object に配置し、クラス全体で共有する
        private val ROMAN_MAP = mapOf(
            "ltsu" to "っ", "xtsu" to "っ",
            "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
            "sha" to "しゃ", "shi" to "し",  "shu" to "しゅ", "she" to "しぇ", "sho" to "しょ",
            "cha" to "ちゃ", "chi" to "ち",  "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
            "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
            "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
            "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
            "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
            "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
            "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
            "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
            "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
            "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
            "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
            "fya" to "ふゃ", "fyu" to "ふゅ", "fyo" to "ふょ",
            "tya" to "ちゃ", "thu" to "ちゅ", "tye" to "ちぇ", "tyo" to "ちょ",
            "tha" to "てゃ", "thi" to "てぃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
            "dha" to "でゃ", "dhi" to "でぃ", "dhu" to "でゅ", "dhe" to "でぇ", "dho" to "でょ",
            "tsa" to "つぁ", "tsi" to "つぃ", "tsu" to "つ",   "tse" to "つぇ", "tso" to "つぉ",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
            "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "ltu" to "っ", "xtu" to "っ",
            "lwa" to "ゎ", "xwa" to "ゎ",
            "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
            "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
            "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
            "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
            "ha" to "は", "hi" to "ひ", "hu" to "ふ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
            "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
            "ya" to "や", "yu" to "ゆ", "yo" to "よ",
            "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
            "wa" to "わ", "wo" to "を",
            "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
            "za" to "ざ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
            "ja" to "じゃ", "ji" to "じ", "ju" to "じゅ", "je" to "じぇ", "jo" to "じょ",
            "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
            "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
            "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
            "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
            "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ",   "ve" to "ゔぇ", "vo" to "ゔぉ",
            "wi" to "うぃ", "we" to "うぇ", "ye" to "いぇ",
            "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
            "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            "-" to "ー",
            "nn" to "ん",
            "nna" to "んな", "nni" to "んに", "nnu" to "んぬ", "nne" to "んね", "nno" to "んの",
            "nnya" to "んにゃ", "nnyu" to "んにゅ", "nnyo" to "んにょ",
            "n" to "ん"
        )

        // 🌟 2. 逆引き用のマップを起動時に1回だけ構築 (O(1)の爆速アクセスを実現)
        // 例: REVERSE_MAP["し"] = "shi", REVERSE_MAP["きゃ"] = "kya"
        private val REVERSE_MAP = ROMAN_MAP.entries.associate { it.value to it.key }
    }
    // 🌟 HashMapすら使わない、C言語レベルの爆速Trieノード（ASCII 128文字分のアレイ）
    private class TrieNode {
        val children = Array<TrieNode?>(128) { null }
        var output: String? = null
    }

    private val root = TrieNode()
    private val CONSONANTS = setOf('k', 's', 't', 'h', 'm', 'r', 'w', 'g', 'z', 'd', 'b', 'p', 'j', 'c', 'f', 'l', 'v')

    init {
        // 起動時にTrie木を構築
        for ((key, value) in ROMAN_MAP) {
            insertTrie(key, value)
        }
    }
    private fun insertTrie(key: String, value: String) {
        var curr = root
        for (c in key) {
            val idx = c.code
            if (idx >= 128) continue // ASCII以外はスキップ
            if (curr.children[idx] == null) {
                curr.children[idx] = TrieNode()
            }
            curr = curr.children[idx]!!
        }
        curr.output = value
    }

    // 🌟 ゼロアロケーション・最長一致オートマトン
    fun convertRomajiToHiragana(romaji: String): String = buildString(romaji.length) {
        var i = 0
        val len = romaji.length

        while (i < len) {
            val c = romaji[i]

            // 1. 促音（っ）の処理：連続する子音 (例: kk -> っk)
            if (i + 1 < len && c == romaji[i + 1] && CONSONANTS.contains(c)) {
                append("っ")
                i++
                continue
            }

            // 2. Trie木による最長一致検索
            var curr = root
            var matchLen = 0
            var bestOutput: String? = null
            var j = i

            while (j < len) {
                val idx = romaji[j].code
                if (idx >= 128) break
                curr = curr.children[idx] ?: break

                if (curr.output != null) {
                    matchLen = j - i + 1
                    bestOutput = curr.output
                }
                j++
            }

            if (bestOutput != null) {
                append(bestOutput)
                i += matchLen // 一致した文字数だけポインタを進める
            } else {
                append(c) // マッチしなかったら生の文字を出力
                i++
            }
        }
    }
    // 🌟 ゼロアロケーション対応・バックスペース処理
    fun computeBackspace(currentRomaji: String, isDirectMode: Boolean): String {
        if (currentRomaji.isEmpty()) return ""

        if (isDirectMode) {
            return currentRomaji.dropLast(1)
        }

        val maxKeyLen = minOf(currentRomaji.length, 4)
        var matchedRomaji = ""
        var matchedHiragana = ""

        for (len in maxKeyLen downTo 1) {
            val suffix = currentRomaji.takeLast(len)
            // 🌟 3. クラス全体で共有された ROMAN_MAP を参照
            val hiragana = ROMAN_MAP[suffix]
            if (hiragana != null) {
                matchedRomaji = suffix
                matchedHiragana = hiragana
                break
            }
        }

        // 入力途中の子音など
        if (matchedRomaji.isEmpty()) {
            return currentRomaji.dropLast(1)
        }

        val newHiragana = matchedHiragana.dropLast(1)

        val newRomajiSuffix = if (newHiragana.isEmpty()) {
            ""
        } else {
            // 🌟 4. イテレータの生成を排除し、O(1) で即座に逆引き！
            REVERSE_MAP[newHiragana] ?: ""
        }

        val baseRomaji = currentRomaji.dropLast(matchedRomaji.length)
        return baseRomaji + newRomajiSuffix
    }
}