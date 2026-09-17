package com.dsharnessmobile.shell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/**
 * Z Flip 外屏（Flex Window）小组件（0.14.0-preview）：合盖不开内屏也能看状态、启停、重启、进面板。
 *
 * 三星放行条件（developer.samsung.com/galaxy-z/flex_window.html）全部落在 manifest/xml：
 * `android.appwidget.provider`（resizeMode horizontal|vertical + widgetCategory keyguard）+
 * `com.samsung.android.appwidget.provider`（display="sub_screen"）。用户在「设置 → 外屏 → 小组件」勾选后，
 * 外屏左滑即见；按钮经 PendingIntent 回到本 receiver（启停/重启）或直接把 Activity 投到外屏
 * （控制面板 / 完整界面，ActivityOptions.setLaunchDisplayId = 外屏 id，见 CoverScreen）。
 *
 * 来源校验（导出组件铁律，check-manifest-hardening 的 source-check 档）：系统 `APPWIDGET_*` 动作是受保护广播，
 * 直接放行；本类自定义动作必须满足其一——① API 34+ `sentFromUid == 本应用 uid`（PendingIntent 代发即本应用）；
 * ② 携带与私有 nonce 文件一致的 `auth` extra（PendingIntent 内嵌，第三方读不到；常量时间比较）。
 * 进程被杀后小组件点击仍要能用，故 nonce 落文件而非进程内存。
 *
 * 刷新节流：看门狗每 5s 一拍都会调 [refreshFromWatchdog]，但只在**渲染签名变化**时才推 RemoteViews
 * （AppWidgetManager 更新有系统侧开销，外屏常亮时尤其要省）。
 */
class CoverWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    // 先给一帧「读取中」骨架（进程冷启时探活要 ~1s），随后后台采样覆盖。
    for (id in appWidgetIds) {
      try { appWidgetManager.updateAppWidget(id, skeleton(context)) } catch (_: Throwable) {
      }
    }
    refreshAsync(context)
  }

  override fun onEnabled(context: Context) {
    super.onEnabled(context)
    LogCollector.log(TAG, "cover widget enabled")
    refreshAsync(context)
  }

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (!action.startsWith(ACTION_PREFIX)) {
      super.onReceive(context, intent)
      return
    }
    if (!isTrustedSender(context, intent)) {
      Log.w(TAG, "cover action rejected (untrusted sender): " + action)
      return
    }
    val displayId = intent.getIntExtra(EXTRA_DISPLAY, CoverScreen.coverDisplayId(context))
    if (action == ACTION_REFRESH) { refreshAsync(context); return }
    // 进程可能只因这条广播而活着（引擎已停、无 Activity）：goAsync 把 receiver 生命期延到工作线程结束
    // （阻塞主体都控制在 10s 预算内），否则 pkill/startEngine 半途就可能随进程一起被回收。
    val pending = goAsync()
    Thread {
      try {
        when (action) {
          ACTION_TOGGLE -> {
            // 启/停按**当前采样**决定，而不是按按钮上一次渲染的文案——两次点击之间状态可能已变。
            val snap = CoverEngineControl.sample(context)
            if (snap.running) CoverEngineControl.stopNow(context)
            else if (CoverEngineControl.prepareStart(context, displayId)) CoverEngineControl.startNow(context)
          }
          ACTION_START -> if (CoverEngineControl.prepareStart(context, displayId)) CoverEngineControl.startNow(context)
          ACTION_STOP -> CoverEngineControl.stopNow(context)
          ACTION_RESTART -> CoverEngineControl.restartNow(context)
          else -> Log.w(TAG, "unknown cover action: " + action)
        }
      } catch (t: Throwable) {
        Log.e(TAG, "cover action failed: " + action, t)
      } finally {
        pending.finish()
      }
    }.apply { isDaemon = true; name = "cover-action" }.start()
  }

  companion object {
    private const val TAG = "dsh-cover"
    private const val ACTION_PREFIX = "com.dsharnessmobile.shell.action.COVER_"
    const val ACTION_TOGGLE = ACTION_PREFIX + "TOGGLE"
    const val ACTION_START = ACTION_PREFIX + "START"
    const val ACTION_STOP = ACTION_PREFIX + "STOP"
    const val ACTION_RESTART = ACTION_PREFIX + "RESTART"
    const val ACTION_REFRESH = ACTION_PREFIX + "REFRESH"
    const val EXTRA_AUTH = "auth"
    const val EXTRA_DISPLAY = "display"
    private const val NONCE_FILE_NAME = "cover-widget-nonce"

    /** 上次推送的渲染签名（状态+文案），相同即跳过 updateAppWidget。 */
    @Volatile private var lastSignature: String? = null

    /** 是否有已放置的实例（无实例时采样纯属浪费）。 */
    fun hasInstances(context: Context): Boolean = try {
      val mgr = AppWidgetManager.getInstance(context)
      mgr.getAppWidgetIds(ComponentName(context, CoverWidgetProvider::class.java)).isNotEmpty()
    } catch (_: Throwable) {
      false
    }

    /** 后台采样 + 渲染（任意线程可调；无实例直接返回）。 */
    fun refreshAsync(context: Context) {
      val app = context.applicationContext
      if (!hasInstances(app)) return
      Thread {
        try {
          render(app, CoverEngineControl.sample(app), force = true)
        } catch (t: Throwable) {
          Log.w(TAG, "widget refresh failed: " + t.message)
        }
      }.apply { isDaemon = true; name = "cover-widget-refresh" }.start()
    }

    /** 看门狗 tick 入口（已在后台线程）：复用本拍的探活结论，避免再打一次 HTTP。 */
    fun refreshFromWatchdog(context: Context) {
      val app = context.applicationContext
      if (!hasInstances(app)) return
      try {
        render(app, CoverEngineControl.sample(app), force = false)
      } catch (t: Throwable) {
        Log.w(TAG, "widget watchdog refresh failed: " + t.message)
      }
    }

    /** 冷启骨架帧：只填静态文案与按钮 PendingIntent，状态由 refreshAsync 覆盖。 */
    private fun skeleton(context: Context): RemoteViews {
      val views = RemoteViews(context.packageName, R.layout.cover_widget)
      views.setTextViewText(R.id.cover_title, context.getString(R.string.cover_state_unknown))
      views.setTextViewText(R.id.cover_version, "v" + BuildConfig.VERSION_NAME)
      views.setInt(R.id.cover_dot, "setColorFilter", context.getColor(R.color.ds_text_tertiary))
      bindActions(context, views, running = false)
      return views
    }

    private fun render(context: Context, snap: CoverEngineControl.Snapshot, force: Boolean) {
      val title = when (snap.state) {
        CoverEngineControl.State.RUNNING -> context.getString(R.string.cover_state_running)
        CoverEngineControl.State.STARTING -> context.getString(R.string.cover_state_starting)
        CoverEngineControl.State.STOPPED -> context.getString(R.string.cover_state_stopped)
        CoverEngineControl.State.REFRESHING -> context.getString(R.string.cover_state_refreshing)
        CoverEngineControl.State.ERROR -> context.getString(R.string.cover_state_error)
        CoverEngineControl.State.NOT_READY -> context.getString(R.string.cover_state_not_ready)
      }
      val keepalive = context.getString(
        R.string.cover_keepalive_fmt,
        if (snap.serviceAlive) "✓" else "✗",
        if (snap.batteryWhitelisted) "✓" else "✗",
      )
      // 签名不含延迟毫秒/运行时长秒级抖动（否则每拍都变）：状态 + 分钟级时长 + 保活位。
      val signature = snap.state.name + "|" + (snap.uptimeMs / 60_000L) + "|" + keepalive + "|" + snap.failureCount
      if (!force && signature == lastSignature) return
      lastSignature = signature

      val views = RemoteViews(context.packageName, R.layout.cover_widget)
      views.setTextViewText(R.id.cover_title, title)
      views.setTextViewText(R.id.cover_version, "v" + BuildConfig.VERSION_NAME)
      views.setTextViewText(R.id.cover_detail, snap.detail)
      views.setTextViewText(R.id.cover_keepalive, keepalive)
      views.setInt(R.id.cover_dot, "setColorFilter", dotColor(context, snap.state))
      bindActions(context, views, running = snap.running)
      try {
        val mgr = AppWidgetManager.getInstance(context)
        mgr.updateAppWidget(ComponentName(context, CoverWidgetProvider::class.java), views)
      } catch (t: Throwable) {
        Log.w(TAG, "updateAppWidget failed: " + t.message)
      }
    }

    private fun dotColor(context: Context, state: CoverEngineControl.State): Int = when (state) {
      CoverEngineControl.State.RUNNING -> context.getColor(R.color.ds_ok)
      CoverEngineControl.State.STARTING, CoverEngineControl.State.REFRESHING -> context.getColor(R.color.ds_warn)
      CoverEngineControl.State.ERROR -> context.getColor(R.color.ds_danger)
      CoverEngineControl.State.STOPPED, CoverEngineControl.State.NOT_READY -> context.getColor(R.color.ds_text_tertiary)
    }

    private fun bindActions(context: Context, views: RemoteViews, running: Boolean) {
      val coverId = CoverScreen.coverDisplayId(context)
      views.setTextViewText(
        R.id.cover_btn_toggle,
        context.getString(if (running) R.string.cover_action_stop else R.string.cover_action_start),
      )
      views.setOnClickPendingIntent(R.id.cover_btn_toggle, actionPendingIntent(context, ACTION_TOGGLE, coverId))
      views.setOnClickPendingIntent(R.id.cover_btn_restart, actionPendingIntent(context, ACTION_RESTART, coverId))
      views.setOnClickPendingIntent(R.id.cover_detail, actionPendingIntent(context, ACTION_REFRESH, coverId))
      views.setOnClickPendingIntent(R.id.cover_title, actionPendingIntent(context, ACTION_REFRESH, coverId))
      views.setOnClickPendingIntent(R.id.cover_btn_panel, activityPendingIntent(context, CoverActivity::class.java, coverId, 1))
      views.setOnClickPendingIntent(R.id.cover_btn_full, activityPendingIntent(context, MainActivity::class.java, coverId, 2))
    }

    /**
     * 自定义动作广播的 PendingIntent（小组件按钮 / 前台通知动作共用）。显式 component + nonce extra；
     * requestCode 按 action 区分，FLAG_UPDATE_CURRENT 让 display extra 随最近一次渲染刷新。
     */
    fun actionPendingIntent(context: Context, action: String, displayId: Int): PendingIntent {
      val intent = Intent(action)
        .setComponent(ComponentName(context, CoverWidgetProvider::class.java))
        .putExtra(EXTRA_AUTH, ensureNonce(context))
        .putExtra(EXTRA_DISPLAY, displayId)
      return PendingIntent.getBroadcast(
        context, action.hashCode() and 0x7fffffff, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    /** 把 Activity 投到 [displayId]（外屏）的 PendingIntent；默认屏时不带 options。 */
    private fun activityPendingIntent(context: Context, cls: Class<*>, displayId: Int, requestCode: Int): PendingIntent {
      val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      val options = CoverScreen.launchOptions(displayId)
      return if (options != null) {
        PendingIntent.getActivity(context, requestCode, intent, flags, options)
      } else {
        PendingIntent.getActivity(context, requestCode, intent, flags)
      }
    }

    /** 私有 nonce 文件（filesDir 根；只有本应用 uid 可读）。 */
    private fun nonceFile(context: Context): java.io.File = java.io.File(context.filesDir, NONCE_FILE_NAME)

    fun ensureNonce(context: Context): String {
      val f = nonceFile(context)
      try {
        if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
      } catch (_: Throwable) {
      }
      val secret = java.math.BigInteger(256, java.security.SecureRandom()).toString(16)
      try {
        f.parentFile?.mkdirs()
        f.writeText(secret)
      } catch (t: Throwable) {
        Log.w(TAG, "nonce persist failed: " + t.message)
      }
      return secret
    }

    /**
     * 来源校验：34+ 代发 uid == 本应用 uid；否则比对 nonce（常量时间）。
     * `Intent.getSentFromUid` 经反射（与 AdbKeyboardService 同款：本机 SDK 平台 jar 缺该符号）。
     */
    fun isTrustedSender(context: Context, intent: Intent): Boolean {
      if (android.os.Build.VERSION.SDK_INT >= 34) {
        val uid = try {
          Intent::class.java.getMethod("getSentFromUid").invoke(intent) as? Int
        } catch (_: Throwable) {
          null
        }
        if (uid != null && uid == android.os.Process.myUid()) return true
      }
      val given = intent.getStringExtra(EXTRA_AUTH) ?: return false
      val secret = try {
        nonceFile(context).takeIf { it.exists() }?.readText()?.trim()
      } catch (_: Throwable) {
        null
      } ?: return false
      return java.security.MessageDigest.isEqual(
        given.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8),
      )
    }
  }
}
