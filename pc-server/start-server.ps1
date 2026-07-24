param(
    [Parameter(Mandatory = $true)]
    [string]$MediaDirectory,
    [int]$Port = 8787,
    [string]$Token = ""
)

$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "lan_torrent_server.py"
python $scriptPath $MediaDirectory --port $Port --token $Token
