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

    private var isDirectRomajiMode = false

    private var lastShiftTime: Long = 0
    private val DOUBLE_TAP_TIMEOUT = 400L

    private val composingText = java.lang.StringBuilder()

    private val candidateScroll: HorizontalScrollView? = keyboardView.findViewById(R.id.candidate_scroll)
    private val candidateLayout: LinearLayout? = keyboardView.findViewById(R.id.candidate_layout)

    private val dbHelper = DictionaryDatabaseHelper(context)
    private val matrixManager = MatrixManager(context)
    private val viterbiConverter = JapaneseConverter(dbHelper, matrixManager)

    // 🌟 タッチルーターとマルチタッチ管理
    private val dynamicRouterViews = mutableMapOf<Int, View>()
    private val dynamicRouterHandlers = mutableMapOf<Int, TouchEventHandler>()
    private val activeTargetIds = mutableMapOf<Int, Int>()

    private val keyCentersRel = mutableMapOf<Int, Pair<Float, Float>>()
    private var isCacheInitialized = false

    private val VOWELS = setOf("a", "i", "u", "e", "o")
    private val CONSONANTS = setOf("k", "s", "t", "n", "h", "m", "y", "r", "w", "g", "z", "d", "b", "p", "j", "c", "f", "l", "v", "q", "x")

    private val defaultKeyWeights = mapOf(
        "a" to 0.9f, "i" to 0.9f, "u" to 0.9f, "e" to 0.9f, "o" to 0.9f,
        "k" to 0.95f, "s" to 0.95f, "t" to 0.95f, "n" to 0.95f,
        "q" to 1.1f, "j" to 1.1f, "v" to 1.1f, "l" to 1.1f
    )

    init {
        Thread { matrixManager.loadMatrix() }.start()
    }

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

                    val len = composingText.length
                    val lastChar = if (len > 0) composingText[len - 1] else null

                    for ((id, center) in keyCentersRel) {
                        val dx = x - center.first
                        val dy = y - center.second
                        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                        val view = dynamicRouterViews[id]
                        val label = (view as? TextView)?.text?.toString() ?: ""
                        var weight = defaultKeyWeights[label] ?: 1.0f

                        if (lastChar != null) {
                            val lastStr = lastChar.toString()

                            if (label == lastStr && label in CONSONANTS) {
                                weight *= 0.4f // ssなどの連続を超優遇
                            }
                            else when (lastChar) {
                                'n' -> {
                                    if (label in VOWELS || label == "y" || label == "n") weight *= 0.6f
                                    else if (label in CONSONANTS) weight *= 1.2f
                                }
                                's', 'k', 't', 'm', 'r', 'g', 'z', 'd', 'b', 'p', 'c', 'f', 'v', 'w', 'j', 'l', 'q', 'x' -> {
                                    if (label in VOWELS || label == "y") weight *= 0.6f
                                    else if (label in CONSONANTS) weight *= 1.8f
                                }
                                'h' -> {
                                    if (label in VOWELS) weight *= 0.6f
                                    else if (label in CONSONANTS) weight *= 1.8f
                                }
                                'y' -> {
                                    if (label == "a" || label == "u" || label == "o") weight *= 0.5f
                                    else weight *= 1.8f
                                }
                                '\\' -> {
                                    if (label.length == 1 && label[0].isLetter()) weight *= 0.6f
                                }
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

        setupModeKey(rippleResId)
        setupDeleteKey()
        setupShiftKey()
        setupSpaceKey()
        setupEnterKey()
    }

    // ==========================================
    // 🌟 修正：kanna と sannno を完璧にさばくローマ字変換
    // ==========================================
    private fun convertRomajiToHiragana(romaji: String): String {
        var res = romaji
        res = res.replace(Regex("([ksthmrwgzdbpjcflv])\\1"), "っ$1")

        val map = linkedMapOf(
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
            "nn" to "ん", // na, no等より後に処理されるので sannno は さんnnの とならず完璧になる
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            "-" to "ー",
            "n" to "ん"  // 🌟 上のすべてに当てはまらず残ったn（末尾や子音の前）をん化
        )

        map.forEach { (k, v) -> res = res.replace(k, v) }
        return res
    }

    private fun forceCommitComposingText(appendSpace: Boolean = false) {
        if (composingText.isEmpty()) {
            if (appendSpace) currentInputConnection?.commitText(" ", 1)
            return
        }

        val textToCommit = if (isDirectRomajiMode) {
            val raw = composingText.toString()
            if (appendSpace) "$raw " else raw
        } else {
            val hiragana = convertRomajiToHiragana(composingText.toString())
            if (appendSpace) "$hiragana " else hiragana
        }

        currentInputConnection?.commitText(textToCommit, 1)
        composingText.clear()
        isDirectRomajiMode = false
        updateCandidates()
    }

    // ==========================================
    // 入力ロジックのコア
    // ==========================================

    private fun handleKeyTap(buttonId: Int, keyData: KeyData) {
        val isUpper = (shiftState == MathKeyboardService.ShiftState.SHIFTED || shiftState == MathKeyboardService.ShiftState.CAPSLOCKED)
        val textToInput = currentMode.resolveText(context, themeManager, buttonId, keyData, isUpper)

        // 🌟 修正: \ も入力バッファに入るようにする
        val canCompose = currentMode == MathKeyboardService.InputMode.JAPANESE && textToInput.length == 1 &&
                (textToInput[0].isLetterOrDigit() || textToInput[0] == '-' || textToInput[0] == '\\')

        if (canCompose) {
            // 🌟 修正: ShiftがON、または '\' が打たれたら直接入力（ローマ字）モードへ
            if ((isUpper || textToInput == "\\") && !isDirectRomajiMode) {
                if (composingText.isNotEmpty()) {
                    forceCommitComposingText(appendSpace = false)
                }
                isDirectRomajiMode = true
            }

            composingText.append(textToInput)

            // 🌟 修正: 単発シフトなら、文字を打った直後に解除する
            if (shiftState == MathKeyboardService.ShiftState.SHIFTED) {
                shiftState = MathKeyboardService.ShiftState.NORMAL
                requestUpdateLabels()
            }

            if (isDirectRomajiMode) {
                currentInputConnection?.setComposingText(composingText.toString(), 1)
            } else {
                val previewHiragana = convertRomajiToHiragana(composingText.toString())
                currentInputConnection?.setComposingText(previewHiragana, 1)
            }

            updateCandidates()
        } else {
            commitDirectText(textToInput)
        }
    }

    private fun commitDirectText(text: String) {
        forceCommitComposingText(appendSpace = false)
        TextProcessor.commitTextWithNormalization(currentInputConnection, text)
        resetStateAfterInput()
    }

    private fun handleBackspaceLogic() {
        if (composingText.isNotEmpty()) {
            if (isDirectRomajiMode) {
                composingText.deleteCharAt(composingText.length - 1)
            } else {
                val currentHira = convertRomajiToHiragana(composingText.toString())
                val romaji = composingText.toString()

                if (currentHira.matches(Regex(".*[a-zA-Z]$"))) {
                    composingText.deleteCharAt(composingText.length - 1)
                } else {
                    when {
                        romaji.matches(Regex(".*([ksthmrwgzdbpjcflv])\\1[aiueo]$")) -> {
                            composingText.delete(composingText.length - 3, composingText.length)
                            composingText.append("xtsu")
                        }
                        romaji.matches(Regex(".*[ksthmyrgzdbp]y[auo]$")) -> {
                            composingText.delete(composingText.length - 2, composingText.length)
                            composingText.append("i")
                        }
                        romaji.matches(Regex(".*(sh|ch)[auo]$")) -> {
                            composingText.deleteCharAt(composingText.length - 1)
                            composingText.append("i")
                        }
                        romaji.matches(Regex(".*j[auo]$")) -> {
                            composingText.deleteCharAt(composingText.length - 1)
                            composingText.append("i")
                        }
                        romaji.matches(Regex(".*f[aieo]$")) -> {
                            composingText.deleteCharAt(composingText.length - 1)
                            composingText.append("u")
                        }
                        romaji.matches(Regex(".*ts[aieo]$")) -> {
                            composingText.deleteCharAt(composingText.length - 1)
                            composingText.append("u")
                        }
                        romaji.matches(Regex(".*(th|dh)[aiueo]$")) -> {
                            composingText.delete(composingText.length - 2, composingText.length)
                            composingText.append("e")
                        }
                        romaji.matches(Regex(".*w[ie]$")) -> {
                            composingText.delete(composingText.length - 2, composingText.length)
                            composingText.append("u")
                        }
                        romaji.matches(Regex(".*v[aiueo]$")) -> {
                            composingText.deleteCharAt(composingText.length - 1)
                            composingText.append("u")
                        }
                        else -> {
                            var dropped = false
                            for (dropCount in 1..4) {
                                if (composingText.length >= dropCount) {
                                    val testStr = composingText.substring(0, composingText.length - dropCount)
                                    val hira = convertRomajiToHiragana(testStr)

                                    if (!hira.matches(Regex(".*[a-zA-Z].*"))) {
                                        composingText.delete(composingText.length - dropCount, composingText.length)
                                        dropped = true
                                        break
                                    }
                                } else if (composingText.length == dropCount) {
                                    composingText.clear()
                                    dropped = true
                                    break
                                }
                            }

                            if (!dropped) {
                                composingText.deleteCharAt(composingText.length - 1)
                            }
                        }
                    }
                }
            }

            if (composingText.isEmpty()) {
                currentInputConnection?.commitText("", 1)
                isDirectRomajiMode = false
            } else {
                if (isDirectRomajiMode) {
                    currentInputConnection?.setComposingText(composingText.toString(), 1)
                } else {
                    val previewHiragana = convertRomajiToHiragana(composingText.toString())
                    currentInputConnection?.setComposingText(previewHiragana, 1)
                }
            }
            updateCandidates()
        } else {
            TextProcessor.handleBackspace(currentInputConnection)
        }
    }

    private fun handleKeyFlick(buttonId: Int, direction: TouchEventHandler.FlickDirection) {
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
            if (flickMode != null) {
                forceCommitComposingText(appendSpace = false)
                currentMode = flickMode
                isOneShotMode = (flickMode != MathKeyboardService.InputMode.NORMAL && flickMode != MathKeyboardService.InputMode.JAPANESE)
                requestUpdateLabels()
            }
        }
    }

    // ==========================================
    // 🌟 修正：予測変換（dbCandidates）を最優先にする！
    // ==========================================
    private fun updateCandidates() {
        candidateLayout?.removeAllViews()

        if (composingText.isEmpty()) {
            candidateScroll?.visibility = View.GONE
            return
        }

        candidateScroll?.visibility = View.VISIBLE
        val finalCandidates = mutableListOf<String>()

        if (isDirectRomajiMode) {
            val rawStr = composingText.toString()
            finalCandidates.add(rawStr)

            // 🌟 バックスラッシュ入力時、そのままコマンド名を予測検索する神機能
            if (rawStr.startsWith("\\") && rawStr.length > 1) {
                val query = rawStr.substring(1) // 例: "\alpha" -> "alpha"
                val dbCandidates = dbHelper.getCandidates(query, 10)
                // DBには "alpha" という読みで "\alpha" が登録されているため、コマンドがサジェストされる
                for (word in dbCandidates) {
                    if (!finalCandidates.contains(word)) finalCandidates.add(word)
                }
            }
        } else {
            val rawStr = composingText.toString()
            val hiraganaStr = convertRomajiToHiragana(rawStr)

            val trailingRomajiMatch = Regex("[a-zA-Z-]+$").find(hiraganaStr)
            val trailingRomaji = trailingRomajiMatch?.value ?: ""
            val cleanHiragana = if (trailingRomaji.isNotEmpty()) {
                hiraganaStr.dropLast(trailingRomaji.length)
            } else {
                hiraganaStr
            }

            val viterbiResult = if (cleanHiragana.isNotEmpty()) viterbiConverter.convert(cleanHiragana) else ""
            val exactViterbi = if (viterbiResult.isNotEmpty()) viterbiResult + trailingRomaji else ""

            // 予測変換を取得
            val dbCandidates = if (cleanHiragana.isNotEmpty()) dbHelper.getCandidates(cleanHiragana, 10) else emptyList()

            // 1. DB予測変換を最優先 (完全一致が先頭、次に前方一致と続くため最強)
            finalCandidates.addAll(dbCandidates)

            // 2. 予測に出なかった複数文節などのViterbi変換を追加
            if (exactViterbi.isNotEmpty() && !finalCandidates.contains(exactViterbi)) {
                finalCandidates.add(exactViterbi)
            }

            // 3. 生テキスト群
            if (!finalCandidates.contains(hiraganaStr)) finalCandidates.add(hiraganaStr)
            val katakanaStr = hiraganaStr.map { if (it in 'ぁ'..'ん') it + 0x60 else it }.joinToString("")
            if (katakanaStr != hiraganaStr && !finalCandidates.contains(katakanaStr)) finalCandidates.add(katakanaStr)
            if (rawStr != hiraganaStr && !finalCandidates.contains(rawStr)) finalCandidates.add(rawStr)
        }

        val rippleResId = getRippleResource()

        for (candidate in finalCandidates) {
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
                    isDirectRomajiMode = false // タップ確定時もモード解除
                    updateCandidates()
                    resetStateAfterInput()
                }
            }
            candidateLayout?.addView(tv)
        }
    }

    private fun setupSpaceKey() {
        keyboardView.findViewById<TextView>(R.id.btn_space)?.setOnClickListener {
            forceCommitComposingText(appendSpace = true)
        }
    }

    private fun setupEnterKey() {
        keyboardView.findViewById<TextView>(R.id.btn_enter)?.setOnClickListener {
            if (composingText.isNotEmpty()) {
                forceCommitComposingText(appendSpace = false)
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
                forceCommitComposingText(appendSpace = false)
                currentMode = if (currentMode == MathKeyboardService.InputMode.NORMAL) MathKeyboardService.InputMode.MATHSYMBOL else MathKeyboardService.InputMode.NORMAL
                isOneShotMode = false
                requestUpdateLabels()
            },
            onLongPressSetup = {
                PopupManager.createModeKeyPopup(
                    context = context, anchorView = btnMode, rippleResId = rippleResId,
                    onModeSelected = { m ->
                        forceCommitComposingText(appendSpace = false)
                        currentMode = m; isOneShotMode = false; requestUpdateLabels()
                    },
                    onSymbolSelected = { sym -> commitDirectText(sym) },
                    onBackspaceSelected = { handleBackspaceLogic() },
                    onSpaceSelected = { forceCommitComposingText(appendSpace = true) },
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