# run.ps1 - launch the ChemLab Analyzer desktop app.
#
# Assumes build-all.ps1 has been run at least once.
# Usage:  powershell -ExecutionPolicy Bypass -File run.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }

$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javacCmd) { Fail "javac not found on PATH. Install a JDK 17+ and retry." }
$jdkHome = Split-Path (Split-Path $javacCmd.Source -Parent) -Parent
$java = Join-Path $jdkHome 'bin\java.exe'

$classes = "$root\app-java\build\classes"
$sqliteJar = "$root\app-java\lib\sqlite-jdbc-3.46.1.3.jar"
$nativeDir = "$root\dist\native\Release"

if (-not (Test-Path $classes)) { Fail "Java classes missing. Run build-all.ps1 first." }
if (-not (Test-Path "$nativeDir\chemlab_jni.dll")) { Fail "chemlab_jni.dll missing. Run build-all.ps1 first." }

$cp = "$classes;$sqliteJar"

# 优先挑一个能 import cv2 的解释器：PATH 上可能同时存在多个 Python，
# 缺少 OpenCV 的那个会导致光谱分析降级。
function Test-VisionPython($exe) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        & $exe -c "import cv2, numpy" *> $null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $eap
    }
}

$visionPython = $null
$cands = @()
if ($env:CHEMLAB_PYTHON) { $cands += $env:CHEMLAB_PYTHON }
$cands += 'python', 'python3', 'py'
foreach ($c in $cands) {
    $exe = (Get-Command $c -ErrorAction SilentlyContinue).Source
    if (-not $exe) { continue }
    if (Test-VisionPython $exe) { $visionPython = $exe; break }
}

$jvmArgs = @(
    "-Dchemlab.home=$root",
    "-Djava.library.path=$nativeDir",
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8"
)
if ($visionPython) {
    $jvmArgs += "-Dchemlab.python=$visionPython"
    Write-Host "[INFO] Python 分析解释器: $visionPython" -ForegroundColor Cyan
} else {
    Write-Host "[WARN] 未找到含 OpenCV/NumPy 的解释器，光谱分析将降级。" -ForegroundColor Yellow
}

& $java @jvmArgs -cp $cp com.chemlab.Main

exit $LASTEXITCODE