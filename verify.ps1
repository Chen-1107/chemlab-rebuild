# verify.ps1 - run the whole-stack self-check suite.
#
# Covers every cross-language link:
#   1. C++ kernel self-test            (chemlab_cli --selftest)
#   2. Python -> C++ via ctypes        (analysis-python/cbridge.py)
#   3. Python analysis service         (analysis-python/service.py --selftest)
#   4. Spectrum image analysis         (analysis-python/spectrum.py)  [needs cv2]
#   5. Java -> C++ via JNI             (tests/JniSmoke.java)
#   6. Java -> SQLite + Python bridge  (tests/Smoke.java)
#   7. Obsidian bidirectional sync     (tests/ObsidianSmoke.java)
#   8. Word report export              (tests/DocxSmoke.java)
#   9. Knowledge backends (local/ima)   (tests/KbSmoke.java)
#
# Requires build-all.ps1 to have been run at least once.
# Usage:  powershell -ExecutionPolicy Bypass -File verify.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 让中文自检输出在控制台正确显示
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$failures = 0
function Step($msg) { Write-Host "`n[STEP] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }
function Skip($msg) { Write-Host "[SKIP] $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; $script:failures++ }

# ---- resolve toolchain ---------------------------------------------------
$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javacCmd) { Write-Host "[FAIL] javac not found on PATH." -ForegroundColor Red; exit 1 }
$jdkHome = Split-Path (Split-Path $javacCmd.Source -Parent) -Parent
$javac = Join-Path $jdkHome 'bin\javac.exe'
$java  = Join-Path $jdkHome 'bin\java.exe'

$classes   = "$root\app-java\build\classes"
$sqliteJar = "$root\app-java\lib\sqlite-jdbc-3.46.1.3.jar"
$nativeDir = "$root\dist\native\Release"
$coreDll   = "$nativeDir\chemlab_core.dll"

if (-not (Test-Path "$classes\com\chemlab\Main.class")) { Write-Host "[FAIL] Java classes missing. Run build-all.ps1 first." -ForegroundColor Red; exit 1 }
if (-not (Test-Path $coreDll)) { Write-Host "[FAIL] chemlab_core.dll missing. Run build-all.ps1 first." -ForegroundColor Red; exit 1 }

$env:CHEMLAB_CORE_DLL = $coreDll

# 选择一个能 import cv2 的解释器（PATH 上可能同时存在多个 Python）
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

function Find-VisionPython {
    $cands = @()
    if ($env:CHEMLAB_PYTHON) { $cands += $env:CHEMLAB_PYTHON }
    $cands += 'python', 'python3', 'py'
    foreach ($c in $cands) {
        $exe = (Get-Command $c -ErrorAction SilentlyContinue).Source
        if (-not $exe) { continue }
        if (Test-VisionPython $exe) { return $exe }
    }
    return $null
}

# ---- 1. C++ kernel -------------------------------------------------------
Step "C++ kernel self-test"
& "$nativeDir\chemlab_cli.exe" --selftest | Select-Object -Last 2
if ($LASTEXITCODE -eq 0) { Ok "C++ kernel" } else { Fail "C++ kernel (exit $LASTEXITCODE)" }

# ---- 2. Python -> C++ (ctypes) -------------------------------------------
Step "Python -> C++ bridge (ctypes)"
& python "$root\analysis-python\cbridge.py" | Select-Object -Last 1
if ($LASTEXITCODE -eq 0) { Ok "ctypes bridge" } else { Fail "ctypes bridge (exit $LASTEXITCODE)" }

# ---- 3. Python service ---------------------------------------------------
Step "Python analysis service"
& python "$root\analysis-python\service.py" --selftest | Select-Object -Last 1
if ($LASTEXITCODE -eq 0) { Ok "service" } else { Fail "service (exit $LASTEXITCODE)" }

# ---- 4. Spectrum analysis (needs cv2) ------------------------------------
Step "Spectrum image analysis"
$visionPython = Find-VisionPython
if ($visionPython) {
    Write-Host "使用解释器: $visionPython"
    & $visionPython "$root\analysis-python\spectrum.py" | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0) { Ok "spectrum" } else { Fail "spectrum (exit $LASTEXITCODE)" }
} else {
    Skip "未找到含 cv2/numpy 的解释器，光谱分析不可用（应用会降级，记录本归纳不受影响）"
}

# ---- 5. Java -> C++ (JNI) ------------------------------------------------
Step "Java -> C++ (JNI)"
$testOut = "$root\tests\build"
New-Item -ItemType Directory -Force -Path $testOut | Out-Null
& $javac -encoding UTF-8 -cp $classes -d $testOut "$root\tests\JniSmoke.java"
if ($LASTEXITCODE -ne 0) { Fail "JniSmoke compile" } else {
    & $java "-Djava.library.path=$nativeDir" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" -cp "$testOut;$classes" JniSmoke | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0) { Ok "JNI" } else { Fail "JNI (exit $LASTEXITCODE)" }
}

# ---- 6. Java full stack (SQLite + Python bridge) -------------------------
Step "Java full stack (SQLite + Python bridge)"
$cp = "$classes;$sqliteJar"
& $javac -encoding UTF-8 -cp $cp -d $testOut "$root\tests\Smoke.java"
if ($LASTEXITCODE -ne 0) { Fail "Smoke compile" } else {
    & $java "-Dchemlab.home=$root" "-Djava.library.path=$nativeDir" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" -cp "$testOut;$cp" Smoke | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0) { Ok "full stack" } else { Fail "full stack (exit $LASTEXITCODE)" }
}

# ---- 7. Obsidian bidirectional sync --------------------------------------
Step "Obsidian bidirectional sync"
$cp = "$classes;$sqliteJar"
& $javac -encoding UTF-8 -cp $cp -d $testOut "$root\tests\ObsidianSmoke.java"
if ($LASTEXITCODE -ne 0) { Fail "ObsidianSmoke compile" } else {
    & $java "-Dchemlab.home=$root" "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" -cp "$testOut;$cp" ObsidianSmoke "$root" | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0) { Ok "Obsidian sync" } else { Fail "Obsidian sync (exit $LASTEXITCODE)" }
}

# ---- 8. Word report export (DocxWriter) -----------------------------------
Step "Word report export (DocxWriter)"
$cp = "$classes;$sqliteJar"
& $javac -encoding UTF-8 -cp $cp -d $testOut "$root\tests\DocxSmoke.java"
if ($LASTEXITCODE -ne 0) { Fail "DocxSmoke compile" } else {
    & $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" -cp "$testOut;$cp" DocxSmoke | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0) { Ok "Word report" } else { Fail "Word report (exit $LASTEXITCODE)" }
}

# ---- 9. Knowledge backends (local / ima) ---------------------------------
Step "Knowledge backends (local / ima)"
$cp = "$classes;$sqliteJar"
& $javac -encoding UTF-8 -cp $cp -d $testOut "$root\tests\KbSmoke.java"
if ($LASTEXITCODE -ne 0) { Fail "KbSmoke compile" } else {
    & $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" -cp "$testOut;$cp" KbSmoke "$root" | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0) { Ok "knowledge backends" } else { Fail "knowledge backends (exit $LASTEXITCODE)" }
}

# ---- summary -------------------------------------------------------------
Write-Host ""
if ($failures -eq 0) {
    Write-Host "[ OK ] All self-checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAIL] $failures check(s) failed." -ForegroundColor Red
    exit 1
}