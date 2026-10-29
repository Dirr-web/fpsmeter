package com.dirr.fpsmeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var tv: TextView? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val ui = Handler(Looper.getMainLooper())
    private var shell: Shell? = null
    private var fps: FpsSampler? = null
    private var stats: SysStats? = null
    private val sb = StringBuilder()
    @Volatile private var running = false
    private var debug = true

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val wantRoot = prefs.getBoolean("root", false)
        debug = prefs.getBoolean("debug", true)
        addOverlay(prefs.getInt("corner", 0))

        val t = HandlerThread("fpsmeter").also { it.start() }
        thread = t
        bg = Handler(t.looper)
        bg!!.post {
            val sh = openShell(wantRoot)
            shell = sh
            fps = sh?.let { FpsSampler(it, packageName) }
            stats = SysStats(this, if (sh?.su == true) sh else null)
            tick.run()
        }
        return START_STICKY
    }

    private fun openShell(wantRoot: Boolean): Shell? {
        if (wantRoot) {
            val s = Shell(true)
            if (s.run("id").contains("uid=0")) return s
            s.close()
        }
        if (checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED) return Shell(false)
        return null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val pm = getSystemService(PowerManager::class.java)
                if (pm.isInteractive) {   // layar mati = tidak usah ukur, hemat baterai
                    val f = fps?.sample() ?: -2
                    val text = render(f, stats!!.read())
                    ui.post { tv?.text = text }
                }
            } catch (_: Throwable) {}
            bg?.postDelayed(this, 1000)
        }
    }

    private fun render(f: Int, s: SysStats.Snap): String {
        val b = sb
        b.setLength(0)
        when {
            f > 0 -> {
                val t10 = 10000 / f
                b.append("FPS ").append(f).append("  ").append(t10 / 10).append('.').append(t10 % 10).append("ms")
            }
            f == 0 -> b.append("FPS 0")
            f == -2 -> b.append("FPS: butuh izin DUMP / root")
            else -> b.append("FPS --")
        }
        b.append("\nCPU ")
        if (s.cpu >= 0) { if (s.cpuEst) b.append('~'); b.append(s.cpu).append('%') } else b.append("--")
        if (s.cpuMhz > 0) b.append(' ').append(s.cpuMhz).append("MHz")
        if (s.cpuTemp > 0) b.append(' ').append(s.cpuTemp).append("°C")
        b.append("\nGPU ")
        if (s.gpu >= 0) b.append(s.gpu).append('%') else b.append("--")
        if (s.gpuMhz > 0) b.append(' ').append(s.gpuMhz).append("MHz")
        b.append("\nBAT ").append(s.bat).append("°C")
        b.append("\nRAM ").append(gb(s.ramUsed)).append('/').append(gb(s.ramTotal)).append("G  free ").append(gb(s.ramTotal - s.ramUsed)).append('G')
        if (debug) b.append("\n[").append(fps?.info ?: "tanpa shell").append(']')
        return b.toString()
    }

    private fun gb(v: Long): String {
        val x = v * 10 / (1L shl 30)
        return (x / 10).toString() + "." + (x % 10)
    }

    private fun addOverlay(corner: Int) {
        val w = getSystemService(WindowManager::class.java)
        wm = w
        val d = resources.displayMetrics.density
        val v = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(0, 255, 120))
            setBackgroundColor(0x99000000.toInt())
            val p = (6 * d).toInt()
            setPadding(p, p, p, p)
            text = "FPS --"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = when (corner) {
            1 -> Gravity.TOP or Gravity.END
            2 -> Gravity.BOTTOM or Gravity.START
            3 -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.START
        }
        lp.x = (8 * d).toInt(); lp.y = (8 * d).toInt()
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        w.addView(v, lp)
        tv = v
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fps", "FPS Meter", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "fps")
            .setContentTitle("FPS Meter aktif")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        bg?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        shell?.close()
        tv?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        tv = null
        super.onDestroy()
    }
}
