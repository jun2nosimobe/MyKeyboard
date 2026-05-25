package com.example.mykeyboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MatrixManager(private val context: Context) {

    private val matrixSize = 3000
    private var mappedMatrix: MappedByteBuffer? = null
    private var isLoaded = false

    // 🌟 PCから ADB で直接上書き可能なディレクトリを指定
    private val externalDir: File?
        get() = context.getExternalFilesDir(null)

    fun loadMatrix() {
        if (isLoaded) return
        loadInternal()
    }

    // 🌟 アプリ起動中いつでも新しいファイルを読み込み直すメソッド
    fun reloadMatrix() {
        mappedMatrix = null // 古いメモリマップを解放（GCに任せる）
        loadInternal()
    }

    private fun loadInternal() {
        try {
            val fileName = "matrix.dat" // ※DBファイルも扱う場合はここを引数にするなど調整してください
            val dir = externalDir ?: context.filesDir
            val file = File(dir, fileName)

            // 外部領域にファイルが無い場合は、初回のみ assets からコピー
            if (!file.exists()) {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                mappedMatrix = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    load()
                }
            }
            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
            isLoaded = false
        }
    }

    fun getConnectionCost(rid: Int, lid: Int): Int {
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
        val packedArray = IntArray(matrixSize - 1)
        for (lid in 1 until matrixSize) {
            val cost = getConnectionCost(prevRid, lid)
            packedArray[lid - 1] = (cost shl 16) or (lid and 0xFFFF)
        }
        packedArray.sort()
        val result = mutableListOf<Int>()
        for (i in 0 until minOf(limit, packedArray.size)) {
            result.add(packedArray[i] and 0xFFFF)
        }
        return result
    }
}