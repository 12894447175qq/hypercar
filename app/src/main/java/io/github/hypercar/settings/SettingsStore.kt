package io.github.hypercar.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置仓库（SharedPreferences 落盘；UI 只依赖这里的默认值与键名）。
 * 与 UI（子代理E）的契约：全部属性为 suspend-free 同步读，写入立即生效并广播 Flow。
 */
object SettingsStore {

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences("hypercar_settings", Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun p(): SharedPreferences = requireNotNull(prefs) { "SettingsStore.init(context) 未调用" }

    private const val KEY_TARGET_MAC = "target_car_mac"
    private const val KEY_TARGET_NAME = "target_car_name"
    private const val KEY_AUTO_CONNECT = "auto_connect_on_bt"
    private const val KEY_SCREEN_OFF_ON_CONNECT = "screen_off_on_connect"
    private const val KEY_WIDTH = "carlife_width"
    private const val KEY_HEIGHT = "carlife_height"
    private const val KEY_FPS = "carlife_fps"
    private const val KEY_ENABLE_AUDIO = "enable_audio"
    private const val KEY_CARLIFE_PKG = "carlife_package"
    private const val KEY_KEEP_ALIVE_STATS = "keep_alive_stats"
    private const val KEY_DEBUG_LOG = "debug_log_enabled"
    private const val KEY_STABLE_DEVICE_ID = "stable_device_id"
    private const val KEY_DEGRADED_FIRST_CONNECT = "degraded_first_connect_done"

    /** 目标车机（多车场景）：null = 自动（来者不拒）。 */
    var targetCarMac: String?
        get() = p().getString(KEY_TARGET_MAC, null)
        set(v) = p().edit().putString(KEY_TARGET_MAC, v).apply()

    var targetCarName: String?
        get() = p().getString(KEY_TARGET_NAME, null)
        set(v) = p().edit().putString(KEY_TARGET_NAME, v).apply()

    /** 上车自动连接（ACL 广播唤醒），默认开。 */
    var autoConnect: Boolean
        get() = p().getBoolean(KEY_AUTO_CONNECT, true)
        set(v) = p().edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    /** 推流时手机面板降亮度（本项目无虚拟屏，只做省电提示，不做物理断电）。 */
    var dimWhileConnected: Boolean
        get() = p().getBoolean(KEY_SCREEN_OFF_ON_CONNECT, true)
        set(v) = p().edit().putBoolean(KEY_SCREEN_OFF_ON_CONNECT, v).apply()

    /**
     * CarLife 编码分辨率（下发 0x10007 video encoder init）。
     * 宝马 iD8.5 曲面屏默认 1920×720（偶数、16:6 带鱼比；实车可视区域可在此微调）。
     */
    var carlifeWidth: Int
        get() = p().getInt(KEY_WIDTH, 1920)
        set(v) = p().edit().putInt(KEY_WIDTH, v.coerceAtLeast(2)).apply()

    var carlifeHeight: Int
        get() = p().getInt(KEY_HEIGHT, 720)
        set(v) = p().edit().putInt(KEY_HEIGHT, v.coerceAtLeast(2)).apply()

    var carlifeFps: Int
        get() = p().getInt(KEY_FPS, 60)
        set(v) = p().edit().putInt(KEY_FPS, v.coerceIn(20, 60)).apply()

    var enableAudio: Boolean
        get() = p().getBoolean(KEY_ENABLE_AUDIO, true)
        set(v) = p().edit().putBoolean(KEY_ENABLE_AUDIO, v).apply()

    /** 唤醒目标 CarLife 系 app 的包名（CarLife / CarWith 的 CarLife 兼容 / 荣耀车联）。 */
    var carlifePackage: String
        get() = p().getString(KEY_CARLIFE_PKG, "com.baidu.carlife") ?: "com.baidu.carlife"
        set(v) = p().edit().putString(KEY_CARLIFE_PKG, v).apply()

    /** 车机 /info 声明 keepAliveSendStatsAsBody 时置 true（屏幕 keepAlive 带统计体）。 */
    var keepAliveStats: Boolean
        get() = p().getBoolean(KEY_KEEP_ALIVE_STATS, false)
        set(v) = p().edit().putBoolean(KEY_KEEP_ALIVE_STATS, v).apply()

    /** 调试日志记录开关（诊断页切换；关=暂停写入环形缓冲）。 */
    var debugLogEnabled: Boolean
        get() = p().getBoolean(KEY_DEBUG_LOG, true)
        set(v) = p().edit().putBoolean(KEY_DEBUG_LOG, v).apply()

    /**
     * 无 Shizuku 时用的稳定 deviceID（首次生成后持久化，格式仿 MAC）。
     *
     * Android 11+ 把本机真实蓝牙 MAC 锁死（无 root/Shizuku 拿不到）。AirPlay 会话级
     * SETUP 的 deviceID、iAP2 0x4300/0x4E0C/0x4E0E 的传输标识都要它。WinPlay 会按
     * deviceID 严格 403、奥迪校验 0x4300，但宝马 iD8.5 实测未出现 MAC 校验——
     * 故无 Shizuku 时退化为一个「本地管理位置位」的稳定伪 MAC，保证同一台手机跨会话
     * 标识一致（比恒定占位 02:00:00:00:00:00 更不易被当占位值丢弃）。
     */
    val stableDeviceId: String
        get() {
            p().getString(KEY_STABLE_DEVICE_ID, null)?.let { return it }
            // 首次生成：本地管理位置位(0x02) + 单播位清零，规避与真实 MAC 撞车
            val bytes = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            val mac = bytes.joinToString(":") { "%02X".format(it) }
            p().edit().putString(KEY_STABLE_DEVICE_ID, mac).apply()
            return mac
        }

    /**
     * 无 Shizuku 降级模式下，是否已完成过一次「前台首连」（系统确认框已批准）。
     *
     * WifiNetworkSpecifier 每个热点**首次**连接弹一次系统确认框，且后台 FGS 拉起时
     * 不保证弹得出。所以推荐装好后在前台手动连一次完成批准；本标记用于 UI 判断是否
     * 还需要提示用户「去前台完成首连」。只在降级模式有意义，装了 Shizuku 就不再看它。
     */
    var degradedFirstConnectDone: Boolean
        get() = p().getBoolean(KEY_DEGRADED_FIRST_CONNECT, false)
        set(v) = p().edit().putBoolean(KEY_DEGRADED_FIRST_CONNECT, v).apply()

    /** 引擎配置 JSON（Rust EngineConfig::from_json 消费）。 */
    fun engineConfigJson(carIp: String, airplayPort: Int, deviceBluetoothMac: String, identityPath: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{")
            append("\"device_id\":\"").append(esc(deviceBluetoothMac)).append("\",")
            append("\"car_ip\":\"").append(esc(carIp)).append("\",")
            append("\"airplay_port\":").append(airplayPort).append(",")
            append("\"width\":").append(carlifeWidth.coerceAtLeast(2) / 2 * 2).append(",")
            append("\"height\":").append(carlifeHeight.coerceAtLeast(2) / 2 * 2).append(",")
            append("\"fps\":").append(carlifeFps).append(",")
            append("\"enable_audio\":").append(enableAudio).append(",")
            append("\"keep_alive_stats\":").append(keepAliveStats).append(",")
            append("\"identity_path\":\"").append(esc(identityPath)).append("\"")
            append("}")
        }
    }
}
