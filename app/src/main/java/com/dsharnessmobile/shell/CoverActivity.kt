package com.dsharnessmobile.shell

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 外屏控制面板（0.14.0-preview，Z Flip5 Flex Window 720x748 ≈ 360x374dp）：合盖状态下完成**全部壳侧设置**
 * 与运行状态查看；引擎侧设置（模型/供应商/插件……）由「完整界面」在**同一块屏**上打开 WebView 承载
 * （MainActivity 的响应式 UI 在 360dp 宽下本就是手机形态）。
 *
 * 版面原则：单列、纵向可滚、触控目标 ≥ 44dp、字号不低于 12sp；样式复用 DsUi / ds_* token 与 GuideChrome 的
 * 双层卡片语言。所有开关的**展示值 = 壳侧真源**（ImmersiveMode / DevLogControl / OverlayController /
 * BatteryWhitelist / Environment.isExternalStorageManager / DeviceControlService.connected），写后回读，不做乐观置位。
 *
 * 状态每 3s 采样一次（后台线程，CoverEngineControl.sample），与 MainActivity 前台监控同周期；
 * onPause 停表。启停/重启动作走 CoverEngineControl（与小组件、通知动作同一实现）。
 */
class CoverActivity : ComponentActivity() {

  private val handler = android.os.Handler(android.os.Looper.getMainLooper())
  private lateinit var statusDot: View
  private lateinit var statusTitle: TextView
  private lateinit var statusDetail: TextView
  private lateinit var statusKeepalive: TextView
  private lateinit var toggleButton: Button
  private lateinit var restartButton: Button
  private lateinit var displayInfo: TextView

  private lateinit var batteryRow: RowHandle
  private lateinit var storageRow: RowHandle
  private lateinit var a11yRow: RowHandle
  private lateinit var notifyRow: RowHandle
  private lateinit var adbRow: RowHandle
  private lateinit var overlaySwitch: Switch
  private lateinit var immersiveSwitch: Switch
  private lateinit var devLogSwitch: Switch

  /** 最近一次采样（按钮语义按它决定，不按文案）。 */
  @Volatile private var lastSnapshot: CoverEngineControl.Snapshot? = null
  private var busyUntil = 0L

  private val tick = object : Runnable {
    override fun run() {
      sampleAsync()
      handler.postDelayed(this, 3_000)
    }
  }

  /** 设置行句柄：标题固定，副标题/右侧状态运行时刷新。 */
  private class RowHandle(val root: View, val subtitle: TextView, val trailing: TextView)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val root = buildContent()
    setContentView(root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
      val g = dp(12f)
      v.setPadding(g + maxOf(bars.left, cutout.left), g + maxOf(bars.top, cutout.top), g + maxOf(bars.right, cutout.right), g + bars.bottom)
      insets
    }
    ViewCompat.requestApplyInsets(root)
    LogCollector.log(TAG, "cover panel opened (onCover=" + CoverScreen.isOnCover(this) + ")")
  }

  override fun onResume() {
    super.onResume()
    refreshSettingsRows()
    handler.removeCallbacks(tick)
    handler.post(tick)
  }

  override fun onPause() {
    handler.removeCallbacks(tick)
    super.onPause()
  }

  // ── 采样与渲染 ─────────────────────────────────────────────────────────

  private fun sampleAsync() {
    Thread {
      val snap = try { CoverEngineControl.sample(this) } catch (_: Throwable) { null }
      val adb = try { AdbState.stateJson(this) } catch (_: Throwable) { null }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        if (snap != null) renderStatus(snap)
        renderAdb(adb)
      }
    }.apply { isDaemon = true; name = "cover-sample" }.start()
  }

  private fun renderStatus(snap: CoverEngineControl.Snapshot) {
    lastSnapshot = snap
    statusTitle.text = when (snap.state) {
      CoverEngineControl.State.RUNNING -> getString(R.string.cover_state_running)
      CoverEngineControl.State.STARTING -> getString(R.string.cover_state_starting)
      CoverEngineControl.State.STOPPED -> getString(R.string.cover_state_stopped)
      CoverEngineControl.State.REFRESHING -> getString(R.string.cover_state_refreshing)
      CoverEngineControl.State.ERROR -> getString(R.string.cover_state_error)
      CoverEngineControl.State.NOT_READY -> getString(R.string.cover_state_not_ready)
    }
    statusDetail.text = snap.detail
    statusKeepalive.text = getString(
      R.string.cover_keepalive_fmt,
      if (snap.serviceAlive) "✓" else "✗",
      if (snap.batteryWhitelisted) "✓" else "✗",
    )
    val color = when (snap.state) {
      CoverEngineControl.State.RUNNING -> getColor(R.color.ds_ok)
      CoverEngineControl.State.STARTING, CoverEngineControl.State.REFRESHING -> getColor(R.color.ds_warn)
      CoverEngineControl.State.ERROR -> getColor(R.color.ds_danger)
      CoverEngineControl.State.STOPPED, CoverEngineControl.State.NOT_READY -> getColor(R.color.ds_text_tertiary)
    }
    statusDot.background = DsUi.oval(color)
    val busy = System.currentTimeMillis() < busyUntil || snap.state == CoverEngineControl.State.REFRESHING
    toggleButton.text = getString(if (snap.running) R.string.cover_action_stop else R.string.cover_action_start)
    toggleButton.isEnabled = !busy
    toggleButton.alpha = if (busy) 0.55f else 1f
    restartButton.isEnabled = !busy && snap.state != CoverEngineControl.State.NOT_READY
    restartButton.alpha = if (restartButton.isEnabled) 1f else 0.55f
    batteryRow.trailing.text = if (snap.batteryWhitelisted) "已加入" else "去设置"
    batteryRow.trailing.setTextColor(getColor(if (snap.batteryWhitelisted) R.color.ds_text_secondary else R.color.ds_accent))
  }

  private fun renderAdb(json: String?) {
    if (json == null) { adbRow.trailing.text = "—"; return }
    try {
      val j = org.json.JSONObject(json)
      val authorized = j.optBoolean("authorized", false)
      val connected = j.optBoolean("connected", false)
      adbRow.trailing.text = when {
        authorized && connected -> "已连接"
        authorized -> "已配对"
        j.optBoolean("paired", false) -> "待授权"
        else -> "未配对"
      }
      adbRow.subtitle.text = j.optString("message").takeIf { it.isNotBlank() && it != "null" }
        ?: "无线调试通道正常（配对/回收请在完整界面 → 设置 → 开发者选项）"
    } catch (_: Throwable) {
      adbRow.trailing.text = "—"
    }
  }

  /** 壳侧开关/权限的真源回读（onResume + 每次开关写后）。 */
  private fun refreshSettingsRows() {
    overlaySwitch.setOnCheckedChangeListener(null)
    immersiveSwitch.setOnCheckedChangeListener(null)
    devLogSwitch.setOnCheckedChangeListener(null)
    overlaySwitch.isChecked = OverlayController.isEnabled(this)
    immersiveSwitch.isChecked = ImmersiveMode.isEnabled(this)
    devLogSwitch.isChecked = DevLogControl.isEnabled(this)
    overlaySwitch.setOnCheckedChangeListener { _, on ->
      val started = OverlayController.setEnabled(this, on)
      if (on && !started) toast("已打开系统授权页；授予「显示在其他应用上层」后请重新打开本开关")
      refreshSettingsRows()
    }
    immersiveSwitch.setOnCheckedChangeListener { _, on ->
      ImmersiveMode.setEnabled(this, on)
      refreshSettingsRows()
    }
    devLogSwitch.setOnCheckedChangeListener { _, on ->
      DevLogControl.setEnabled(this, on)
      toast(if (on) "开发者日志已开启（按天写入 Documents/dshdata/log/）" else "开发者日志已关闭")
      refreshSettingsRows()
    }

    val storageOk = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    storageRow.trailing.text = if (storageOk) "已授权" else "去授权"
    storageRow.trailing.setTextColor(getColor(if (storageOk) R.color.ds_text_secondary else R.color.ds_accent))

    val a11yOn = DeviceControlService.connected()
    a11yRow.trailing.text = if (a11yOn) "已开启" else "去开启"
    a11yRow.trailing.setTextColor(getColor(if (a11yOn) R.color.ds_text_secondary else R.color.ds_accent))

    val notifyOn = try {
      androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
    } catch (_: Throwable) {
      true
    }
    notifyRow.trailing.text = if (notifyOn) "已允许" else "去允许"
    notifyRow.trailing.setTextColor(getColor(if (notifyOn) R.color.ds_text_secondary else R.color.ds_accent))

    displayInfo.text = describeDisplay()
  }

  private fun describeDisplay(): String {
    val id = try {
      if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: 0 else 0
    } catch (_: Throwable) {
      0
    }
    val m = resources.displayMetrics
    val onCover = CoverScreen.isOnCover(this)
    return "显示器 #" + id + " · " + m.widthPixels + "×" + m.heightPixels + " · density " + m.density +
      (if (onCover) " · 外屏形态" else " · 内屏形态") + " · v" + BuildConfig.VERSION_NAME
  }

  // ── 动作 ───────────────────────────────────────────────────────────────

  private fun onToggle() {
    val snap = lastSnapshot ?: return
    busyUntil = System.currentTimeMillis() + 4_000
    if (snap.running) {
      CoverEngineControl.stop(this)
      toast("正在停止引擎（不会自动恢复）")
    } else {
      val started = CoverEngineControl.start(this, currentDisplayId())
      toast(if (started) "正在启动引擎…" else "运行时需先解压：已打开完整界面完成首启")
    }
    renderStatus(snap)
    handler.postDelayed({ sampleAsync() }, 1_500)
  }

  private fun onRestartEngine() {
    busyUntil = System.currentTimeMillis() + 5_000
    CoverEngineControl.restart(this)
    toast("引擎重启中…")
    lastSnapshot?.let { renderStatus(it) }
    handler.postDelayed({ sampleAsync() }, 2_000)
  }

  private fun openFullUi() {
    CoverScreen.startOnSameDisplay(this, Intent(this, MainActivity::class.java))
  }

  private fun openConsole() {
    CoverScreen.startOnSameDisplay(this, Intent(this, ConsoleActivity::class.java))
  }

  private fun currentDisplayId(): Int = try {
    if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: 0 else 0
  } catch (_: Throwable) {
    0
  }

  private fun openBatterySettings() {
    val intent = BatteryWhitelist.requestIntent(this)
      ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    startSafely(intent, "无法打开电池优化设置，请手动前往 设置 → 应用 → DeepCode → 电池")
  }

  private fun openAllFilesAccess() {
    if (Build.VERSION.SDK_INT < 30) { toast("当前系统无需此权限"); return }
    val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(Uri.parse("package:$packageName"))
    if (!startQuietly(perApp)) startSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), "无法打开「所有文件访问」授权页")
  }

  private fun openAccessibility() {
    startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "无法打开系统设置，请手动前往 系统设置 → 无障碍")
  }

  private fun openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    startSafely(intent, "无法打开通知设置")
  }

  private fun exportConfig() {
    Thread {
      val manager = EngineManager(applicationContext, EngineManager.ensurePickToken())
      val raw = try { ConfigTransfer(manager.homeDir, manager.dshDataDir).exportToShared() } catch (t: Throwable) { "{\"ok\":false,\"error\":\"" + t.message + "\"}" }
      runOnUiThread { toast(transferMessage(raw, "已导出到 ", "导出失败：")) }
    }.start()
  }

  private fun importConfig() {
    Thread {
      val manager = EngineManager(applicationContext, EngineManager.ensurePickToken())
      val raw = try { ConfigTransfer(manager.homeDir, manager.dshDataDir).importFromShared() } catch (t: Throwable) { "{\"ok\":false,\"error\":\"" + t.message + "\"}" }
      runOnUiThread { toast(transferMessage(raw, "已导入并生效 ", "导入失败：")) }
    }.start()
  }

  private fun transferMessage(raw: String, okPrefix: String, failPrefix: String): String = try {
    val j = org.json.JSONObject(raw)
    if (j.optBoolean("ok")) okPrefix + (j.optString("path").takeIf { it.isNotBlank() } ?: j.optString("hint"))
    else failPrefix + j.optString("error", "未知错误")
  } catch (_: Throwable) {
    failPrefix + raw.take(120)
  }

  /** 只做检查并提示（下载/安装的二次确认与授权续继在启动页，避免两处状态机）。 */
  private fun checkUpdate() {
    toast("正在检查 APK 更新…")
    Thread {
      val r = UpdateChecker.checkLatest()
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        when (r) {
          is UpdateChecker.CheckResult.UpToDate -> toast("APK 已是最新 v" + UpdateChecker.currentVersion())
          is UpdateChecker.CheckResult.Available -> toast("发现新版 " + r.tag + "——请在完整界面的启动页点「检查更新」下载安装")
          is UpdateChecker.CheckResult.Failed -> toast(r.reason)
        }
      }
    }.start()
  }

  private fun startQuietly(intent: Intent): Boolean = try {
    startActivity(intent)
    true
  } catch (_: Throwable) {
    false
  }

  private fun startSafely(intent: Intent, failure: String) {
    if (!startQuietly(intent)) toast(failure)
  }

  private fun toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
  }

  // ── 视图构建（纯代码，风格对齐 GuideChrome）─────────────────────────────

  private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
  private fun color(id: Int): Int = getColor(id)
  private fun typeMedium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  private fun dim(id: Int): Float = resources.getDimension(id)

  private fun buildContent(): View {
    val hairline = resources.displayMetrics.density.toInt().coerceAtLeast(1)
    val outer = FrameLayout(this).apply {
      background = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(color(R.color.ds_glow), color(R.color.ds_bg), color(R.color.ds_bg)),
      )
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // —— 头部：图标 + 标题 + 版本 ——
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, 0, 0, dp(10f))
    }
    header.addView(android.widget.ImageView(this).apply {
      setImageResource(R.mipmap.ic_launcher)
      layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(10f) }
    })
    header.addView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      addView(label(getString(R.string.app_name), 16f, R.color.ds_text_primary, typeMedium()))
      addView(label(getString(R.string.cover_panel_title), 11f, R.color.ds_text_secondary))
    })
    header.addView(TextView(this).apply {
      text = "v" + BuildConfig.VERSION_NAME
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      setTextColor(color(R.color.ds_text_tertiary))
      typeface = typeMedium()
      background = DsUi.roundRect(color(R.color.ds_chip), dim(R.dimen.ds_radius_pill))
      setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
    })
    column.addView(header)

    // —— 状态卡 ——
    val statusCard = card(hairline)
    val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    statusDot = View(this).apply {
      layoutParams = LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(8f) }
      background = DsUi.oval(color(R.color.ds_text_tertiary))
    }
    statusTitle = label(getString(R.string.cover_state_unknown), 16f, R.color.ds_text_primary, typeMedium()).apply {
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    statusRow.addView(statusDot)
    statusRow.addView(statusTitle)
    statusCard.addView(statusRow)
    statusDetail = label("—", 12f, R.color.ds_text_secondary).apply { setPadding(0, dp(6f), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    statusKeepalive = label("—", 11f, R.color.ds_text_tertiary).apply { setPadding(0, dp(2f), 0, 0) }
    statusCard.addView(statusDetail)
    statusCard.addView(statusKeepalive)
    statusCard.isClickable = true
    statusCard.setOnClickListener { sampleAsync() }
    column.addView(wrapShell(statusCard, hairline))

    // —— 动作 ——
    toggleButton = primaryButton(getString(R.string.cover_action_start)) { onToggle() }
    restartButton = secondaryButton(getString(R.string.cover_action_restart)) { onRestartEngine() }
    column.addView(buttonRow(toggleButton, restartButton))
    column.addView(buttonRow(
      secondaryButton(getString(R.string.cover_action_full_ui)) { openFullUi() },
      secondaryButton(getString(R.string.ds_open_console)) { openConsole() },
    ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6f) })

    // —— 设置卡 ——
    val settings = card(hairline)
    settings.addView(sectionTitle("保活与权限"))
    batteryRow = statusRowView("电池优化白名单", "合盖后系统不冻结引擎的前提；未加入时后台会被 Doze 限制") { openBatterySettings() }
    storageRow = statusRowView("所有文件访问", "外部工作区 / 日志落公共目录所需（特殊权限）") { openAllFilesAccess() }
    a11yRow = statusRowView("无障碍设备控制", "AI 读屏与操作控件的通道（DSH 设备控制）") { openAccessibility() }
    notifyRow = statusRowView("通知权限", "任务完成 / 审批提问 / 保活通知都靠它") { openNotificationSettings() }
    adbRow = statusRowView("ADB 无线调试通道", "—") { startSafely(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), "无法打开开发者选项") }
    for (r in listOf(batteryRow, storageRow, a11yRow, notifyRow, adbRow)) settings.addView(r.root)

    settings.addView(divider(hairline))
    settings.addView(sectionTitle("界面与调试"))
    overlaySwitch = Switch(this)
    immersiveSwitch = Switch(this)
    devLogSwitch = Switch(this)
    settings.addView(switchRow("悬浮球", "任意界面实时查看 AI 工作，可一键停止", overlaySwitch))
    settings.addView(switchRow("沉浸式状态栏", "完整界面的状态栏常态收起（边缘下滑临时呼出）", immersiveSwitch))
    settings.addView(switchRow("开发者日志", "按天写入 Documents/dshdata/log/（含命令与模型内容）", devLogSwitch))

    settings.addView(divider(hairline))
    settings.addView(sectionTitle("配置与更新"))
    settings.addView(buttonRow(
      secondaryButton("导出配置") { exportConfig() },
      secondaryButton("导入配置") { importConfig() },
    ))
    settings.addView(buttonRow(
      secondaryButton(getString(R.string.ds_check_update)) { checkUpdate() },
      secondaryButton("刷新状态") { sampleAsync(); refreshSettingsRows() },
    ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6f) })
    settings.addView(label(
      "模型 / 供应商 / 密钥 / 插件等引擎侧设置：点「完整界面」在本屏打开 DeepCode 设置页。",
      11f, R.color.ds_text_tertiary,
    ).apply { setPadding(0, dp(10f), 0, 0); setLineSpacing(0f, 1.3f) })
    column.addView(wrapShell(settings, hairline).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12f) })

    displayInfo = label("", 10f, R.color.ds_text_tertiary).apply { setPadding(dp(4f), dp(8f), dp(4f), dp(4f)) }
    column.addView(displayInfo)

    val scroll = ScrollView(this).apply {
      isFillViewport = true
      overScrollMode = View.OVER_SCROLL_NEVER
      addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // 内屏/平板上限宽居中（与引导页 ds_guide_max_width 同口径）；外屏 360dp 宽时自然铺满。
    val maxWidth = resources.getDimensionPixelSize(R.dimen.ds_guide_max_width)
    outer.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
      gravity = Gravity.CENTER_HORIZONTAL
    })
    outer.viewTreeObserver.addOnGlobalLayoutListener {
      val avail = outer.width - outer.paddingLeft - outer.paddingRight
      val lp = scroll.layoutParams as FrameLayout.LayoutParams
      val want = if (avail > maxWidth) maxWidth else ViewGroup.LayoutParams.MATCH_PARENT
      if (lp.width != want) { lp.width = want; scroll.layoutParams = lp }
    }
    return outer
  }

  private fun label(text: String, sp: Float, colorId: Int, type: Typeface? = null): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color(colorId))
    if (type != null) typeface = type
  }

  private fun card(hairline: Int): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
    background = DsUi.roundRect(color(R.color.ds_surface), dim(R.dimen.ds_radius_md), color(R.color.ds_hairline), hairline)
  }

  private fun wrapShell(inner: View, hairline: Int): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
    background = DsUi.roundRect(color(R.color.ds_shell), dim(R.dimen.ds_radius_md) + dp(2f), color(R.color.ds_border), hairline)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  }

  private fun sectionTitle(text: String): TextView = label(text, 11f, R.color.ds_text_tertiary, typeMedium()).apply {
    letterSpacing = 0.04f
    setPadding(0, dp(2f), 0, dp(4f))
  }

  private fun divider(hairline: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairline).apply {
      topMargin = dp(10f); bottomMargin = dp(10f)
    }
    setBackgroundColor(color(R.color.ds_hairline))
  }

  /** 左：标题 + 副标题；右：状态文字；整行可点（≥ 48dp 触控高）。 */
  private fun statusRowView(title: String, subtitle: String, onClick: () -> Unit): RowHandle {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = dp(48f)
      setPadding(0, dp(6f), 0, dp(6f))
      isClickable = true
      isFocusable = true
      background = DsUi.ripple(DsUi.roundRect(0, dim(R.dimen.ds_radius_sm)), color(R.color.ds_chip))
      setOnClickListener { onClick() }
    }
    val texts = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(label(title, 14f, R.color.ds_text_primary))
    val sub = label(subtitle, 11f, R.color.ds_text_tertiary).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(1f), 0, 0) }
    texts.addView(sub)
    val trailing = label("—", 12f, R.color.ds_text_secondary, typeMedium()).apply {
      setPadding(dp(10f), 0, 0, 0)
      maxLines = 1
    }
    row.addView(texts)
    row.addView(trailing)
    return RowHandle(row, sub, trailing)
  }

  private fun switchRow(title: String, subtitle: String, sw: Switch): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = dp(48f)
      setPadding(0, dp(6f), 0, dp(6f))
    }
    val texts = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(label(title, 14f, R.color.ds_text_primary))
    texts.addView(label(subtitle, 11f, R.color.ds_text_tertiary).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(1f), 0, 0) })
    row.addView(texts)
    row.addView(sw.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) } })
    return row
  }

  private fun buttonRow(left: Button, right: Button): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)).apply { topMargin = dp(10f) }
    addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(8f) })
  }

  private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setTextColor(color(R.color.ds_text_on_accent))
    typeface = typeMedium()
    stateListAnimator = null
    background = DsUi.ripple(DsUi.roundRect(color(R.color.ds_accent), dim(R.dimen.ds_radius_pill)), color(R.color.ds_accent_pressed))
    DsUi.bindPressScale(this)
    setOnClickListener { onClick() }
  }

  private fun secondaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(color(R.color.ds_text_primary))
    typeface = typeMedium()
    stateListAnimator = null
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    background = DsUi.ripple(DsUi.roundRect(color(R.color.ds_surface_muted), dim(R.dimen.ds_radius_pill)), color(R.color.ds_chip))
    DsUi.bindPressScale(this, 0.97f)
    setOnClickListener { onClick() }
  }

  companion object {
    private const val TAG = "dsh-cover"
  }
}
