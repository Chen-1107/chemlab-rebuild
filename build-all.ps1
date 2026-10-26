# build-all.ps1 - build the whole ChemLab Analyzer stack.
#
# Layers:
#   1. C++ core (core-cpp)  -> dist/native/Release/chemlab_core.dll + chemlab_jni.dll
#   2. Java app  (app-java) -> app-java/build/classes
#   3. Launcher  (launcher) -> ChemLab Analyzer.exe（项目根目录，双击启动）
#
# Requirements: JDK (javac on PATH), CMake, Visual Studio C++ toolchain, Python 3.
# Usage:  powershell -ExecutionPolicy Bypass -File build-all.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "[STEP] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }

# ---- resolve JDK ---------------------------------------------------------
$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javacCmd) { Fail "javac not found on PATH. Install a JDK 17+ and retry." }
$javacExe = $javacCmd.Source
$jdkHome = Split-Path (Split-Path $javacExe -Parent) -Parent
if (-not (Test-Path (Join-Path $jdkHome 'include\jni.h'))) {
    Fail "jni.h not found under $jdkHome. Point JAVA_HOME at a full JDK."
}
Step "JDK: $jdkHome"

# ---- 1. C++ core ---------------------------------------------------------
Step "Configuring C++ core with CMake"
cmake -S "$root\core-cpp" -B "$root\build-core" -DJAVA_HOME="$jdkHome"
if ($LASTEXITCODE -ne 0) { Fail "CMake configure failed" }

Step "Building C++ core (Release)"
cmake --build "$root\build-core" --config Release
if ($LASTEXITCODE -ne 0) { Fail "C++ build failed" }

$coreDll = "$root\dist\native\Release\chemlab_core.dll"
$jniDll  = "$root\dist\native\Release\chemlab_jni.dll"
if (-not (Test-Path $coreDll)) { Fail "chemlab_core.dll not produced" }
if (-not (Test-Path $jniDll))  { Fail "chemlab_jni.dll not produced" }
Ok "Native libraries: $coreDll ; $jniDll"

# ---- 2. Java app ---------------------------------------------------------
Step "Compiling Java sources"
$srcDir = "$root\app-java\src"
$outDir = "$root\app-java\build\classes"
$sqliteJar = "$root\app-java\lib\sqlite-jdbc-3.46.1.3.jar"
if (-not (Test-Path $sqliteJar)) { Fail "sqlite-jdbc jar missing at $sqliteJar" }

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$javaFiles = Get-ChildItem -Recurse -Filter *.java -Path $srcDir | ForEach-Object { $_.FullName }
if ($javaFiles.Count -eq 0) { Fail "No .java sources found under $srcDir" }

& $javacExe -encoding UTF-8 -d $outDir -cp $sqliteJar $javaFiles
if ($LASTEXITCODE -ne 0) { Fail "javac failed" }
Ok "Java classes: $outDir"

# ---- 3. Desktop launcher -------------------------------------------------
Step "Building desktop launcher"
cmake -S "$root\launcher" -B "$root\build-launcher"
if ($LASTEXITCODE -ne 0) { Fail "Launcher CMake configure failed" }

cmake --build "$root\build-launcher" --config Release
if ($LASTEXITCODE -ne 0) { Fail "Launcher build failed" }

$launcherExe = "$root\ChemLab Analyzer.exe"
if (-not (Test-Path $launcherExe)) { Fail "Launcher exe not produced" }
Ok "Launcher: $launcherExe"

Write-Host ""
Ok "Build complete. Launch with:  ChemLab Analyzer.exe   (or run.ps1)"