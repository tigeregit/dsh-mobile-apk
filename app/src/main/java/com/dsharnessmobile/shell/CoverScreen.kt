package com.dsharnessmobile.shell

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * 折叠屏外屏（Galaxy Z Flip5「Flex Window」，720x748 / 约 360x374dp）支撑件（0.14.0-preview）。
 *
 * 用户形态：手机合盖当 mini 服务器，尽量不开内屏——所有状态与设置都要能在外屏完成。
 * Samsung 的外屏承载面有两条：① 官方 Flex Window 小组件（`samsung-appwidget-provider display="sub_screen"`，
 * 见 CoverWidgetProvider）；② 在外屏上直接运行 Activity（Good Lock MultiStar「Launcher Widget」放行任意应用，
 * 或由本应用自己的小组件按 `ActivityOptions.setLaunchDisplayId(<外屏 id>)` 拉起——三星官方 codelab 的
 * `launchDisplayId = 1` 先例）。本文件只做两件事：**找到外屏**、**把 Intent 投到外屏**；不持任何状态。
 *
 * 外屏判定口径（Flip5 / One UI 6.1 `dumpsys display` 实测，2026-09-17）：
 *  - 外屏 = displayId 1，748x720，density 340，flags 含 **FLAG_PRESENTATION**、FLAG_OWN_CONTENT_ONLY、
 *    FLAG_EXTRA_BUILT_IN_DISPLAY；展开态 state OFF 且**不在** `getDisplays()` 列表里，合盖后才出现。
 *    所以不能用「非 presentation」过滤（会把外屏本身排除掉），只能排 FLAG_PRIVATE，再按**物理尺寸**挑：
 *    最长边 ≤ [COVER_MAX_EDGE_PX]（同机接的 HDMI 外接屏 3840x2160 由此排除）。
 *  - 找不到时回落「当前窗口尺寸像外屏」——最长边 ≤ [COVER_MAX_EDGE_PX] 且近似方形（0.8 ≤ w/h ≤ 1.25）——
 *    覆盖 MultiStar 把应用整任务搬到外屏后 `Activity.display` 已是外屏但 id 因 ROM 而异的情形。
 */
internal object CoverScreen {

  private const val TAG = "dsh-cover"

  /** Flip5 外屏 748x720；给 Flip6/7 的 748/更高留余量，但明显排除内屏（1080x2640）与外接屏。 */
  const val COVER_MAX_EDGE_PX = 1000

  /** 非默认、非私有、物理尺寸像外屏的显示器（多个命中取 id 最小）；无外屏（或展开态外屏未上线）返回 null。 */
  fun coverDisplay(context: Context): Display? {
    return try {
      val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
      dm.displays
        .filter { d -> d.displayId != Display.DEFAULT_DISPLAY && (d.flags and Display.FLAG_PRIVATE) == 0 }
        .filter { d -> physicalMaxEdge(d) <= COVER_MAX_EDGE_PX }
        .minByOrNull { it.displayId }
    } catch (t: Throwable) {
      Log.w(TAG, "coverDisplay lookup failed: " + t.message)
      null
    }
  }

  /** 物理分辩率最长边（Display.Mode，API 23+；取不到按 Int.MAX 视为「不像外屏」）。 */
  private fun physicalMaxEdge(d: Display): Int = try {
    val m = d.mode
    maxOf(m.physicalWidth, m.physicalHeight)
  } catch (_: Throwable) {
    Int.MAX_VALUE
  }

  /** 外屏 displayId；无外屏回落默认屏（调用方直接拿去 setLaunchDisplayId，无需再判空）。 */
  fun coverDisplayId(context: Context): Int = coverDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY

  /** 当前窗口是否呈外屏形态（尺寸口径，见类注释）。Activity 未 attach 到窗口时按 false。 */
  fun isCoverSized(activity: Activity): Boolean {
    val (w, h) = windowSize(activity) ?: return false
    if (w <= 0 || h <= 0) return false
    val ratio = w.toFloat() / h.toFloat()
    return maxOf(w, h) <= COVER_MAX_EDGE_PX && ratio in 0.8f..1.25f
  }

  /** Activity 是否正显示在外屏上（显示器 id 或尺寸口径任一命中）。 */
  fun isOnCover(activity: Activity): Boolean {
    val displayId = try {
      if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.displayId
      }
    } catch (_: Throwable) {
      null
    }
    if (displayId != null && displayId != Display.DEFAULT_DISPLAY) return true
    return isCoverSized(activity)
  }

  /** 窗口尺寸（物理像素）；API 30+ 走 WindowMetrics，之前走 Display.getSize。 */
  private fun windowSize(activity: Activity): Pair<Int, Int>? = try {
    if (Build.VERSION.SDK_INT >= 30) {
      val b = activity.windowManager.currentWindowMetrics.bounds
      Pair(b.width(), b.height())
    } else {
      val p = Point()
      @Suppress("DEPRECATION")
      activity.windowManager.defaultDisplay.getSize(p)
      Pair(p.x, p.y)
    }
  } catch (_: Throwable) {
    null
  }

  /**
   * 投到指定显示器的 ActivityOptions（Bundle 形态，PendingIntent.getActivity 与 startActivity 通用）。
   * 默认屏返回 null（不带 options 即默认行为，避免在单屏机上多一层系统判定）。
   */
  fun launchOptions(displayId: Int): android.os.Bundle? {
    if (displayId == Display.DEFAULT_DISPLAY) return null
    return try {
      ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    } catch (t: Throwable) {
      Log.w(TAG, "launchOptions failed: " + t.message)
      null
    }
  }

  /**
   * 把 Activity 投到指定显示器；系统拒绝（SecurityException / 显示器已消失）时回落普通启动——
   * 用户至多多翻开一次手机，绝不因外屏投放失败而什么都不发生。
   */
  fun startOnDisplay(context: Context, intent: Intent, displayId: Int) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = launchOptions(displayId)
    try {
      if (options != null) context.startActivity(intent, options) else context.startActivity(intent)
    } catch (t: Throwable) {
      Log.w(TAG, "startOnDisplay(" + displayId + ") failed, falling back to default display: " + t.message)
      try { context.startActivity(intent) } catch (_: Throwable) {
      }
    }
  }

  /** 从一个已在某显示器上的 Activity 出发，把另一个 Activity 开在**同一块屏**上（外屏面板 → 完整界面）。 */
  fun startOnSameDisplay(activity: Activity, intent: Intent) {
    val id = try {
      if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY
    } catch (_: Throwable) {
      Display.DEFAULT_DISPLAY
    }
    startOnDisplay(activity, intent, id)
  }
}
