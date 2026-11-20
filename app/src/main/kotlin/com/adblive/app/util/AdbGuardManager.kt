package com.adblive.app.util

import android.content.Context
import android.util.Base64
import android.util.Log

object AdbGuardManager {
    private const val TAG = "ADBLive_Guard"
    private const val SERVICE_DIR = "/data/adb/service.d"
    private const val SCRIPT_NAME = "99_adblive_guard\\.sh"
    private const val SCRIPT_PATH = "$SERVICE_DIR/$SCRIPT_NAME"
    private const val PORT_FILE = "/data/adb/adblive_guard_port"
    private const val PID_FILE = "/data/local/tmp/adblive_guard.pid"
    private const val DISABLED_FILE = "/data/adb/adblive_guard_disabled"
    private const val BOOT_ENABLED_FILE = "/data/adb/adblive_boot_enabled"
    private const val KEY_USER_DISABLED = "guard_user_disabled"
    private const val USER_DISABLED_ADB_FILE = "/data/adb/adblive_user_disabled_adb"
    private const val CLEANER_SCRIPT = "/data/local/tmp/adblive_cleaner.sh"
    private const val CLEANER_PID = "/data/local/tmp/adblive_cleaner.pid"
    private const val CLEANER_B64_TMP = "/data/local/tmp/adblive_cleaner.b64.tmp"
    private const val CLEANER_VERSION_FILE = "/data/local/tmp/adblive_cleaner.ver"
    /** 脚本实现变更时递增：旧版本进程还活着也要重新部署 */
    private const val CLEANER_VERSION = "4"

    private const val STATE_CACHE_TTL_MS = 10_000L
    @Volatile private var cachedRunning: Boolean? = null
    @Volatile private var cachedAtMs = 0L

    fun shouldAutoEnableGuard(context: Context): Boolean {
        return !context.getSharedPreferences("adblive_guard", Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_DISABLED, false)
    }

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("adblive_guard", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("guard_enabled", enabled)
            .putBoolean(KEY_USER_DISABLED, !enabled)
            .apply()
    }

    /** Write boot auto-start marker file so the guard knows whether to self-start at boot */
    fun writeBootEnabled(context: Context, enabled: Boolean) {
        ShellUtils.executeSu(
            "echo " + (if (enabled) 1 else 0) + " > " + BOOT_ENABLED_FILE +
            " && chmod 644 " + BOOT_ENABLED_FILE
        )
    }


    fun isScriptDeployed(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedRunning == true && now - cachedAtMs < STATE_CACHE_TTL_MS) return true
        val r = ShellUtils.executeSu("test -f " + SCRIPT_PATH + " && echo yes")
        val deployed = r.isSuccess() && r.output.contains("yes")
        if (deployed) { cachedRunning = true; cachedAtMs = now } else { cachedRunning = null }
        return deployed
    }

    fun invalidateStateCache() {
        cachedRunning = null
        cachedAtMs = 0L
    }

    fun isGuardRunning(): Boolean {
        val r = ShellUtils.executeSu("cat " + PID_FILE + " 2>/dev/null")
        val pid = r.scalar()
        if (pid.isNotEmpty() && pid.all { it.isDigit() }) {
            val alive = ShellUtils.executeSu("kill -0 " + pid + " 2>&1; echo EXIT=$?")
            if (alive.output.contains("EXIT=0")) return true
        }
        val pg = ShellUtils.executeSu("pgrep -f '99_adblive_guard\\.sh' 2>/dev/null")
        return pg.isSuccess() && pg.scalar().isNotEmpty()
    }



    /** Write current watchdog script (plus port/boot markers) to disk. Safe to call while guard runs (B5). */
    fun refreshScript(context: Context): Boolean {
        return try {
            val script = context.assets.open("watchdog.sh").bufferedReader().use { it.readText() }
            val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
            val port = readCurrentPort()
            val tmpB64 = "/data/local/tmp/adblive_b64.tmp"
            val bootEn = context.getSharedPreferences("adblive_guard", Context.MODE_PRIVATE)
                .getBoolean("boot_enabled", true)
            writeBootEnabled(context, bootEn)
            val cmd = "rm -f " + DISABLED_FILE + " && " +
                "mkdir -p " + SERVICE_DIR + " && " +
                "echo " + port + " > " + PORT_FILE + " && chmod 644 " + PORT_FILE + " && " +
                "cat > " + tmpB64 + " << 'B64EOF'\n" + b64 + "\nB64EOF\n" +
                "base64 -d " + tmpB64 + " > " + SCRIPT_PATH + " && " +
                "rm -f " + tmpB64 + " && " +
                "chmod 755 " + SCRIPT_PATH
            val r = ShellUtils.executeSu(cmd, 3000)
            invalidateStateCache()
            r.isSuccess()
        } catch (t: Throwable) {
            Log.e(TAG, "refresh script failed: " + t.message)
            false
        }
    }

    // #10: use temp file for base64 decode to avoid ARG_MAX limit
    fun deployAndStart(context: Context): Boolean {
        return try {
            clearStopSignal(context)
            if (!refreshScript(context)) return false
            val r = ShellUtils.executeSu("setsid sh " + SCRIPT_PATH + " manual >/dev/null 2>&1 & echo started")
            invalidateStateCache()
            r.isSuccess() && r.output.contains("started")
        } catch (t: Throwable) {
            Log.e(TAG, "deploy failed: " + t.message)
            false
        }
    }

    fun stopAndRemove(context: Context): Boolean {
        try {
            val f = java.io.File(context.filesDir, "guard_stop")
            f.parentFile?.mkdirs()
            f.writeText("1")
        } catch (_: Exception) { }
        val pid = ShellUtils.executeSu("cat " + PID_FILE + " 2>/dev/null").scalar()
        val safePid = if (pid.isNotEmpty() && pid.all { it.isDigit() }) pid else ""
        val r = ShellUtils.executeSu(
            "touch " + DISABLED_FILE + " && " +
            (if (safePid.isNotEmpty()) "kill " + safePid + " 2>/dev/null; " else "") +
            "pkill -f '^sh " + SCRIPT_PATH + "' 2>/dev/null; " +
        "rm -f " + SCRIPT_PATH + " && " +
        "rm -f " + PID_FILE + " && " +
        "rm -f " + PORT_FILE + " && " +
        "rm -rf /data/local/tmp/adblive_guard.lock" +
        "; W=\$(cat /data/local/tmp/adblive_guard.watch 2>/dev/null); [ -n \"\$W\" ] && kill -9 \$W 2>/dev/null; " +
        // 先删文件再 pkill：本命令串里出现了 "*uninst*" 字面量，用通配符写避免 pkill 匹配到
        // 自己的调用方（su -c）——否则 pkill 会先把自己这个 shell 杀掉，后面的 rm 根本执行不到。
        "rm -f /data/local/tmp/adblive_uninst* /data/local/tmp/adblive_guard.watch; " +
        "pkill -9 -f '[a]dblive_uninstalled' 2>/dev/null; exit 0"
    )
        invalidateStateCache()
        // 守护停了，但 App 设的固定端口可能还在，交给一次性清理器兜底（卸载后零残留）
        val port = ShellUtils.executeSu("getprop service.adb.tcp.port").scalar()
        if (port == "5555") ensureUninstallCleaner(context) else removeUninstallCleaner()
        return r.isSuccess()
    }

    private fun clearStopSignal(context: Context) {
        try {
            java.io.File(context.filesDir, "guard_stop").delete()
        } catch (_: Exception) { }
    }

    /**
     * 部署一次性卸载清理器（轮询检测 App 是否被卸载，不守护、不占端口）。
     *
     * 覆盖"用户没开被动守护、但 App 用 root 设过固定端口"的路径：此时没有守护脚本在
     * 卸载瞬间做清理，`service.adb.tcp.port=5555` 会残留到下次重启（5555 仍可 adb connect）。
     * system_server（模块 hook 宿主）受 SELinux 限制无权写该属性（adbd_config_prop 只允许
     * adbd/init），所以清理只能由 root 侧执行。
     *
     * PID 由脚本自己写（`setsid` 会 fork，外面拿 `$!` 只会拿到已退出的父进程）。
     */
    fun ensureUninstallCleaner(context: Context): Boolean {
        return try {
            val script = context.assets.open("cleaner.sh").bufferedReader().use { it.readText() }
            val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
            val cmd = "cat > " + CLEANER_B64_TMP + " << 'B64EOF'\n" + b64 + "\nB64EOF\n" +
                // 先停旧实例，再覆盖脚本（旧 sh 可能正在读这个文件）
                "K=\$(cat " + CLEANER_PID + " 2>/dev/null); [ -n \"\$K\" ] && kill -9 \$K 2>/dev/null; " +
                "rm -f " + CLEANER_PID + "; sleep 0.3; " +
                "base64 -d " + CLEANER_B64_TMP + " > " + CLEANER_SCRIPT + " && " +
                "rm -f " + CLEANER_B64_TMP + " && " +
                "chmod 755 " + CLEANER_SCRIPT + " && " +
                "echo " + CLEANER_VERSION + " > " + CLEANER_VERSION_FILE + "; " +
                // 轮询式（不用 inotifyd：App 命名空间里收不到卸载事件，见 cleaner.sh 注释）
                "setsid sh " + CLEANER_SCRIPT + " >/dev/null 2>&1 & " +
                "echo started"
            val r = ShellUtils.executeSu(cmd, 3000)
            r.isSuccess() && r.output.contains("started")
        } catch (t: Throwable) {
            Log.e(TAG, "cleaner deploy failed: " + t.message)
            false
        }
    }

    /** 固定端口已释放，不再需要清理器（保持零残留） */
    fun removeUninstallCleaner() {
        ShellUtils.executeSu(
            "K=\$(cat " + CLEANER_PID + " 2>/dev/null); [ -n \"\$K\" ] && kill \$K 2>/dev/null; " +
            "rm -f " + CLEANER_PID + " " + CLEANER_SCRIPT + " " + CLEANER_B64_TMP + " " + CLEANER_VERSION_FILE
        )
    }

    /**
     * 卸载清理器是否在位：版本一致 + PID 存活 + 真的是我们的脚本 + 真的拿到了 root。
     * 缺任何一条都算不在位，由 refresh() 重新部署。
     */
    fun isUninstallCleanerAlive(): Boolean {
        val r = ShellUtils.executeSu(
            "[ \"\$(cat " + CLEANER_VERSION_FILE + " 2>/dev/null)\" = \"" + CLEANER_VERSION + "\" ] || exit 0; " +
            "P=\$(cat " + CLEANER_PID + " 2>/dev/null); " +
            "[ -n \"\$P\" ] || exit 0; " +
            "kill -0 \$P 2>/dev/null || exit 0; " +
            "grep -q adblive_cleaner /proc/\$P/cmdline 2>/dev/null || exit 0; " +
            "[ \"\$(grep '^Uid:' /proc/\$P/status 2>/dev/null | cut -f2)\" = \"0\" ] && echo yes"
        )
        return r.output.contains("yes")
    }

    // #11: also read port from PORT_FILE, not just getprop
    /** Write intent file: tells guard not to restore ADB */
    fun writeUserDisabledAdb(): Boolean {
        val result = ShellUtils.executeSu("touch " + USER_DISABLED_ADB_FILE)
        return result.isSuccess() && isUserDisabledAdb()
    }

    /** Delete intent file: guard will resume normal ADB restoration */
    fun clearUserDisabledAdb(): Boolean {
        return ShellUtils.executeSu(
            "rm -f " + USER_DISABLED_ADB_FILE + " && [ ! -e " + USER_DISABLED_ADB_FILE + " ]"
        ).isSuccess()
    }

    /** Check if user has manually disabled ADB */
    fun isUserDisabledAdb(): Boolean {
        val r = ShellUtils.executeSu("test -f " + USER_DISABLED_ADB_FILE + " && echo yes")
        return r.isSuccess() && r.output.contains("yes")
    }

    fun readCurrentPort(): Int {
        // Try port file first (most reliable if watchdog set it)
        val fileR = ShellUtils.executeSu("cat " + PORT_FILE + " 2>/dev/null")
        val filePort = fileR.scalar().toIntOrNull()
        if (filePort != null && filePort in 1024..65535) return filePort

        // Fallback to getprop
        val propR = ShellUtils.executeSu("getprop service.adb.tcp.port")
        val propPort = propR.scalar().toIntOrNull() ?: 0
        return if (propPort in 1024..65535) propPort else 5555
    }
}
