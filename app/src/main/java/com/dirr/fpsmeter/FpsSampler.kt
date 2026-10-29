package com.dirr.fpsmeter

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * FPS aplikasi yang sedang di depan. Dua metode:
 *  0 = `dumpsys SurfaceFlinger --latency <layer>` (timestamp tiap frame, paling akurat)
 *  1 = `dumpsys SurfaceFlinger --timestats` (hitung selisih totalFrames per detik),
 *      dipakai otomatis kalau metode 0 tidak mengembalikan data (sering terjadi di Android 12 / BLAST).
 *
 * Return: >=0 FPS, -1 belum ada data, -2 tidak punya izin (DUMP/root).
 * `info` berisi status singkat untuk ditampilkan di overlay (mode debug).
 */
class FpsSampler(private val sh: Shell, private val ownPkg: String) {
    var info = "mulai..."
        private set

    private var pkg = ""
    private var layer: String? = null
    private var lastTs = 0L
    private var nextFocus = 0L
    private var nextPick = 0L
    private var empty = 0
    private var failPicks = 0
    private var denied = false
    private var mode = 0
    private var tsOn = false
    private var tsTime = 0L
    private val tsLast = HashMap<String, Long>()

    private val focusRe = Regex("""u\d+\s+([\w.]+)/""")
    private val ws = Regex("\\s+")
    private val buf = LongArray(160)

    private fun short() = pkg.takeLast(20)

    private fun findFocus(): String? {
        var out = sh.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (out.contains("Denial")) { denied = true; return null }
        focusRe.find(out)?.let { return it.groupValues[1] }
        out = sh.run("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
        if (out.contains("Denial")) { denied = true; return null }
        val m = focusRe.find(out)
        if (m == null) info = "nofocus"
        return m?.groupValues?.get(1)
    }

    fun sample(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextFocus) {
            nextFocus = now + 3000
            val p = findFocus()
            if (p != null && p != pkg) {
                pkg = p; layer = null; lastTs = 0; empty = 0; nextPick = 0
                failPicks = 0; mode = 0; tsOn = false; tsLast.clear()
            }
        }
        if (denied) { info = "DUMP ditolak"; return -2 }
        if (pkg.isEmpty()) return -1
        if (pkg == ownPkg) { info = "buka game dulu"; return -1 }
        return if (mode == 1) sampleTs(now) else sampleLatency(now)
    }

    // ---------- metode 0: --latency ----------
    private fun sampleLatency(now: Long): Int {
        if (layer == null || empty >= 3) {
            if (now < nextPick) return -1
            nextPick = now + 2000
            layer = pickLayer()
            empty = 0; lastTs = 0
            if (layer == null) {
                if (denied) return -2
                if (++failPicks >= 2) { mode = 1; tsOn = false; tsLast.clear() }
                return -1
            }
            failPicks = 0
        }
        val l = layer ?: return -1

        val n = latency(l)
        if (n < 2) { empty++; info = short() + " idle"; return 0 }

        val first = buf[0]
        val last = buf[n - 1]
        val base = if (lastTs >= first) lastTs else first
        var cnt = 0
        for (i in 0 until n) if (buf[i] > base) cnt++
        val span = last - base
        lastTs = last
        info = "lat " + short()
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
        if (best == null) info = short() + " L" + cands.size + " v0"
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
            if (first) { first = false; continue }
            val p = line.trim().split(ws)
            if (p.size < 3) continue
            val t = p[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            if (c < buf.size) buf[c++] = t
        }
        return c
    }

    // ---------- metode 1: --timestats ----------
    private fun sampleTs(now: Long): Int {
        if (!tsOn) {
            sh.run("dumpsys SurfaceFlinger --timestats -enable")
            tsOn = true; tsLast.clear(); tsTime = now
            info = "ts aktif"
            return -1
        }
        val txt = sh.run("dumpsys SurfaceFlinger --timestats -dump -maxlayers 20")
        if (txt.contains("Denial")) { denied = true; return -2 }
        var cur: String? = null
        var best = -1L
        var seen = 0
        for (line in txt.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("layerName = ")) {
                cur = t.substring(12)
            } else if (t.startsWith("totalFrames = ")) {
                val c = cur
                cur = null
                if (c == null || !c.contains(pkg)) continue
                val total = t.substring(14).toLongOrNull() ?: continue
                seen++
                val prev = tsLast.put(c, total)
                if (prev != null && total >= prev && total - prev > best) best = total - prev
            }
        }
        val dt = now - tsTime
        tsTime = now
        if (seen == 0) { info = "ts nolayer len=" + txt.length; return -1 }
        if (best < 0 || dt <= 0) { info = "ts warmup"; return -1 }
        info = "ts " + short()
        return (best * 1000.0 / dt).roundToInt()
    }
}
