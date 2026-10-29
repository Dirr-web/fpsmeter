package com.dirr.fpsmeter

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * CPU / GPU / suhu. Semua dibaca dari sysfs. Path yang tidak bisa dibaca
 * dicoba lewat root (kalau mode root aktif), kalau tidak bisa tampil "--".
 */
class SysStats(private val ctx: Context, private val root: Shell?) {

    class Snap(
        val cpu: Int, val cpuEst: Boolean, val cpuMhz: Int, val cpuTemp: Int,
        val gpu: Int, val gpuMhz: Int, val bat: Int,
        val ramUsed: Long, val ramTotal: Long
    )

    private val n = Runtime.getRuntime().availableProcessors()
    private val maxF = LongArray(n)
    private val paths = ArrayList<String>()
    private var iStat = -1
    private var iTemp = -1
    private var iBusy = -1
    private var iClk = -1
    private var iFreq = 0
    private var pTotal = 0L
    private var pIdle = 0L
    private val batFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val am = ctx.getSystemService(ActivityManager::class.java)
    private val mi = ActivityManager.MemoryInfo()

    init { probe() }

    private fun direct(p: String): String? = try {
        BufferedReader(FileReader(p)).use { it.readLine() }?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun batch(ps: List<String>): Array<String?> {
        val r = Array(ps.size) { direct(ps[it]) }
        if (root != null && r.any { it == null }) {
            val cmd = StringBuilder()
            for (i in ps.indices) if (r[i] == null) cmd.append("echo @").append(i).append("; head -n1 ").append(ps[i]).append(" 2>/dev/null; ")
            var idx = -1
            for (line in root.run(cmd.toString()).lineSequence()) {
                if (line.startsWith("@")) idx = line.substring(1).toIntOrNull() ?: -1
                else if (idx >= 0 && line.isNotBlank() && r[idx] == null) r[idx] = line.trim()
            }
        }
        return r
    }

    private fun works(p: String) = batch(listOf(p))[0] != null

    private fun probe() {
        if (works("/proc/stat")) iStat = add("/proc/stat")

        // suhu CPU: cari thermal zone yang namanya mengarah ke CPU/SoC
        cpuTempPath()?.let { iTemp = add(it) }

        // GPU load (Qualcomm Adreno umumnya) dan clock
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy",
            "/proc/mali/utilization",
            "/sys/kernel/ged/hal/gpu_utilization"
        ).firstOrNull { works(it) }?.let { iBusy = add(it) }

        val clk = arrayListOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            "/sys/kernel/ged/hal/current_freqency"
        )
        try {
            File("/sys/class/devfreq").list()?.filter { it.contains("mali") || it.contains("gpu") }
                ?.forEach { clk.add("/sys/class/devfreq/$it/cur_freq") }
        } catch (_: Exception) {}
        clk.firstOrNull { works(it) }?.let { iClk = add(it) }

        iFreq = paths.size
        for (i in 0 until n) paths.add("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
        val mx = batch((0 until n).map { "/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq" })
        for (i in 0 until n) maxF[i] = mx[i]?.toLongOrNull() ?: 0L
    }

    private fun add(p: String): Int { paths.add(p); return paths.size - 1 }

    private fun cpuTempPath(): String? {
        val re = Regex("(?i)cpu|soc|tsens|big|little")
        for (i in 0..60) {
            val t = direct("/sys/class/thermal/thermal_zone$i/type") ?: continue
            if (re.containsMatchIn(t)) {
                val p = "/sys/class/thermal/thermal_zone$i/temp"
                if (works(p)) return p
            }
        }
        return null
    }

    private fun num(s: String?): Long {
        if (s == null) return -1
        var i = 0
        while (i < s.length && !s[i].isDigit()) i++
        var j = i
        while (j < s.length && s[j].isDigit()) j++
        return if (j > i) s.substring(i, j).toLongOrNull() ?: -1 else -1
    }

    private fun mhz(v: Long): Int = when {
        v <= 0 -> 0
        v > 100_000_000 -> (v / 1_000_000).toInt() // Hz
        v > 10_000 -> (v / 1000).toInt()           // kHz
        else -> v.toInt()                          // MHz
    }

    fun read(): Snap {
        val r = batch(paths)

        // frekuensi tiap core
        var topF = 0L; var sumCur = 0L; var sumMax = 0L
        for (i in 0 until n) {
            val c = num(r[iFreq + i])
            if (c > 0) {
                if (c > maxF[i]) maxF[i] = c
                sumCur += c; sumMax += maxF[i]
                if (c > topF) topF = c
            }
        }

        // beban CPU: /proc/stat kalau bisa dibaca, kalau tidak estimasi dari frekuensi
        var cpu = -1; var est = false
        val st = if (iStat >= 0) r[iStat] else null
        if (st != null && st.startsWith("cpu")) {
            val p = st.split(Regex("\\s+"))
            var total = 0L
            for (k in 1..minOf(8, p.size - 1)) total += p[k].toLongOrNull() ?: 0L
            val idle = (p.getOrNull(4)?.toLongOrNull() ?: 0L) + (p.getOrNull(5)?.toLongOrNull() ?: 0L)
            val dt = total - pTotal; val di = idle - pIdle
            if (pTotal != 0L && dt > 0) cpu = (100 * (dt - di) / dt).toInt().coerceIn(0, 100)
            pTotal = total; pIdle = idle
        } else if (sumMax > 0) {
            cpu = (100 * sumCur / sumMax).toInt().coerceIn(0, 100); est = true
        }

        var ct = 0
        if (iTemp >= 0) {
            val t = num(r[iTemp])
            ct = when { t > 1000 -> (t / 1000).toInt(); t > 150 -> (t / 10).toInt(); else -> t.toInt() }
        }

        val gpu = if (iBusy >= 0) num(r[iBusy]).toInt().coerceAtMost(100) else -1
        val gclk = if (iClk >= 0) mhz(num(r[iClk])) else 0

        val bi = ctx.registerReceiver(null, batFilter)
        val bat = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10

        am.getMemoryInfo(mi)
        return Snap(cpu, est, mhz(topF), ct, gpu, gclk, bat, mi.totalMem - mi.availMem, mi.totalMem)
    }
}
