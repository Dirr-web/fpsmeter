package com.dirr.fpsmeter

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Hitung FPS aplikasi yang sedang di depan dari `dumpsys SurfaceFlinger --latency <layer>`.
 * Ini timestamp frame asli yang ditampilkan ke layar, jadi hasilnya FPS sebenarnya
 * (bukan sekadar refresh rate layar).
 *
 * Return: >=0 FPS, -1 belum ada data, -2 tidak punya izin (DUMP/root).
 */
class FpsSampler(private val sh: Shell) {
    private var pkg = ""
    private var layer: String? = null
    private var lastTs = 0L
    private var nextFocus = 0L
    private var nextPick = 0L
    private var empty = 0
    private var denied = false

    private val focusRe = Regex("""u\d+\s+([\w.]+)/""")
    private val ws = Regex("\\s+")
    private val buf = LongArray(160)

    fun sample(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextFocus) {
            nextFocus = now + 3000
            val out = sh.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            if (out.contains("Denial")) denied = true
            val p = focusRe.find(out)?.groupValues?.get(1)
            if (p != null && p != pkg) {
                pkg = p; layer = null; lastTs = 0; empty = 0; nextPick = 0
            }
        }
        if (denied) return -2
        if (pkg.isEmpty()) return -1

        if (layer == null || empty >= 3) {
            if (now < nextPick) return -1
            nextPick = now + 2000
            layer = pickLayer()
            empty = 0; lastTs = 0
        }
        val l = layer ?: return -1

        val n = latency(l)
        if (n < 2) { empty++; return 0 }

        // frame baru = timestamp yang lebih besar dari sampel sebelumnya
        val first = buf[0]
        val last = buf[n - 1]
        val base = if (lastTs >= first) lastTs else first
        var cnt = 0
        for (i in 0 until n) if (buf[i] > base) cnt++
        val span = last - base
        lastTs = last
        if (cnt == 0 || span <= 0) return 0
        return (cnt * 1_000_000_000.0 / span).roundToInt()
    }

    private fun pickLayer(): String? {
        val raw = sh.run("dumpsys SurfaceFlinger --list")
        if (raw.contains("Denial")) { denied = true; return null }
        val cands = raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains(pkg) && !it.startsWith("Background for") && !it.startsWith("Bounds for") }
            .sortedBy { (if (it.contains("SurfaceView")) 0 else 2) + (if (it.contains("BLAST")) 0 else 1) }
            .take(4)
            .toList()
        var best: String? = null
        var bestN = 1
        for (c in cands) {
            val n = latency(c)
            if (n > bestN) { best = c; bestN = n }
        }
        return best
    }

    /** Isi buf dengan timestamp "actual present" yang valid, return jumlahnya. */
    private fun latency(name: String): Int {
        val q = "'" + name.replace("'", "'\\''") + "'"
        val txt = sh.run("dumpsys SurfaceFlinger --latency $q")
        if (txt.contains("Denial")) { denied = true; return 0 }
        var c = 0
        var first = true
        for (line in txt.lineSequence()) {
            if (first) { first = false; continue } // baris 1 = periode vsync
            val p = line.trim().split(ws)
            if (p.size < 3) continue
            val t = p[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            if (c < buf.size) buf[c++] = t
        }
        return c
    }
}
