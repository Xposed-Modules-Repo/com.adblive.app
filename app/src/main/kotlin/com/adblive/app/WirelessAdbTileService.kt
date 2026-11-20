package com.adblive.app

import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.adblive.app.util.AdbGuardManager
import com.adblive.app.util.SecureSettingsPermission
import com.adblive.app.util.ShellUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 下拉快捷磁贴：开关无线 ADB（端口固定 5555）。 */
class WirelessAdbTileService : TileService() {

    private enum class AdbState {
        ON,
        OFF,
        INCONSISTENT,
        UNKNOWN,
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        worker.execute { updateTileState() }
    }

    override fun onClick() {
        super.onClick()
        setTileUnavailable()
        val sequence = toggleSequence.incrementAndGet()
        worker.execute {
            if (sequence != toggleSequence.get()) return@execute
            toggleAdb(sequence)
        }
    }

    private fun toggleAdb(sequence: Int) {
        val root = ShellUtils.probeRoot()
        val secure = SecureSettingsPermission.ensureGranted(applicationContext, root)
        if (!secure) {
            publishState(AdbState.UNKNOWN)
            return
        }

        val current = readAdbState(root, secure)
        if (current == AdbState.UNKNOWN) {
            publishState(current)
            return
        }
        val targetOn = current != AdbState.ON
        if (!applyAdbState(targetOn, root, secure)) {
            if (sequence != toggleSequence.get()) return
            publishState(readAdbState(root, secure))
            return
        }

        Thread.sleep(READBACK_DELAY_MS)
        if (sequence != toggleSequence.get()) return
        publishState(readAdbState(root, secure))
    }

    private fun applyAdbState(targetOn: Boolean, root: Boolean, secure: Boolean): Boolean {
        var intentWasPresent = false
        if (root) {
            intentWasPresent = AdbGuardManager.isUserDisabledAdb()
            val intentChanged = if (targetOn) {
                AdbGuardManager.clearUserDisabledAdb()
            } else {
                AdbGuardManager.writeUserDisabledAdb()
            }
            if (!intentChanged) return false
        }

        if (!writeWifiSetting(targetOn, root, secure)) {
            if (root) restoreIntent(intentWasPresent)
            return false
        }

        if (root) {
            val port = if (targetOn) "5555" else "0"
            val restart = ShellUtils.executeSu(
                "setprop service.adb.tcp.port " + port + " && stop adbd && start adbd"
            )
            if (!restart.isSuccess()) {
                restoreIntent(intentWasPresent)
                writeWifiSetting(!targetOn, root, secure)
                val previousPort = if (targetOn) "0" else "5555"
                ShellUtils.executeSu(
                    "setprop service.adb.tcp.port " + previousPort + " && stop adbd && start adbd"
                )
                return false
            }
            if (targetOn) {
                AdbGuardManager.ensureUninstallCleaner(applicationContext)
            } else {
                AdbGuardManager.removeUninstallCleaner()
            }
        }
        return true
    }

    private fun restoreIntent(intentWasPresent: Boolean) {
        if (intentWasPresent) {
            AdbGuardManager.writeUserDisabledAdb()
        } else {
            AdbGuardManager.clearUserDisabledAdb()
        }
    }

    private fun writeWifiSetting(targetOn: Boolean, root: Boolean, secure: Boolean): Boolean {
        val value = if (targetOn) "1" else "0"
        writeSecureAdb(targetOn)
        if (wifiMatches(targetOn, secure)) return true
        if (!root || !targetOn || !secure) return false
        ShellUtils.executeSu("settings put global adb_wifi_enabled " + value)
        return wifiMatches(targetOn, secure)
    }

    private fun wifiMatches(targetOn: Boolean, secure: Boolean): Boolean {
        val result = if (secure) {
            readSecureAdb()
        } else {
            ShellUtils.executeSu("settings get global adb_wifi_enabled")
        }
        val expected = if (targetOn) "1" else "0"
        return result.isSuccess() && result.scalar() == expected
    }

    private fun readAdbState(root: Boolean, hasPermission: Boolean): AdbState {
        if (!root && !hasPermission) return AdbState.UNKNOWN

        val wifiResult = if (hasPermission) {
            readSecureAdb()
        } else {
            ShellUtils.executeSu("settings get global adb_wifi_enabled")
        }
        if (!wifiResult.isSuccess()) return AdbState.UNKNOWN
        val wifi = wifiResult.scalar()
        if (!root || !hasPermission) {
            return when (wifi) {
                "1" -> AdbState.ON
                "0" -> AdbState.OFF
                else -> AdbState.INCONSISTENT
            }
        }

        val portResult = ShellUtils.executeSu("getprop service.adb.tcp.port")
        if (!portResult.isSuccess()) return AdbState.UNKNOWN
        val port = portResult.scalar()
        return when {
            wifi == "1" && port == "5555" -> AdbState.ON
            wifi == "0" && port == "0" -> AdbState.OFF
            else -> AdbState.INCONSISTENT
        }
    }

    private fun updateTileState() {
        val root = ShellUtils.probeRoot()
        val secure = SecureSettingsPermission.ensureGranted(applicationContext, root)
        val state = if (root || secure) readAdbState(root, secure) else AdbState.UNKNOWN
        publishState(state)
    }

    private fun publishState(state: AdbState) {
        mainHandler.post {
            val tile = qsTile ?: return@post
            tile.state = when (state) {
                AdbState.ON -> Tile.STATE_ACTIVE
                AdbState.OFF -> Tile.STATE_INACTIVE
                AdbState.INCONSISTENT,
                AdbState.UNKNOWN,
                -> Tile.STATE_UNAVAILABLE
            }
            tile.updateTile()
        }
    }

    private fun setTileUnavailable() {
        publishState(AdbState.UNKNOWN)
    }

    private fun writeSecureAdb(on: Boolean): Boolean {
        return try {
            Settings.Global.putString(
                applicationContext.contentResolver,
                "adb_wifi_enabled",
                if (on) "1" else "0"
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun readSecureAdb(): ShellUtils.Result {
        val value = try {
            Settings.Global.getString(applicationContext.contentResolver, "adb_wifi_enabled")
        } catch (_: Exception) {
            null
        }
        return if (value != null) ShellUtils.Result(0, value) else ShellUtils.Result(-1, "")
    }

    private companion object {
        const val READBACK_DELAY_MS = 1_000L
        val worker = Executors.newSingleThreadExecutor()
        val toggleSequence = AtomicInteger(0)
    }
}
