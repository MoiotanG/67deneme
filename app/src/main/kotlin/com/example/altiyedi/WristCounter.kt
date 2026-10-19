package com.example.altiyedi

/**
 * Tek bir bileğin yukarı-aşağı hareketini sayar (67speed mantığı: histerezisli yön değişimi).
 * v: yukarı = büyük değer. Eşik, gövde boyuna oranlı.
 * Bir "tekrar" = bilek yukarı çıkıp, eşikten fazla geri döndüğünde sayılır.
 */
class WristCounter(private val threshold: Float = THRESHOLD, private val minGapMs: Long = 120) {
    private var dir = 0            // 1 = yukarı, -1 = aşağı, 0 = başlangıç
    private var extreme = 0f
    private var ref = 0f
    private var started = false
    private var lastRepMs = 0L

    fun reset() { dir = 0; started = false }

    /** Yeni bir tekrar tamamlandıysa true döner. */
    fun update(v: Float, nowMs: Long): Boolean {
        if (!started) { ref = v; extreme = v; started = true; return false }
        when (dir) {
            0 -> {
                if (v - ref > threshold) { dir = 1; extreme = v }
                else if (ref - v > threshold) { dir = -1; extreme = v }
            }
            1 -> {
                if (v > extreme) extreme = v
                else if (extreme - v > threshold) {
                    dir = -1; extreme = v
                    if (nowMs - lastRepMs >= minGapMs) { lastRepMs = nowMs; return true }
                }
            }
            else -> {
                if (v < extreme) extreme = v
                else if (v - extreme > threshold) { dir = 1; extreme = v }
            }
        }
        return false
    }

    companion object {
        // Gövde boyunun oranı. Çok fazla yanlış sayıyorsa büyüt, kaçırıyorsa küçült.
        const val THRESHOLD = 0.12f
    }
}
