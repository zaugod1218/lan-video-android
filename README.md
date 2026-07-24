# 局域网种子影院

这是一个 Android 客户端和电脑服务端组成的局域网视频播放器。电脑扫描
`.torrent` 元数据，内置 BitTorrent 引擎按播放器读取位置下载所需分片，手机通过
HTTP Range 边下边播；已经存在的本地数据会直接复用。

> 仅用于你拥有或获授权的内容，不会绕过 DRM。下载和缓存发生在电脑上，手机只播放
> 电脑提供的局域网流。

## 运行电脑服务

需要 Python 3.10+。自动发现是可选功能：

最简单的方法是双击：

```text
pc-server\一键启动服务.bat
```

首次启动会弹出窗口选择 `.torrent` 和视频所在目录，并把选择保存到
`pc-server\server-config.json`。以后每次启动都会显示当前绑定目录，可选择继续使用、
更换目录或取消启动。

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
- 读取并显示 `.torrent` 内的视频清单，按种子名称归组
- 内置按需 BitTorrent 引擎，支持未下载完成的视频边下边播
- HTTP Range 拖动会调整按需读取位置；启动速度取决于种子健康度和网络速度
- MP4、MKV、WebM、AVI、MOV、M2TS 等常见扩展名
- HTTP Range 播放和进度条跳转
- 可选 Bearer Token

当前内置引擎优先支持 BitTorrent v1 和混合种子。没有可用做种者、下载速度低于视频
码率、私有站要求登录 Cookie，或视频编码不受手机支持时，可能无法流畅播放。
