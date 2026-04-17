package com.example.mykeyboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MatrixManager(private val context: Context) {

    // 🌟 先ほど確認した行列サイズ
    private val matrixSize = 3000

    // ShortArrayの代わりに、OSの仮想メモリ空間への参照を持つ
    private var mappedMatrix: MappedByteBuffer? = null
    private var isLoaded = false

    fun loadMatrix() {
        if (isLoaded) return

        try {
            val fileName = "matrix.dat"
            val file = File(context.filesDir, fileName)

            // 1. 初回のみ assets から内部ストレージへファイルをコピー
            // （Androidではassets内のファイルを直接メモリマッピングできないため）
            if (!file.exists()) {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // 2. メモリマップドファイルとしてOSの仮想メモリ空間に展開（ヒープ消費ゼロ！）
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                mappedMatrix = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).apply {
                    // 🌟 超重要：既存のバイナリと同じエンディアン（リトルエンディアン）を指定
                    order(ByteOrder.LITTLE_ENDIAN)
                    // OSに「すぐに使うからページキャッシュに乗せておいて」とヒントを出す
                    load()
                }
            }
            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // 接続コストを取得
    fun getConnectionCost(rid: Int, lid: Int): Int {
        // 🌟 修正：BOS/EOS (0) の接続コストを確実に 0 にしてViterbiの崩壊を防ぐ！
        if (rid == 0 || lid == 0) return 0

        val buffer = mappedMatrix
        if (buffer == null || !isLoaded || rid >= matrixSize || lid >= matrixSize || rid < 0 || lid < 0) {
            return 30000
        }

        val byteOffset = (rid * matrixSize + lid) * 2

        if (byteOffset < 0 || byteOffset >= buffer.capacity() - 1) {
            return 30000
        }

        return buffer.getShort(byteOffset).toInt()
    }

    fun getTopConnectingLids(prevRid: Int, limit: Int = 5): List<Int> {
        // 32ビットのIntの中に、上位16ビットにコスト、下位16ビットにlidを詰め込んでソートする変態的最適化
        val packedArray = IntArray(matrixSize - 1)

        for (lid in 1 until matrixSize) {
            val cost = getConnectionCost(prevRid, lid)
            // コストを上位にシフトし、lidを下位に結合（ビット演算）
            packedArray[lid - 1] = (cost shl 16) or (lid and 0xFFFF)
        }

        // プリミティブ配列のままソート（オブジェクト生成ゼロ）
        packedArray.sort()

        val result = mutableListOf<Int>()
        for (i in 0 until minOf(limit, packedArray.size)) {
            // 下位16ビットを取り出して lid に復元
            result.add(packedArray[i] and 0xFFFF)
        }
        return result
    }
}