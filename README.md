# 网络面板

一个原生 Android 网络流量面板，用于查看实时速率、累计流量、本次消耗、运行线程，并支持线路切换、速率上限、流量上限和主题切换。

## 功能

- 实时显示 `MB/s` 和 `Mbps`
- 开始 / 暂停跑流量
- 总流量与本次消耗统计
- 流量线路选择和管理
- 线程数设置
- 速率上限与本次流量上限
- 锁屏运行、增强并发、通知栏控制
- 多主题切换

## 构建

需要 Android SDK 和 Gradle。

```powershell
gradle assembleDebug
```

如果本机没有全局 Gradle，可以使用 Android Studio 打开项目后构建，或使用自己的 Gradle Wrapper。

## 发布说明

仓库不包含 APK、签名证书、keystore 或本地配置文件。正式发布 APK 时，请在本地自行创建签名文件并配置 release signing。

## License

MIT
