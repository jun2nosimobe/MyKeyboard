package com.example.mykeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

class MathKeyboardService : InputMethodService() {

    // 🌟 Enum定義はIMEの「状態」を定義するものとしてここに保持
    enum class InputMode(
        val shortName: String,
        val displayName: String,
        val transformer: (String) -> String
    ) {
        NORMAL("NOR", "Normal", { it }),
        BLACKBOARD("BB", "Blackboard", { TextProcessor.toBlackboard(it) }),
        FRAKTUR("FRK", "Fraktur", { TextProcessor.toFraktur(it) }),
        MATHCAL("CAL", "Mathcal", { TextProcessor.toMathscript(it) }),
        TEXTBF("B", "Bold", { TextProcessor.toTextbf(it) }),
        GREEK("GRK", "Greek", { TextProcessor.toGreek(it) }),
        MATHSCRIPT("SCR", "Script", { TextProcessor.toMathscript(it) }),
        MATHSYMBOL("SYM", "Symbol", { TextProcessor.toMathsymbol(it) }),
        SUPERSCRIPT("SUP", "Super (^高)", { TextProcessor.toSuperscript(it) }),
        SUBSCRIPT("SUB", "Sub (_低)", { TextProcessor.toSubscript(it) }),
        ITALIC("ITA", "Italic", { TextProcessor.toItalic(it) }),
        FULLWIDTH("FUL", "全角", { TextProcessor.toFullWidth(it) }),

        JAPANESE("JA", "日本語", { it });

        fun resolveText(
            context: android.content.Context,
            themeManager: KeyboardThemeManager,
            buttonId: Int,
            keyData: KeyData,
            isUpper: Boolean
        ): String {
            val suffix = if (isUpper) "normalShift" else "normal"
            val defaultText = if (isUpper) keyData.normalShift else keyData.normal
            val baseText = themeManager.getCustomText(context, buttonId, suffix, defaultText)
            return transformer.invoke(baseText)
        }
    }

    enum class ShiftState { NORMAL, SHIFTED, CAPSLOCKED }

    private lateinit var keyboardView: View
    private lateinit var themeManager: KeyboardThemeManager
    private lateinit var controller: KeyboardController

    override fun onCreate() {
        super.onCreate()
        themeManager = KeyboardThemeManager(this)
    }

    override fun onCreateInputView(): View {
        // XMLのインフレート
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)

        // 🌟 Controllerの初期化（パイプラインの心臓部）
        controller = KeyboardController(
            context = this,
            themeManager = themeManager,
            keyboardView = keyboardView,
            requestUpdateLabels = { updateKeyboardLabels() }
        )

        // 現在の入力コネクションを渡し、セットアップを開始
        controller.currentInputConnection = currentInputConnection
        controller.setupKeyboard()

        themeManager.reloadBackground(keyboardView)

        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // 接続先が切り替わる可能性があるため、常に最新のコネクションをControllerに更新
        controller.currentInputConnection = currentInputConnection

        themeManager.reloadBackground(keyboardView)
        updateKeyboardLabels()
    }

    /**
     * 表示の更新処理（Controllerからのコールバックなどで呼び出される）
     */
    private fun updateKeyboardLabels() {
        if (!::keyboardView.isInitialized) return

        val isUpper = (controller.shiftState == ShiftState.SHIFTED || controller.shiftState == ShiftState.CAPSLOCKED)

        // 全キーの印字更新
        for ((buttonId, keyData) in KeyDatabase.keys) {
            val button = keyboardView.findViewById<TextView>(buttonId) ?: continue
            button.text = controller.currentMode.resolveText(this, themeManager, buttonId, keyData, isUpper)
        }

        // シフトボタンの見た目更新
        keyboardView.findViewById<TextView>(R.id.btn_shift)?.text = when (controller.shiftState) {
            ShiftState.NORMAL -> "⇧"
            ShiftState.SHIFTED -> "⬆"
            ShiftState.CAPSLOCKED -> "⇪"
        }

        // モードボタンのテキスト更新
        keyboardView.findViewById<TextView>(R.id.btn_mode)?.text = controller.currentMode.shortName
    }
}