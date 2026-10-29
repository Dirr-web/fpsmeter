package com.dirr.fpsmeter

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * FPS tanpa perlu tahu aplikasi mana yang di depan.
 * Tiap detik membaca `dumpsys SurfaceFlinger --timestats -dump`, lalu memilih layer
 * (selain layer sistem dan aplikasi ini) yang jumlah frame-nya paling banyak bertambah.
 * FPS = pertambahan totalFrames / selisih waktu. Itu jumlah frame yang benar-benar tampil.
 *
 * Return: >=0 FPS, -1 belum ada data, -2 tidak punya izin (DUMP/root).
 * `info` = status singkat untuk baris debug di overlay.
 */
class FpsSampler(private val sh: Shell, private val ownPkg: String) {
    var info = "mulai..."
        private set

    private var tsOn = false
    private var tsTime = 0L
    private val prev = HashMap<String, Long>()
    private val skip = arrayOf(
        "StatusBar", "NavigationBar", "ScreenDecor", "Wallpaper", "InputMethod", "systemui",
        "DockedStackDivider", "Splash Screen", "ShellDropTarget", "Bounds for", "Background for"
    )

    fun sample(): Int {
        if (!tsOn) {
            val r = sh.run("dumpsys SurfaceFlinger --timestats -enable")
            if (r.contains("Denial")) { info = "DUMP ditolak"; return -2 }
            tsOn = true
            tsTime = SystemClock.elapsedRealtime()
            prev.clear()
            info = "ts aktif"
            return -1
        }

        val txt = sh.run("dumpsys SurfaceFlinger --timestats -dump -maxlayers 30")
        val now = SystemClock.elapsedRealtime()
        if (txt.contains("Denial")) { info = "DUMP ditolak"; return -2 }
        val dt = now - tsTime
        tsTime = now

        var cur: String? = null
        var bestName: String? = null
        var bestD = -1L
        var n = 0
        for (line in txt.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("layerName = ")) {
                cur = t.substring(12)
            } else if (t.startsWith("totalFrames = ")) {
                val c = cur
                cur = null
                if (c == null) continue
                if (c.contains(ownPkg) || skip.any { c.contains(it) }) continue
                val total = t.substring(14).toLongOrNull() ?: continue
                n++
                val p = prev.put(c, total)
                if (p != null && total >= p && total - p > bestD) { bestD = total - p; bestName = c }
            }
        }

        if (n == 0) {
            info = "ts kosong len=" + txt.length + " " + txt.trim().replace('\n', ' ').take(24)
            return -1
        }
        if (bestName == null || dt <= 0) { info = "ts warmup n=$n"; return -1 }
        info = bestName.substringBefore('#').takeLast(26)
        return (bestD * 1000.0 / dt).roundToInt()
    }
}
