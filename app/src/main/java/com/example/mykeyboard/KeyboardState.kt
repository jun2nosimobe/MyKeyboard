package com.example.mykeyboard

// 🌟 キーボードが持ちうる「すべての状態」
data class KeyboardState(
    val composingText: String = "",
    val isDirectRomajiMode: Boolean = false,
    val currentMode: MathKeyboardService.InputMode = MathKeyboardService.InputMode.NORMAL,
    val shiftState: MathKeyboardService.ShiftState = MathKeyboardService.ShiftState.NORMAL,
    val isOneShotMode: Boolean = false,
    val lastConfirmedWord: String = "",
    // ==========================================
    // 🌟 NEW: 動的ヒットボックス用のパラメータ
    // ==========================================
    val lastKeyPressTime: Long = 0L,
    val predictedNextKeys: Map<String, Float> = emptyMap()
) {
    // 状態から派生する便利なプロパティ
    val isUpper: Boolean get() = shiftState == MathKeyboardService.ShiftState.SHIFTED || shiftState == MathKeyboardService.ShiftState.CAPSLOCKED
    val lastChar: Char? get() = if (composingText.isNotEmpty()) composingText.last() else null
}

// 🌟 ユーザーが起こしうる「すべてのアクション」
sealed class KeyboardEvent {
    data class KeyTapped(val buttonId: Int, val keyData: KeyData) : KeyboardEvent()
    data class DirectTextCommitted(val text: String) : KeyboardEvent()
    object SpaceTapped : KeyboardEvent()
    object EnterTapped : KeyboardEvent()
    object BackspaceTapped : KeyboardEvent()
    data class CandidateSelected(val text: String) : KeyboardEvent()
    data class ModeChanged(val mode: MathKeyboardService.InputMode) : KeyboardEvent()
    object ShiftToggled : KeyboardEvent()
}