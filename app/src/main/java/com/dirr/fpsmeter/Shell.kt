package com.dirr.fpsmeter

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Satu proses shell yang hidup terus (sh biasa, atau su kalau mode root).
 * Jauh lebih hemat daripada spawn proses baru tiap detik.
 */
class Shell(val su: Boolean) {
    private var proc: Process? = null
    private var out: OutputStreamWriter? = null
    private var inp: BufferedReader? = null

    @Synchronized
    private fun ensure(): Boolean {
        if (proc != null) return true
        return try {
            val p = ProcessBuilder(if (su) "su" else "sh").redirectErrorStream(true).start()
            proc = p
            out = OutputStreamWriter(p.outputStream)
            inp = BufferedReader(InputStreamReader(p.inputStream))
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun run(cmd: String): String {
        if (!ensure()) return ""
        return try {
            out!!.write(cmd + "\necho\necho " + END + "\n")
            out!!.flush()
            val sb = StringBuilder()
            while (true) {
                val line = inp!!.readLine() ?: throw IOException("shell closed")
                if (line == END) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (e: Exception) {
            close()
            ""
        }
    }

    @Synchronized
    fun close() {
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null; out = null; inp = null
    }

    companion object { private const val END = "__FPSEND__" }
}
