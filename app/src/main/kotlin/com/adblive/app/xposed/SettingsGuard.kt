package com.adblive.app.xposed

import android.content.ContentResolver
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object SettingsGuard {
    private const val KEY = "adb_wifi_enabled"
    private const val OUR_PACKAGE = "com.adblive.app"
    private const val GUARD_NOTICE = "ADBLive 主动守护已开启，无法关闭无线 ADB"
    private const val PASSIVE_NOTICE = "ADBLive 被动守护已拉回无线 ADB"
    private val registeredSys = AtomicBoolean(false)
    private var ourUid = 0
    @Volatile private var lastNoticeAtMs = 0L
    @Volatile private var lastPassiveNoticeAtMs = 0L

    private fun shouldBlock(): Boolean {
        if (Entry.isShieldDisabled()) return false
        if (isCallerOurs()) return false
        return true
    }

    private fun isCallerOurs(): Boolean {
        if (ourUid == 0) return false
        val uid = Binder.getCallingUid()
        return uid == ourUid
    }

    fun hookSystemServer(lpparam: LoadPackageParam) {
        if (!registeredSys.compareAndSet(false, true)) return
        ourUid = resolveOurUid()
        Entry.log("SettingsGuard ourUid=" + ourUid)
        Entry.log("SettingsGuard mounting system_server guards")
        hookGlobalPutInt()
        hookGlobalPutString()
        hookTransportCall(lpparam.classLoader)
    }

    /**
     * 解析本应用 uid。system_server 内没有 Application，
     * ActivityThread.currentApplication() 恒为 null —— 曾因此 ourUid=0，
     * 盾把本应用自己的写入也一并拦截（App 内关不掉无线 ADB）。
     * 这里用系统上下文 + 包列表多路兜底。
     */
    private fun resolveOurUid(): Int {
        try {
            val atCls = XposedHelpers.findClass("android.app.ActivityThread", null)
            val at = XposedHelpers.callStaticMethod(atCls, "currentActivityThread")
            if (at != null) {
                val sysCtx = try {
                    XposedHelpers.callMethod(at, "getSystemContext") as? Context
                } catch (_: Throwable) { null }
                val uidSys = sysCtx?.packageManager?.getApplicationInfo(OUR_PACKAGE, 0)?.uid ?: 0
                if (uidSys != 0) return uidSys

                val appCtx = try {
                    XposedHelpers.callMethod(at, "getApplication") as? Context
                } catch (_: Throwable) { null }
                val uidApp = appCtx?.packageManager?.getApplicationInfo(OUR_PACKAGE, 0)?.uid ?: 0
                if (uidApp != 0) return uidApp
            }
        } catch (_: Throwable) { }

        try {
            File("/data/system/packages.list").useLines { lines ->
                for (line in lines) {
                    val parts = line.split(' ')
                    if (parts.size >= 2 && parts[0] == OUR_PACKAGE) {
                        val uid = parts[1].trim().toIntOrNull() ?: 0
                        if (uid != 0) return uid
                    }
                }
            }
        } catch (_: Throwable) { }

        return 0
    }

    fun hookSettings(lpparam: LoadPackageParam) {
        Entry.log("SettingsGuard mounting Settings guards")
        hookWirelessPreference(lpparam)
        hookGlobalPutInt()
        hookGlobalPutString()
        hookTransportCall(lpparam.classLoader)
    }

    private fun hookWirelessPreference(lpparam: LoadPackageParam) {
        try {
            val preferenceClass = XposedHelpers.findClass("androidx.preference.Preference", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                preferenceClass,
                "callChangeListener",
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldBlock()) return
                        val key = try {
                            XposedHelpers.callMethod(param.thisObject, "getKey") as? String
                        } catch (_: Throwable) { null }
                        if (key != "enable_adb_wireless" || param.args[0] != false) return

                        Entry.log("SettingsGuard blocked wireless preference change")
                        notifyGuardBlocked()
                        param.setResult(false)
                    }
                },
            )
            Entry.log("SettingsGuard hooked wireless preference change guard")
        } catch (t: Throwable) {
            Entry.log("SettingsGuard hook wireless preference failed: " + t.message)
        }
    }

    private fun hookGlobalPutInt() {
        val clazz = try {
            XposedHelpers.findClass("android.provider.Settings\$Global", null)
        } catch (t: Throwable) { Entry.log("SettingsGuard findClass failed: " + t.message); return }
        for (sig in arrayOf(
            arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType),
            arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
        )) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "putInt", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldBlock()) return
                        val name = param.args[1] as? String ?: return
                        val value = param.args[2] as? Int ?: return
                        if (name == KEY && value == 0) {
                            Entry.log("SettingsGuard blocked putInt(" + KEY + "=0)")
                            notifyGuardBlocked()
                            param.setResult(true)
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    private fun hookGlobalPutString() {
        val clazz = try {
            XposedHelpers.findClass("android.provider.Settings\$Global", null)
        } catch (_: Throwable) { return }
        for (sig in arrayOf(
            arrayOf(ContentResolver::class.java, String::class.java, String::class.java),
            arrayOf(ContentResolver::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType),
        )) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "putString", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldBlock()) return
                        val name = param.args[1] as? String ?: return
                        val value = param.args[2] as? String ?: return
                        if (name == KEY && isOff(value)) {
                            Entry.log("SettingsGuard blocked putString(" + KEY + ")")
                            notifyGuardBlocked()
                            param.setResult(true)
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    private fun hookTransportCall(classLoader: ClassLoader?) {
        try {
            val transport = XposedHelpers.findClass("android.content.ContentProvider\$Transport", classLoader)
            val sigs = mutableListOf(
                arrayOf<Class<*>>(String::class.java, String::class.java, Bundle::class.java),
                arrayOf<Class<*>>(String::class.java, String::class.java, String::class.java, Bundle::class.java),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val attributionSource = XposedHelpers.findClass(
                        "android.content.AttributionSource",
                        classLoader
                    )
                    sigs.add(
                        arrayOf(
                            attributionSource,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                            Bundle::class.java
                        )
                    )
                } catch (_: Throwable) { }
            }
            for (sig in sigs) {
                try {
                    XposedHelpers.findAndHookMethod(transport, "call", *sig, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val argc = param.args.size
                                val method: String = when (argc) {
                                    3 -> param.args[1] as? String ?: return
                                    4 -> param.args[1] as? String ?: return
                                    else -> param.args[2] as? String ?: return
                                }
                                val name: String? = when (argc) {
                                    3 -> null
                                    4 -> param.args[2] as? String
                                    else -> param.args[3] as? String
                                }
                                val extras: Bundle? = when (argc) {
                                    3 -> param.args[2] as? Bundle
                                    4 -> param.args[3] as? Bundle
                                    else -> param.args[4] as? Bundle
                                }
                                if (method != "PUT_global" || name != KEY) return
                                val value = extras?.getString("value")
                                if (isOff(value)) {
                                    if (!shouldBlock()) return
                                    Entry.log("SettingsGuard blocked Transport.call(" + KEY + ") argc=" + argc)
                                    notifyGuardBlocked()
                                    param.setResult(Bundle())
                                    return
                                }
                                if (isOn(value) &&
                                    Entry.isShieldDisabled() &&
                                    isLikelyPassiveRestoreCaller()
                                ) {
                                    Entry.log("SettingsGuard passive guard restored " + KEY)
                                    notifyPassiveRestored()
                                }
                            } catch (_: Throwable) { }
                        }
                    })
                } catch (_: Throwable) { }
            }
            Entry.log("SettingsGuard hooked Transport.call variants")
        } catch (t: Throwable) {
            Entry.log("SettingsGuard hook Transport.call failed: " + t.message)
        }
    }

    private fun notifyGuardBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastNoticeAtMs < 1500L) return
        lastNoticeAtMs = now
        showNotice(GUARD_NOTICE, lastNoticeAtMs)
    }

    private fun notifyPassiveRestored() {
        val now = System.currentTimeMillis()
        if (now - lastPassiveNoticeAtMs < 3000L) return
        lastPassiveNoticeAtMs = now
        showNotice(PASSIVE_NOTICE, lastPassiveNoticeAtMs)
    }

    private fun showNotice(message: String, timestamp: Long) {
        try {
            val activityThread = XposedHelpers.findClass("android.app.ActivityThread", null)
            val appContext = XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context
            val activityThreadInstance = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread")
            val systemContext = try {
                XposedHelpers.callMethod(activityThreadInstance, "getSystemContext") as? Context
            } catch (_: Throwable) { null }
            val context = appContext ?: systemContext ?: return
            Entry.log("SettingsGuard notice context=" + context.javaClass.name + " package=" + context.packageName)
            Handler(Looper.getMainLooper()).post {
                try {
                    if (timestamp != lastNoticeAtMs && timestamp != lastPassiveNoticeAtMs) return@post
                    Entry.log("SettingsGuard showing notice: " + message)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    Entry.log("SettingsGuard notice shown: " + message)
                } catch (t: Throwable) {
                    Entry.log("SettingsGuard notice failed: " + t.javaClass.name + ": " + t.message)
                }
            }
        } catch (t: Throwable) {
            Entry.log("SettingsGuard notice context failed: " + t.javaClass.name + ": " + t.message)
        }
    }

    private fun isOff(value: String?): Boolean {
        val v = (value ?: return false).trim()
        return v == "0" || v.equals("false", true) || v.equals("disable", true) ||
            v.equals("off", true) || v.equals("no", true)
    }

    private fun isOn(value: String?): Boolean {
        val v = (value ?: return false).trim()
        return v == "1" || v.equals("true", true) || v.equals("enable", true) ||
            v.equals("on", true) || v.equals("yes", true)
    }

    private fun isLikelyPassiveRestoreCaller(): Boolean {
        if (Binder.getCallingUid() != 0) return false
        var pid = Binder.getCallingPid()
        if (pid <= 0) return false
        repeat(16) {
            val cmdline = try {
                File("/proc/" + pid + "/cmdline").readText().replace('\u0000', ' ')
            } catch (_: Throwable) {
                return false
            }
            if (cmdline.contains("99_adblive_guard.sh") || cmdline.contains("adblive_guard")) return true
            val ppid = try {
                File("/proc/" + pid + "/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("PPid:") }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull() ?: 0
                }
            } catch (_: Throwable) {
                return false
            }
            if (ppid <= 0 || ppid == pid) return false
            pid = ppid
        }
        return false
    }
}
