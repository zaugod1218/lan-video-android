# 局域网种子影院

这是一个 Android 客户端和轻量电脑服务端组成的局域网视频播放器。电脑扫描
`.torrent` 元数据及其已经下载完成的视频数据，手机通过 HTTP Range 读取并播放。

> 仅用于你拥有或获授权的内容。当前 MVP 不在手机上下载 BitTorrent 数据，也不会
> 绕过 DRM；视频数据需已存在于电脑共享目录中。

## 运行电脑服务

需要 Python 3.10+。自动发现是可选功能：

最简单的方法是双击：

```text
pc-server\一键启动服务.bat
```

首次启动会弹出窗口选择 `.torrent` 和视频所在目录，并把选择保存到
`pc-server\server-config.json`。以后只需再次双击即可。

也可以使用命令行：

```powershell
cd pc-server
python -m pip install -r requirements.txt
.\start-server.ps1 -MediaDirectory "D:\你的种子和视频目录"
```

也可不安装依赖，直接运行 `python lan_torrent_server.py "D:\目录"`，然后在 App
输入电脑的局域网地址，例如 `192.168.1.10:8787`。

目录既可包含 `.torrent` 和同名数据目录，也可直接包含视频文件。服务默认仅接受
私有/回环 IP。若局域网并非完全可信，启动时传入 `-Token "一段随机字符"`，并在
App 填写相同令牌。

## 构建 Android App

用 Android Studio 打开项目，等待同步后运行 `app`。命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

要求 JDK 17、Android SDK 35。首次构建需要联网下载 Gradle 和 Android 依赖。

## 当前能力

- Android NSD/mDNS 自动发现，也支持手动填写服务器地址
- 按 `.torrent` 元数据归组视频
- MP4、MKV、WebM、AVI、MOV、M2TS 等常见扩展名
- HTTP Range 播放和进度条跳转
- 可选 Bearer Token

下一阶段可在电脑服务中接入 qBittorrent Web API 或 libtorrent 的 piece deadline，
实现未下载完成资源的边下边播；Android API 和播放界面可保持不变。
