package com.example.mykeyboard

import android.view.inputmethod.InputConnection
import java.text.Normalizer

object TextProcessor {

    // マップ定義はそのまま
    private val superscriptMap = mapOf('0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹", '+' to "⁺", '-' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾", 'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ", 'f' to "ᶠ", 'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ", 'k' to "ᵏ", 'l' to "ˡ", 'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ", 'p' to "ᵖ", 'r' to "ʳ", 's' to "ˢ", 't' to "ᵗ", 'u' to "ᵘ", 'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ", 'z' to "ᶻ", 'A' to "ᴬ", 'B' to "ᴮ", 'D' to "ᴰ", 'E' to "ᴱ", 'G' to "ᴳ", 'H' to "ᴴ", 'I' to "ᴵ", 'J' to "ᴶ", 'K' to "ᴷ", 'L' to "ᴸ", 'M' to "ᴹ", 'N' to "ᴺ", 'O' to "ᴼ", 'P' to "ᴾ", 'R' to "ᴿ", 'T' to "ᵀ", 'U' to "ᵁ", 'V' to "ⱽ", 'W' to "ᵂ")
    private val subscriptMap = mapOf('0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄", '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉", '+' to "₊", '-' to "₋", '=' to "₌", '(' to "₍", ')' to "₎", 'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ", 'x' to "ₓ")
    private val greekMap = mapOf('a' to "α", 'A' to "Α", 'b' to "β", 'B' to "Β", 'c' to "ψ", 'C' to "Ψ", 'd' to "δ", 'D' to "Δ", 'e' to "ε", 'E' to "Ε", 'f' to "φ", 'F' to "Φ", 'g' to "γ", 'G' to "Γ", 'h' to "η", 'H' to "Η", 'i' to "ι", 'I' to "Ι", 'j' to "ξ", 'J' to "Ξ", 'k' to "κ", 'K' to "Κ", 'l' to "λ", 'L' to "Λ", 'm' to "μ", 'M' to "Μ", 'n' to "ν", 'N' to "Ν", 'o' to "ο", 'O' to "Ο", 'p' to "π", 'P' to "Π", 'q' to "ϕ", 'Q' to "ϑ", 'r' to "ρ", 'R' to "Ρ", 's' to "σ", 'S' to "Σ", 't' to "τ", 'T' to "Τ", 'u' to "θ", 'U' to "Θ", 'v' to "ω", 'V' to "Ω", 'w' to "ϖ", 'W' to "ς", 'x' to "ξ", 'X' to "Ξ", 'y' to "υ", 'Y' to "Υ", 'z' to "ζ", 'Z' to "Ζ")
    private val symbolMap = mapOf('a' to "∀", 'A' to "&", 'b' to "∵", 'B' to "♭", 'c' to "⊂", 'C' to "⊆", 'd' to "∂", 'D' to "∇", 'e' to "∃", 'E' to "∈", 'f' to "∫", 'F' to "∬", 'g' to "≥", 'G' to "≧", 'h' to "#", 'H' to "⇔", 'i' to "∞", 'I' to "!", 'j' to "<", 'J' to ">", 'k' to "≅", 'K' to "≡", 'l' to "≤", 'L' to "≦", 'm' to "|", 'M' to "?", 'n' to "≠", 'N' to "¬", 'o' to "⊕", 'O' to "∅", 'p' to "∏", 'P' to "∐", 'q' to "≃", 'Q' to "∎", 'r' to "√", 'R' to "∋", 's' to "∑", 'S' to "∼", 't' to "∴", 'T' to "△", 'u' to "∪", 'U' to "⋁", 'v' to "⊃", 'V' to "⊇", 'w' to "→", 'W' to "←", 'x' to "×", 'X' to "⊗", 'y' to "⋂", 'Y' to "⋀", 'z' to "⇒", 'Z' to "⇐")
    private val scriptMap = mapOf('a' to "𝒶", 'A' to "𝒜", 'b' to "𝒷", 'B' to "ℬ", 'c' to "𝒸", 'C' to "𝒞", 'd' to "𝒹", 'D' to "𝒟", 'e' to "ℯ", 'E' to "ℰ", 'f' to "𝒻", 'F' to "ℱ", 'g' to "ℊ", 'G' to "𝒢", 'h' to "𝒽", 'H' to "ℋ", 'i' to "𝒾", 'I' to "ℐ", 'j' to "𝒿", 'J' to "𝒥", 'k' to "𝓀", 'K' to "𝒦", 'l' to "𝓁", 'L' to "ℒ", 'm' to "𝓂", 'M' to "ℳ", 'n' to "𝓃", 'N' to "𝒩", 'o' to "ℴ", 'O' to "𝒪", 'p' to "𝓅", 'P' to "𝒫", 'q' to "𝓆", 'Q' to "𝒬", 'r' to "𝓇", 'R' to "ℛ", 's' to "𝓈", 'S' to "𝒮", 't' to "𝓉", 'T' to "𝒯", 'u' to "𝓊", 'U' to "𝒰", 'v' to "𝓋", 'V' to "𝒱", 'w' to "𝓌", 'W' to "𝒲", 'x' to "𝓍", 'X' to "𝒳", 'y' to "𝓎", 'Y' to "𝒴", 'z' to "𝓏", 'Z' to "𝒵")

    // 🌟 劇的な最適化：オブジェクトを生成せず、直接文字列を構築する（爆速化）
    fun toBlackboard(str: String): String = buildString(str.length) {
        for (i in str.indices) {
            val c = str[i]
            when (c) {
                'C' -> append("ℂ"); 'H' -> append("ℍ"); 'N' -> append("ℕ")
                'P' -> append("ℙ"); 'Q' -> append("ℚ"); 'R' -> append("ℝ"); 'Z' -> append("ℤ")
                in 'A'..'Z' -> appendCodePoint(0x1D538 + (c - 'A'))
                in 'a'..'z' -> appendCodePoint(0x1D552 + (c - 'a'))
                in '0'..'9' -> appendCodePoint(0x1D7D8 + (c - '0'))
                else -> append(c)
            }
        }
    }

    fun toFraktur(str: String): String = buildString(str.length) {
        for (i in str.indices) {
            val c = str[i]
            when (c) {
                'C' -> append("ℭ"); 'H' -> append("ℌ"); 'I' -> append("ℑ"); 'R' -> append("ℜ"); 'Z' -> append("ℨ")
                in 'A'..'Z' -> appendCodePoint(0x1D504 + (c - 'A'))
                in 'a'..'z' -> appendCodePoint(0x1D51E + (c - 'a'))
                else -> append(c)
            }
        }
    }

    fun toTextbf(str: String): String = buildString(str.length) {
        for (i in str.indices) {
            val c = str[i]
            when (c) {
                in 'A'..'Z' -> appendCodePoint(0x1D400 + (c - 'A'))
                in 'a'..'z' -> appendCodePoint(0x1D41A + (c - 'a'))
                in '0'..'9' -> appendCodePoint(0x1D7CE + (c - '0'))
                else -> append(c)
            }
        }
    }

    fun toItalic(str: String): String = buildString(str.length) {
        for (i in str.indices) {
            val c = str[i]
            when (c) {
                'h' -> append("ℎ")
                in 'A'..'Z' -> appendCodePoint(0x1D434 + (c - 'A'))
                in 'a'..'z' -> appendCodePoint(0x1D44E + (c - 'a'))
                else -> append(c)
            }
        }
    }

    fun toFullWidth(str: String): String = buildString(str.length) {
        for (i in str.indices) {
            val c = str[i]
            when (c) {
                ' ' -> append("　")
                in '!'..'~' -> append((c.code + 0xFEE0).toChar())
                else -> append(c)
            }
        }
    }

    // 🌟 辞書引き系も buildString で最適化
    fun toGreek(str: String): String = buildString(str.length) {
        for (i in str.indices) { val c = str[i]; append(greekMap[c] ?: c) }
    }
    fun toMathsymbol(str: String): String = buildString(str.length) {
        for (i in str.indices) { val c = str[i]; append(symbolMap[c] ?: c) }
    }
    fun toMathscript(str: String): String = buildString(str.length) {
        for (i in str.indices) { val c = str[i]; append(scriptMap[c] ?: c) }
    }
    fun toSuperscript(str: String): String = buildString(str.length) {
        for (i in str.indices) { val c = str[i]; append(superscriptMap[c] ?: c) }
    }
    fun toSubscript(str: String): String = buildString(str.length) {
        for (i in str.indices) { val c = str[i]; append(subscriptMap[c] ?: c) }
    }

    // ==========================================
    // 入力および削除処理
    // ==========================================
    fun commitTextWithNormalization(ic: InputConnection?, textToCommit: String) {
        if (ic == null || textToCommit.isEmpty()) return

        // Iteratorを生成しない高速なチェック
        var isCombining = true
        for (i in textToCommit.indices) {
            val c = textToCommit[i]
            if (c !in '\u0300'..'\u036F' && c !in '\u20D0'..'\u20FF') {
                isCombining = false
                break
            }
        }

        if (isCombining) {
            val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (before.isNotEmpty() && !Character.isSurrogate(before[0])) {
                val combined = before + textToCommit
                val normalized = Normalizer.normalize(combined, Normalizer.Form.NFC)
                ic.deleteSurroundingText(1, 0)
                ic.commitText(normalized, 1)
                return
            }
        }
        ic.commitText(textToCommit, 1)
    }

    // 🚀 爆速版（IPC通信を排除したゼロ遅延コード）
    fun handleBackspace(ic: InputConnection?) {
        if (ic == null) return
        // ターゲットアプリに「Backspaceキーが押された」という信号だけを非同期で投げる
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
    }
}