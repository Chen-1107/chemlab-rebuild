# package-release.ps1 - 打包 ChemLab Analyzer Windows 便携发行版
#
# 用法: powershell -ExecutionPolicy Bypass -File package-release.ps1 [版本号]
# 默认版本号 1.0.0，输出到 dist/release/ChemLab-Analyzer-<version>-win64.zip

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "[STEP] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }

$version = if ($args.Count -gt 0) { $args[0] } else { '1.0.0' }
$name = "ChemLab-Analyzer-$version-win64"
$outDir = Join-Path $root 'dist\release'
$stageRoot = Join-Path $outDir '.stage'
$stage = Join-Path $stageRoot $name
$outZip = Join-Path $outDir "$name.zip"

Step "打包版本: $version"
Step "输出: $outZip"

# ---- 校验构建产物 ----
$required = @(
    'ChemLab Analyzer.exe',
    'app-java\build\classes',
    'app-java\lib\sqlite-jdbc-3.46.1.3.jar',
    'dist\native\Release\chemlab_core.dll',
    'dist\native\Release\chemlab_jni.dll',
    'analysis-python\service.py',
    'analysis-python\spectrum.py',
    'analysis-python\notebook.py',
    'analysis-python\cbridge.py',
    'app-java\resources\kb.json',
    'app-java\resources\kb\index.json'
)

$missing = $false
foreach ($f in $required) {
    $p = Join-Path $root $f
    if (-not (Test-Path $p)) {
        Write-Host "[FAIL] 缺失: $f" -ForegroundColor Red
        $missing = $true
    }
}
if ($missing) { Fail "请先运行 build-all.ps1 完成构建。" }

# ---- 清理并创建 stage ----
if (Test-Path $stageRoot) { Remove-Item -Recurse -Force $stageRoot }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# ---- 复制文件 ----
Copy-Item (Join-Path $root 'ChemLab Analyzer.exe') $stage
Copy-Item (Join-Path $root 'README.md') $stage
Copy-Item (Join-Path $root 'run.ps1') $stage
Copy-Item (Join-Path $root 'verify.ps1') $stage

New-Item -ItemType Directory -Force -Path (Join-Path $stage 'app-java\build\classes') | Out-Null
Copy-Item -Recurse (Join-Path $root 'app-java\build\classes\*') (Join-Path $stage 'app-java\build\classes')
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'app-java\lib') | Out-Null
Copy-Item (Join-Path $root 'app-java\lib\sqlite-jdbc-3.46.1.3.jar') (Join-Path $stage 'app-java\lib')
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'app-java\resources\kb') | Out-Null
Copy-Item (Join-Path $root 'app-java\resources\kb.json') (Join-Path $stage 'app-java\resources')
Copy-Item -Recurse (Join-Path $root 'app-java\resources\kb\*') (Join-Path $stage 'app-java\resources\kb')

New-Item -ItemType Directory -Force -Path (Join-Path $stage 'dist\native\Release') | Out-Null
Copy-Item (Join-Path $root 'dist\native\Release\chemlab_core.dll') (Join-Path $stage 'dist\native\Release')
Copy-Item (Join-Path $root 'dist\native\Release\chemlab_jni.dll') (Join-Path $stage 'dist\native\Release')
Copy-Item (Join-Path $root 'dist\native\Release\chemlab_cli.exe') (Join-Path $stage 'dist\native\Release')

New-Item -ItemType Directory -Force -Path (Join-Path $stage 'analysis-python') | Out-Null
Copy-Item (Join-Path $root 'analysis-python\*.py') (Join-Path $stage 'analysis-python')

# ---- 数据目录留空模板 ----
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'data') | Out-Null
@'{
  "clientId": "",
  "apiKey": "",
  "knowledgeBaseId": "",
  "knowledgeBaseName": "",
  "folderId": "",
  "timeoutMs": 8000,
  "autoSync": false
}
'@ | Set-Content -Encoding UTF8 (Join-Path $stage 'data\ima.json')

# ---- 写入版本信息 ----
$version | Set-Content -Encoding UTF8 (Join-Path $stage 'VERSION')

# ---- 打包 ----
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
if (Test-Path $outZip) { Remove-Item -Force $outZip }

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $outZip, 'Optimal', $false)

Ok "发行包已生成: $outZip"
Get-Item $outZip | Select-Object Length, LastWriteTime, Name | Format-Table -AutoSize
