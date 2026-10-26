#!/usr/bin/env bash
# package-release.sh - 打包 ChemLab Analyzer Windows 便携发行版
#
# 在 Linux/macOS 上把已有的 Windows 构建产物打包成 zip，供 GitHub Release 或分发。
# Windows 上请使用 package-release.ps1。

set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
cd "$root"

version="${1:-1.0.0}"
name="ChemLab-Analyzer-${version}-win64"
outDir="$root/dist/release"
outZip="$outDir/${name}.zip"
stage="$outDir/.stage/$name"

echo "[STEP] 打包版本: $version"
echo "[STEP] 输出: $outZip"

# ---- 校验构建产物 ----
required=(
    "ChemLab Analyzer.exe"
    "app-java/build/classes"
    "app-java/lib/sqlite-jdbc-3.46.1.3.jar"
    "dist/native/Release/chemlab_core.dll"
    "dist/native/Release/chemlab_jni.dll"
    "analysis-python/service.py"
    "analysis-python/spectrum.py"
    "analysis-python/notebook.py"
    "analysis-python/cbridge.py"
    "app-java/resources/kb.json"
    "app-java/resources/kb/index.json"
)

missing=0
for f in "${required[@]}"; do
    if [[ ! -e "$f" ]]; then
        echo "[FAIL] 缺失: $f"
        missing=1
    fi
done
if [[ $missing -eq 1 ]]; then
    echo "[FAIL] 请先完成 Windows 构建（build-all.ps1）再打包。"
    exit 1
fi

# ---- 清理并创建 stage ----
rm -rf "$stage"
mkdir -p "$stage"

# ---- 复制文件 ----
cp "ChemLab Analyzer.exe" "$stage/"
cp README.md "$stage/"
cp run.ps1 "$stage/"
cp verify.ps1 "$stage/"

mkdir -p "$stage/app-java/build/classes"
cp -r app-java/build/classes/* "$stage/app-java/build/classes/"
mkdir -p "$stage/app-java/lib"
cp app-java/lib/sqlite-jdbc-3.46.1.3.jar "$stage/app-java/lib/"
mkdir -p "$stage/app-java/resources/kb"
cp app-java/resources/kb.json "$stage/app-java/resources/"
cp -r app-java/resources/kb/* "$stage/app-java/resources/kb/"

mkdir -p "$stage/dist/native/Release"
cp dist/native/Release/chemlab_core.dll "$stage/dist/native/Release/"
cp dist/native/Release/chemlab_jni.dll "$stage/dist/native/Release/"
cp dist/native/Release/chemlab_cli.exe "$stage/dist/native/Release/"

mkdir -p "$stage/analysis-python"
cp analysis-python/*.py "$stage/analysis-python/"

# ---- 数据目录留空模板（不打包用户数据） ----
mkdir -p "$stage/data"
cat > "$stage/data/ima.json" <<'EOF'
{
  "clientId": "",
  "apiKey": "",
  "knowledgeBaseId": "",
  "knowledgeBaseName": "",
  "folderId": "",
  "timeoutMs": 8000,
  "autoSync": false
}
EOF

# ---- 写入版本信息 ----
echo "$version" > "$stage/VERSION"

# ---- 打包 ----
mkdir -p "$outDir"
rm -f "$outZip"
cd "$outDir/.stage"
zip -r "$outZip" "$name"

echo "[ OK ] 发行包已生成: $outZip"
ls -lh "$outZip"
