package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Color

object PHashUtil {
    private const val SIZE = 32

    fun computePHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val grayPixels = IntArray(SIZE * SIZE)
        var total = 0L

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val color = scaled.getPixel(x, y)
                val gray = (Color.red(color) * 0.3 + Color.green(color) * 0.59 + Color.blue(color) * 0.11).toInt()
                grayPixels[y * SIZE + x] = gray
                total += gray
            }
        }

        val avg = total / (SIZE * SIZE)
        val hash = StringBuilder()
        for (pixel in grayPixels) {
            hash.append(if (pixel >= avg) "1" else "0")
        }
        return hash.toString()
    }

    fun calculateSimilarity(hash1: String, hash2: String): Double {
        if (hash1.length != hash2.length) return 0.0
        var distance = 0
        for (i in hash1.indices) {
            if (hash1[i] != hash2[i]) distance++
        }
        return (1.0 - distance.toDouble() / hash1.length) * 100
    }
}
