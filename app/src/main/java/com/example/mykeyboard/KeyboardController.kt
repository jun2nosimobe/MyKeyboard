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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class KeyboardController(
    private val context: Context,
    private val themeManager: KeyboardThemeManager,
    private val keyboardView: View,
    private val requestUpdateLabels: () -> Unit
) {
    // 🌟 状態管理 (MVI)
    var state = KeyboardState()
        private set

    var currentInputConnection: InputConnection? = null

    // 各モジュールのインスタンス
    private val composer = Composer()
    private val dbHelper = DictionaryDatabaseHelper(context)
    private val engDbHelper = EnglishDictionaryHelper(context)
    private val matrixManager = MatrixManager(context)
    private val viterbiConverter = JapaneseConverter(dbHelper, matrixManager)
    private val candidateManager = CandidateManager(dbHelper, engDbHelper, viterbiConverter, composer, matrixManager)

    // UI参照
    private val candidateScroll: HorizontalScrollView? = keyboardView.findViewById(R.id.candidate_scroll)
    private val candidateLayout: LinearLayout? = keyboardView.findViewById(R.id.candidate_layout)

    // 🌟 非同期処理 (Flow/Coroutines)
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateFlow = MutableStateFlow(state)

    // タッチルーター用
    private val dynamicRouterViews = mutableMapOf<Int, View>()
    private val dynamicRouterHandlers = mutableMapOf<Int, TouchEventHandler>()
    private val activeTargetIds = mutableMapOf<Int, Int>()
    private val keyCentersRel = mutableMapOf<Int, Pair<Float, Float>>()
    private var isCacheInitialized = false

    private val VOWELS = setOf("a", "i", "u", "e", "o")
    private val CONSONANTS = setOf("k", "s", "t", "n", "h", "m", "y", "r", "w", "g", "z", "d", "b", "p", "j", "c", "f", "l", "v", "q", "x")
    private val defaultKeyWeights = mapOf("a" to 0.9f, "i" to 0.9f, "u" to 0.9f, "e" to 0.9f, "o" to 0.9f, "k" to 0.95f, "s" to 0.95f, "t" to 0.95f, "n" to 0.95f)

    private var lastShiftTime: Long = 0
    private val DOUBLE_TAP_TIMEOUT = 400L

    // 削除キー長押し用
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

    init {
        Thread { matrixManager.loadMatrix() }.start()

        // 🌟 変換計算の非同期パイプライン (Debounce 50ms)
        controllerScope.launch {
            stateFlow
                .debounce(100L)
                .distinctUntilChangedBy { it.composingText }
                .collectLatest { currentState ->
                    if (currentState.composingText.isEmpty() && currentState.lastConfirmedWord.isEmpty()) {
                        updateCandidateView(emptyList())
                        return@collectLatest
                    }

                    // DB検索をIOスレッドで実行
                    val candidates = withContext(Dispatchers.IO) {
                        candidateManager.generateCandidates(currentState)
                    }

                    // UI更新（メインスレッド）
                    updateCandidateView(candidates)
                }
        }
    }

    private fun getRippleResource(): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    fun dispatch(event: KeyboardEvent) {
        when (event) {
            is KeyboardEvent.KeyTapped -> handleKeyTapped(event.buttonId, event.keyData)
            is KeyboardEvent.DirectTextCommitted -> commitDirectText(event.text)
            is KeyboardEvent.SpaceTapped -> forceCommitComposingText(appendSpace = true)
            is KeyboardEvent.EnterTapped -> handleEnterTapped()
            is KeyboardEvent.BackspaceTapped -> handleBackspace()
            is KeyboardEvent.CandidateSelected -> handleCandidateSelected(event.text)
            is KeyboardEvent.ModeChanged -> handleModeChanged(event.mode, event.isOneShot)
            is KeyboardEvent.ShiftToggled -> handleShiftToggled()
        }
    }

    private fun handleKeyTapped(buttonId: Int, keyData: KeyData) {
        val textToInput = state.currentMode.resolveText(context, themeManager, buttonId, keyData, state.isUpper)
        val canCompose = (state.currentMode == MathKeyboardService.InputMode.JAPANESE || state.currentMode == MathKeyboardService.InputMode.NORMAL) &&
                textToInput.length == 1 &&
                (textToInput[0].isLetterOrDigit() || textToInput[0] == '-' || textToInput[0] == '\\')

        if (canCompose) {
            var newDirectMode = state.isDirectRomajiMode
            var newShiftState = state.shiftState
            var needsLabelUpdate = false // 🌟 追加: ラベル更新が必要かどうかのフラグ

            if ((state.isUpper || textToInput == "\\") && !state.isDirectRomajiMode) {
                if (state.composingText.isNotEmpty()) forceCommitComposingText(appendSpace = false)
                newDirectMode = true
            }
            val newComposing = state.composingText + textToInput

            // 🌟 修正: シフト状態が解除されるならフラグを立てる
            if (state.shiftState == MathKeyboardService.ShiftState.SHIFTED) {
                newShiftState = MathKeyboardService.ShiftState.NORMAL
                needsLabelUpdate = true
            }

            state = state.copy(
                composingText = newComposing,
                isDirectRomajiMode = newDirectMode,
                shiftState = newShiftState,
                lastKeyPressTime = System.currentTimeMillis()
            )
            updateUI()

            // 🌟 修正: フラグを見てUIを更新する
            if (needsLabelUpdate) requestUpdateLabels()
        } else {
            commitDirectText(textToInput)
        }
    }

    private fun handleBackspace() {
        if (state.composingText.isNotEmpty()) {
            val newRomaji = composer.computeBackspace(state.composingText, state.isDirectRomajiMode)
            state = state.copy(
                composingText = newRomaji,
                isDirectRomajiMode = if (newRomaji.isEmpty()) false else state.isDirectRomajiMode,
                lastKeyPressTime = System.currentTimeMillis()
            )
            updateUI()
        } else {
            TextProcessor.handleBackspace(currentInputConnection)
        }
    }

    private fun handleCandidateSelected(candidate: String) {
        // 🌟 修正: 日本語モードの時だけ学習機能（Viterbi辞書への登録）を動かす
        if (state.currentMode == MathKeyboardService.InputMode.JAPANESE) {
            val hiraganaStr = composer.convertRomajiToHiragana(state.composingText)
            val cleanHiragana = hiraganaStr.replace(Regex("[a-zA-Z-]+$"), "")

            if (cleanHiragana.isNotEmpty() && candidate.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    dbHelper.learnWord(candidate, cleanHiragana)
                }
            }
        }

        currentInputConnection?.commitText(candidate, 1)
        state = state.copy(
            composingText = "",
            isDirectRomajiMode = false,
            lastConfirmedWord = candidate,
            lastKeyPressTime = System.currentTimeMillis()
        )
        updateUI()

        var needsUpdate = false
        var newShift = state.shiftState
        var newMode = state.currentMode
        var newOneShot = state.isOneShotMode

        if (state.shiftState == MathKeyboardService.ShiftState.SHIFTED) {
            newShift = MathKeyboardService.ShiftState.NORMAL
            needsUpdate = true
        }
        if (state.isOneShotMode && state.currentMode != MathKeyboardService.InputMode.JAPANESE) {
            newMode = MathKeyboardService.InputMode.NORMAL
            newOneShot = false
            needsUpdate = true
        }
        state = state.copy(shiftState = newShift, currentMode = newMode, isOneShotMode = newOneShot)
        if (needsUpdate) requestUpdateLabels()
    }

    private fun forceCommitComposingText(appendSpace: Boolean) {
        if (state.composingText.isEmpty()) {
            if (appendSpace) currentInputConnection?.commitText(" ", 1)
            return
        }

        // 🌟 修正: ここも同様に JAPANESE モード以外はそのまま確定する
        val textToCommit = if (state.isDirectRomajiMode || state.currentMode != MathKeyboardService.InputMode.JAPANESE) {
            state.composingText
        } else {
            composer.convertRomajiToHiragana(state.composingText)
        }

        currentInputConnection?.commitText(if (appendSpace) "$textToCommit " else textToCommit, 1)
        state = state.copy(composingText = "", isDirectRomajiMode = false, lastKeyPressTime = System.currentTimeMillis())
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
        if (state.isOneShotMode && state.currentMode != MathKeyboardService.InputMode.JAPANESE) {
            newMode = MathKeyboardService.InputMode.NORMAL
            newOneShot = false
            needsUpdate = true
        }
        state = state.copy(shiftState = newShift, currentMode = newMode, isOneShotMode = newOneShot)
        if (needsUpdate) requestUpdateLabels()
    }

    private fun handleEnterTapped() {
        if (state.composingText.isNotEmpty()) {
            forceCommitComposingText(appendSpace = false)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun handleModeChanged(mode: MathKeyboardService.InputMode, isOneShot: Boolean) {
        forceCommitComposingText(appendSpace = false)
        state = state.copy(
            currentMode = mode,
            isOneShotMode = isOneShot
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

    // ==========================================
    // 🌟 UI 更新処理
    // ==========================================
    private fun updateUI() {
        // プレビュー表示 (即時)
        if (state.composingText.isEmpty()) {
            currentInputConnection?.commitText("", 1)
        } else {
            // 🌟 修正: JAPANESEモード以外（NORMAL等）なら、勝手にひらがな化せずそのまま表示する！
            val previewText = if (state.isDirectRomajiMode || state.currentMode != MathKeyboardService.InputMode.JAPANESE) {
                state.composingText
            } else {
                composer.convertRomajiToHiragana(state.composingText)
            }
            currentInputConnection?.setComposingText(previewText, 1)
        }
        // 重い変換処理はFlowに投げてdebounceさせる
        stateFlow.value = state
    }

    private fun updateCandidateView(candidates: List<Pair<String, String>>) {
        if (candidates.isEmpty()) {
            candidateScroll?.visibility = View.GONE
            return
        }
        candidateScroll?.visibility = View.VISIBLE
        val rippleResId = getRippleResource()

        val childCount = candidateLayout?.childCount ?: 0
        for (i in 0 until maxOf(candidates.size, childCount)) {
            if (i < candidates.size) {
                val word = candidates[i].first
                var tv = candidateLayout?.getChildAt(i) as? TextView
                if (tv == null) {
                    tv = TextView(context).apply {
                        textSize = 18f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
                        setPadding(30, 20, 30, 20); setBackgroundResource(rippleResId)
                        isClickable = true; isFocusable = true
                    }
                    candidateLayout?.addView(tv)
                }
                tv.text = word; tv.visibility = View.VISIBLE
                tv.setOnClickListener { dispatch(KeyboardEvent.CandidateSelected(word)) }
            } else {
                candidateLayout?.getChildAt(i)?.visibility = View.GONE
            }
        }
    }

    // ==========================================
    // 🌟 タッチルーターとセットアップ
    // ==========================================
    fun setupKeyboard() {
        val rippleResId = getRippleResource()

        // 個別ボタンのハンドラー設定
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
                            R.id.btn_m -> MathKeyboardService.InputMode.MATHSYMBOL
                            R.id.btn_n -> MathKeyboardService.InputMode.NORMAL
                            R.id.btn_caret -> MathKeyboardService.InputMode.SUPERSCRIPT
                            R.id.btn_underscore -> MathKeyboardService.InputMode.SUBSCRIPT
                            R.id.btn_i -> MathKeyboardService.InputMode.ITALIC
                            R.id.btn_z -> MathKeyboardService.InputMode.FULLWIDTH
                            R.id.btn_j -> MathKeyboardService.InputMode.JAPANESE
                            else -> null
                        }
                        if (flickMode != null) {
                            // 🌟 判定：現在のモードがフリック対象と同じ、かつまだ1回限りの状態なら「2回目の連続フリック」とみなす
                            val isTwoTimesFlick = (state.currentMode == flickMode && state.isOneShotMode)

                            // 2回目なら固定(isOneShot=false)、1回目ならワンショット(isOneShot=true)で送る
                            dispatch(KeyboardEvent.ModeChanged(flickMode, isOneShot = !isTwoTimesFlick))
                        }
                    }
                },
                getRippleResource = { rippleResId }
            )

            button.isClickable = false
            button.isFocusable = false
            dynamicRouterViews[buttonId] = button
            dynamicRouterHandlers[buttonId] = touchHandler
        }

        // 🌟 動的タッチルーター本体
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

                    val now = System.currentTimeMillis()
                    val elapsedMs = now - state.lastKeyPressTime
                    // 減衰カーブ: 300ms維持 -> 700msで減衰
                    val decayFactor = if (elapsedMs < 300) 1.0f else (1.0f - ((elapsedMs - 300) / 700f)).coerceIn(0f, 1f)

                    for ((id, center) in keyCentersRel) {
                        val dx = x - center.first
                        val dy = y - center.second
                        val distSq = dx * dx + dy * dy

                        val label = (dynamicRouterViews[id] as? TextView)?.text?.toString()?.lowercase() ?: ""
                        var contextWeight = defaultKeyWeights[label] ?: 1.0f

                        // 🌟 日本語モードの時だけ、強力なローマ字アシスト（物理ルール）を発動！
                        if (state.currentMode == MathKeyboardService.InputMode.JAPANESE) {

                            // 🌟 NEW: 数字キーへの誤爆を防ぐため、数字のウェイトを極端に下げる！
                            if (label.length == 1 && label[0].isDigit()) {
                                contextWeight *= 0.5f // 2分の1の評価にする（必要に応じて 0.2f などに調整してください）
                            }
                            else if (state.lastChar != null) {
                                val last = state.lastChar.toString()
                                if (label == last && label in CONSONANTS && label != "n") {
                                    contextWeight *= 1.5f // 促音
                                } else {
                                    when (state.lastChar) {
                                        'n' -> if (label in VOWELS || label == "y" || label == "n") contextWeight *= 2.5f else contextWeight *= 0.5f
                                        's', 'k', 't', 'm', 'r', 'g', 'z', 'd', 'b', 'p', 'c', 'f', 'v', 'w', 'j', 'l', 'q', 'x', 'h' ->
                                            if (label in VOWELS || label == "y") contextWeight *= 3.0f else contextWeight *= 0.2f
                                        'y' -> if (label in setOf("a", "u", "o")) contextWeight *= 3.0f else contextWeight *= 0.2f
                                        '\\' -> if (label.length == 1 && label[0].isLetter()) contextWeight *= 2.0f
                                    }
                                }
                            } else if (label in VOWELS) {
                                contextWeight *= 1.2f
                            }
                        }

                        // 時間減衰の適用
                        val finalWeight = 1.0f + (contextWeight - 1.0f) * decayFactor
                        val score = distSq / (finalWeight * finalWeight)

                        if (score < minScore) {
                            minScore = score
                            bestId = id
                        }
                    }

                    if (bestId != null) {
                        activeTargetIds[pointerId] = bestId

                        // 🌟 修正1：タッチされた瞬間に即座にViewを「押下状態」にして色を暗くする！
                        dynamicRouterViews[bestId]?.isPressed = true

                        // マルチタッチバグ回避のため handleRoutedTouch を使用
                        dynamicRouterHandlers[bestId]?.handleRoutedTouch(dynamicRouterViews[bestId]!!, MotionEvent.ACTION_DOWN, x, y, event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pId = event.getPointerId(i)
                        val targetId = activeTargetIds[pId]
                        if (targetId != null) {
                            val x = event.getX(i)
                            val y = event.getY(i)
                            dynamicRouterHandlers[targetId]?.handleRoutedTouch(dynamicRouterViews[targetId]!!, MotionEvent.ACTION_MOVE, x, y, event.rawX, event.rawY)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val targetId = activeTargetIds[pointerId]
                    if (targetId != null) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val childAction = if (action == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                        dynamicRouterViews[targetId]?.isPressed = false
                        dynamicRouterHandlers[targetId]?.handleRoutedTouch(dynamicRouterViews[targetId]!!, childAction, x, y, event.rawX, event.rawY)
                        activeTargetIds.remove(pointerId)
                    }
                    true
                }
                else -> false
            }
        }

        // 特殊キーのセットアップ
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
            onSingleTap = {
                val nextMode = if (state.currentMode == MathKeyboardService.InputMode.NORMAL) MathKeyboardService.InputMode.MATHSYMBOL else MathKeyboardService.InputMode.NORMAL
                val defaultOneShot = (nextMode != MathKeyboardService.InputMode.NORMAL && nextMode != MathKeyboardService.InputMode.JAPANESE)
                dispatch(KeyboardEvent.ModeChanged(nextMode, isOneShot = defaultOneShot))
            },
            onLongPressSetup = {
                PopupManager.createModeKeyPopup(
                    context = context,
                    anchorView = btnMode,
                    rippleResId = rippleResId,
                    onModeSelected = { m -> dispatch(KeyboardEvent.ModeChanged(m, isOneShot = false)) },
                    onSymbolSelected = { sym -> dispatch(KeyboardEvent.DirectTextCommitted(sym)) },
                    onBackspaceSelected = { dispatch(KeyboardEvent.BackspaceTapped) },
                    onSpaceSelected = { dispatch(KeyboardEvent.SpaceTapped) },
                    onSettingsClicked = {
                        // 🌟 MainActivity (統合設定パネル) へ直接飛ぶように修正
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        try { context.startActivity(intent) } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            },
            onFlick = {},
            getRippleResource = { rippleResId }
        ))
    }
}