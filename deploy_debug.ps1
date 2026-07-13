<#
.SYNOPSIS
    部署 py2roid debug APK 到 Android 设备，通过 ADB intent extras 覆写运行时参数。

.DESCRIPTION
    安装 app-debug.apk → force-stop → am start 传入所有指定的 extra 参数。
    未传入的参数不会出现在 intent 中，SettingsStore 原有值不受影响。

.PARAMETER Serial
    ADB 设备序列号（如 192.168.1.100:39791）。
    缺省时使用单设备模式（无 -s 参数）。
    可通过环境变量 PY2ROID_SERIAL 设定默认值。

.PARAMETER Adb
    adb.exe 路径。
    缺省时依次尝试：$env:PY2ROID_ADB → adb（PATH）。
    可通过环境变量 PY2ROID_ADB 设定默认值。

.PARAMETER Apk
    APK 文件路径。
    缺省时自动推断：脚本所在目录/android/app/build/outputs/apk/debug/app-debug.apk

.PARAMETER Model
    模型文件名，如 yolov8n.tflite。

.PARAMETER Backend
    推理后端：Auto / CPU / XNNPACK / NNAPI / VCAP / TFLITE / TFLITE_GPU / TFLITE_NNAPI

.PARAMETER Confidence
    置信度阈值 (0.0~1.0)，如 0.5。

.PARAMETER Iou
    IoU 阈值 (0.0~1.0)，如 0.45。

.PARAMETER Comm
    通讯模式：USB / WiFi / Off

.PARAMETER Debug
    是否启用调试叠加层（Debug Overlay）。

.PARAMETER LogWeb
    日志推送 WebSocket 地址，如 http://192.168.1.100:8765。

.EXAMPLE
    # 最简用法：仅安装启动，无 extra
    .\deploy_debug.ps1

.EXAMPLE
    # 指定设备和日志推送
    .\deploy_debug.ps1 -Serial "192.168.1.100:39791" -LogWeb "http://192.168.1.100:8765"

.EXAMPLE
    # 全参数
    .\deploy_debug.ps1 -Serial "192.168.1.100:39791" `
        -Adb "F:\dev\trae\sbb-2\sdk\platform-tools\adb.exe" `
        -Model "yolov8n.tflite" -Backend TFLITE `
        -Confidence 0.5 -Iou 0.45 -Comm USB -Debug $true `
        -LogWeb "http://192.168.1.100:8765"

.EXAMPLE
    # 环境变量预设默认值
    # $env:PY2ROID_ADB = "F:\dev\trae\sbb-2\sdk\platform-tools\adb.exe"
    # $env:PY2ROID_SERIAL = "192.168.1.100:39791"
    .\deploy_debug.ps1 -Model "yolov8n.tflite" -Backend TFLITE
#>

param(
    [string]$Serial,
    [string]$Adb,
    [string]$Apk,
    [string]$Model,
    [string]$Backend,
    [float]$Confidence,
    [float]$Iou,
    [string]$Comm,
    [bool]$Debug,
    [string]$LogWeb
)

# ── 路径解析 ──
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ADB 路径
if (-not $Adb) { $Adb = $env:PY2ROID_ADB }
if (-not $Adb) { $Adb = "adb" }

# APK 路径
if (-not $Apk) {
    $Apk = Join-Path $ScriptDir "android\app\build\outputs\apk\debug\app-debug.apk"
}
if (-not (Test-Path $Apk)) {
    Write-Warning "APK not found: $Apk"
    Write-Warning "Build it first: cd android; .\gradlew assembleDebug"
    exit 1
}

# ── 设备选择 ──
if (-not $Serial) { $Serial = $env:PY2ROID_SERIAL }
$deviceArg = if ($Serial) { @("-s", $Serial) } else { @() }

# ── 构建 am start extras ──
$extras = @()

if ($Model)   { $extras += "--es"; $extras += "model";    $extras += $Model }
if ($Backend) { $extras += "--es"; $extras += "backend";  $extras += $Backend }
if ($PSBoundParameters.ContainsKey("Confidence")) {
    $extras += "--ef"; $extras += "confidence"; $extras += $Confidence
}
if ($PSBoundParameters.ContainsKey("Iou")) {
    $extras += "--ef"; $extras += "iou"; $extras += $Iou
}
if ($Comm)    { $extras += "--es"; $extras += "comm";     $extras += $Comm }
if ($PSBoundParameters.ContainsKey("Debug")) {
    $ezDebug = if ($Debug) { "true" } else { "false" }
    $extras += "--ez"; $extras += "debug";   $extras += $ezDebug
}
if ($LogWeb)  { $extras += "--es"; $extras += "log_web";  $extras += $LogWeb }

# ── 执行 ──
Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  py2roid Debug Deploy" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  ADB:      $Adb"
Write-Host "  APK:      $Apk"
if ($Serial) { Write-Host "  Device:   $Serial" }
Write-Host "  Extras:   $(if ($extras.Count -gt 0) { $extras -join ' ' } else { '(none)' })"
Write-Host "───────────────────────────────────────────" -ForegroundColor Gray

# Step 1: Install
Write-Host "» Installing APK..." -ForegroundColor Yellow
& $Adb $deviceArg install -r $Apk 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "Install failed (exit=$LASTEXITCODE)"
    exit $LASTEXITCODE
}

# Step 2: Force-stop
Write-Host "» Force-stop existing process..." -ForegroundColor Yellow
& $Adb $deviceArg shell am force-stop com.xz.py2roid 2>&1
Start-Sleep -Seconds 1

# Step 3: Start with extras
Write-Host "» Launching with extras..." -ForegroundColor Yellow
if ($extras.Count -gt 0) {
    & $Adb $deviceArg shell am start -n com.xz.py2roid/.MainActivity @extras 2>&1
} else {
    & $Adb $deviceArg shell am start -n com.xz.py2roid/.MainActivity 2>&1
}

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Deploy complete!" -ForegroundColor Green
} else {
    Write-Error "Launch failed (exit=$LASTEXITCODE)"
    exit $LASTEXITCODE
}
