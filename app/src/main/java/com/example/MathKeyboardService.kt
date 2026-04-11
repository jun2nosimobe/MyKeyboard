package com.example.mykeyboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import java.io.File
import java.text.Normalizer
import kotlin.math.min

class MathKeyboardService : InputMethodService() {

    enum class InputMode(val shortName: String, val displayName: String) {
        NORMAL("NOR", "Normal"),
        BLACKBOARD("BB", "Blackboard"),
        FRAKTUR("FRK", "Fraktur"),
        GREEK("GRK", "Greek"),
        MATHSCRIPT("SCR", "Script"),
        MATHSYMBOL("SYM", "Symbol"),
        SUPERSCRIPT("SUP", "Super (^高)"),
        SUBSCRIPT("SUB", "Sub (_低)"),
        ITALIC("ITA", "Italic"),
        FULLWIDTH("FUL", "全角")
    }

    private val modeCycle = listOf(InputMode.NORMAL, InputMode.MATHSYMBOL)
    private var currentCycleIndex = 0
    private var currentMode = modeCycle[currentCycleIndex]

    private var isOneShotMode = false

    enum class ShiftState { NORMAL, SHIFTED, CAPSLOCKED }
    private var shiftState = ShiftState.NORMAL

    private var lastShiftTime: Long = 0
    private val DOUBLE_TAP_TIMEOUT = 400L

    private lateinit var keyboardView: View
    private lateinit var prefs: SharedPreferences
    private var keyTextColor = Color.BLACK

    private var activePopup: PopupWindow? = null
    private var currentPopupOptions: List<Pair<View, String>> = emptyList()
    private var hoveredOption: View? = null

    private val deleteHandler = Handler(Looper.getMainLooper())
    private var isDeleting = false
    private val deleteRunnable = object : Runnable {
        override fun run() {
            if (isDeleting) {
                currentInputConnection?.deleteSurroundingText(1, 0)
                deleteHandler.postDelayed(this, 50)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("KeyboardSettings", Context.MODE_PRIVATE)
        keyTextColor = prefs.getInt("keyTextColor", Color.BLACK)
    }

    private fun getCustomText(buttonId: Int, suffix: String, defaultValue: String): String {
        val idName = try { resources.getResourceEntryName(buttonId) } catch (e: Exception) { buttonId.toString() }
        return prefs.getString("key_${idName}_$suffix", defaultValue) ?: defaultValue
    }

    private fun getRippleResource(): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    private val superscriptMap = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾",
        'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ", 'f' to "ᶠ", 'g' to "ᵍ", 'h' to "ʰ", 'i' to "ⁱ", 'j' to "ʲ", 'k' to "ᵏ", 'l' to "ˡ", 'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ", 'p' to "ᵖ", 'r' to "ʳ", 's' to "ˢ", 't' to "ᵗ", 'u' to "ᵘ", 'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ", 'z' to "ᶻ",
        'A' to "ᴬ", 'B' to "ᴮ", 'D' to "ᴰ", 'E' to "ᴱ", 'G' to "ᴳ", 'H' to "ᴴ", 'I' to "ᴵ", 'J' to "ᴶ", 'K' to "ᴷ", 'L' to "ᴸ", 'M' to "ᴹ", 'N' to "ᴺ", 'O' to "ᴼ", 'P' to "ᴾ", 'R' to "ᴿ", 'T' to "ᵀ", 'U' to "ᵁ", 'V' to "ⱽ", 'W' to "ᵂ"
    )

    private val subscriptMap = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄", '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", '=' to "₌", '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ", 'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ", 'x' to "ₓ"
    )

    private fun toSuperscript(str: String): String = str.map { superscriptMap[it] ?: it.toString() }.joinToString("")
    private fun toSubscript(str: String): String = str.map { subscriptMap[it] ?: it.toString() }.joinToString("")

    private fun toItalic(str: String): String = str.map { c ->
        when (c) {
            'h' -> "ℎ"
            in 'A'..'Z' -> String(Character.toChars(0x1D434 + (c - 'A')))
            in 'a'..'z' -> String(Character.toChars(0x1D44E + (c - 'a')))
            else -> c.toString()
        }
    }.joinToString("")

    private fun toFullWidth(str: String): String = str.map { c ->
        when (c) {
            ' ' -> "　"
            in '!'..'~' -> (c.code + 0xFEE0).toChar().toString()
            else -> c.toString()
        }
    }.joinToString("")

    private fun commitTextWithNormalization(textToCommit: String) {
        val ic = currentInputConnection ?: return
        val isCombining = textToCommit.isNotEmpty() && textToCommit.all {
            it in '\u0300'..'\u036F' || it in '\u20D0'..'\u20FF'
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

    private fun resetStateAfterInput() {
        var needsUpdate = false
        if (shiftState == ShiftState.SHIFTED) {
            shiftState = ShiftState.NORMAL
            needsUpdate = true
        }
        if (isOneShotMode) {
            currentMode = InputMode.NORMAL
            currentCycleIndex = modeCycle.indexOf(InputMode.NORMAL).takeIf { it >= 0 } ?: 0
            isOneShotMode = false
            needsUpdate = true
        }
        if (needsUpdate) {
            updateShiftButtonUI()
            updateKeyboardLabels()
            keyboardView.findViewById<TextView>(R.id.btn_mode)?.text = currentMode.shortName
        }
    }

    private fun setupFastKey(
        button: TextView,
        buttonId: Int,
        onClick: () -> Unit,
        onSetupPopup: () -> Pair<PopupWindow, List<Pair<View, String>>>?
    ) {
        val handler = Handler(Looper.getMainLooper())
        var isLongPress = false
        var runnable: Runnable? = null
        var isDragging = false
        var startX = 0f
        var startY = 0f

        button.setOnTouchListener { v, event ->
            val rawX = event.rawX
            val rawY = event.rawY

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPress = false
                    isDragging = false
                    startX = rawX
                    startY = rawY
                    v.isPressed = true
                    runnable = Runnable {
                        isLongPress = true
                        val result = onSetupPopup()
                        if (result != null) {
                            activePopup = result.first
                            currentPopupOptions = result.second
                            activePopup?.setOnDismissListener {
                                if (activePopup == result.first) {
                                    activePopup = null
                                    currentPopupOptions = emptyList()
                                    hoveredOption = null
                                }
                            }
                        }
                    }
                    handler.postDelayed(runnable!!, 200L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging && (Math.abs(rawX - startX) > 15f || Math.abs(rawY - startY) > 15f)) {
                        isDragging = true
                    }
                    if (isLongPress && activePopup != null && activePopup!!.isShowing && currentPopupOptions.isNotEmpty()) {
                        var foundHover: View? = null
                        for ((optionView, _) in currentPopupOptions) {
                            val loc = IntArray(2)
                            optionView.getLocationOnScreen(loc)
                            val rect = android.graphics.Rect(loc[0] - 20, loc[1] - 20, loc[0] + optionView.width + 20, loc[1] + optionView.height + 20)
                            if (rect.contains(rawX.toInt(), rawY.toInt())) {
                                foundHover = optionView
                                break
                            }
                        }

                        if (hoveredOption != foundHover) {
                            hoveredOption?.setBackgroundResource(getRippleResource())
                            hoveredOption = foundHover
                            hoveredOption?.setBackgroundColor(Color.parseColor("#B3D4FF"))
                        }
                    }

                    if (!isLongPress && isDragging && (rawY - startY) > 80f) {
                        if (runnable != null) handler.removeCallbacks(runnable!!)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (runnable != null) handler.removeCallbacks(runnable!!)

                    if (isLongPress && activePopup != null && activePopup!!.isShowing) {
                        if (hoveredOption != null) {
                            val textToCommit = currentPopupOptions.firstOrNull { it.first == hoveredOption }?.second
                            if (textToCommit != null) {
                                commitTextWithNormalization(textToCommit)
                                resetStateAfterInput()
                            }
                            activePopup?.dismiss()
                        } else if (isDragging && currentPopupOptions.isNotEmpty()) {
                            activePopup?.dismiss()
                        }
                    } else if (!isLongPress) {
                        if (isDragging && (rawY - startY) > 80f && Math.abs(rawX - startX) < 100f) {
                            var flickMode: InputMode? = null
                            when (buttonId) {
                                R.id.btn_g -> flickMode = InputMode.GREEK
                                R.id.btn_b -> flickMode = InputMode.BLACKBOARD
                                R.id.btn_s -> flickMode = InputMode.MATHSCRIPT
                                R.id.btn_f -> flickMode = InputMode.FRAKTUR
                                R.id.btn_n -> flickMode = InputMode.NORMAL
                                R.id.btn_caret -> flickMode = InputMode.SUPERSCRIPT
                                R.id.btn_underscore -> flickMode = InputMode.SUBSCRIPT
                                R.id.btn_i -> flickMode = InputMode.ITALIC
                                R.id.btn_z -> flickMode = InputMode.FULLWIDTH
                            }
                            if (flickMode != null) {
                                currentMode = flickMode
                                isOneShotMode = (flickMode != InputMode.NORMAL)
                                val modeBtn = keyboardView.findViewById<TextView>(R.id.btn_mode)
                                modeBtn?.text = currentMode.shortName
                                updateKeyboardLabels()
                            }
                        } else if (!isDragging || (Math.abs(rawX - startX) < 15f && Math.abs(rawY - startY) < 15f)) {
                            onClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (runnable != null) handler.removeCallbacks(runnable!!)
                    activePopup?.dismiss()
                    true
                }
                else -> false
            }
        }
    }

    private fun reloadBackground() {
        if (!::keyboardView.isInitialized) return
        val rootLayout = keyboardView.findViewById<RelativeLayout>(R.id.keyboard_root_layout)
        val bgPackedColor = prefs.getInt("bgColorPacked", Color.parseColor("#ECECEC"))
        rootLayout?.setBackgroundColor(bgPackedColor)
        val bgImage = keyboardView.findViewById<ImageView>(R.id.keyboard_bg)
        val bgPath = prefs.getString("bgImagePath", null)
        if (bgPath != null) {
            val file = File(bgPath)
            if (file.exists()) {
                bgImage?.setImageURI(Uri.fromFile(file)); bgImage?.visibility = View.VISIBLE
            } else { bgImage?.setImageDrawable(null) }
        } else { bgImage?.setImageDrawable(null) }
        bgImage?.alpha = prefs.getFloat("bgAlpha", 0.4f)
        updateAllTextColors()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        reloadBackground(); updateKeyboardLabels()
    }

    private fun updateAllTextColors() {
        if (!::keyboardView.isInitialized) return
        val controlKeys = listOf(R.id.btn_shift, R.id.btn_space, R.id.btn_delete, R.id.btn_enter, R.id.btn_mode, R.id.btn_comma, R.id.btn_period)
        (KeyDatabase.keys.keys + controlKeys).forEach { id -> keyboardView.findViewById<TextView>(id)?.setTextColor(keyTextColor) }
    }

    override fun onCreateInputView(): View {
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        val rippleResId = getRippleResource()

        for ((buttonId, keyData) in KeyDatabase.keys) {
            val button = keyboardView.findViewById<TextView>(buttonId) ?: continue
            setupFastKey(button, buttonId,
                onClick = {
                    val isUpper = (shiftState == ShiftState.SHIFTED || shiftState == ShiftState.CAPSLOCKED)
                    val baseNormalText = if (isUpper) getCustomText(buttonId, "normalShift", keyData.normalShift) else getCustomText(buttonId, "normal", keyData.normal)
                    val textToInput = when (currentMode) {
                        InputMode.NORMAL -> baseNormalText
                        InputMode.BLACKBOARD -> if (isUpper) getCustomText(buttonId, "blackboardShift", keyData.blackboardShift) else getCustomText(buttonId, "blackboard", keyData.blackboard)
                        InputMode.FRAKTUR -> if (isUpper) getCustomText(buttonId, "frakturShift", keyData.frakturShift) else getCustomText(buttonId, "fraktur", keyData.fraktur)
                        InputMode.GREEK -> if (isUpper) getCustomText(buttonId, "greekShift", keyData.greekShift) else getCustomText(buttonId, "greek", keyData.greek)
                        InputMode.MATHSCRIPT -> if (isUpper) getCustomText(buttonId, "scriptShift", keyData.mathscriptShift) else getCustomText(buttonId, "script", keyData.mathscript)
                        InputMode.MATHSYMBOL -> if (isUpper) getCustomText(buttonId, "symbolShift", keyData.mathsymbolShift) else getCustomText(buttonId, "symbol", keyData.mathsymbol)
                        InputMode.SUPERSCRIPT -> toSuperscript(baseNormalText)
                        InputMode.SUBSCRIPT -> toSubscript(baseNormalText)
                        InputMode.ITALIC -> toItalic(baseNormalText)
                        InputMode.FULLWIDTH -> toFullWidth(baseNormalText)
                    }
                    commitTextWithNormalization(textToInput)
                    resetStateAfterInput()
                },
                onSetupPopup = {
                    val isUpper = (shiftState == ShiftState.SHIFTED || shiftState == ShiftState.CAPSLOCKED)

                    val fontOptions = if (isUpper) {
                        listOf(getCustomText(buttonId, "greekShift", keyData.greekShift), getCustomText(buttonId, "symbolShift", keyData.mathsymbolShift))
                    } else {
                        listOf(getCustomText(buttonId, "greek", keyData.greek), getCustomText(buttonId, "symbol", keyData.mathsymbol))
                    }

                    val lpNormalString = getCustomText(buttonId, "longPressNormal", keyData.longPressNormal.joinToString(" "))
                    val lpShiftString = getCustomText(buttonId, "longPressShift", keyData.longPressShift.joinToString(" "))
                    val customSymbolList = if (isUpper) lpShiftString.split(" ").filter { it.isNotEmpty() } else lpNormalString.split(" ").filter { it.isNotEmpty() }

                    val allOptions = (fontOptions + customSymbolList).filter { it.isNotEmpty() }.distinct()

                    val mainLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.parseColor("#D9E6E6E6"))
                        setPadding(8, 8, 8, 8)
                    }
                    val popupWindow = PopupWindow(mainLayout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        elevation = 20f
                    }
                    val optionsList = mutableListOf<Pair<View, String>>()

                    // 🌟 実際の要素数に応じて、1行の最大要素数を決定（最大7個）
                    val actualColumns = min(7, allOptions.size)
                    val chunks = allOptions.chunked(actualColumns).reversed()

                    val cellWidth = 100
                    val cellHeight = 130

                    fun addRow(chars: List<String>) {
                        if (chars.isEmpty()) return
                        val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

                        // 🌟 actualColumns の数だけループを回す
                        for (i in 0 until actualColumns) {
                            if (i < chars.size) {
                                val char = chars[i]
                                val textView = TextView(this).apply {
                                    val isCombining = char.isNotEmpty() && char.all {
                                        it in '\u0300'..'\u036F' || it in '\u20D0'..'\u20FF'
                                    }
                                    val displayText = if (isCombining) "◌$char" else char

                                    text = displayText
                                    isSingleLine = true
                                    textSize = if (displayText.length > 3) 14f else 18f
                                    setTextColor(Color.BLACK)
                                    gravity = Gravity.CENTER
                                    setBackgroundResource(rippleResId)

                                    layoutParams = LinearLayout.LayoutParams(cellWidth, cellHeight).apply { setMargins(2, 2, 2, 2) }

                                    setOnClickListener {
                                        commitTextWithNormalization(char)
                                        popupWindow.dismiss()
                                        resetStateAfterInput()
                                    }
                                }
                                rowLayout.addView(textView)
                                optionsList.add(textView to char)
                            } else {
                                val dummyView = View(this).apply {
                                    layoutParams = LinearLayout.LayoutParams(cellWidth, cellHeight).apply { setMargins(2, 2, 2, 2) }
                                }
                                rowLayout.addView(dummyView)
                            }
                        }
                        mainLayout.addView(rowLayout)
                    }

                    chunks.forEach { addRow(it) }

                    if (mainLayout.childCount > 0) {
                        popupWindow.showAsDropDown(button, 0, -button.height - ((cellHeight + 4) * mainLayout.childCount) - 40)
                        popupWindow to optionsList
                    } else {
                        null
                    }
                }
            )
        }

        val btnMode = keyboardView.findViewById<TextView>(R.id.btn_mode)
        btnMode?.let { modeBtn ->
            setupFastKey(modeBtn, R.id.btn_mode,
                onClick = {
                    currentCycleIndex = (currentCycleIndex + 1) % modeCycle.size
                    currentMode = modeCycle[currentCycleIndex]
                    isOneShotMode = false
                    modeBtn.text = currentMode.shortName
                    updateKeyboardLabels()
                },
                onSetupPopup = {
                    val popupView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#EEEEEE")); setPadding(12, 12, 12, 12) }
                    val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = 20f }

                    val popupHeight = 600

                    val modeScroll = ScrollView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(260, popupHeight)
                        isScrollbarFadingEnabled = false
                    }
                    val modeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    InputMode.values().forEach { m ->
                        modeLayout.addView(TextView(this).apply {
                            text = m.displayName; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(4, 4, 4, 4) }
                            setOnClickListener {
                                currentMode = m
                                isOneShotMode = false
                                modeBtn.text = m.shortName
                                updateKeyboardLabels()
                                popupWindow.dismiss()
                            }
                        })
                    }
                    modeScroll.addView(modeLayout)

                    val rightFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(300, popupHeight).apply { setMargins(24, 0, 0, 0) } }
                    val categoryScroll = ScrollView(this)
                    val categoryLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    val symbolScroll = ScrollView(this).apply { visibility = View.GONE }
                    val symbolLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    symbolScroll.addView(symbolLayout)
                    val settingScroll = ScrollView(this).apply { visibility = View.GONE }
                    val settingLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                    settingScroll.addView(settingLayout)

                    KeyDatabase.extraSymbols.forEach { (category, symbols) ->
                        categoryLayout.addView(TextView(this).apply {
                            text = category; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 4, 0, 4) }
                            setOnClickListener {
                                symbolLayout.removeAllViews()
                                symbolLayout.addView(LinearLayout(this@MathKeyboardService).apply {
                                    orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120)
                                    addView(TextView(this@MathKeyboardService).apply { text = "◀ $category"; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#D0D0D0")); layoutParams = LinearLayout.LayoutParams(0, -1, 2f).apply { setMargins(0,0,4,0) }; setOnClickListener { symbolScroll.visibility = View.GONE; categoryScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 300; rightFrame.requestLayout() } })
                                    addView(TextView(this@MathKeyboardService).apply { text = "Space"; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#E0E0E0")); layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f).apply { setMargins(0,0,4,0) }; setOnClickListener { currentInputConnection?.commitText(" ", 1) } })
                                    addView(TextView(this@MathKeyboardService).apply { text = "⌫"; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#E0E0E0")); layoutParams = LinearLayout.LayoutParams(0, -1, 1f); setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) } })
                                })
                                symbols.chunked(5).forEach { row ->
                                    symbolLayout.addView(LinearLayout(this@MathKeyboardService).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        row.forEach { sym -> addView(TextView(this@MathKeyboardService).apply {
                                            text = sym; textSize = 20f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(110, 120).apply { setMargins(1,1,1,1) }
                                            setOnClickListener { commitTextWithNormalization(sym) }
                                        }) }
                                    })
                                }
                                categoryScroll.visibility = View.GONE; symbolScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 650; rightFrame.requestLayout()
                            }
                        })
                    }
                    categoryLayout.addView(TextView(this).apply { text = "設定"; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 16, 0, 4) }; setOnClickListener { categoryScroll.visibility = View.GONE; settingScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 650; rightFrame.requestLayout() } })
                    settingLayout.addView(TextView(this).apply { text = "◀ 戻る"; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#D0D0D0")); layoutParams = LinearLayout.LayoutParams(-1, 120).apply { setMargins(0,0,0,30) }; setOnClickListener { settingScroll.visibility = View.GONE; categoryScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 300; rightFrame.requestLayout() } })

                    val colorBtn = TextView(this).apply {
                        text = if (keyTextColor == Color.BLACK) "文字色を反転 (現在: 黒)" else "文字色を反転 (現在: 白)"
                        textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 0, 0, 30)
                        setOnClickListener {
                            keyTextColor = if (keyTextColor == Color.BLACK) Color.WHITE else Color.BLACK
                            prefs.edit().putInt("keyTextColor", keyTextColor).apply()
                            updateAllTextColors()
                            text = if (keyTextColor == Color.BLACK) "文字色を反転 (現在: 黒)" else "文字色を反転 (現在: 白)"
                        }
                    }
                    settingLayout.addView(colorBtn)
                    settingLayout.addView(TextView(this).apply { text = "画像の透過率"; setTextColor(Color.BLACK) })
                    settingLayout.addView(SeekBar(this).apply { max = 100; progress = (prefs.getFloat("bgAlpha", 0.4f) * 100).toInt(); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { keyboardView.findViewById<ImageView>(R.id.keyboard_bg)?.alpha = p/100f; prefs.edit().putFloat("bgAlpha", p/100f).apply() }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) })
                    settingLayout.addView(TextView(this).apply { text = "背景の明るさ"; setTextColor(Color.BLACK); setPadding(0,20,0,0) })
                    settingLayout.addView(SeekBar(this).apply { max = 255; progress = Color.red(prefs.getInt("bgColorPacked", Color.parseColor("#ECECEC"))); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { val c = Color.rgb(p,p,p); keyboardView.findViewById<RelativeLayout>(R.id.keyboard_root_layout)?.setBackgroundColor(c); prefs.edit().putInt("bgColorPacked", c).apply() }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) })
                    settingLayout.addView(TextView(this).apply { text = "詳細設定"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#4285F4")); setPadding(20, 30, 20, 30); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 30, 0, 0) }; setOnClickListener { val intent = Intent(this@MathKeyboardService, SettingsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }; try { startActivity(intent); popupWindow.dismiss() } catch (e: Exception) {} } })

                    categoryScroll.addView(categoryLayout); rightFrame.addView(categoryScroll); rightFrame.addView(symbolScroll); rightFrame.addView(settingScroll)
                    popupView.addView(modeScroll)
                    popupView.addView(rightFrame)

                    popupWindow.showAsDropDown(modeBtn, 0, -modeBtn.height - popupHeight - 40)

                    popupWindow to emptyList()
                }
            )
        }

        val btnDelete = keyboardView.findViewById<TextView>(R.id.btn_delete)
        btnDelete?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { isDeleting = true; v.isPressed = true; currentInputConnection?.deleteSurroundingText(1, 0); deleteHandler.postDelayed(deleteRunnable, 400); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isDeleting = false; v.isPressed = false; deleteHandler.removeCallbacks(deleteRunnable); true }
                else -> false
            }
        }

        keyboardView.findViewById<TextView>(R.id.btn_shift)?.setOnClickListener {
            val now = System.currentTimeMillis()
            shiftState = if (shiftState == ShiftState.NORMAL) ShiftState.SHIFTED else if (shiftState == ShiftState.SHIFTED && now - lastShiftTime < DOUBLE_TAP_TIMEOUT) ShiftState.CAPSLOCKED else ShiftState.NORMAL
            lastShiftTime = now; updateShiftButtonUI(); updateKeyboardLabels()
        }
        keyboardView.findViewById<TextView>(R.id.btn_space)?.setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        keyboardView.findViewById<TextView>(R.id.btn_enter)?.setOnClickListener {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        reloadBackground(); return keyboardView
    }

    private fun updateShiftButtonUI() {
        keyboardView.findViewById<TextView>(R.id.btn_shift)?.text = when (shiftState) { ShiftState.NORMAL -> "⇧"; ShiftState.SHIFTED -> "⬆"; ShiftState.CAPSLOCKED -> "⇪" }
    }

    private fun updateKeyboardLabels() {
        if (!::keyboardView.isInitialized) return
        val isUpper = (shiftState == ShiftState.SHIFTED || shiftState == ShiftState.CAPSLOCKED)
        for ((buttonId, keyData) in KeyDatabase.keys) {
            val button = keyboardView.findViewById<TextView>(buttonId) ?: continue

            val baseNormalText = if (isUpper) getCustomText(buttonId, "normalShift", keyData.normalShift) else getCustomText(buttonId, "normal", keyData.normal)

            button.text = when (currentMode) {
                InputMode.NORMAL -> baseNormalText
                InputMode.BLACKBOARD -> if (isUpper) getCustomText(buttonId, "blackboardShift", keyData.blackboardShift) else getCustomText(buttonId, "blackboard", keyData.blackboard)
                InputMode.FRAKTUR -> if (isUpper) getCustomText(buttonId, "frakturShift", keyData.frakturShift) else getCustomText(buttonId, "fraktur", keyData.fraktur)
                InputMode.GREEK -> if (isUpper) getCustomText(buttonId, "greekShift", keyData.greekShift) else getCustomText(buttonId, "greek", keyData.greek)
                InputMode.MATHSCRIPT -> if (isUpper) getCustomText(buttonId, "scriptShift", keyData.mathscriptShift) else getCustomText(buttonId, "script", keyData.mathscript)
                InputMode.MATHSYMBOL -> if (isUpper) getCustomText(buttonId, "symbolShift", keyData.mathsymbolShift) else getCustomText(buttonId, "symbol", keyData.mathsymbol)
                InputMode.SUPERSCRIPT -> toSuperscript(baseNormalText)
                InputMode.SUBSCRIPT -> toSubscript(baseNormalText)
                InputMode.ITALIC -> toItalic(baseNormalText)
                InputMode.FULLWIDTH -> toFullWidth(baseNormalText)
            }
        }
    }
}