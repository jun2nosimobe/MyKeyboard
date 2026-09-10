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
import android.text.InputType
import android.view.inputmethod.EditorInfo
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
    // 🌟 「複数行入力できる場所ではEnterは送信ではなく改行」への対応。
    // MathKeyboardService.onStartInputViewから、入力欄が切り替わるたびに渡される。
    var currentEditorInfo: EditorInfo? = null

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

    // 🌟 フリック入力: ローマ字QWERTY配列と12キーフリック配列は同じ場所に重ねて
    // 配置してあり、設定(useFlickInput)とモードに応じてどちらか一方だけを表示する。
    private val qwertyRowsContainer: View? = keyboardView.findViewById(R.id.qwerty_rows_container)
    private val flickRowsContainer: View? = keyboardView.findViewById(R.id.flick_rows_container)

    // 🌟 「入力中のまま別画面に飛んだ時にキャッシュを消す」への対応。
    // MathKeyboardService.onStartInputViewから、入力欄/アプリが切り替わるたびに
    // 呼ばれる。前の入力欄向けのcomposingText等がここでクリアされないと、
    // 全く関係のない次の入力欄に古い未確定テキストの記憶が残ってしまう。
    // 🌟 新しい入力コネクションには一切触れない: まだ何も打っていないフィールドに
    // commitText等を送ると余計な文字が挿入されてしまうため。
    fun resetForNewInputSession() {
        if (state.composingText.isEmpty() && state.lastConfirmedWord.isEmpty()) return
        state = state.copy(composingText = "", isDirectRomajiMode = false, lastConfirmedWord = "")
        viterbiConverter.resetCache()
        updateCandidateView(emptyList())
        stateFlow.value = state
    }

    private fun isFlickModeActive(): Boolean {
        if (state.currentMode != MathKeyboardService.InputMode.JAPANESE) return false
        val prefs = context.getSharedPreferences("KeyboardSettings", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("useFlickInput", false)
    }

    // 🌟 「設定でフリック入力のフリック先を表示できるようにする」への対応
    private fun isFlickPreviewEnabled(): Boolean {
        val prefs = context.getSharedPreferences("KeyboardSettings", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("showFlickPreview", true)
    }

    private fun updateKeyboardLayoutVisibility() {
        val flick = isFlickModeActive()
        flickRowsContainer?.visibility = if (flick) View.VISIBLE else View.GONE
        qwertyRowsContainer?.visibility = if (flick) View.GONE else View.VISIBLE
        // 🌟 フリックグリッド側に space/enter/記号一覧が揃っているので、共通の
        // 最下段(mode/,/Space/./Enter)はフリックモード中は不要になる
        keyboardView.findViewById<View>(R.id.shared_bottom_row)?.visibility = if (flick) View.GONE else View.VISIBLE
        if (!flick) {
            // 🌟 数字グリッドを表示したまま他モードへ抜けた場合に備え、次に
            // フリックモードへ戻った時は必ずかなグリッドから始まるようにする
            keyboardView.findViewById<View>(R.id.flick_number_grid)?.visibility = View.GONE
            keyboardView.findViewById<View>(R.id.flick_kana_grid)?.visibility = View.VISIBLE
        }
    }

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

    // 🌟 タッチ判定(当たり判定)の重み。以前は 1.5・2.5・3.0 等を全て決め打ちしていたが、
    // mine_key_weights.py で数学コーパスの実際のローマ字ビグラム頻度から算出した
    // key_bigram_weights.json を読み込むように変更した（assetsから1回だけロード）。
    // 読み込みに失敗した場合は空のマップになり、倍率が一切かからない
    // (=無調整、フォールバックとして安全)。
    private val keyWeightData: KeyWeightData = loadKeyWeightData()
    private val defaultKeyWeights: Map<String, Float> get() = keyWeightData.baselineWeight

    private data class KeyWeightData(
        val bigramMultiplier: Map<Char, Map<Char, Float>>,
        val baselineWeight: Map<String, Float>
    )

    private fun loadKeyWeightData(): KeyWeightData {
        return try {
            val json = context.assets.open("key_bigram_weights.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(json)

            val bigramObj = root.optJSONObject("bigram_multiplier")
            val bigramMap = mutableMapOf<Char, Map<Char, Float>>()
            bigramObj?.keys()?.forEach { prevKey ->
                if (prevKey.isEmpty()) return@forEach
                val row = bigramObj.getJSONObject(prevKey)
                val rowMap = mutableMapOf<Char, Float>()
                row.keys().forEach { nextKey ->
                    if (nextKey.isNotEmpty()) rowMap[nextKey[0]] = row.getDouble(nextKey).toFloat()
                }
                bigramMap[prevKey[0]] = rowMap
            }

            val baseObj = root.optJSONObject("baseline_weight")
            val baseMap = mutableMapOf<String, Float>()
            baseObj?.keys()?.forEach { key -> baseMap[key] = baseObj.getDouble(key).toFloat() }

            KeyWeightData(bigramMap, baseMap)
        } catch (e: Exception) {
            e.printStackTrace()
            KeyWeightData(emptyMap(), emptyMap())
        }
    }

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
                .debounce(20L)
                .distinctUntilChangedBy { it.composingText }
                .collectLatest { currentState ->
                    if (currentState.composingText.isEmpty() && currentState.lastConfirmedWord.isEmpty()) {
                        updateCandidateView(emptyList())
                        return@collectLatest
                    }

                    // DB検索をIOスレッドで実行
                    val candidates = withContext(Dispatchers.IO) {
                        candidateManager.generateCandidates(currentState, isFlickInputMode = isFlickModeActive())
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
            is KeyboardEvent.FlickInput -> handleFlickInput(event.hiragana)
            is KeyboardEvent.DakutenCycleTapped -> handleDakutenCycle()
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

    // ==========================================
    // 🌟 フリック入力: composingTextにひらがなを直接追記する。
    // Composer.convertRomajiToHiragana はASCII以外の文字をそのまま素通しするので
    // (Trieが128以上のcode pointで即break→未一致の文字はそのままappend)、
    // ローマ字用のcomposingTextパイプラインに一切手を加えずに共存できる。
    // ==========================================
    private fun handleFlickInput(hiragana: String) {
        if (hiragana.isEmpty()) return
        state = state.copy(
            composingText = state.composingText + hiragana,
            lastKeyPressTime = System.currentTimeMillis()
        )
        updateUI()
    }

    // 🌟 フリック入力の「゛゜」キー: 直前の1文字を濁点/半濁点/小文字サイクルで巡回させる
    private fun handleDakutenCycle() {
        if (state.composingText.isEmpty()) return
        val last = state.composingText.last()
        val next = FlickKeyDatabase.dakutenCycle[last] ?: return
        state = state.copy(
            composingText = state.composingText.dropLast(1) + next,
            lastKeyPressTime = System.currentTimeMillis()
        )
        updateUI()
    }

    private fun handleBackspace() {
        if (state.composingText.isNotEmpty()) {
            val newRomaji = composer.computeBackspace(state.composingText, state.isDirectRomajiMode)
            // 🌟 全部消してcomposingTextが空になる時もキャッシュをリセット（次の単語に古いDPを持ち越さない）
            if (newRomaji.isEmpty()) viterbiConverter.resetCache()
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
                // 🌟 NEW: 設定（SharedPreferences）からβ版学習機能のオン/オフを読み取る
                val prefs = context.getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)
                val isLearningEnabled = prefs.getBoolean("enableLearningBeta", false)

                // 設定がON(true)の時だけ、裏で学習処理を走らせる
                if (isLearningEnabled) {
                    CoroutineScope(Dispatchers.IO).launch {
                        dbHelper.learnWord(candidate, cleanHiragana)
                    }
                }
            }
        }

        currentInputConnection?.commitText(candidate, 1)
        // 🌟 確定したのでViterbiの差分探索キャッシュ（前回入力の名残）をリセットする。
        // これを忘れると次の単語の変換時に古いDPが再利用され、BOSノードが重複増殖してしまう。
        viterbiConverter.resetCache()
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
        // 🌟 こちらも確定操作なのでキャッシュをリセット（handleCandidateSelectedと同様の理由）
        viterbiConverter.resetCache()
        state = state.copy(composingText = "", isDirectRomajiMode = false, lastKeyPressTime = System.currentTimeMillis())
        updateUI()
    }

    // 🌟 「、。が確定を兼ねているのをやめたい」への対応。
    // 句読点は本来「確定」とは無関係な1文字の入力に過ぎないのに、これまでは
    // commitDirectText経由で必ずforceCommitComposingText(未変換のひらがな確定)を
    // 挟んでいた。composing中の時は、確定ではなく単純にcomposingバッファへ追記する
    // (handleFlickInputと同じ扱い)ことで、変換候補を選ぶ機会を奪わないようにする。
    // composingが無い(何も入力中でない)時だけ、これまで通り即時に確定入力する。
    private fun handlePunctuationTapped(text: String) {
        if (state.composingText.isEmpty()) {
            commitDirectText(text)
        } else {
            handleFlickInput(text)
        }
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

    // 🌟 「複数行入力できる場所ではEnterは送信ではなく改行」への対応。
    // inputTypeにTYPE_TEXT_FLAG_MULTI_LINEが立っているフィールド(メモ欄など)では
    // 常に改行を挿入する。それ以外(1行入力欄)では、imeOptionsのアクション
    // (送信/検索/次へ等)をperformEditorActionで明示的に呼び出す。これは
    // 生のKEYCODE_ENTERイベント送出よりも、Compose製の入力欄やWebViewなどでも
    // 確実にアプリ側の「送信」ハンドラを起動できる標準的な方法。
    private fun handleEnterTapped() {
        if (state.composingText.isNotEmpty()) {
            forceCommitComposingText(appendSpace = false)
            return
        }
        val info = currentEditorInfo
        val isMultiline = info != null &&
                (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (isMultiline) {
            currentInputConnection?.commitText("\n", 1)
            return
        }
        val actionId = (info?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED) and EditorInfo.IME_MASK_ACTION
        val noEnterActionFlag = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info != null && actionId != EditorInfo.IME_ACTION_NONE && !noEnterActionFlag) {
            currentInputConnection?.performEditorAction(actionId)
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
        updateKeyboardLayoutVisibility()
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
            // 🌟 性能改善のため、一時 finishComposingText() に変えたが、これは誤りだった:
            // finishComposingText() は「現在composing中の文字列をそのまま確定する」だけで
            // 中身を空にはしない。バックスペースで最後の1文字を消してcomposingTextが
            // 空になった時、相手アプリの画面にはまだ直前のsetComposingText(1文字前)の
            // 表示が残ったままなので、finishComposingText()を呼ぶとその「消したはずの
            // 1文字」がそのまま(未変換の生かなで)確定されてしまっていた
            // (「一文字だけに対してバックスペースを打つとおかしい」の原因)。
            // setComposingText("", 1) は commitText("", 1) の前半(表示を空にする部分)
            // だけを行う軽量な呼び出しで、どんな状態からでも正しく composing 表示を
            // 空にできる。
            currentInputConnection?.setComposingText("", 1)
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

    // 🌟 「.,()など全角半角間違いやすいキーを追加したので、候補欄で右下に小さく
    // (半)/(全)と表す」への対応。全角⇄半角のペアを持つ記号候補にだけ、小さく
    // ラベルを付ける。既存の1文字TextViewのレイアウトを崩さないよう、
    // SpannableStringで文字本体の右下に小さく追記する形にした。
    private val halfWidthToFull = mapOf(
        '.' to '．', ',' to '，', '(' to '（', ')' to '）', '!' to '！', '?' to '？',
        '/' to '／', '+' to '＋', '-' to '－', '*' to '＊', '=' to '＝', '<' to '＜',
        '>' to '＞', ':' to '：', ';' to '；', '[' to '［', ']' to '］', '{' to '｛',
        '}' to '｝', '|' to '｜', '~' to '～', '#' to '＃', '$' to '＄', '\\' to '＼',
        '\'' to '’', '"' to '”', '@' to '＠', '%' to '％', '^' to '＾', '_' to '＿'
    )
    private val fullWidthToHalf = halfWidthToFull.entries.associate { (h, f) -> f to h }

    private fun widthBadgeSuffix(word: String): String? {
        if (word.length != 1) return null
        val c = word[0]
        if (halfWidthToFull.containsKey(c)) return " (半)"
        if (fullWidthToHalf.containsKey(c)) return " (全)"
        return null
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
                val badge = widthBadgeSuffix(word)
                if (badge != null) {
                    val spannable = android.text.SpannableString(word + badge)
                    spannable.setSpan(
                        android.text.style.RelativeSizeSpan(0.55f),
                        word.length, spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(Color.GRAY),
                        word.length, spannable.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tv.text = spannable
                } else {
                    tv.text = word
                }
                tv.visibility = View.VISIBLE
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
            // 🌟 重要: このルーターは qwerty_rows_container の「子ボタンをすべて
            // isClickable=false にして、親(keyboard_keys)側で一括処理する」設計に
            // 依存している。フリックのボタンは isClickable=true で自前のリスナーを
            // 持つので通常はここまで来ないはずだが、ボタンとボタンの隙間(余白)を
            // タップした場合は子が誰も消費せずこのリスナーまで届いてしまい、
            // 表示上は存在しないはずのQWERTYキー(古いキャッシュ座標)へ誤って
            // ローマ字が1文字入力される事故が起きていた
            // (例:「ひ」を打った直後に隙間を触ると"d"が紛れ込む)。
            // フリックモード中はこのルーター自体を完全に無効化する。
            if (isFlickModeActive()) return@setOnTouchListener false

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
                                if (state.lastChar == '\\') {
                                    // TeXコマンド直後(\から始まる)は文脈が全く異なるので
                                    // ローマ字ビグラム頻度の対象外として個別に維持する
                                    if (label.length == 1 && label[0].isLetter()) contextWeight *= 2.0f
                                } else if (label.length == 1) {
                                    // 🌟 数学コーパスの実測ローマ字ビグラム頻度による倍率。
                                    // 観測が無い組み合わせは無調整(1.0倍)のまま。
                                    val multiplier = keyWeightData.bigramMultiplier[state.lastChar]?.get(label[0])
                                    if (multiplier != null) contextWeight *= multiplier
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

        setupFlickKeyboard(rippleResId)
        updateKeyboardLayoutVisibility()
    }

    // ==========================================
    // 🌟 フリック入力用12キーのセットアップ
    // ==========================================
    private fun setupFlickKeyboard(rippleResId: Int) {
        // 🌟 TouchEventHandlerは「長押し(200ms)でポップアップを出す」前提の実装で、
        // ポップアップを返さない(null)場合は指を離してもタップ扱いにならず入力が
        // 消えてしまう(isLongPress=trueのままonSingleTap/onFlickどちらにも
        // 分岐しないため)。フリックキーは長押しポップアップ自体が不要なので、
        // タップ/フリックの判定だけを行う専用の軽量リスナーを使う。
        val flickThreshold = 40f
        fun resolveDirection(dx: Float, dy: Float): TouchEventHandler.FlickDirection? = when {
            Math.abs(dx) < flickThreshold && Math.abs(dy) < flickThreshold -> null
            Math.abs(dx) > Math.abs(dy) -> if (dx > 0) TouchEventHandler.FlickDirection.RIGHT else TouchEventHandler.FlickDirection.LEFT
            else -> if (dy > 0) TouchEventHandler.FlickDirection.DOWN else TouchEventHandler.FlickDirection.UP
        }
        fun wireFlickKey(button: TextView?, data: FlickKeyData, onResolved: (String) -> Unit) {
            button ?: return
            var startX = 0f
            var startY = 0f
            var previewHandle: PopupManager.FlickPreviewHandle? = null
            val hasFlickDirections = data.up != null || data.down != null || data.left != null || data.right != null
            button.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true; startX = event.x; startY = event.y
                        if (hasFlickDirections && isFlickPreviewEnabled()) {
                            previewHandle = PopupManager.showFlickPreview(context, v, data)
                            previewHandle?.highlight(null)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        previewHandle?.highlight(resolveDirection(event.x - startX, event.y - startY))
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        val direction = resolveDirection(event.x - startX, event.y - startY)
                        previewHandle?.dismiss(); previewHandle = null
                        onResolved(direction?.let { data.forDirection(it) } ?: data.center)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        previewHandle?.dismiss(); previewHandle = null
                        true
                    }
                    else -> true
                }
            }
        }

        // 🌟 フリックの削除キーもQWERTY側のbtn_deleteと同じ「長押しリピート」を持たせる。
        // ACTION_DOWNで即1文字削除+400ms後からdeleteRunnableで50ms間隔リピート、
        // ACTION_UP/CANCELで停止。単純なClickListenerだと長押し連続削除ができない。
        // 🌟 さらに「左フリックで。、.,スペースまで一括削除」を追加。ACTION_MOVEで
        // 左方向の移動を検知したら、それまでの1文字削除/リピートを打ち切り、
        // 直近の区切り文字まで一括削除する。
        fun wireDeleteKey(button: TextView?) {
            button ?: return
            var startX = 0f
            var startY = 0f
            var didBulkDelete = false
            button.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isDeleting = true; v.isPressed = true
                        startX = event.x; startY = event.y
                        didBulkDelete = false
                        dispatch(KeyboardEvent.BackspaceTapped)
                        deleteHandler.postDelayed(deleteRunnable, 400)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - startX
                        val dy = event.y - startY
                        if (!didBulkDelete && -dx > flickThreshold && Math.abs(dx) > Math.abs(dy)) {
                            didBulkDelete = true
                            isDeleting = false
                            deleteHandler.removeCallbacks(deleteRunnable)
                            deleteToNearestBoundary()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDeleting = false; v.isPressed = false
                        deleteHandler.removeCallbacks(deleteRunnable)
                        true
                    }
                    else -> true
                }
            }
        }

        // --- かなグリッド (あ〜わ行) ---
        for ((buttonId, flickData) in FlickKeyDatabase.keys) {
            wireFlickKey(keyboardView.findViewById(buttonId), flickData) { dispatch(KeyboardEvent.FlickInput(it)) }
        }

        // --- 句読点キー (タップ=。、フリックで、/？/！) ---
        wireFlickKey(keyboardView.findViewById(R.id.flick_punct), FlickKeyDatabase.punctKey) {
            handlePunctuationTapped(it)
        }

        keyboardView.findViewById<TextView>(R.id.flick_dakuten)?.setOnClickListener {
            dispatch(KeyboardEvent.DakutenCycleTapped)
        }
        keyboardView.findViewById<TextView>(R.id.flick_space)?.setOnClickListener { dispatch(KeyboardEvent.SpaceTapped) }
        keyboardView.findViewById<TextView>(R.id.flick_enter)?.setOnClickListener { dispatch(KeyboardEvent.EnterTapped) }
        wireDeleteKey(keyboardView.findViewById(R.id.flick_delete))

        // --- カーソル移動 (composing中の文字は確定してから移動する) ---
        keyboardView.findViewById<TextView>(R.id.flick_cursor_left)?.setOnClickListener { moveCursor(forward = false) }
        keyboardView.findViewById<TextView>(R.id.flick_cursor_right)?.setOnClickListener { moveCursor(forward = true) }

        // --- 記号一覧 (「記号ボタンを押したら直接記号がダーッと出てほしい」に対応した
        // 専用ポップアップ。カテゴリ選択を挟まず、最初から記号グリッドが見える) ---
        val flickSymbols = keyboardView.findViewById<TextView>(R.id.flick_symbols)
        flickSymbols?.setOnClickListener {
            PopupManager.createDirectSymbolPopup(
                context = context,
                anchorView = flickSymbols,
                rippleResId = rippleResId,
                onSymbolSelected = { sym -> dispatch(KeyboardEvent.DirectTextCommitted(sym)) },
                onBackspaceSelected = { dispatch(KeyboardEvent.BackspaceTapped) },
                onSpaceSelected = { dispatch(KeyboardEvent.SpaceTapped) }
            )
        }

        // --- 英数キー: ローマ字/英語入力(NORMAL)へ切り替え ---
        keyboardView.findViewById<TextView>(R.id.flick_switch_alpha)?.setOnClickListener {
            dispatch(KeyboardEvent.ModeChanged(MathKeyboardService.InputMode.NORMAL, isOneShot = false))
        }
        keyboardView.findViewById<TextView>(R.id.flick_num_switch_alpha)?.setOnClickListener {
            dispatch(KeyboardEvent.ModeChanged(MathKeyboardService.InputMode.NORMAL, isOneShot = false))
        }

        // --- 数字グリッドの切り替え (かな⇄数字)
        // 🌟 位置をかなグリッドと揃えてある: 「数字」(row3col1)を押すと「戻る」(row3col1)
        // が同じ位置に来て、指の移動なしにトグルできる。 ---
        val kanaGrid = keyboardView.findViewById<View>(R.id.flick_kana_grid)
        val numberGrid = keyboardView.findViewById<View>(R.id.flick_number_grid)
        keyboardView.findViewById<TextView>(R.id.flick_numbers)?.setOnClickListener {
            kanaGrid?.visibility = View.GONE
            numberGrid?.visibility = View.VISIBLE
        }
        keyboardView.findViewById<TextView>(R.id.flick_back_to_kana)?.setOnClickListener {
            numberGrid?.visibility = View.GONE
            kanaGrid?.visibility = View.VISIBLE
        }

        // --- 数字グリッドの記号一覧・スペース・句読点・エンター (かなグリッドと同じ挙動) ---
        val flickNumSymbols = keyboardView.findViewById<TextView>(R.id.flick_num_symbols)
        flickNumSymbols?.setOnClickListener {
            PopupManager.createDirectSymbolPopup(
                context = context,
                anchorView = flickNumSymbols,
                rippleResId = rippleResId,
                onSymbolSelected = { sym -> dispatch(KeyboardEvent.DirectTextCommitted(sym)) },
                onBackspaceSelected = { dispatch(KeyboardEvent.BackspaceTapped) },
                onSpaceSelected = { dispatch(KeyboardEvent.SpaceTapped) }
            )
        }
        keyboardView.findViewById<TextView>(R.id.flick_num_space)?.setOnClickListener { dispatch(KeyboardEvent.SpaceTapped) }
        keyboardView.findViewById<TextView>(R.id.flick_num_enter)?.setOnClickListener { dispatch(KeyboardEvent.EnterTapped) }
        // 🌟 「・」は濁点キーの代わりの位置にある単純な直接入力キー(数字モードでは濁点切替は不要)
        keyboardView.findViewById<TextView>(R.id.flick_num_dot)?.setOnClickListener {
            dispatch(KeyboardEvent.DirectTextCommitted("・"))
        }
        // --- 数字モードの句読点キー (半角 .,?!) ---
        wireFlickKey(keyboardView.findViewById(R.id.flick_num_punct), FlickKeyDatabase.numPunctKey) {
            handlePunctuationTapped(it)
        }

        // --- 数字グリッドの各キー (タップ=数字、フリックで演算子) ---
        for ((buttonId, flickData) in FlickKeyDatabase.numberKeys) {
            wireFlickKey(keyboardView.findViewById(buttonId), flickData) { dispatch(KeyboardEvent.DirectTextCommitted(it)) }
        }
        wireDeleteKey(keyboardView.findViewById(R.id.flick_num_delete))
        keyboardView.findViewById<TextView>(R.id.flick_num_cursor_left)?.setOnClickListener { moveCursor(forward = false) }
        keyboardView.findViewById<TextView>(R.id.flick_num_cursor_right)?.setOnClickListener { moveCursor(forward = true) }
    }

    // 🌟 確定済みテキスト上でカーソルを1文字分移動する。composing中の文字がある場合は
    // 先に確定してから移動する(未確定文字を跨いだカーソル移動は挙動が不定なため)。
    private fun moveCursor(forward: Boolean) {
        // 🌟 修正: 「方向キーで勝手に無変換確定してカーソル移動どころか入力欄から
        // 消える」バグへの対応。forceCommitComposingText()は内部でupdateUI()を呼び、
        // composingTextが空になった直後にさらにsetComposingText("", 1)を発行していた。
        // commitText()は仕様上すでにcomposing領域を確定済みなので、その直後に
        // もう一度composing操作(setComposingText)を発行し、さらに間髪入れずDPADの
        // sendKeyEventを送るという3連続のIPCが、相手アプリ側のカーソル/composing状態と
        // 競合し、テキストが消える不具合につながっていた可能性が高い。
        // ここではcommitTextだけを行い、composing操作の再発行を挟まずに直接DPADへ進む。
        if (state.composingText.isNotEmpty()) {
            val textToCommit = if (state.isDirectRomajiMode || state.currentMode != MathKeyboardService.InputMode.JAPANESE) {
                state.composingText
            } else {
                composer.convertRomajiToHiragana(state.composingText)
            }
            currentInputConnection?.commitText(textToCommit, 1)
            viterbiConverter.resetCache()
            state = state.copy(composingText = "", isDirectRomajiMode = false, lastKeyPressTime = System.currentTimeMillis())
            // 🌟 入力コネクションへの再操作はせず、候補バーのクリア等の内部状態だけ更新する
            stateFlow.value = state
        }
        val keyCode = if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // 🌟 「バックスペース左フリックで。、.,スペースまで一括削除」への対応。
    // composing中の文字がある場合は、その未確定バッファ全体を1つの塊として消す
    // (境界文字探索は既に確定済みのテキストにのみ意味があるため)。
    // 確定済みテキストの場合は、カーソル直前から直近の区切り文字の手前まで削除する。
    // カーソル直前がすでに区切り文字自体の場合は、まずそれを1つ消してから
    // (区切り文字の連打で1文字ずつしか消えないのを防ぐ)、次の区切りを探す。
    private fun deleteToNearestBoundary() {
        if (state.composingText.isNotEmpty()) {
            state = state.copy(composingText = "", isDirectRomajiMode = false, lastKeyPressTime = System.currentTimeMillis())
            viterbiConverter.resetCache()
            updateUI()
            return
        }
        val boundaryChars = setOf('。', '、', '.', ',', ' ', '\n')
        val before = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: return
        if (before.isEmpty()) return

        var deleteCount = 0
        var i = before.length - 1
        if (before[i] in boundaryChars) {
            deleteCount++
            i--
        }
        while (i >= 0 && before[i] !in boundaryChars) {
            deleteCount++
            i--
        }
        if (deleteCount > 0) currentInputConnection?.deleteSurroundingText(deleteCount, 0)
    }
}