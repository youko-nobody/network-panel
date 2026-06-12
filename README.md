# 网络面板

网络面板是一个原生 Android 网络流量工具，适合查看实时网速、累计流量、本次消耗和运行线程，并提供流量线路、速率上限、流量上限、锁屏运行和多主题切换等功能。

## 下载

游客可以在 GitHub Releases 页面直接下载 APK：

[下载最新版 APK](../../releases/latest)

如果还没有 Release，请先查看 [发布指南](docs/RELEASE.md) 生成并上传正式 APK。

## 功能

- 实时网速：同时显示 `MB/s` 和 `Mbps`
- 流量统计：显示总流量和本次消耗，支持 TB 级显示
- 跑流量控制：一个主按钮完成开始和暂停
- 线路选择：支持切换和管理流量线路
- 运行设置：线程数、速率上限、本次流量上限、锁屏运行、增强并发、通知栏控制
- 延迟检测：支持地区延迟检测展示
- 主题切换：内置多套浅色、深色和季节配色主题

## 截图

项目发布后可以在 README 中补充正式截图。请不要提交包含私人信息、聊天记录、设备信息或未授权素材的图片。

## 构建

推荐使用 Android Studio 打开项目，或使用 Gradle Wrapper 构建。

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

生成的 debug APK 位于：

```text
app/build/outputs/apk/debug/
```

## 发布正式 APK

正式 APK 应使用本地 keystore 签名，签名文件和密码不要提交到仓库。

详细步骤见：

[docs/RELEASE.md](docs/RELEASE.md)

## 开源内容

本仓库包含：

- 原生 Android Java 源码
- Gradle 构建配置
- GitHub Actions 构建和发布流程
- MIT License
- 贡献、安全、隐私和发布说明

本仓库不包含：

- APK / AAB 构建产物
- keystore / 签名证书 / 密码
- 本地配置文件
- 参考 APK 拆包内容
- 私人截图或个人信息

## 隐私

网络面板不包含广告 SDK、统计 SDK 或第三方追踪服务。更多说明见 [PRIVACY.md](PRIVACY.md)。

## License

MIT License. See [LICENSE](LICENSE).
