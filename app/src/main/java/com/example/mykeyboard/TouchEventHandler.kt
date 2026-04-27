package com.example.mykeyboard

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow

class TouchEventHandler(
    private val onSingleTap: () -> Unit,
    private val onLongPressSetup: () -> Pair<PopupWindow, List<Pair<View, String>>>?,
    private val onFlick: (FlickDirection) -> Unit,
    private val getRippleResource: () -> Int
) : View.OnTouchListener {

    enum class FlickDirection { UP, DOWN, LEFT, RIGHT, NONE }

    private val handler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private var runnable: Runnable? = null
    private var isDragging = false
    private var startX = 0f
    private var startY = 0f

    private var activePopup: PopupWindow? = null
    private var currentPopupOptions: List<Pair<View, String>> = emptyList()
    private var hoveredOption: View? = null

    private val tempLoc = IntArray(2)

    // 🌟 既存の onTouch は念のため残しつつ、中身を handleRoutedTouch に横流しします
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return handleRoutedTouch(v, event.actionMasked, event.x, event.y, event.rawX, event.rawY)
    }

    // ==========================================
    // 🌟 NEW: コントローラーから正確な座標を直接受け取る専用メソッド
    // ==========================================
    fun handleRoutedTouch(v: View, action: Int, x: Float, y: Float, rawX: Float, rawY: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isLongPress = false
                isDragging = false
                startX = x // 🌟 正確なローカルX座標を保存
                startY = y
                v.isPressed = true
                runnable = Runnable {
                    isLongPress = true
                    val result = onLongPressSetup()
                    if (result != null) {
                        activePopup = result.first
                        currentPopupOptions = result.second
                        activePopup?.setOnDismissListener {
                            if (activePopup == result.first) {
                                activePopup = null; currentPopupOptions = emptyList(); hoveredOption = null
                            }
                        }
                    }
                }
                handler.postDelayed(runnable!!, 200L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 🌟 修正: 誤判定を防ぐため、ドラッグ判定の遊びを 30f に拡大
                if (!isDragging && (Math.abs(x - startX) > 30f || Math.abs(y - startY) > 30f)) isDragging = true

                if (isLongPress && activePopup != null && activePopup!!.isShowing && currentPopupOptions.isNotEmpty()) {
                    var foundHover: View? = null
                    val rx = rawX.toInt() // ポップアップは画面全体座標なのでrawXを使う
                    val ry = rawY.toInt()

                    for ((optionView, _) in currentPopupOptions) {
                        optionView.getLocationOnScreen(tempLoc)
                        val left = tempLoc[0] - 20
                        val top = tempLoc[1] - 20
                        val right = tempLoc[0] + optionView.width + 20
                        val bottom = tempLoc[1] + optionView.height + 20

                        if (rx in left..right && ry in top..bottom) {
                            foundHover = optionView
                            break
                        }
                    }
                    if (hoveredOption != foundHover) {
                        hoveredOption?.setBackgroundResource(getRippleResource())
                        hoveredOption = foundHover
                        hoveredOption?.setBackgroundColor(android.graphics.Color.parseColor("#B3D4FF"))
                    }
                }
                if (!isLongPress && isDragging && Math.abs(y - startY) > 80f) runnable?.let { handler.removeCallbacks(it) }
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                runnable?.let { handler.removeCallbacks(it) }

                if (isLongPress && activePopup != null && activePopup!!.isShowing) {
                    if (hoveredOption != null) {
                        hoveredOption?.performClick()
                        activePopup?.dismiss()
                    } else if (isDragging && currentPopupOptions.isNotEmpty()) {
                        activePopup?.dismiss()
                    }
                } else if (!isLongPress) {
                    val dx = x - startX
                    val dy = y - startY
                    if (isDragging && Math.abs(dy) > 80f && Math.abs(dy) > Math.abs(dx)) {
                        if (dy > 0) onFlick(FlickDirection.DOWN) else onFlick(FlickDirection.UP)
                    } else if (isDragging && Math.abs(dx) > 80f && Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) onFlick(FlickDirection.RIGHT) else onFlick(FlickDirection.LEFT)
                    } else if (!isDragging || (Math.abs(dx) < 30f && Math.abs(dy) < 30f)) { // 🌟 修正: 30f未満なら単推し
                        onSingleTap()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                runnable?.let { handler.removeCallbacks(it) }
                activePopup?.dismiss()
                return true
            }
            else -> return false
        }
    }
}