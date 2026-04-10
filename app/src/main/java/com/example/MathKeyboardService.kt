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

class MathKeyboardService : InputMethodService() {

    enum class InputMode(val shortName: String, val displayName: String) {
        NORMAL("NOR", "Normal"),
        BLACKBOARD("BB", "Blackboard"),
        FRAKTUR("FRK", "Fraktur"),
        GREEK("GRK", "Greek"),
        MATHSCRIPT("SCR", "Script"),
        MATHSYMBOL("SYM", "Symbol")
    }
    private var currentMode = InputMode.NORMAL

    enum class ShiftState { NORMAL, SHIFTED, CAPSLOCKED }
    private var shiftState = ShiftState.NORMAL

    private var lastShiftTime: Long = 0
    private val DOUBLE_TAP_TIMEOUT = 400L

    private lateinit var keyboardView: View
    private lateinit var prefs: SharedPreferences
    private var keyTextColor = Color.BLACK

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
        return prefs.getString("key_${buttonId}_$suffix", defaultValue) ?: defaultValue
    }

    private fun getRippleResource(): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    private fun setupFastKey(button: TextView, onClick: () -> Unit, onLongPress: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var isLongPress = false
        var runnable: Runnable? = null
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPress = false; v.isPressed = true
                    runnable = Runnable { isLongPress = true; onLongPress() }
                    handler.postDelayed(runnable!!, 150L); true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (runnable != null) handler.removeCallbacks(runnable!!)
                    if (!isLongPress) onClick(); true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (runnable != null) handler.removeCallbacks(runnable!!)
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
            setupFastKey(button,
                onClick = {
                    val isUpper = (shiftState == ShiftState.SHIFTED || shiftState == ShiftState.CAPSLOCKED)
                    val textToInput = when (currentMode) {
                        InputMode.NORMAL -> if (isUpper) getCustomText(buttonId, "normalShift", keyData.normalShift) else getCustomText(buttonId, "normal", keyData.normal)
                        InputMode.BLACKBOARD -> if (isUpper) getCustomText(buttonId, "blackboardShift", keyData.blackboardShift) else getCustomText(buttonId, "blackboard", keyData.blackboard)
                        InputMode.FRAKTUR -> if (isUpper) getCustomText(buttonId, "frakturShift", keyData.frakturShift) else getCustomText(buttonId, "fraktur", keyData.fraktur)
                        InputMode.GREEK -> if (isUpper) getCustomText(buttonId, "greekShift", keyData.greekShift) else getCustomText(buttonId, "greek", keyData.greek)
                        InputMode.MATHSCRIPT -> if (isUpper) getCustomText(buttonId, "scriptShift", keyData.mathscriptShift) else getCustomText(buttonId, "script", keyData.mathscript)
                        InputMode.MATHSYMBOL -> if (isUpper) getCustomText(buttonId, "symbolShift", keyData.mathsymbolShift) else getCustomText(buttonId, "symbol", keyData.mathsymbol)
                    }
                    currentInputConnection?.commitText(textToInput, 1)
                    if (shiftState == ShiftState.SHIFTED) { shiftState = ShiftState.NORMAL; updateShiftButtonUI(); updateKeyboardLabels() }
                },
                onLongPress = {
                    val isUpper = (shiftState == ShiftState.SHIFTED || shiftState == ShiftState.CAPSLOCKED)

                    // 1. 各フォントの候補をシフト状態で切り替え
                    val fontOptions = if (isUpper) {
                        listOf(
                            getCustomText(buttonId, "normalShift", keyData.normalShift),
                            getCustomText(buttonId, "blackboardShift", keyData.blackboardShift),
                            getCustomText(buttonId, "greekShift", keyData.greekShift),
                            getCustomText(buttonId, "scriptShift", keyData.mathscriptShift),
                            getCustomText(buttonId, "frakturShift", keyData.frakturShift)
                        )
                    } else {
                        listOf(
                            getCustomText(buttonId, "normal", keyData.normal),
                            getCustomText(buttonId, "blackboard", keyData.blackboard),
                            getCustomText(buttonId, "greek", keyData.greek),
                            getCustomText(buttonId, "script", keyData.mathscript),
                            getCustomText(buttonId, "fraktur", keyData.fraktur)
                        )
                    }

                    // 2. 長押し専用カスタムリストもシフト状態で切り替え
                    val lpNormalString = getCustomText(buttonId, "longPressNormal", keyData.longPressNormal.joinToString(" "))
                    val lpShiftString = getCustomText(buttonId, "longPressShift", keyData.longPressShift.joinToString(" "))

                    val customSymbolList = if (isUpper) {
                        lpShiftString.split(" ").filter { it.isNotEmpty() }
                    } else {
                        lpNormalString.split(" ").filter { it.isNotEmpty() }
                    }

                    // 🌟 3. 全ての候補を結合し、空文字を消して重複を排除（ここで完全に1つにまとめる）
                    val allOptions = (fontOptions + customSymbolList).filter { it.isNotEmpty() }.distinct()

                    val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(8, 8, 8, 8) }
                    val popupWindow = PopupWindow(mainLayout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = 20f }

                    fun addRow(chars: List<String>) {
                        if (chars.isEmpty()) return
                        val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        for (char in chars) {
                            rowLayout.addView(TextView(this).apply {
                                text = char; isSingleLine = true; textSize = if (char.length > 3) 14f else 18f
                                setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId); setPadding(20, 0, 20, 0)
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 140); minWidth = 100
                                setOnClickListener {
                                    currentInputConnection?.commitText(char, 1)
                                    popupWindow.dismiss()

                                    // ポップアップから入力した後、1回シフトなら通常モードに戻す
                                    if (shiftState == ShiftState.SHIFTED) {
                                        shiftState = ShiftState.NORMAL
                                        updateShiftButtonUI()
                                        updateKeyboardLabels()
                                    }
                                }
                            })
                        }
                        mainLayout.addView(rowLayout)
                    }

                    // 🌟 4. 重複をなくしたリストを4個ずつ改行して表示する
                    allOptions.chunked(4).forEach { addRow(it) }

                    if (mainLayout.childCount > 0) { popupWindow.showAsDropDown(button, 0, -button.height - (140 * mainLayout.childCount) - 40) }
                }
            )
        }

        val btnMode = keyboardView.findViewById<TextView>(R.id.btn_mode)
        btnMode?.let { modeBtn ->
            setupFastKey(modeBtn,
                onClick = {
                    currentMode = InputMode.values()[(currentMode.ordinal + 1) % InputMode.values().size]
                    modeBtn.text = currentMode.shortName; updateKeyboardLabels()
                },
                onLongPress = {
                    val popupView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#EEEEEE")); setPadding(12, 12, 12, 12) }
                    val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = 20f }

                    val modeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    InputMode.values().forEach { m ->
                        modeLayout.addView(TextView(this).apply {
                            text = m.displayName; textSize = 15f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
                            setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(260, 130).apply { setMargins(0, 4, 0, 4) }
                            setOnClickListener { currentMode = m; modeBtn.text = m.shortName; updateKeyboardLabels(); popupWindow.dismiss() }
                        })
                    }

                    val rightFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(300, 830).apply { setMargins(24, 0, 0, 0) } }

                    val categoryScroll = ScrollView(this)
                    val categoryLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

                    val symbolScroll = ScrollView(this).apply { visibility = View.GONE }
                    val symbolLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    symbolScroll.addView(symbolLayout)

                    val settingScroll = ScrollView(this).apply { visibility = View.GONE }
                    val settingLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                    settingScroll.addView(settingLayout)

                    // カテゴリ一覧
                    KeyDatabase.extraSymbols.forEach { (category, symbols) ->
                        categoryLayout.addView(TextView(this).apply {
                            text = category; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
                            setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 4, 0, 4) }
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
                                            text = sym; textSize = 20f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
                                            setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(110, 120).apply { setMargins(1,1,1,1) }; setOnClickListener { currentInputConnection?.commitText(sym, 1) }
                                        }) }
                                    })
                                }
                                categoryScroll.visibility = View.GONE; symbolScroll.visibility = View.VISIBLE
                                rightFrame.layoutParams.width = 650; rightFrame.requestLayout()
                            }
                        })
                    }

                    categoryLayout.addView(TextView(this).apply {
                        text = "設定"; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 16, 0, 4) }
                        setOnClickListener { categoryScroll.visibility = View.GONE; settingScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 650; rightFrame.requestLayout() }
                    })

                    // 🌟 設定画面（文字色反転ボタンの追加）
                    settingLayout.addView(TextView(this).apply { text = "◀ 戻る"; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#D0D0D0")); layoutParams = LinearLayout.LayoutParams(-1, 120).apply { setMargins(0,0,0,30) }; setOnClickListener { settingScroll.visibility = View.GONE; categoryScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 300; rightFrame.requestLayout() } })

                    // 文字色トグル
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
                    popupView.addView(modeLayout); popupView.addView(rightFrame)
                    popupWindow.showAsDropDown(modeBtn, 0, -modeBtn.height - 830 - 40)
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
            button.text = when (currentMode) {
                InputMode.NORMAL -> if (isUpper) getCustomText(buttonId, "normalShift", keyData.normalShift) else getCustomText(buttonId, "normal", keyData.normal)
                InputMode.BLACKBOARD -> if (isUpper) getCustomText(buttonId, "blackboardShift", keyData.blackboardShift) else getCustomText(buttonId, "blackboard", keyData.blackboard)
                InputMode.FRAKTUR -> if (isUpper) getCustomText(buttonId, "frakturShift", keyData.frakturShift) else getCustomText(buttonId, "fraktur", keyData.fraktur)
                InputMode.GREEK -> if (isUpper) getCustomText(buttonId, "greekShift", keyData.greekShift) else getCustomText(buttonId, "greek", keyData.greek)
                InputMode.MATHSCRIPT -> if (isUpper) getCustomText(buttonId, "scriptShift", keyData.mathscriptShift) else getCustomText(buttonId, "script", keyData.mathscript)
                InputMode.MATHSYMBOL -> if (isUpper) getCustomText(buttonId, "symbolShift", keyData.mathsymbolShift) else getCustomText(buttonId, "symbol", keyData.mathsymbol)
            }
        }
    }
}