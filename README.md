# ADBLive

一个 Android 无线 ADB 保护应用（单 APK），防止无线 ADB 被意外关闭。适配 KernelSU / Magisk root 与 LSPosed 模块环境。

## 功能

* 无线 ADB 开关：一键开关无线 ADB（固定端口 5555）。App 内开关 = 用户最高意志，关闭后被动守护不会拉回
* 主动守护（Shield）：Xposed/LSPosed 模块，拦截系统关闭无线 ADB 与杀 adbd 的操作
* 被动守护（Guard）：root 授权后部署 shell 守护脚本，10s 轮询自动恢复无线 ADB
* 用户意志尊重：用户主动关闭 ADB 时写入意图文件，守护 / 开机自启均不恢复
* 开机自启：重启后自动部署守护、恢复无线 ADB
* 卸载零残留：卸载时清理全部注入钩子、守护进程与状态文件，无僵尸残留

## 贡献鸣谢

* [@gjk0510](https://github.com/gjk0510)（净沙）：贡献无线 ADB 快捷磁贴与系统开发者选项入口（[PR #1](https://github.com/Xposed-Modules-Repo/com.adblive.app/pull/1)）。

## 构建

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## 安装与使用

1. 安装 APK 后在 KernelSU / Magisk 中授权 root，并重启一次 App 解锁（首次授权需重启 App 一次）
2. 在 LSPosed 中启用本模块（作用域 system + settings + 本应用），重启激活主动守护
3. 打开被动守护开关部署守护脚本；按需开启开机自启

## 状态文件（卸载清理清单）

卸载后软件会自动清理全部状态文件与守护进程（无残留）。核心文件：

* 盾状态：/data/system/adblive_shield_armed
* 守护：/data/adb/service.d/99_adblive_guard.sh
* 用户意图：/data/adb/adblive_user_disabled_adb
* 开机自启标记：/data/adb/adblive_boot_enabled

---

## English Overview

**ADBLive** is a wireless ADB protection app for Android (single APK). It keeps wireless ADB alive so it is not accidentally turned off.

* **Active Shield (Xposed/LSPosed)**: hooks and blocks the system from disabling wireless ADB (`adb_wifi_enabled=0`) or killing `adbd`
* **Passive Guard (root)**: deploys a shell watchdog script that polls every 10s and restores wireless ADB automatically
* **User's will respected**: turning off wireless ADB inside the app writes an intent file; guard / boot auto-start will not pull it back
* **Boot auto-start**: redeploys the guard and restores wireless ADB after reboot
* **Zero residue on uninstall**: all hooks, guard process and state files are cleaned up on uninstall

Compatible with KernelSU / Magisk root and the LSPosed module environment. Fixed port **5555**, minSdk 30 (Android 11).
