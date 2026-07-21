# 局域网视频 Android App

这个项目让安卓手机浏览电脑文件夹中的视频、图片和 PDF。手机和电脑需要连接同一个 Wi-Fi。

## 1. 在电脑上共享视频文件夹

电脑端已固定共享 `D:\yingshiapp`。可以直接双击：

```text
pc-server\start_server.bat
```

也可以在 PowerShell 中运行：

```powershell
python .\pc-server\video_server.py
```

终端会显示类似 `http://192.168.1.10:8787` 的地址。首次运行时，Windows 防火墙可能询问是否允许访问，请只勾选“专用网络”。

## 2. 构建安卓 App

1. 使用 Android Studio 打开本项目。
2. 等待 Gradle 同步完成。
3. 连接安卓手机并点击 Run，或用 **Build > Build APK(s)** 生成 APK。
4. 在 App 中填入电脑终端显示的地址，点击“连接并刷新”。

## 当前功能

- 递归扫描电脑文件夹中的视频、图片和 PDF
- 视频自动截取真实画面作为预览封面
- 卡片网格与视频/图片/PDF 分类筛选
- 流式播放，无需先下载完整视频
- 支持拖动播放进度（HTTP Range）
- 全屏查看图片，在 App 内逐页阅读 PDF
- 自动记住上次连接地址

## 注意

这是家庭局域网 MVP，没有账号和加密。不要把 8787 端口暴露到公网。电脑休眠、切换 Wi-Fi 或 IP 改变后，需要重新启动服务或更新 App 地址。

## 使用 GitHub 自动生成 APK

把整个项目上传到 GitHub 的 `main` 或 `master` 分支后，GitHub Actions 会自动构建：

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build Android APK**。
3. 等待任务显示绿色对勾。
4. 打开该次任务，在页面底部下载 `LanVideo-debug-apk`。
5. 解压后得到 `app-debug.apk`，传到安卓手机安装。

也可以在 Actions 页面点击 **Run workflow** 手动重新生成。
