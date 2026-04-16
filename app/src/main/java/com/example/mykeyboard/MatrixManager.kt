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

    // 接続コストを取得（実行速度は完全なメモリアクセスと同等）
    fun getConnectionCost(rid: Int, lid: Int): Int {
        val buffer = mappedMatrix

        if (buffer == null || !isLoaded || rid >= matrixSize || lid >= matrixSize || rid < 0 || lid < 0) {
            return 30000 // 未ロードやエラー時はペナルティコスト
        }

        // 🌟 1要素がShort（2バイト）なので、インデックスに2を掛けてバイトオフセット（位置）を計算
        val byteOffset = (rid * matrixSize + lid) * 2

        // ファイル容量を超えないか安全のためのチェック
        if (byteOffset < 0 || byteOffset >= buffer.capacity() - 1) {
            return 30000
        }

        // メモリマップドファイルから直接2バイトを読み取り、Intにして返す
        return buffer.getShort(byteOffset).toInt()
    }
}