package com.example.mykeyboard

import android.content.Context
import android.graphics.Color
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KeyboardController(
    private val context: Context,
    private val themeManager: KeyboardThemeManager,
    private val keyboardView: View,
    private val requestUpdateLabels: () -> Unit
) {
    // 🌟 MVIアーキテクチャの要：状態 (State)
    var state = KeyboardState()
        private set

    var currentInputConnection: InputConnection? = null

    // 🌟 シフトキーのダブルタップ判定用タイマー
    private var lastShiftTime: Long = 0
    private val DOUBLE_TAP_TIMEOUT = 400L

    // 各モジュールのインスタンス化
    private val composer = Composer()
    private val dbHelper = DictionaryDatabaseHelper(context)
    private val matrixManager = MatrixManager(context)
    private val viterbiConverter = JapaneseConverter(dbHelper, matrixManager)
    private val candidateManager = CandidateManager(dbHelper, viterbiConverter, composer, matrixManager)

    // UI系の参照
    private val candidateScroll: HorizontalScrollView? = keyboardView.findViewById(R.id.candidate_scroll)
    private val candidateLayout: LinearLayout? = keyboardView.findViewById(R.id.candidate_layout)

    private val deleteHandler = Handler(Looper.getMainLooper())
    private var isDeleting = false
    private val deleteRunnable = object : Runnable {
        override fun run() {
            if (isDeleting) {
                dispatch(KeyboardEvent.BackspaceTapped)
                deleteHandler.postDelayed(this, 50)
            }
        }
    }

    // 🌟 動的タッチルーター用のプロパティ群
    private val dynamicRouterViews = mutableMapOf<Int, View>()
    private val dynamicRouterHandlers = mutableMapOf<Int, TouchEventHandler>()
    private val activeTargetIds = mutableMapOf<Int, Int>()
    private val keyCentersRel = mutableMapOf<Int, Pair<Float, Float>>()
    private var isCacheInitialized = false

    private val VOWELS = setOf("a", "i", "u", "e", "o")
    private val CONSONANTS = setOf("k", "s", "t", "n", "h", "m", "y", "r", "w", "g", "z", "d", "b", "p", "j", "c", "f", "l", "v", "q", "x")
    private val defaultKeyWeights = mapOf("a" to 0.9f, "i" to 0.9f, "u" to 0.9f, "e" to 0.9f, "o" to 0.9f, "k" to 0.95f, "s" to 0.95f, "t" to 0.95f, "n" to 0.95f, "q" to 1.1f, "j" to 1.1f, "v" to 1.1f, "l" to 1.1f)

    init {
        Thread { matrixManager.loadMatrix() }.start()
    }

    // 🌟 復活：タップ時の波紋エフェクトを取得
    private fun getRippleResource(): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    // ==========================================
    // 🌟 MVIパターン：イベント（Intent）の受付と状態更新
    // ==========================================
    fun dispatch(event: KeyboardEvent) {
        when (event) {
            is KeyboardEvent.KeyTapped -> handleKeyTapped(event.buttonId, event.keyData)
            is KeyboardEvent.DirectTextCommitted -> commitDirectText(event.text)
            is KeyboardEvent.SpaceTapped -> forceCommitComposingText(appendSpace = true)
            is KeyboardEvent.EnterTapped -> handleEnterTapped()
            is KeyboardEvent.BackspaceTapped -> handleBackspace()
            is KeyboardEvent.CandidateSelected -> handleCandidateSelected(event.text) // 🌟 ここを専用関数に変更！
            is KeyboardEvent.ModeChanged -> handleModeChanged(event.mode)
            is KeyboardEvent.ShiftToggled -> handleShiftToggled()
        }
    }
    // 🌟 候補をタップした時専用の処理（ひらがなを上書きして確定する）
    private fun handleCandidateSelected(candidate: String) {
        val rawStr = state.composingText

        // 🌟 修正：生のローマ字を「綺麗なひらがな」に変換し、末尾の打ちかけの子音を除去する
        val hiraganaStr = composer.convertRomajiToHiragana(rawStr)
        val cleanHiragana = hiraganaStr.replace(Regex("[a-zA-Z-]+$"), "")

        // 🌟 ここで学習を実行！
        if (cleanHiragana.isNotEmpty() && candidate.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                dbHelper.learnWord(candidate, cleanHiragana) // 綺麗になった yomi を渡す

                // (確認用：ダンプを残しておく場合はここ)
                // dbHelper.dumpUserHistory()
            }
        }

        // 現在の「未確定のひらがな」を、選択された「候補（漢字など）」で上書き確定する
        currentInputConnection?.commitText(candidate, 1)

        // バッファを空にして、確定単語を記憶（次単語予測用）
        state = state.copy(
            composingText = "",
            isDirectRomajiMode = false,
            lastConfirmedWord = candidate
        )
        updateUI()

        // 入力後の状態リセット（Shiftの一回解除や、1ショットモードの解除）
        var needsUpdate = false
        var newShift = state.shiftState
        var newMode = state.currentMode
        var newOneShot = state.isOneShotMode

        if (state.shiftState == MathKeyboardService.ShiftState.SHIFTED) {
            newShift = MathKeyboardService.ShiftState.NORMAL
            needsUpdate = true
        }
        if (state.isOneShotMode) {
            newMode = MathKeyboardService.InputMode.NORMAL
            newOneShot = false
            needsUpdate = true
        }
        state = state.copy(shiftState = newShift, currentMode = newMode, isOneShotMode = newOneShot)
        if (needsUpdate) requestUpdateLabels()
    }

    private fun handleKeyTapped(buttonId: Int, keyData: KeyData) {
        val textToInput = state.currentMode.resolveText(context, themeManager, buttonId, keyData, state.isUpper)

        val canCompose = state.currentMode == MathKeyboardService.InputMode.JAPANESE && textToInput.length == 1 &&
                (textToInput[0].isLetterOrDigit() || textToInput[0] == '-' || textToInput[0] == '\\')

        if (canCompose) {
            var newDirectMode = state.isDirectRomajiMode
            var newShiftState = state.shiftState

            // バックスラッシュ( \ ) または Shift入力時は直接(ローマ字)モードへ！
            if ((state.isUpper || textToInput == "\\") && !state.isDirectRomajiMode) {
                if (state.composingText.isNotEmpty()) forceCommitComposingText(appendSpace = false)
                newDirectMode = true
            }

            val newComposing = state.composingText + textToInput

            // シフトで1文字打ったら、自動で小文字(NORMAL)に戻す！
            if (state.shiftState == MathKeyboardService.ShiftState.SHIFTED) {
                newShiftState = MathKeyboardService.ShiftState.NORMAL
                requestUpdateLabels()
            }

            state = state.copy(
                composingText = newComposing,
                isDirectRomajiMode = newDirectMode,
                shiftState = newShiftState
                // 🌟 補足: ここでは lastConfirmedWord はあえて更新・クリアしません。
                // ユーザーが文字を打ち始めた瞬間、CandidateManager は composingText を見て通常のViterbi変換に切り替わるからです。
            )
            updateUI()
        } else {
            commitDirectText(textToInput)
        }
    }
    private fun handleBackspace() {
        if (state.composingText.isNotEmpty()) {
            val newRomaji = composer.computeBackspace(state.composingText, state.isDirectRomajiMode)
            state = state.copy(
                composingText = newRomaji,
                isDirectRomajiMode = if (newRomaji.isEmpty()) false else state.isDirectRomajiMode
            )
            updateUI()
        } else {
            TextProcessor.handleBackspace(currentInputConnection)
        }
    }

    private fun handleEnterTapped() {
        if (state.composingText.isNotEmpty()) {
            forceCommitComposingText(appendSpace = false)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun handleModeChanged(mode: MathKeyboardService.InputMode) {
        forceCommitComposingText(appendSpace = false)
        state = state.copy(
            currentMode = mode,
            isOneShotMode = (mode != MathKeyboardService.InputMode.NORMAL && mode != MathKeyboardService.InputMode.JAPANESE)
        )
        requestUpdateLabels()
    }

    private fun handleShiftToggled() {
        val now = System.currentTimeMillis()
        val newState = when {
            state.shiftState == MathKeyboardService.ShiftState.NORMAL -> MathKeyboardService.ShiftState.SHIFTED
            state.shiftState == MathKeyboardService.ShiftState.SHIFTED && now - lastShiftTime < DOUBLE_TAP_TIMEOUT -> MathKeyboardService.ShiftState.CAPSLOCKED
            else -> MathKeyboardService.ShiftState.NORMAL
        }
        lastShiftTime = now
        state = state.copy(shiftState = newState)
        requestUpdateLabels()
    }

    private fun forceCommitComposingText(appendSpace: Boolean) {
        if (state.composingText.isEmpty()) {
            if (appendSpace) currentInputConnection?.commitText(" ", 1)
            return
        }

        val textToCommit = if (state.isDirectRomajiMode) {
            val raw = state.composingText
            if (appendSpace) "$raw " else raw
        } else {
            val hiragana = composer.convertRomajiToHiragana(state.composingText)
            if (appendSpace) "$hiragana " else hiragana
        }

        currentInputConnection?.commitText(textToCommit, 1)
        state = state.copy(composingText = "", isDirectRomajiMode = false)
        updateUI()
    }

    private fun commitDirectText(text: String) {
        forceCommitComposingText(appendSpace = false)
        TextProcessor.commitTextWithNormalization(currentInputConnection, text)

        var needsUpdate = false
        var newShift = state.shiftState
        var newMode = state.currentMode
        var newOneShot = state.isOneShotMode

        if (state.shiftState == MathKeyboardService.ShiftState.SHIFTED) {
            newShift = MathKeyboardService.ShiftState.NORMAL
            needsUpdate = true
        }
        if (state.isOneShotMode) {
            newMode = MathKeyboardService.InputMode.NORMAL
            newOneShot = false
            needsUpdate = true
        }
        state = state.copy(shiftState = newShift, currentMode = newMode, isOneShotMode = newOneShot)
        if (needsUpdate) requestUpdateLabels()
    }

    // ==========================================
    // 🌟 View の更新処理
    // ==========================================
    private fun updateUI() {
        // 1. InputConnection (プレビュー) の更新
        if (state.composingText.isEmpty()) {
            currentInputConnection?.commitText("", 1)
        } else {
            val previewText = if (state.isDirectRomajiMode) state.composingText else composer.convertRomajiToHiragana(state.composingText)
            currentInputConnection?.setComposingText(previewText, 1)
        }

        // 2. 候補バーの更新
        candidateLayout?.removeAllViews()
        val candidates = candidateManager.generateCandidates(state)

        if (candidates.isEmpty()) {
            candidateScroll?.visibility = View.GONE
            return
        }

        candidateScroll?.visibility = View.VISIBLE
        val rippleResId = getRippleResource()

        for (candidate in candidates) {
            val tv = TextView(context).apply {
                text = candidate
                textSize = 18f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(30, 20, 30, 20)
                setBackgroundResource(rippleResId)
                isClickable = true
                isFocusable = true

                setOnClickListener { dispatch(KeyboardEvent.CandidateSelected(candidate)) }
            }
            candidateLayout?.addView(tv)
        }
    }

    // ==========================================
    // タッチルーターとセットアップ
    // ==========================================
    fun setupKeyboard() {
        val rippleResId = getRippleResource()

        for ((buttonId, keyData) in KeyDatabase.keys) {
            val button = keyboardView.findViewById<TextView>(buttonId) ?: continue

            val touchHandler = TouchEventHandler(
                onSingleTap = { dispatch(KeyboardEvent.KeyTapped(buttonId, keyData)) },
                onLongPressSetup = {
                    val baseNormalText = if (state.isUpper) themeManager.getCustomText(context, buttonId, "normalShift", keyData.normalShift) else themeManager.getCustomText(context, buttonId, "normal", keyData.normal)
                    val fontOptions = listOf(TextProcessor.toGreek(baseNormalText), TextProcessor.toMathsymbol(baseNormalText))
                    val lpNormalString = themeManager.getCustomText(context, buttonId, "longPressNormal", keyData.longPressNormal.joinToString(" "))
                    val lpShiftString = themeManager.getCustomText(context, buttonId, "longPressShift", keyData.longPressShift.joinToString(" "))
                    val customSymbolList = if (state.isUpper) lpShiftString.split(" ").filter { it.isNotEmpty() } else lpNormalString.split(" ").filter { it.isNotEmpty() }

                    val allOptions = (fontOptions + customSymbolList).filter { it.isNotEmpty() }.distinct()
                    PopupManager.createNormalKeyPopup(context, button, rippleResId, allOptions) { char ->
                        dispatch(KeyboardEvent.DirectTextCommitted(char))
                    }
                },
                onFlick = { direction ->
                    if (direction == TouchEventHandler.FlickDirection.DOWN) {
                        val flickMode = when (buttonId) {
                            R.id.btn_g -> MathKeyboardService.InputMode.GREEK
                            R.id.btn_b -> MathKeyboardService.InputMode.BLACKBOARD
                            R.id.btn_c -> MathKeyboardService.InputMode.MATHCAL
                            R.id.btn_v -> MathKeyboardService.InputMode.TEXTBF
                            R.id.btn_s -> MathKeyboardService.InputMode.MATHSCRIPT
                            R.id.btn_f -> MathKeyboardService.InputMode.FRAKTUR
                            R.id.btn_n -> MathKeyboardService.InputMode.NORMAL
                            R.id.btn_caret -> MathKeyboardService.InputMode.SUPERSCRIPT
                            R.id.btn_underscore -> MathKeyboardService.InputMode.SUBSCRIPT
                            R.id.btn_i -> MathKeyboardService.InputMode.ITALIC
                            R.id.btn_z -> MathKeyboardService.InputMode.FULLWIDTH
                            R.id.btn_j -> MathKeyboardService.InputMode.JAPANESE
                            else -> null
                        }
                        if (flickMode != null) dispatch(KeyboardEvent.ModeChanged(flickMode))
                    }
                },
                getRippleResource = { rippleResId }
            )

            button.isClickable = false
            button.isFocusable = false
            dynamicRouterViews[buttonId] = button
            dynamicRouterHandlers[buttonId] = touchHandler
        }

        val keyboardKeysLayout = keyboardView.findViewById<LinearLayout>(R.id.keyboard_keys)
        keyboardKeysLayout.setOnTouchListener { _, event ->
            if (!isCacheInitialized) {
                val parentLoc = IntArray(2)
                keyboardKeysLayout.getLocationOnScreen(parentLoc)
                for ((id, view) in dynamicRouterViews) {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val cx = (loc[0] - parentLoc[0]) + view.width / 2f
                    val cy = (loc[1] - parentLoc[1]) + view.height / 2f
                    keyCentersRel[id] = Pair(cx, cy)
                }
                isCacheInitialized = true
            }

            val action = event.actionMasked
            val pointerIndex = event.actionIndex
            val pointerId = event.getPointerId(pointerIndex)

            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    var bestId: Int? = null
                    var minScore = Float.MAX_VALUE

                    for ((id, center) in keyCentersRel) {
                        val dx = x - center.first
                        val dy = y - center.second
                        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                        val view = dynamicRouterViews[id]
                        val label = (view as? TextView)?.text?.toString() ?: ""
                        var weight = defaultKeyWeights[label] ?: 1.0f

                        if (state.lastChar != null) {
                            val lastStr = state.lastChar.toString()
                            if (label == lastStr && label in CONSONANTS) weight *= 0.4f
                            else when (state.lastChar) {
                                'n' -> { if (label in VOWELS || label == "y" || label == "n") weight *= 0.6f else if (label in CONSONANTS) weight *= 1.2f }
                                's', 'k', 't', 'm', 'r', 'g', 'z', 'd', 'b', 'p', 'c', 'f', 'v', 'w', 'j', 'l', 'q', 'x' -> { if (label in VOWELS || label == "y") weight *= 0.6f else if (label in CONSONANTS) weight *= 1.8f }
                                'h' -> { if (label in VOWELS) weight *= 0.6f else if (label in CONSONANTS) weight *= 1.8f }
                                'y' -> { if (label == "a" || label == "u" || label == "o") weight *= 0.5f else weight *= 1.8f }
                                '\\' -> { if (label.length == 1 && label[0].isLetter()) weight *= 0.6f }
                            }
                        } else if (label in VOWELS) {
                            weight *= 0.9f
                        }

                        val score = dist * weight
                        if (score < minScore) {
                            minScore = score
                            bestId = id
                        }
                    }

                    if (bestId != null) {
                        activeTargetIds[pointerId] = bestId
                        val childEvent = MotionEvent.obtain(event)
                        childEvent.action = MotionEvent.ACTION_DOWN
                        dynamicRouterHandlers[bestId]?.onTouch(dynamicRouterViews[bestId]!!, childEvent)
                        childEvent.recycle()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pId = event.getPointerId(i)
                        val targetId = activeTargetIds[pId]
                        if (targetId != null) {
                            val childEvent = MotionEvent.obtain(event)
                            childEvent.action = MotionEvent.ACTION_MOVE
                            dynamicRouterHandlers[targetId]?.onTouch(dynamicRouterViews[targetId]!!, childEvent)
                            childEvent.recycle()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val targetId = activeTargetIds[pointerId]
                    if (targetId != null) {
                        val childEvent = MotionEvent.obtain(event)
                        childEvent.action = if (action == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                        dynamicRouterHandlers[targetId]?.onTouch(dynamicRouterViews[targetId]!!, childEvent)
                        childEvent.recycle()
                        activeTargetIds.remove(pointerId)
                    }
                    true
                }
                else -> false
            }
        }

        keyboardView.findViewById<TextView>(R.id.btn_space)?.setOnClickListener { dispatch(KeyboardEvent.SpaceTapped) }
        keyboardView.findViewById<TextView>(R.id.btn_enter)?.setOnClickListener { dispatch(KeyboardEvent.EnterTapped) }
        keyboardView.findViewById<TextView>(R.id.btn_shift)?.setOnClickListener { dispatch(KeyboardEvent.ShiftToggled) }

        val btnDelete = keyboardView.findViewById<TextView>(R.id.btn_delete)
        btnDelete?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { isDeleting = true; v.isPressed = true; dispatch(KeyboardEvent.BackspaceTapped); deleteHandler.postDelayed(deleteRunnable, 400); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isDeleting = false; v.isPressed = false; deleteHandler.removeCallbacks(deleteRunnable); true }
                else -> false
            }
        }

        val btnMode = keyboardView.findViewById<TextView>(R.id.btn_mode)
        btnMode?.setOnTouchListener(TouchEventHandler(
            onSingleTap = { dispatch(KeyboardEvent.ModeChanged(if (state.currentMode == MathKeyboardService.InputMode.NORMAL) MathKeyboardService.InputMode.MATHSYMBOL else MathKeyboardService.InputMode.NORMAL)) },
            onLongPressSetup = {
                PopupManager.createModeKeyPopup(context, btnMode, rippleResId,
                    onModeSelected = { m -> dispatch(KeyboardEvent.ModeChanged(m)) },
                    onSymbolSelected = { sym -> dispatch(KeyboardEvent.DirectTextCommitted(sym)) },
                    onBackspaceSelected = { dispatch(KeyboardEvent.BackspaceTapped) },
                    onSpaceSelected = { dispatch(KeyboardEvent.SpaceTapped) },
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
        ))
    }
}