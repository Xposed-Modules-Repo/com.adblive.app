package com.adblive.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.adblive.app.util.AdbGuardManager
import com.adblive.app.util.ShieldStateFile
import com.adblive.app.util.ShellUtils
import com.adblive.app.util.XposedStatus

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "adblive_guard"
        const val KEY_BOOT_ENABLED = "boot_enabled"
    }

    private lateinit var tvRoot: TextView
    private lateinit var tvGuard: TextView
    private lateinit var tvAdb: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvVersion: TextView
    private lateinit var swRoot: MaterialSwitch
    private lateinit var tvBoot: TextView
    private lateinit var tvShield: TextView
    private lateinit var swBoot: MaterialSwitch
    private lateinit var swShield: MaterialSwitch
    private lateinit var swGuard: MaterialSwitch
    private lateinit var swAdb: MaterialSwitch
    private lateinit var cardXposed: MaterialCardView
    private lateinit var cardRoot: MaterialCardView
    private lateinit var cardGuard: MaterialCardView
    private lateinit var cardAdb: MaterialCardView
    private lateinit var cardBoot: MaterialCardView
    private lateinit var icShield: ImageView
    private lateinit var icRoot: ImageView
    private lateinit var icAdb: ImageView
    private lateinit var icGuard: ImageView
    private lateinit var icBoot: ImageView
    private lateinit var tvAboutDesc: TextView
    private lateinit var tvAboutToggle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnRefresh: ImageButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var toolbar: Toolbar

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var rootOk = false
    @Volatile private var legacyShieldSwept = false
    @Volatile private var xposedOk = false
    @Volatile private var shieldOn = false
    @Volatile private var guardOn = false
    @Volatile private var adbOn = false
    private var adbObserver: ContentObserver? = null
    private var refreshing = false
    @Volatile private var ipText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvRoot = findViewById(R.id.tvRoot)
        tvGuard = findViewById(R.id.tvGuard)
        tvAdb = findViewById(R.id.tvAdb)
        tvLog = findViewById(R.id.tvLog)
        tvIp = findViewById(R.id.tvIp)
        tvAboutDesc = findViewById(R.id.tvAboutDesc)
        tvAboutToggle = findViewById(R.id.tvAboutToggle)
        tvVersion = findViewById(R.id.tvVersion)
        swRoot = findViewById(R.id.swRoot)
        tvBoot = findViewById(R.id.tvBoot)
        tvShield = findViewById(R.id.tvXposed)
        swShield = findViewById(R.id.swShield)
        swBoot = findViewById(R.id.swBoot)
        swGuard = findViewById(R.id.swGuard)
        swAdb = findViewById(R.id.swAdb)
        cardXposed = findViewById(R.id.cardXposed)
        cardRoot = findViewById(R.id.cardRoot)
        cardGuard = findViewById(R.id.cardGuard)
        cardAdb = findViewById(R.id.cardAdb)
        cardBoot = findViewById(R.id.cardBoot)
        icShield = findViewById(R.id.icShield)
        icRoot = findViewById(R.id.icRoot)
        icAdb = findViewById(R.id.icAdb)
        icGuard = findViewById(R.id.icGuard)
        icBoot = findViewById(R.id.icBoot)
        progress = findViewById(R.id.progress)
        btnRefresh = findViewById(R.id.btnRefresh)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swGuard.setOnCheckedChangeListener { _, checked -> if (swGuard.isPressed) toggleGuard(checked) }
        swAdb.setOnCheckedChangeListener { _, checked -> if (swAdb.isPressed) toggleAdb(checked) }
        swShield.setOnCheckedChangeListener { _, checked -> if (swShield.isPressed) toggleShield(checked) }
        swBoot.setOnCheckedChangeListener { _, checked -> if (swBoot.isPressed) toggleBoot(checked) }
        btnRefresh.setOnClickListener { refresh() }
        swipeRefresh.setOnRefreshListener { refresh() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.teal))
        tvIp.setOnClickListener { copyIp() }
        findViewById<View>(R.id.btnDevOptions).setOnClickListener { openDevOptions() }

        tvAboutToggle.setOnClickListener { toggleAbout() }

        cardRoot.setOnClickListener { onRootCardClick() }

        tvVersion.text = "ver " + BuildConfig.VERSION_NAME + " (code " + BuildConfig.VERSION_CODE + ")"
        appendLog("ADBLive started")
    }

    override fun onResume() {
        super.onResume()
        val uri = Settings.Global.getUriFor("adb_wifi_enabled")
        adbObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }.also { contentResolver.registerContentObserver(uri, false, it) }
        refresh()
        startRootPoller()
        maybeAutoEnableGuard()
    }

    override fun onPause() {
        super.onPause()
        adbObserver?.let { contentResolver.unregisterContentObserver(it) }
        adbObserver = null
        stopRootPoller()
    }

    @Volatile private var pollerRunning = false

    private fun startRootPoller() {
        if (pollerRunning) return
        pollerRunning = true
        val t = Thread {
            var last = rootOk
            while (pollerRunning) {
                val delay = if (last) 15000L else 3000L
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) { break }
                val now = try { ShellUtils.probeRoot() } catch (_: Exception) { last }
                if (now != last) {
                    last = now
                    if (now) maybeAutoEnableGuard()
                    runOnUiThread { refresh() }
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun maybeAutoEnableGuard() {
        if (!AdbGuardManager.shouldAutoEnableGuard(this@MainActivity)) return
        Thread {
            try {
                if (!ShellUtils.probeRoot()) return@Thread
                if (AdbGuardManager.isGuardRunning()) {
                    // B5: guard already up, but refresh script file so upgraded versions take effect
                    AdbGuardManager.refreshScript(this@MainActivity)
                    return@Thread
                }
                val ok = AdbGuardManager.deployAndStart(this@MainActivity)
                if (ok) AdbGuardManager.setGuardEnabled(this@MainActivity, true)
                if (ok) runOnUiThread {
                    appendLog("auto guard deployed & started")
                    refresh()
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun stopRootPoller() {
        pollerRunning = false
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val cur = tvLog.text.toString()
        val next = if (cur.isBlank()) "[" + ts + "] " + msg
                   else cur + System.lineSeparator() + "[" + ts + "] " + msg
        val lines = next.split(System.lineSeparator())
        tvLog.text = if (lines.size > 8) lines.takeLast(8).joinToString(System.lineSeparator()) else next
    }

    private fun getLocalIp(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val link = cm.getLinkProperties(cm.activeNetwork) ?: return ""
            link.linkAddresses.asSequence()
                .map { it.address }
                .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun openDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            // 个别 ROM 没有该入口时退回系统设置
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun copyIp() {
        if (ipText.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ip", ipText))
        appendLog("IP copied: " + ipText)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        btnRefresh.isEnabled = false
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val ip = getLocalIp()
                val rootNow = ShellUtils.probeRoot()
                val xposedNow = XposedStatus.isActive(this)
                val guardDeployed = AdbGuardManager.isScriptDeployed()
                val guardRunning = AdbGuardManager.isGuardRunning()

                val rootChanged = rootNow != rootOk
                val adbWifiOut = if (rootNow) ShellUtils.executeSu("settings get global adb_wifi_enabled")
                                 else getSecureAdb()
                val adbPortOut = if (rootNow) ShellUtils.executeSu("getprop service.adb.tcp.port")
                                 else adbWifiOut
                val adbNow = wirelessAdbOn(adbPortOut, adbWifiOut, rootNow)

                rootOk = rootNow
                xposedOk = xposedNow
                if (rootNow && rootChanged) {
                    ShellUtils.executeSu("pm grant com.adblive.app android.permission.WRITE_SECURE_SETTINGS")
                }
                if (rootNow && !legacyShieldSwept) {
                    legacyShieldSwept = true
                    // 新设计盾状态只在 /data/system；清掉旧版本残留的盾文件
                    ShellUtils.executeSu(
                        "rm -f /data/local/tmp/adblive_shield_armed /data/local/tmp/adblive_shield_off " +
                        "/data/adb/adblive_shield_armed /data/adb/adblive_shield_off"
                    )
                    // 旧版本的 inotifyd 监视进程只删了 PID 文件没杀进程，会变僵尸
                    ShellUtils.executeSu("pkill -9 -f '[a]dblive_uninstalled'")
                }
                // 固定端口还在就必须让卸载清理器在位（没开被动守护时唯一的兜底）
                if (rootNow && adbPortOut.scalar() == "5555" && !guardRunning &&
                    !AdbGuardManager.isUninstallCleanerAlive()) {
                    AdbGuardManager.ensureUninstallCleaner(this)
                }
                val shieldActual = ShieldStateFile.exists()
                adbOn = adbNow
                guardOn = guardDeployed && guardRunning

                runOnUiThread {
                    ipText = ip
                    tvIp.text = ip.ifEmpty { "--" }

                    val bootPref = getPref(KEY_BOOT_ENABLED, true)
                    tvBoot.text = if (bootPref) getString(R.string.boot_active) else getString(R.string.boot_inactive)
                    swBoot.isChecked = bootPref
                    cardBoot.strokeColor = getColor(if (bootPref) R.color.card_border_on else R.color.card_border_off)
                    tintCircle(icBoot, bootPref)

                    tvAdb.text = if (adbOn) getString(R.string.adb_active) else getString(R.string.adb_inactive)
                    swAdb.isChecked = adbOn
                    cardAdb.strokeColor = getColor(if (adbOn) R.color.card_border_on else R.color.card_border_off)
                    tintCircle(icAdb, adbOn)

                    tvGuard.text = if (guardOn) getString(R.string.guard_active) else getString(R.string.guard_inactive)
                    swGuard.isChecked = guardOn
                    cardGuard.strokeColor = getColor(if (guardOn) R.color.card_border_on else R.color.card_border_off)
                    tintCircle(icGuard, guardOn)

                    val shieldUi = shieldActual
                    val shieldActive = shieldUi && xposedOk
                    tvShield.text = if (shieldActive) getString(R.string.shield_active) else getString(R.string.shield_inactive)
                    swShield.isChecked = shieldActive
                    shieldOn = shieldActive
                    cardXposed.strokeColor = getColor(if (shieldActive) R.color.card_border_on else R.color.card_border_off)
                    tintCircle(icShield, shieldActive)

                    tvRoot.text = if (rootOk) getString(R.string.root_active) else getString(R.string.root_inactive)
                    swRoot.isChecked = rootOk
                    cardRoot.strokeColor = getColor(if (rootOk) R.color.card_border_on else R.color.card_border_off)
                    tintCircle(icRoot, rootOk)

                    if (rootChanged) {
                        appendLog("root " + (if (rootOk) "granted" else "lost"))
                    }
                    if (!rootOk && ShellUtils.suHidden()) {
                        appendLog("提示: su 被 KernelSU 隐藏，点 Root 卡片可一键重启解锁")
                    }
                    appendLog("guard: script=" + (if (guardDeployed) "ok" else "none") +
                              " run=" + (if (guardRunning) "yes" else "no"))
                }
            } catch (t: Throwable) {
                appendLog("refresh error: " + t.message)
            } finally {
                runOnUiThread {
                    refreshing = false
                    btnRefresh.isEnabled = true
                    progress.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }
            }
        }.start()
    }

    private fun onRootCardClick() {
        if (rootOk) {
            refresh()
            return
        }
        val hidden = ShellUtils.suHidden()
        if (!hidden) {
            val d = android.app.AlertDialog.Builder(this)
                .setTitle("Root 权限")
                .setMessage("未检测到 Root。请先在 KernelSU/Magisk 中授权本应用。")
                .setPositiveButton("知道了", null)
                .show()
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.teal))
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTypeface(android.graphics.Typeface.MONOSPACE)
            d.setCanceledOnTouchOutside(true)
            return
        }
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_root_hint)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.findViewById<android.widget.TextView>(R.id.txtHint)?.text =
            "su 被 KernelSU 隐藏：本应用启动时未授权，内核会锁定隐藏状态，授权后也需重启一次进程才生效。\n\n" +
            "跨过这一次后，授权/撤销将全部实时自动检测，无需再手动刷新。\n\n" +
            "请先在 KernelSU 中授权本应用，然后点「重启应用」完成解锁（仅需一次）。"
        dialog.findViewById<android.widget.TextView>(R.id.btnCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<android.widget.TextView>(R.id.btnRestart)?.setOnClickListener {
            dialog.dismiss()
            restartApp()
        }
        dialog.show()
        val w = resources.displayMetrics.widthPixels
        dialog.window?.setLayout((w * 0.88).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun restartApp() {
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i == null) { Process.killProcess(Process.myPid()); return }
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 250, pi)
            finish()
            Process.killProcess(Process.myPid())
        } catch (e: Exception) {
            Process.killProcess(Process.myPid())
        }
    }

    private fun tintCircle(iv: ImageView, active: Boolean) {
        iv.setBackgroundResource(if (active) R.drawable.bg_icon_circle_on else R.drawable.bg_icon_circle_off)
        iv.setColorFilter(ContextCompat.getColor(this,
            if (active) R.color.on_teal_container else R.color.text_secondary))
    }

    // #12: add 500ms delay before deploy to let guard_stop propagate
    private fun toggleGuard(on: Boolean) {
        if (!rootOk) {
            appendLog("需要 Root 权限才能开关被动守护")
            showRootRequiredHint()
            swGuard.isChecked = !on
            return
        }
        AdbGuardManager.setGuardEnabled(this, on)
        Thread {
            if (!on) {
                AdbGuardManager.stopAndRemove(this)
                guardOn = false
                runOnUiThread { appendLog("guard stopped") }
                // #12: wait for old guard process to see guard_stop and exit
                Thread.sleep(500)
            }
            if (on) {
                val ok = AdbGuardManager.deployAndStart(this)
                guardOn = ok
                runOnUiThread { appendLog("guard " + (if (ok) "deployed & started" else "deploy failed")) }
            }
            runOnUiThread {
                tvGuard.text = if (guardOn) getString(R.string.guard_active) else getString(R.string.guard_inactive)
                swGuard.isChecked = guardOn
                cardGuard.strokeColor = getColor(if (guardOn) R.color.card_border_on else R.color.card_border_off)
                tintCircle(icGuard, guardOn)
            }
        }.start()
    }

    private fun setPref(key: String, enabled: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, enabled).apply()
    }

    private fun getPref(key: String, def: Boolean): Boolean {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, def)
    }

    private fun toggleBoot(on: Boolean) {
        setPref(KEY_BOOT_ENABLED, on)
        AdbGuardManager.writeBootEnabled(this, on)
        runOnUiThread {
            tvBoot.text = if (on) getString(R.string.boot_active) else getString(R.string.boot_inactive)
            swBoot.isChecked = on
            cardBoot.strokeColor = getColor(if (on) R.color.card_border_on else R.color.card_border_off)
            tintCircle(icBoot, on)
            appendLog("auto-start " + (if (on) "enabled" else "disabled"))
        }
    }

    // #7: refresh shield state in-memory after arm/disarm
    private fun toggleShield(on: Boolean) {
        if (!xposedOk) {
            appendLog("需要 LSPosed 启用本模块才能激活主动守护")
            showLsposedHint()
            swShield.isChecked = !on
            return
        }
        Thread {
            val ok = if (on) ShieldStateFile.arm() else ShieldStateFile.disarm()
            val active = ShieldStateFile.exists() && xposedOk
            shieldOn = active
            runOnUiThread {
                appendLog(if (on) "active shield on" else "active shield off")
                if (!ok) appendLog("盾状态文件写入失败（可能无 Root），盾状态未真正切换")
                tvShield.text = if (active) getString(R.string.shield_active) else getString(R.string.shield_inactive)
                swShield.isChecked = active
                cardXposed.strokeColor = getColor(if (active) R.color.card_border_on else R.color.card_border_off)
                tintCircle(icShield, active)
            }
        }.start()
    }

    // #3: move setPref into Thread after shell command succeeds
    // S3: serialize toggles + discard superseded ones to avoid adbd race and UI flicker
    private val adbWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val adbToggleSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun toggleAdb(on: Boolean) {
        val canSecure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!rootOk && !canSecure) {
            appendLog("需要 Root 权限才能开关无线 ADB")
            showRootRequiredHint()
            swAdb.isChecked = !on
            return
        }
        val seq = adbToggleSeq.incrementAndGet()
        adbWorker.execute {
            if (seq != adbToggleSeq.get()) return@execute
            performToggle(on, seq)
        }
    }

    private fun performToggle(on: Boolean, seq: Int) {
        if (on) {
            // User wants ADB on: clear intent file so guard resumes
            AdbGuardManager.clearUserDisabledAdb()
            val secureOk = writeAdbWifiSetting(true)
            if (rootOk) {
                ShellUtils.executeSu("setprop service.adb.tcp.port 5555")
                ShellUtils.executeSu("stop adbd && start adbd")
                // 固定端口已被设置：确保卸载清理器在位（没开被动守护时的兜底）
                AdbGuardManager.ensureUninstallCleaner(this)
            }
            runOnUiThread { appendLog(if (secureOk) "adb enabling on port 5555" else "adb enable failed (no permission)") }
        } else {
            // User wants ADB off: write intent file so guard won't restore
            AdbGuardManager.writeUserDisabledAdb()
            // 必须用本应用自己的 uid 写：盾 armed 时只放行本应用，root/su 写入会被盾拦
            val secureOk = writeAdbWifiSetting(false)
            if (rootOk) {
                ShellUtils.executeSu("setprop service.adb.tcp.port 0")
                // adbd 只在启动时读端口属性，不重启的话 5555 监听仍然在（"假关闭"）
                ShellUtils.executeSu("stop adbd && start adbd")
                // 固定端口已释放，清理器不再需要（保持零残留）
                AdbGuardManager.removeUninstallCleaner()
            }
            runOnUiThread { appendLog(if (secureOk) "adb disabled" else "adb disable failed (no permission)") }
        }
        Thread.sleep(1000)
        if (seq != adbToggleSeq.get()) return
        val adbW = if (rootOk) ShellUtils.executeSu("settings get global adb_wifi_enabled")
                   else getSecureAdb()
        val adbR = if (rootOk) ShellUtils.executeSu("getprop service.adb.tcp.port")
                   else adbW
        val ip = getLocalIp()
        adbOn = wirelessAdbOn(adbR, adbW, rootOk)
        runOnUiThread {
            ipText = ip
            tvIp.text = ip.ifEmpty { "--" }
            tvAdb.text = if (adbOn) getString(R.string.adb_active) else getString(R.string.adb_inactive)
            swAdb.isChecked = adbOn
            cardAdb.strokeColor = getColor(if (adbOn) R.color.card_border_on else R.color.card_border_off)
            tintCircle(icAdb, adbOn)
            appendLog("adb port now " + (adbR.scalar().ifEmpty { "0" }))
        }
    }

    /** 有 root 时必须端口=5555 且 wifi 开关=1；无 root 时只看 wifi 开关。 */
    private fun wirelessAdbOn(portOut: ShellUtils.Result, wifiOut: ShellUtils.Result, hasRoot: Boolean): Boolean {
        val wifiOn = wifiOut.isSuccess() && wifiOut.scalar() == "1"
        if (!hasRoot) return wifiOn
        return portOut.isSuccess() && portOut.scalar() == "5555" && wifiOn
    }

    private fun showRootRequiredHint() {
        showHintDialog("需要 Root 权限",
            "请在 KernelSU/Magisk 中授权本应用后再操作。\n\n有 Root 后无线 ADB 开关、被动守护等全部功能即可正常使用。")
    }

    private fun showLsposedHint() {
        showHintDialog("需要 LSPosed",
            "主动守护依赖 Xposed 拦截，需要在 LSPosed 中启用本模块。\n\n作用域勾选 system + com.android.settings（自身应用自动注入），启用后重启生效。")
    }

    private fun showHintDialog(title: String, message: String) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_hint)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.findViewById<android.widget.TextView>(R.id.txtTitle)?.text = title
        dialog.findViewById<android.widget.TextView>(R.id.txtHint)?.text = message
        dialog.findViewById<android.widget.TextView>(R.id.btnOk)?.setOnClickListener { dialog.dismiss() }
        dialog.show()
        val w = resources.displayMetrics.widthPixels
        dialog.window?.setLayout((w * 0.88).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun putSecureAdb(on: Boolean): Boolean {
        return try {
            Settings.Global.putString(contentResolver, "adb_wifi_enabled", if (on) "1" else "0")
        } catch (_: Exception) { false }
    }

    /** 先用自己的 uid 写（盾放行本应用）；失败且有 root 时退回 su。 */
    private fun writeAdbWifiSetting(on: Boolean): Boolean {
        if (putSecureAdb(on)) return true
        if (!rootOk) return false
        return ShellUtils.executeSu(
            "settings put global adb_wifi_enabled " + (if (on) "1" else "0")
        ).isSuccess()
    }

    private fun getSecureAdb(): ShellUtils.Result {
        val v = try { Settings.Global.getString(contentResolver, "adb_wifi_enabled") } catch (_: Exception) { null }
        return if (v != null) ShellUtils.Result(0, v) else ShellUtils.Result(-1, "")
    }

    private fun toggleAbout() {
        if (tvAboutDesc.visibility == View.VISIBLE) {
            tvAboutDesc.visibility = View.GONE
            tvAboutToggle.text = getString(R.string.about_expand)
        } else {
            tvAboutDesc.visibility = View.VISIBLE
            tvAboutToggle.text = getString(R.string.about_collapse)
        }
    }
}
