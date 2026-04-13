package com.example.mykeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class KeyboardController(
    private val context: Context,
    private val themeManager: KeyboardThemeManager,
    private val keyboardView: View,
    private val requestUpdateLabels: () -> Unit
) {
    var shiftState = MathKeyboardService.ShiftState.NORMAL
    var currentMode = MathKeyboardService.InputMode.NORMAL
    var isOneShotMode = false
    var currentInputConnection: InputConnection? = null

    private var lastShiftTime: Long = 0
    private val DOUBLE_TAP_TIMEOUT = 400L

    // IME入力バッファ（未確定のローマ字・ひらがなを保持する）
    private val composingText = java.lang.StringBuilder()

    // 候補表示用のView
    private val candidateScroll: HorizontalScrollView? = keyboardView.findViewById(R.id.candidate_scroll)
    private val candidateLayout: LinearLayout? = keyboardView.findViewById(R.id.candidate_layout)

    private val deleteHandler = Handler(Looper.getMainLooper())
    private var isDeleting = false
    private val deleteRunnable = object : Runnable {
        override fun run() {
            if (isDeleting) {
                handleBackspaceLogic()
                deleteHandler.postDelayed(this, 50)
            }
        }
    }

    private fun getRippleResource(): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    fun setupKeyboard() {
        val rippleResId = getRippleResource()

        for ((buttonId, keyData) in KeyDatabase.keys) {
            val button = keyboardView.findViewById<TextView>(buttonId) ?: continue

            val touchHandler = TouchEventHandler(
                onSingleTap = { handleKeyTap(buttonId, keyData) },
                onLongPressSetup = {
                    val isUpper = (shiftState == MathKeyboardService.ShiftState.SHIFTED || shiftState == MathKeyboardService.ShiftState.CAPSLOCKED)
                    val baseNormalText = if (isUpper) themeManager.getCustomText(context, buttonId, "normalShift", keyData.normalShift) else themeManager.getCustomText(context, buttonId, "normal", keyData.normal)
                    val fontOptions = listOf(TextProcessor.toGreek(baseNormalText), TextProcessor.toMathsymbol(baseNormalText))
                    val lpNormalString = themeManager.getCustomText(context, buttonId, "longPressNormal", keyData.longPressNormal.joinToString(" "))
                    val lpShiftString = themeManager.getCustomText(context, buttonId, "longPressShift", keyData.longPressShift.joinToString(" "))
                    val customSymbolList = if (isUpper) lpShiftString.split(" ").filter { it.isNotEmpty() } else lpNormalString.split(" ").filter { it.isNotEmpty() }

                    val allOptions = (fontOptions + customSymbolList).filter { it.isNotEmpty() }.distinct()
                    PopupManager.createNormalKeyPopup(context, button, rippleResId, allOptions) { char ->
                        commitDirectText(char)
                    }
                },
                onFlick = { direction -> handleKeyFlick(buttonId, direction) },
                getRippleResource = { rippleResId }
            )
            button.setOnTouchListener(touchHandler)
        }

        setupModeKey(rippleResId)
        setupDeleteKey()
        setupShiftKey()
        setupSpaceKey()
        setupEnterKey()
    }

    // ==========================================
    // 入力ロジックのコア（IMEパイプライン）
    // ==========================================

    private fun handleKeyTap(buttonId: Int, keyData: KeyData) {
        val isUpper = (shiftState == MathKeyboardService.ShiftState.SHIFTED || shiftState == MathKeyboardService.ShiftState.CAPSLOCKED)
        val textToInput = currentMode.resolveText(context, themeManager, buttonId, keyData, isUpper)

        // IME処理：日本語モードで、かつアルファベットが入力された場合
        if (currentMode == MathKeyboardService.InputMode.JAPANESE && textToInput.length == 1 && textToInput[0].isLetter()) {
            composingText.append(textToInput)
            currentInputConnection?.setComposingText(composingText.toString(), 1)
            updateCandidates()
        } else {
            // 日本語モード以外、または記号などは直接入力
            commitDirectText(textToInput)
        }
    }

    // 文字を直接確定させる（バッファもクリア）
    private fun commitDirectText(text: String) {
        if (composingText.isNotEmpty()) {
            currentInputConnection?.commitText(composingText.toString(), 1)
            composingText.clear()
            updateCandidates()
        }
        TextProcessor.commitTextWithNormalization(currentInputConnection, text)
        resetStateAfterInput()
    }

    // バックスペースの処理
    private fun handleBackspaceLogic() {
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            if (composingText.isEmpty()) {
                currentInputConnection?.commitText("", 1)
            } else {
                currentInputConnection?.setComposingText(composingText.toString(), 1)
            }
            updateCandidates()
        } else {
            TextProcessor.handleBackspace(currentInputConnection)
        }
    }

    // ==========================================
    // フリック処理（完全版）
    // ==========================================
    private fun handleKeyFlick(buttonId: Int, direction: TouchEventHandler.FlickDirection) {
        if (direction == TouchEventHandler.FlickDirection.DOWN) {
            val flickMode = when (buttonId) {
                R.id.btn_g -> MathKeyboardService.InputMode.GREEK
                R.id.btn_b -> MathKeyboardService.InputMode.BLACKBOARD
                R.id.btn_c -> MathKeyboardService.InputMode.MATHCAL
                R.id.btn_v -> MathKeyboardService.InputMode.TEXTBF
                // 🌟 省略されてしまっていたフリックをすべて復活！
                R.id.btn_s -> MathKeyboardService.InputMode.MATHSCRIPT
                R.id.btn_f -> MathKeyboardService.InputMode.FRAKTUR
                R.id.btn_n -> MathKeyboardService.InputMode.NORMAL
                R.id.btn_caret -> MathKeyboardService.InputMode.SUPERSCRIPT
                R.id.btn_underscore -> MathKeyboardService.InputMode.SUBSCRIPT
                R.id.btn_i -> MathKeyboardService.InputMode.ITALIC
                R.id.btn_z -> MathKeyboardService.InputMode.FULLWIDTH
                // 🌟 追加: jで日本語モードへ
                R.id.btn_j -> MathKeyboardService.InputMode.JAPANESE
                else -> null
            }
            if (flickMode != null) {
                currentMode = flickMode
                // 🌟 修正: NORMAL と JAPANESE は1文字打っても戻らない（ワンショットにしない）
                isOneShotMode = (flickMode != MathKeyboardService.InputMode.NORMAL && flickMode != MathKeyboardService.InputMode.JAPANESE)
                requestUpdateLabels()
            }
        }
    }

    // ==========================================
    // 変換候補の表示・更新ロジック
    // ==========================================
    private fun updateCandidates() {
        candidateLayout?.removeAllViews()

        if (composingText.isEmpty()) {
            candidateScroll?.visibility = View.GONE
            return
        }

        candidateScroll?.visibility = View.VISIBLE
        val rawStr = composingText.toString()

        val dummyCandidates = listOf(
            rawStr,
            rawStr.uppercase(),
            "∀$rawStr",
            "数学用語テスト"
        )

        val rippleResId = getRippleResource()

        for (candidate in dummyCandidates) {
            val tv = TextView(context).apply {
                text = candidate
                textSize = 18f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(30, 20, 30, 20)
                setBackgroundResource(rippleResId)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    currentInputConnection?.commitText(candidate, 1)
                    composingText.clear()
                    updateCandidates()
                    resetStateAfterInput()
                }
            }
            candidateLayout?.addView(tv)
        }
    }

    // ==========================================
    // 特殊キーの処理
    // ==========================================

    private fun setupSpaceKey() {
        keyboardView.findViewById<TextView>(R.id.btn_space)?.setOnClickListener {
            if (composingText.isNotEmpty()) {
                currentInputConnection?.commitText(composingText.toString(), 1)
                composingText.clear()
                updateCandidates()
            } else {
                currentInputConnection?.commitText(" ", 1)
            }
        }
    }

    private fun setupEnterKey() {
        keyboardView.findViewById<TextView>(R.id.btn_enter)?.setOnClickListener {
            if (composingText.isNotEmpty()) {
                currentInputConnection?.commitText(composingText.toString(), 1)
                composingText.clear()
                updateCandidates()
            } else {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    private fun setupDeleteKey() {
        val btnDelete = keyboardView.findViewById<TextView>(R.id.btn_delete) ?: return
        btnDelete.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDeleting = true; v.isPressed = true
                    handleBackspaceLogic()
                    deleteHandler.postDelayed(deleteRunnable, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDeleting = false; v.isPressed = false
                    deleteHandler.removeCallbacks(deleteRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun resetStateAfterInput() {
        var needsUpdate = false
        if (shiftState == MathKeyboardService.ShiftState.SHIFTED) {
            shiftState = MathKeyboardService.ShiftState.NORMAL
            needsUpdate = true
        }
        if (isOneShotMode) {
            currentMode = MathKeyboardService.InputMode.NORMAL
            isOneShotMode = false
            needsUpdate = true
        }
        if (needsUpdate) {
            requestUpdateLabels()
        }
    }

    private fun setupShiftKey() {
        val btnShift = keyboardView.findViewById<TextView>(R.id.btn_shift) ?: return
        btnShift.setOnClickListener {
            val now = System.currentTimeMillis()
            shiftState = when {
                shiftState == MathKeyboardService.ShiftState.NORMAL -> MathKeyboardService.ShiftState.SHIFTED
                shiftState == MathKeyboardService.ShiftState.SHIFTED && now - lastShiftTime < DOUBLE_TAP_TIMEOUT -> MathKeyboardService.ShiftState.CAPSLOCKED
                else -> MathKeyboardService.ShiftState.NORMAL
            }
            lastShiftTime = now
            requestUpdateLabels()
        }
    }

    private fun setupModeKey(rippleResId: Int) {
        val btnMode = keyboardView.findViewById<TextView>(R.id.btn_mode) ?: return
        val touchHandler = TouchEventHandler(
            onSingleTap = {
                currentMode = if (currentMode == MathKeyboardService.InputMode.NORMAL) MathKeyboardService.InputMode.MATHSYMBOL else MathKeyboardService.InputMode.NORMAL
                isOneShotMode = false
                requestUpdateLabels()
            },
            onLongPressSetup = {
                PopupManager.createModeKeyPopup(
                    context = context, anchorView = btnMode, rippleResId = rippleResId,
                    onModeSelected = { m -> currentMode = m; isOneShotMode = false; requestUpdateLabels() },
                    onSymbolSelected = { sym -> commitDirectText(sym) },
                    onBackspaceSelected = { handleBackspaceLogic() },
                    onSpaceSelected = { currentInputConnection?.commitText(" ", 1) },
                    onSettingsColorToggle = { themeManager.toggleKeyTextColor(); themeManager.updateAllTextColors(keyboardView) },
                    onSettingsAlphaChanged = { p -> themeManager.setBgAlpha(p / 100f, keyboardView) },
                    onSettingsBrightnessChanged = { p -> themeManager.setBgColorPacked(Color.rgb(p, p, p), keyboardView) },
                    onSettingsDetailClicked = {
                        val intent = Intent(context, SettingsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
                        try { context.startActivity(intent) } catch (e: Exception) {}
                    },
                    currentKeyTextColor = themeManager.keyTextColor, currentBgAlpha = themeManager.bgAlpha, currentBgColorPacked = themeManager.bgColorPacked
                )
            },
            onFlick = {}, getRippleResource = { rippleResId }
        )
        btnMode.setOnTouchListener(touchHandler)
    }
}