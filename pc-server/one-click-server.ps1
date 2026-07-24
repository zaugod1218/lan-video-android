$ErrorActionPreference = "Stop"
$Host.UI.RawUI.WindowTitle = "局域网种子影院 - 电脑服务"

$configPath = Join-Path $PSScriptRoot "server-config.json"
$serverPath = Join-Path $PSScriptRoot "lan_torrent_server.py"
$requirementsPath = Join-Path $PSScriptRoot "requirements.txt"

function Find-Python {
    foreach ($command in @("python", "py")) {
        $found = Get-Command $command -ErrorAction SilentlyContinue
        if ($found) {
            return $command
        }
    }
    throw "没有找到 Python。请先从 https://www.python.org/downloads/ 安装 Python 3.10 或更高版本，并勾选 Add Python to PATH。"
}

function Select-MediaDirectory {
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = "选择存放 .torrent 和视频文件的目录"
    $dialog.ShowNewFolderButton = $true
    if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
        throw "没有选择媒体目录。"
    }
    return $dialog.SelectedPath
}

function Get-LocalIPv4 {
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.IPAddress -notlike "169.254.*" -and
            $_.InterfaceAlias -notmatch "Loopback|Virtual|VPN|TAP"
        } |
        Sort-Object InterfaceMetric
    return $addresses | Select-Object -First 1 -ExpandProperty IPAddress
}

$python = Find-Python
$config = $null
if (Test-Path -LiteralPath $configPath) {
    try {
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    } catch {
        Write-Warning "原配置文件无效，将重新配置。"
    }
}

if (-not $config -or -not $config.mediaDirectory -or
    -not (Test-Path -LiteralPath $config.mediaDirectory -PathType Container)) {
    $mediaDirectory = Select-MediaDirectory
    $config = [PSCustomObject]@{
        mediaDirectory = $mediaDirectory
        port = 8787
        token = ""
    }
    $config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    Write-Host "配置已保存到 $configPath" -ForegroundColor Green
}

if (-not $config.port) {
    $config | Add-Member -NotePropertyName port -NotePropertyValue 8787 -Force
}

Write-Host ""
Write-Host "正在检查自动发现组件..." -ForegroundColor Cyan
& $python -c "import zeroconf" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "首次运行，正在安装自动发现组件..." -ForegroundColor Yellow
    & $python -m pip install --user -r $requirementsPath
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "自动发现组件安装失败，仍可在手机中手动输入地址。"
    }
}

$localIp = Get-LocalIPv4
Write-Host ""
Write-Host "============================================" -ForegroundColor DarkCyan
Write-Host "  局域网种子影院电脑服务" -ForegroundColor Green
Write-Host "  共享目录：$($config.mediaDirectory)"
if ($localIp) {
    Write-Host "  手机地址：http://${localIp}:$($config.port)" -ForegroundColor Yellow
} else {
    Write-Host "  未能自动获取电脑 IP，请运行 ipconfig 查看。" -ForegroundColor Yellow
}
Write-Host "  关闭此窗口即可停止服务" -ForegroundColor Gray
Write-Host "============================================" -ForegroundColor DarkCyan
Write-Host ""

$arguments = @(
    $serverPath,
    [string]$config.mediaDirectory,
    "--port",
    [string]$config.port
)
if ($config.token) {
    $arguments += @("--token", [string]$config.token)
}

& $python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "服务异常退出，错误代码：$LASTEXITCODE"
}
