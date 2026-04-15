package com.example.mykeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

object PopupManager {

    // 🌟 システムサービスの呼び出しをキャッシュ化
    private var cachedScreenWidth: Int = -1
    private var cachedModePopup: Pair<PopupWindow, List<Pair<View, String>>>? = null
    // もし設定（色など）が変わったらキャッシュを破棄するための関数
    fun invalidateModePopupCache() {
        cachedModePopup = null
    }
    private fun getScreenWidth(context: Context): Int {
        if (cachedScreenWidth > 0) return cachedScreenWidth
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        cachedScreenWidth = displayMetrics.widthPixels
        return cachedScreenWidth
    }

    private fun getViewScreenLocationY(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1]
    }

    // ==========================================
    // 通常キーの長押しポップアップ
    // ==========================================
    fun createNormalKeyPopup(
        context: Context,
        anchorView: View,
        rippleResId: Int,
        allOptions: List<String>,
        onOptionSelected: (String) -> Unit
    ): Pair<PopupWindow, List<Pair<View, String>>>? {
        if (allOptions.isEmpty()) return null

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 🌟 文字列パースを排除し16進数リテラルを使用 (処理軽減)
            setBackgroundColor(0xD9E6E6E6.toInt())
            setPadding(8, 8, 8, 8)
        }

        val popupWindow = PopupWindow(mainLayout, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 20f
            isFocusable = true
            isOutsideTouchable = true
        }
        val optionsList = mutableListOf<Pair<View, String>>()

        val actualColumns = min(7, allOptions.size)
        val chunks = allOptions.chunked(actualColumns).reversed()

        val screenWidth = getScreenWidth(context)
        val defaultCellWidth = 100
        val paddingAndMargins = 16 + (actualColumns * 4)
        val maxPossibleWidth = (defaultCellWidth * actualColumns) + paddingAndMargins

        val cellWidth = if (maxPossibleWidth > screenWidth) {
            (screenWidth - paddingAndMargins) / actualColumns
        } else {
            defaultCellWidth
        }
        val cellHeight = 130

        for (chars in chunks) {
            if (chars.isEmpty()) continue
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            for (i in 0 until actualColumns) {
                if (i < chars.size) {
                    val char = chars[i]
                    val textView = TextView(context).apply {
                        var isCombining = true
                        for (c in char) {
                            if (c !in '\u0300'..'\u036F' && c !in '\u20D0'..'\u20FF') {
                                isCombining = false; break
                            }
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
                            onOptionSelected(char)
                            popupWindow.dismiss()
                        }
                    }
                    rowLayout.addView(textView)
                    optionsList.add(textView to char)
                } else {
                    val dummyView = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(cellWidth, cellHeight).apply { setMargins(2, 2, 2, 2) }
                    }
                    rowLayout.addView(dummyView)
                }
            }
            mainLayout.addView(rowLayout)
        }

        if (mainLayout.childCount > 0) {
            val buttonY = getViewScreenLocationY(anchorView)
            val popupHeight = (cellHeight + 4) * mainLayout.childCount + 16
            val margin = 40

            var yOffset = -anchorView.height - popupHeight - margin
            if (buttonY - popupHeight - margin < 0) {
                yOffset = -buttonY + 50
            }

            popupWindow.showAsDropDown(anchorView, 0, yOffset)
            return popupWindow to optionsList
        }
        return null
    }

    // ==========================================
    // 左下モードボタンの長押しポップアップ
    // ==========================================
    fun createModeKeyPopup(
        context: Context,
        anchorView: View,
        rippleResId: Int,
        onModeSelected: (MathKeyboardService.InputMode) -> Unit,
        onSymbolSelected: (String) -> Unit,
        onBackspaceSelected: () -> Unit,
        onSpaceSelected: () -> Unit,
        onSettingsColorToggle: () -> Unit,
        onSettingsAlphaChanged: (Int) -> Unit,
        onSettingsBrightnessChanged: (Int) -> Unit,
        onSettingsDetailClicked: () -> Unit,
        currentKeyTextColor: Int,
        currentBgAlpha: Float,
        currentBgColorPacked: Int
    ): Pair<PopupWindow, List<Pair<View, String>>> {
        if (cachedModePopup != null) {
            val buttonY = getViewScreenLocationY(anchorView)
            val popupWindow = cachedModePopup!!.first
            val popupHeight = popupWindow.contentView.height
            var yOffset = -anchorView.height - popupHeight - 40
            if (buttonY - popupHeight - 40 < 0) yOffset = -buttonY + 50
            popupWindow.showAsDropDown(anchorView, 0, yOffset)
            return cachedModePopup!!
        }

        val popupView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 🌟 16進数リテラルを使用
            setBackgroundColor(0xFFEEEEEE.toInt())
            setPadding(12, 12, 12, 12)
        }

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 20f
            isFocusable = true
            isOutsideTouchable = true
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenHeight = displayMetrics.heightPixels

        val buttonY = getViewScreenLocationY(anchorView)
        val availableHeightAbove = max(0, buttonY - 50)
        val popupHeight = min(750, min((screenHeight * 0.8).toInt(), availableHeightAbove))

        val modeScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(260, popupHeight)
            isScrollbarFadingEnabled = false
        }
        val modeLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        MathKeyboardService.InputMode.values().forEach { m ->
            modeLayout.addView(TextView(context).apply {
                text = m.displayName; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId)
                layoutParams = LinearLayout.LayoutParams(220, 130).apply { setMargins(4, 4, 4, 4) }
                setOnClickListener { onModeSelected(m); popupWindow.dismiss() }
            })
        }
        modeScroll.addView(modeLayout)

        val rightFrame = FrameLayout(context).apply { layoutParams = LinearLayout.LayoutParams(300, popupHeight).apply { setMargins(24, 0, 0, 0) } }
        val categoryScroll = ScrollView(context)
        val categoryLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val symbolScroll = ScrollView(context).apply { visibility = View.GONE }
        val symbolLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        symbolScroll.addView(symbolLayout)
        val settingScroll = ScrollView(context).apply { visibility = View.GONE }
        val settingLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        settingScroll.addView(settingLayout)

        KeyDatabase.extraSymbols.forEach { (category, symbols) ->
            categoryLayout.addView(TextView(context).apply {
                text = category; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 4, 0, 4) }
                setOnClickListener {
                    symbolLayout.removeAllViews()
                    symbolLayout.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120)
                        addView(TextView(context).apply { text = "◀ $category"; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(0xFFD0D0D0.toInt()); layoutParams = LinearLayout.LayoutParams(0, -1, 2f).apply { setMargins(0, 0, 4, 0) }; setOnClickListener { symbolScroll.visibility = View.GONE; categoryScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 300; rightFrame.requestLayout() } })
                        addView(TextView(context).apply { text = "Space"; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(0xFFE0E0E0.toInt()); layoutParams = LinearLayout.LayoutParams(0, -1, 1.5f).apply { setMargins(0, 0, 4, 0) }; setOnClickListener { onSpaceSelected() } })
                        addView(TextView(context).apply { text = "⌫"; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(0xFFE0E0E0.toInt()); layoutParams = LinearLayout.LayoutParams(0, -1, 1f); setOnClickListener { onBackspaceSelected() } })
                    })

                    val symCellWidth = 110

                    symbols.chunked(5).forEach { row ->
                        symbolLayout.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            row.forEach { sym ->
                                addView(TextView(context).apply {
                                    text = sym; textSize = 20f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId)
                                    layoutParams = LinearLayout.LayoutParams(symCellWidth, 120).apply { setMargins(1, 1, 1, 1) }
                                    setOnClickListener { onSymbolSelected(sym) }
                                })
                            }
                        })
                    }

                    val screenWidth = getScreenWidth(context)
                    val maxRightWidth = screenWidth - 260 - 48
                    val targetWidth = min(650, maxRightWidth)

                    categoryScroll.visibility = View.GONE
                    symbolScroll.visibility = View.VISIBLE
                    rightFrame.layoutParams.width = targetWidth
                    rightFrame.requestLayout()
                }
            })
        }

        categoryLayout.addView(TextView(context).apply { text = "設定"; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundResource(rippleResId); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 16, 0, 4) }; setOnClickListener {
            val screenWidth = getScreenWidth(context)
            val maxRightWidth = screenWidth - 260 - 48
            val targetWidth = min(650, maxRightWidth)

            categoryScroll.visibility = View.GONE
            settingScroll.visibility = View.VISIBLE
            rightFrame.layoutParams.width = targetWidth
            rightFrame.requestLayout()
        } })

        settingLayout.addView(TextView(context).apply { text = "◀ 戻る"; textSize = 14f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; setBackgroundColor(0xFFD0D0D0.toInt()); layoutParams = LinearLayout.LayoutParams(-1, 120).apply { setMargins(0, 0, 0, 30) }; setOnClickListener { settingScroll.visibility = View.GONE; categoryScroll.visibility = View.VISIBLE; rightFrame.layoutParams.width = 300; rightFrame.requestLayout() } })

        val colorBtn = TextView(context).apply {
            text = if (currentKeyTextColor == Color.BLACK) "文字色を反転 (現在: 黒)" else "文字色を反転 (現在: 白)"
            textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 0, 0, 30)
            setOnClickListener {
                onSettingsColorToggle()
                text = if (text.contains("黒")) "文字色を反転 (現在: 白)" else "文字色を反転 (現在: 黒)"
            }
        }
        settingLayout.addView(colorBtn)
        settingLayout.addView(TextView(context).apply { text = "画像の透過率"; setTextColor(Color.BLACK) })
        settingLayout.addView(SeekBar(context).apply { max = 100; progress = (currentBgAlpha * 100).toInt(); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { onSettingsAlphaChanged(p) }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) })
        settingLayout.addView(TextView(context).apply { text = "背景の明るさ"; setTextColor(Color.BLACK); setPadding(0, 20, 0, 0) })
        settingLayout.addView(SeekBar(context).apply { max = 255; progress = Color.red(currentBgColorPacked); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { onSettingsBrightnessChanged(p) }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) })
        settingLayout.addView(TextView(context).apply { text = "詳細設定"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setBackgroundColor(0xFF4285F4.toInt()); setPadding(20, 30, 20, 30); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 30, 0, 0) }; setOnClickListener { onSettingsDetailClicked(); popupWindow.dismiss() } })

        categoryScroll.addView(categoryLayout); rightFrame.addView(categoryScroll); rightFrame.addView(symbolScroll); rightFrame.addView(settingScroll)
        popupView.addView(modeScroll)
        popupView.addView(rightFrame)

        var yOffset = -anchorView.height - popupHeight - 40
        if (buttonY - popupHeight - 40 < 0) {
            yOffset = -buttonY + 50
        }

        popupWindow.showAsDropDown(anchorView, 0, yOffset)

        val result = popupWindow to emptyList<Pair<View, String>>()
        cachedModePopup = result
        return result
    }
}