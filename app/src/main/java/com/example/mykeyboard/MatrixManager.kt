package com.example.mykeyboard

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MatrixManager(private val context: Context) {

    // 🌟 先ほど確認した行列サイズ
    private val matrixSize = 3000

    private lateinit var matrixData: ShortArray
    private var isLoaded = false

    fun loadMatrix() {
        if (isLoaded) return

        try {
            // assetsから matrix.dat を一気にメモリへ展開
            context.assets.open("matrix.dat").use { inputStream ->
                val bytes = inputStream.readBytes()
                val byteBuffer = ByteBuffer.wrap(bytes).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                matrixData = ShortArray(matrixSize * matrixSize)
                byteBuffer.asShortBuffer().get(matrixData)
            }
            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 接続コストを取得（実行速度はほぼゼロ秒）
    fun getConnectionCost(rid: Int, lid: Int): Int {
        if (!isLoaded || rid >= matrixSize || lid >= matrixSize) {
            return 30000 // 未ロードやエラー時はペナルティコスト
        }
        return matrixData[rid * matrixSize + lid].toInt()
    }
}