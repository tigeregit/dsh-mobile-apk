package com.dsharnessmobile.shell

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 外屏 / 小组件 / 通知动作共用的引擎控制面与状态快照（0.14.0-preview，Z Flip 外屏支持）。
 *
 * 为什么要单独一层：MainActivity 的 EngineStartFlow 绑着 WebView 与引导页（解压进度、自动回撤、
 * 重试倒计时），外屏面板与 RemoteViews 小组件都没有这些视图，却要做同一组动作——启动/停止/重启——
 * 并读同一份「现在到底跑没跑」。本文件把动作收成三个入口、把状态收成一个纯数据快照，
 * 判定口径全部复用既有真源（EngineProbe / EngineService.userShutdown / EngineManager.snapshotRefreshing /
 * WatchdogV2 失败计数），不另立第二份状态。
 *
 * 动作语义与 EngineStartFlow 对齐：
 *  - start：快照未就绪（首启/换版待解压）不在这里解压——解压带进度与事务恢复，只能走 MainActivity 启动流，
 *    因此回落为「把 MainActivity 投到当前屏」；快照就绪时 = 清 userShutdown + 拉前台服务 + 直接 startEngine。
 *  - stop：与 shutdownToGuide 同款（userShutdown 标记 + 停看门狗 + 停引擎 + 停服务 + pkill 兜底）。
 *  - restart：与 EngineStartFlow.restart 同款（pkill → 清冷却 → 1s 后 startEngine）。
 * 全部动作都在后台线程执行（探活/pkill 都是阻塞 IO），调用方不得假设同步完成。
 */
internal object CoverEngineControl {

  private const val TAG = "dsh-cover"

  /** 外屏视图消费的状态枚举（颜色/文案由视图层映射，这里只给语义）。 */
  enum class State { RUNNING, STARTING, STOPPED, REFRESHING, ERROR, NOT_READY }

  /** 一次采样的引擎状态快照（纯数据，可跨线程传递）。 */
  data class Snapshot(
    val state: State,
    val latencyMs: Long,
    /** 引擎监听以来的时长（毫秒，取自 lastStartAttemptAt；未知为 -1）。 */
    val uptimeMs: Long,
    val serviceAlive: Boolean,
    val batteryWhitelisted: Boolean,
    val failureCount: Int,
    val detail: String,
  ) {
    val running: Boolean get() = state == State.RUNNING || state == State.STARTING
  }

  /**
   * 采样（阻塞 ≤ ~1.6s：一次 HTTP 探活 + 必要时一次 TCP 探活；**禁止主线程调用**）。
   * 判定顺序：快照刷新中 > 未解压 > HTTP 存活 > 端口可达（启动中）> 用户已停机 > 看门狗失败计数 > 已停止。
   */
  fun sample(context: Context): Snapshot {
    val manager = EngineManager(context.applicationContext, EngineManager.ensurePickToken())
    val battery = try { BatteryWhitelist.isIgnoring(context) } catch (_: Throwable) { false }
    val serviceAlive = EngineService.instance != null
    val failures = WatchdogV2.effectiveFailureCount()
    if (EngineManager.snapshotRefreshing) {
      return Snapshot(State.REFRESHING, -1, -1, serviceAlive, battery, failures, "正在写入内嵌运行时，请勿结束应用")
    }
    if (!manager.engineReady) {
      return Snapshot(State.NOT_READY, -1, -1, serviceAlive, battery, failures, "运行时待解压：请在完整界面完成首启")
    }
    val probe = try { EngineProbe.check(1_200) } catch (_: Throwable) { org.json.JSONObject().put("running", false) }
    val startedAt = EngineManager.lastStartAttemptAt
    val uptime = if (startedAt > 0) System.currentTimeMillis() - startedAt else -1L
    if (probe.optBoolean("running", false)) {
      val latency = probe.optLong("latencyMs", -1L)
      return Snapshot(State.RUNNING, latency, uptime, serviceAlive, battery, failures,
        "127.0.0.1:3080 · " + latency + "ms" + (if (uptime > 0) " · 已运行 " + formatDuration(uptime) else ""))
    }
    if (EngineProbe.portReachable(600) || manager.engineProcessAlive()) {
      return Snapshot(State.STARTING, -1, uptime, serviceAlive, battery, failures, "进程在场，HTTP 尚未就绪（冷启动 20-45s）")
    }
    if (EngineService.userShutdown) {
      return Snapshot(State.STOPPED, -1, -1, serviceAlive, battery, failures, "已手动停止，不会自动恢复")
    }
    if (failures > 0) {
      return Snapshot(State.ERROR, -1, -1, serviceAlive, battery, failures,
        "连续探活失败 " + failures + " 次" + (if (WatchdogV2.tripped()) "（看门狗已熔断）" else "，看门狗恢复中"))
    }
    return Snapshot(State.STOPPED, -1, -1, serviceAlive, battery, failures,
      if (serviceAlive) "看门狗在场，等待拉起" else "引擎与保活服务均未运行")
  }

  /** 人类可读时长：`3m12s` / `2h05m` / `1d03h`。 */
  fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
      d > 0 -> d.toString() + "d" + (h % 24).toString().padStart(2, '0') + "h"
      h > 0 -> h.toString() + "h" + (m % 60).toString().padStart(2, '0') + "m"
      m > 0 -> m.toString() + "m" + (s % 60).toString().padStart(2, '0') + "s"
      else -> s.toString() + "s"
    }
  }

  /**
   * 启动（异步）。快照未就绪 → 把 MainActivity 投到 [displayId]（解压流只在那里）；就绪 → 后台直接拉起。
   * @return true = 已在后台发起启动；false = 已改投 MainActivity（调用方据此提示）。
   */
  fun start(context: Context, displayId: Int): Boolean {
    val app = context.applicationContext
    if (!prepareStart(app, displayId)) return false
    detach("cover-engine-start") { startNow(app) }
    return true
  }

  /**
   * 启动前置（同步、无阻塞 IO）：新鲜度判定 + 未就绪改投 MainActivity + 清 userShutdown + 拉前台服务。
   * 先拉前台服务是有意的：进程从此有常驻组件，小组件广播的 goAsync 窗口结束后也不会被系统回收。
   * @return true = 可以继续 [startNow]；false = 已改投 MainActivity。
   */
  fun prepareStart(context: Context, displayId: Int): Boolean {
    val app = context.applicationContext
    val manager = EngineManager(app, EngineManager.ensurePickToken())
    val fresh = try { manager.engineReady && manager.snapshotFresh() } catch (_: Throwable) { false }
    if (!fresh) {
      LogCollector.log(TAG, "cover start: snapshot not ready/fresh -> handing off to MainActivity (display " + displayId + ")")
      CoverScreen.startOnDisplay(app, Intent(app, MainActivity::class.java), displayId)
      return false
    }
    EngineService.userShutdown = false
    try { app.startForegroundService(Intent(app, EngineService::class.java)) } catch (t: Throwable) {
      Log.w(TAG, "cover start: foreground service start failed: " + t.message)
    }
    return true
  }

  /** [start] 的阻塞主体（受 goAsync 保护的 receiver 线程可直接调用；startEngine 内含 ≤5s 端口复核）。 */
  fun startNow(context: Context) {
    val manager = EngineManager(context.applicationContext, EngineManager.ensurePickToken())
    try {
      manager.resetCooldown()
      manager.deployUndoCli()
      val accepted = manager.startEngine()
      LogCollector.log(TAG, "cover start requested (accepted=" + accepted + ")")
    } catch (t: Throwable) {
      Log.e(TAG, "cover start failed", t)
    } finally {
      CoverWidgetProvider.refreshAsync(context.applicationContext)
    }
  }

  /** 停止（完整停机语义，与 EngineStartFlow.shutdownToGuide 同款；不自动恢复）。异步。 */
  fun stop(context: Context) {
    val app = context.applicationContext
    EngineService.userShutdown = true
    detach("cover-engine-stop") { stopNow(app) }
  }

  /** [stop] 的阻塞主体。 */
  fun stopNow(context: Context) {
    val app = context.applicationContext
    EngineService.userShutdown = true
    try {
      try { EngineService.instance?.requestShutdown() } catch (_: Throwable) {
      }
      // engineProcess 是进程级共享句柄（EngineManager.sharedEngineProcess），任一实例都能 destroy；
      // 句柄丢失（看门狗曾 fork / 孤儿 linker64）时与 restart 同款 pkill 兜底（坑 31）。
      try { EngineManager(app, EngineManager.ensurePickToken()).stopEngine() } catch (_: Throwable) {
      }
      pkillEngine()
      try { app.stopService(Intent(app, EngineService::class.java)) } catch (_: Throwable) {
      }
      LogCollector.log(TAG, "cover stop: full shutdown requested")
    } finally {
      CoverWidgetProvider.refreshAsync(app)
    }
  }

  /** 重启（与 EngineStartFlow.restart 同款：pkill → 清冷却 → 1s → startEngine）。异步。 */
  fun restart(context: Context) {
    val app = context.applicationContext
    EngineService.userShutdown = false
    detach("cover-engine-restart") { restartNow(app) }
  }

  /** [restart] 的阻塞主体（约 1s 睡眠 + startEngine ≤5s 端口复核，落在 goAsync 10s 预算内）。 */
  fun restartNow(context: Context) {
    val app = context.applicationContext
    EngineService.userShutdown = false
    try {
      // 服务先在场（保进程），再杀引擎：看门狗 5s 一拍也会兜底拉起，这里的 startEngine 只是让它更快。
      try { app.startForegroundService(Intent(app, EngineService::class.java)) } catch (_: Throwable) {
      }
      pkillEngine()
      EngineManager.lastStartAttemptAt = 0
      LogCollector.log(TAG, "cover restart: engine killed, restarting")
      try { Thread.sleep(1000) } catch (_: InterruptedException) {
      }
      val manager = EngineManager(app, EngineManager.ensurePickToken())
      if (manager.engineReady) manager.startEngine()
    } catch (t: Throwable) {
      Log.e(TAG, "cover restart failed", t)
    } finally {
      CoverWidgetProvider.refreshAsync(app)
    }
  }

  private fun detach(name: String, block: () -> Unit) {
    Thread {
      try { block() } catch (t: Throwable) { Log.e(TAG, name + " failed", t) }
    }.apply { isDaemon = true; this.name = name }.start()
  }

  private fun pkillEngine() {
    try {
      Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-f", "bin.js")).waitFor()
    } catch (_: Throwable) {
    }
  }
}
