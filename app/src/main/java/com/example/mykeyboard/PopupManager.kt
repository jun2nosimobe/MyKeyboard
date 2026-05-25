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
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

object PopupManager {

    private var cachedScreenWidth: Int = -1
    private var cachedModePopup: Pair<PopupWindow, List<Pair<View, String>>>? = null

    // 🌟 設定画面から戻ったときなどにキャッシュを明示的に破棄するための関数
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
    // 通常キーの長押しポップアップ (変更なし)
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
    // 🌟 劇的にスリム化した左下モードボタンの長押しポップアップ
    // ==========================================
    fun createModeKeyPopup(
        context: Context,
        anchorView: View,
        rippleResId: Int,
        onModeSelected: (MathKeyboardService.InputMode) -> Unit,
        onSymbolSelected: (String) -> Unit,
        onBackspaceSelected: () -> Unit,
        onSpaceSelected: () -> Unit,
        onSettingsClicked: () -> Unit // 🌟 大量の設定コールバックをこれ1つに統合！
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

        // 左側：モード切り替えリスト
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

        // 右側：記号カテゴリリスト
        val rightFrame = FrameLayout(context).apply { layoutParams = LinearLayout.LayoutParams(300, popupHeight).apply { setMargins(24, 0, 0, 0) } }
        val categoryScroll = ScrollView(context)
        val categoryLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val symbolScroll = ScrollView(context).apply { visibility = View.GONE }
        val symbolLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        symbolScroll.addView(symbolLayout)

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

        // 🌟 変更点：ポップアップ内の複雑なSeekBar群を消去し、タップしたら即MainActivityを開くシンプルな構造に
        categoryLayout.addView(TextView(context).apply {
            text = "キーボード設定を開く ⚙️"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF4285F4.toInt()) // Googleブルー的な色
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140).apply { setMargins(0, 24, 0, 4) }
            setOnClickListener {
                onSettingsClicked()
                popupWindow.dismiss()
            }
        })

        categoryScroll.addView(categoryLayout)
        rightFrame.addView(categoryScroll)
        rightFrame.addView(symbolScroll)
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