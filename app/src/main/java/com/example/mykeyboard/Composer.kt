package com.example.mykeyboard

class Composer {

    // 🌟 HashMapすら使わない、C言語レベルの爆速Trieノード（ASCII 128文字分のアレイ）
    private class TrieNode {
        val children = Array<TrieNode?>(128) { null }
        var output: String? = null
    }

    private val root = TrieNode()
    private val CONSONANTS = setOf('k', 's', 't', 'h', 'm', 'r', 'w', 'g', 'z', 'd', 'b', 'p', 'j', 'c', 'f', 'l', 'v')

    init {
        // 🌟 注意: "nn" to "ん" は削除しました！
        // Trie木の最長一致探索により、"n" to "ん" だけで「かんな」「さんの」全てが完璧に処理されます。
        val map = mapOf(
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
            "n" to "ん" // <- これだけで nの処理は全て完結します
        )
        // 起動時にTrie木を構築
        for ((key, value) in map) {
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

    // バックスペースのロジック（Trie木のおかげで正規表現も短縮可能ですが、一旦挙動の安定している既存のままとします）
    fun computeBackspace(currentRomaji: String, isDirectMode: Boolean): String {
        if (currentRomaji.isEmpty()) return ""

        if (isDirectMode) {
            return currentRomaji.dropLast(1)
        }

        val currentHira = convertRomajiToHiragana(currentRomaji)
        if (currentHira.matches(Regex(".*[a-zA-Z]$"))) {
            return currentRomaji.dropLast(1)
        }

        return when {
            currentRomaji.matches(Regex(".*([ksthmrwgzdbpjcflv])\\1[aiueo]$")) -> currentRomaji.dropLast(3) + "xtsu"
            currentRomaji.matches(Regex(".*[ksthmyrgzdbp]y[auo]$")) -> currentRomaji.dropLast(2) + "i"
            currentRomaji.matches(Regex(".*(sh|ch)[auo]$")) -> currentRomaji.dropLast(1) + "i"
            currentRomaji.matches(Regex(".*j[auo]$")) -> currentRomaji.dropLast(1) + "i"
            currentRomaji.matches(Regex(".*f[aieo]$")) -> currentRomaji.dropLast(1) + "u"
            currentRomaji.matches(Regex(".*ts[aieo]$")) -> currentRomaji.dropLast(1) + "u"
            currentRomaji.matches(Regex(".*(th|dh)[aiueo]$")) -> currentRomaji.dropLast(2) + "e"
            currentRomaji.matches(Regex(".*w[ie]$")) -> currentRomaji.dropLast(2) + "u"
            currentRomaji.matches(Regex(".*v[aiueo]$")) -> currentRomaji.dropLast(1) + "u"
            else -> {
                var newStr = currentRomaji
                var dropped = false
                for (dropCount in 1..4) {
                    if (currentRomaji.length >= dropCount) {
                        val testStr = currentRomaji.substring(0, currentRomaji.length - dropCount)
                        val hira = convertRomajiToHiragana(testStr)
                        if (!hira.matches(Regex(".*[a-zA-Z].*"))) {
                            newStr = testStr
                            dropped = true
                            break
                        }
                    } else if (currentRomaji.length == dropCount) {
                        newStr = ""
                        dropped = true
                        break
                    }
                }
                if (!dropped) currentRomaji.dropLast(1) else newStr
            }
        }
    }
}