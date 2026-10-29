package com.dirr.fpsmeter

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var diag: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, dp(8), 0, dp(8))
        }

        col.addView(label("FPS Meter", 24f))
        status = label("")
        col.addView(status)

        col.addView(Button(this).apply {
            text = "1. Izinkan overlay"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        col.addView(label("2. Izin DUMP (sekali saja). Jalankan lewat ADB / Shizuku terminal / LADB:"))
        col.addView(label("adb shell pm grant $packageName android.permission.DUMP", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })

        col.addView(Switch(this).apply {
            text = "Mode root (su), buat GPU/CPU lebih lengkap  "
            isChecked = prefs.getBoolean("root", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("root", c).apply() }
        })

        col.addView(Switch(this).apply {
            text = "Tampilkan info debug di overlay  "
            isChecked = prefs.getBoolean("debug", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("debug", c).apply() }
        })

        col.addView(Button(this).apply {
            text = "Diagnosa (nyalakan overlay, tekan ini, buka game)"
            setOnClickListener { runDiag() }
        })
        diag = label(prefs.getString("diag", "") ?: "", 10f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(diag)

        col.addView(label("Posisi overlay"))
        val rg = RadioGroup(this)
        arrayOf("Kiri atas", "Kanan atas", "Kiri bawah", "Kanan bawah").forEachIndexed { i, n ->
            rg.addView(RadioButton(this).apply { text = n; id = 100 + i })
        }
        rg.check(100 + prefs.getInt("corner", 0))
        rg.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("corner", id - 100).apply() }
        col.addView(rg)

        col.addView(Button(this).apply {
            text = "Mulai / Terapkan"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) { refresh(); return@setOnClickListener }
                val i = Intent(this@MainActivity, OverlayService::class.java)
                stopService(i)
                startForegroundService(i)
            }
        })
        col.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })

        col.addView(label("Tips MIUI: Info aplikasi > Hemat baterai > Tanpa batasan, dan aktifkan Autostart + \"Tampilkan jendela pop-up saat berjalan di latar belakang\".", 12f))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun runDiag() {
        diag.text = "Tunggu 10 detik. Buka game sekarang, main sebentar, lalu kembali ke sini."
        Thread {
            Thread.sleep(10000)
            val sh = Shell(false)
            val sb = StringBuilder()
            val cmds = arrayOf(
                "id",
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 3",
                "dumpsys window | head -n 3",
                "dumpsys SurfaceFlinger --list | head -n 12",
                "dumpsys SurfaceFlinger --timestats -enable",
                "dumpsys SurfaceFlinger --timestats -dump -maxlayers 8 | grep -E 'layerName|totalFrames' | head -n 24"
            )
            for (c in cmds) sb.append("$ ").append(c).append('\n').append(sh.run(c).take(700)).append('\n')
            sh.close()
            val out = sb.toString()
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("diag", out).apply()
            runOnUiThread { diag.text = out }
        }.start()
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val dump = checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
        status.text = "Overlay: " + (if (ov) "OK" else "BELUM") + "\nDUMP: " + (if (dump) "OK" else "BELUM (FPS tidak akan jalan tanpa ini atau root)")
    }
}
