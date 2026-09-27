# iQOO Neo10 充电监测

一款专为 iQOO Neo10（OriginOS）设计的充电监测应用，实时显示电池参数并记录充电历史。无需 ROOT，数据全部本地存储。

## 主要功能

- **实时监测**：电量、双电芯总电压、总电流、实时功率、电池温度、充电来源、充电状态
- **充电历史**：自动记录每次充电的开始/结束时间、起止电量、峰值功率、最高温度
- **实时曲线**：电流 / 功率 / 温度三条曲线，X 轴以 15 分钟为刻度显示实际时间（HH:mm），1 分钟采样一次
- **历史详情**：单次充电的完整曲线回放与统计
- **主题切换**：浅色模式 / 深色模式，支持记忆偏好
- **通知提醒**：充电中常驻通知、电量充满提醒、电池高温（≥42°C）提醒
- **数据导出**：支持将充电记录导出为 CSV

## 使用说明

1. 安装 APK 后打开 App，主页即显示当前电池状态
2. 插上充电器后会自动开始记录（前台服务常驻通知）
3. 拔下充电器后服务自动停止并完成记录
4. 底部「历史」可查看历次充电记录，点击进入查看详情曲线
5. 右上角菜单进入「设置」切换主题或导出 CSV

## 适配说明

- 仅适配 **iQOO Neo10**（双电芯串联 2S 机型）
- OriginOS 对普通应用完全屏蔽 `/sys/class/power_supply`，因此电流读取使用 Android 公开 API `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)`，电压通过 `EXTRA_VOLTAGE × 2` 得到电芯组总电压
- 在其他机型上可能无法获取完整数据

## 权限说明

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`：充电监测常驻服务
- `POST_NOTIFICATIONS`：充电状态、充满、高温通知
- `RECEIVE_BOOT_COMPLETED`：开机自启监测服务
- 不申请网络权限，数据不离开设备

## 技术栈

- Kotlin + Coroutines
- Material Design 3（DayNight 主题）
- Room 数据库本地持久化
- MPAndroidChart 曲线绘制
- Single Activity + Fragment 架构
- GitHub Actions CI/CD（推送 `v*.*.*` 标签自动构建 Release APK）

## 下载

前往 [Releases 页面](https://github.com/hopeblue1024/iqoo-neo10-charge-monitor/releases) 下载最新版本 `app-release.apk`。
