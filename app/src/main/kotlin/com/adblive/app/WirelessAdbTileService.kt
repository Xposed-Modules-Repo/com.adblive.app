package com.adblive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.adblive.app.util.AdbGuardManager
import com.adblive.app.util.ShellUtils

/** 下拉快捷磁贴：开关无线 ADB（端口固定 5555）。 */
class WirelessAdbTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        Thread { updateTileState() }.start()
    }

    override fun onClick() {
        super.onClick()
        setTileUnavailable()
        Thread {
            val on = try { !isAdbOn() } catch (_: Exception) { true }
            setAdb(on)
            updateTileState()
        }.start()
    }

    private fun setTileUnavailable() {
        mainHandler.post {
            val tile = qsTile ?: return@post
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
        }
    }

    private fun canToggle(): Boolean {
        val secure = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        return secure || ShellUtils.probeRoot()
    }

    /** 有 root 时必须端口=5555 且 wifi 开关=1；无 root 时只看 wifi 开关。 */
    private fun isAdbOn(): Boolean {
        val root = ShellUtils.probeRoot()
        val wifiOut = if (root) ShellUtils.executeSu("settings get global adb_wifi_enabled")
                      else readSecureAdb()
        val wifiOn = wifiOut.isSuccess() && wifiOut.scalar() == "1"
        if (!root) return wifiOn
        val portOut = ShellUtils.executeSu("getprop service.adb.tcp.port")
        return portOut.isSuccess() && portOut.scalar() == "5555" && wifiOn
    }

    private fun setAdb(on: Boolean) {
        val root = ShellUtils.probeRoot()
        // 用户意图文件：关掉时让守护进程不要再自动恢复
        if (root) {
            if (on) AdbGuardManager.clearUserDisabledAdb() else AdbGuardManager.writeUserDisabledAdb()
        }
        var ok = writeSecureAdb(on)
        if (!ok && root) {
            ok = ShellUtils.executeSu("settings put global adb_wifi_enabled " + (if (on) "1" else "0")).isSuccess()
        }
        if (root) {
            // adbd 只在启动时读端口属性，必须重启才真正监听/释放 5555
            ShellUtils.executeSu("setprop service.adb.tcp.port " + (if (on) "5555" else "0"))
            ShellUtils.executeSu("stop adbd && start adbd")
        }
    }

    private fun updateTileState() {
        val hasPerm = canToggle()
        val on = if (hasPerm) try { isAdbOn() } catch (_: Exception) { false } else false
        mainHandler.post {
            val tile = qsTile ?: return@post
            tile.state = when {
                !hasPerm -> Tile.STATE_UNAVAILABLE
                on -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.updateTile()
        }
    }

    private fun writeSecureAdb(on: Boolean): Boolean {
        return try {
            Settings.Global.putString(applicationContext.contentResolver, "adb_wifi_enabled", if (on) "1" else "0")
        } catch (_: Exception) { false }
    }

    private fun readSecureAdb(): ShellUtils.Result {
        val v = try { Settings.Global.getString(applicationContext.contentResolver, "adb_wifi_enabled") } catch (_: Exception) { null }
        return if (v != null) ShellUtils.Result(0, v) else ShellUtils.Result(-1, "")
    }
}
