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

    // 🌟 改善: メモリのゴミを出さないよう、座標計算用の配列を使い回す（ゼロ・アロケーション）
    private val tempLoc = IntArray(2)

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.action) {
            // ACTION_DOWN は変更なし
            MotionEvent.ACTION_DOWN -> {
                isLongPress = false; isDragging = false; startX = rawX; startY = rawY
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
                if (!isDragging && (Math.abs(rawX - startX) > 15f || Math.abs(rawY - startY) > 15f)) isDragging = true

                if (isLongPress && activePopup != null && activePopup!!.isShowing && currentPopupOptions.isNotEmpty()) {
                    var foundHover: View? = null
                    val rx = rawX.toInt()
                    val ry = rawY.toInt()

                    // 🌟 改善: new Rect() や new IntArray() を一切使わずに高速判定
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
                if (!isLongPress && isDragging && Math.abs(rawY - startY) > 80f) runnable?.let { handler.removeCallbacks(it) }
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                runnable?.let { handler.removeCallbacks(it) }

                if (isLongPress && activePopup != null && activePopup!!.isShowing) {
                    if (hoveredOption != null) {
                        // 長押しポップアップからの選択を確定（コールバックではなく、この場で処理しても良いが、ここでは単純にクリックイベントをシミュレートするなどの工夫が必要。
                        // 今回は設計の簡略化のため、コールバックに渡した処理をそのまま使います。）
                        hoveredOption?.performClick()
                        activePopup?.dismiss()
                    } else if (isDragging && currentPopupOptions.isNotEmpty()) {
                        activePopup?.dismiss()
                    }
                } else if (!isLongPress) {
                    val dx = rawX - startX
                    val dy = rawY - startY
                    if (isDragging && Math.abs(dy) > 80f && Math.abs(dy) > Math.abs(dx)) {
                        // 縦フリック
                        if (dy > 0) onFlick(FlickDirection.DOWN) else onFlick(FlickDirection.UP)
                    } else if (isDragging && Math.abs(dx) > 80f && Math.abs(dx) > Math.abs(dy)) {
                        // 横フリック
                        if (dx > 0) onFlick(FlickDirection.RIGHT) else onFlick(FlickDirection.LEFT)
                    } else if (!isDragging || (Math.abs(dx) < 15f && Math.abs(dy) < 15f)) {
                        // 単推し
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