; ChemLab Analyzer Windows 安装包脚本 (NSIS)
; 用法: makensis installer/chemlab-analyzer.nsi
; 输出: dist/release/ChemLab-Analyzer-<version>-setup.exe

!include "MUI2.nsh"
!include "LogicLib.nsh"

!define APP_NAME       "ChemLab Analyzer"
!define APP_SHORT      "ChemLabAnalyzer"
!define VERSION        "1.0.0"
!define PUBLISHER      "Chen-1107"
!define URL            "https://github.com/Chen-1107/chemlab-rebuild"
!define STAGE_DIR      "..\dist\release\.stage\ChemLab-Analyzer-${VERSION}-win64"
!define OUT_FILE       "..\dist\release\ChemLab-Analyzer-${VERSION}-setup.exe"

Name "${APP_NAME} ${VERSION}"
OutFile "${OUT_FILE}"
InstallDir "$PROGRAMFILES64\${APP_NAME}"
InstallDirRegKey HKLM "Software\${APP_SHORT}" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
Unicode True

; ---- MUI 界面 ----
!define MUI_ABORTWARNING
!define MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"
!define MUI_UNICON "${NSISDIR}\Contrib\Graphics\Icons\modern-uninstall.ico"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "SimpChinese"

; ---- 安装类型 ----
InstType "典型安装" FULL
InstType "最小安装" MIN

Section "!主程序" SecMain
    SectionIn 1 2 RO
    SetOutPath "$INSTDIR"

    ; 复制所有发行文件（递归）
    File /r "${STAGE_DIR}\*.*"

    ; 写入卸载信息
    WriteUninstaller "$INSTDIR\uninstall.exe"
    WriteRegStr HKLM "Software\${APP_SHORT}" "InstallDir" "$INSTDIR"
    WriteRegStr HKLM "Software\${APP_SHORT}" "Version" "${VERSION}"

    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "DisplayName" "${APP_NAME}"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "DisplayIcon" "$\"$INSTDIR\ChemLab Analyzer.exe$\""
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "Publisher" "${PUBLISHER}"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "URLInfoAbout" "${URL}"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "DisplayVersion" "${VERSION}"
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "NoModify" 1
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}" \
                     "NoRepair" 1
SectionEnd

Section "开始菜单快捷方式" SecStartMenu
    SectionIn 1
    CreateDirectory "$SMPROGRAMS\${APP_NAME}"
    CreateShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" \
                   "$INSTDIR\ChemLab Analyzer.exe" "" "$INSTDIR\ChemLab Analyzer.exe" 0
    CreateShortcut "$SMPROGRAMS\${APP_NAME}\卸载.lnk" \
                   "$INSTDIR\uninstall.exe" "" "$INSTDIR\uninstall.exe" 0
SectionEnd

Section /o "桌面快捷方式" SecDesktop
    SectionIn 1
    CreateShortcut "$DESKTOP\${APP_NAME}.lnk" \
                   "$INSTDIR\ChemLab Analyzer.exe" "" "$INSTDIR\ChemLab Analyzer.exe" 0
SectionEnd

; ---- 卸载 ----
Section "Uninstall"
    Delete "$INSTDIR\uninstall.exe"
    Delete "$INSTDIR\ChemLab Analyzer.exe"
    Delete "$INSTDIR\README.md"
    Delete "$INSTDIR\run.ps1"
    Delete "$INSTDIR\verify.ps1"
    Delete "$INSTDIR\VERSION"
    RMDir /r "$INSTDIR\app-java"
    RMDir /r "$INSTDIR\dist"
    RMDir /r "$INSTDIR\analysis-python"
    RMDir /r "$INSTDIR\data"

    RMDir /r "$SMPROGRAMS\${APP_NAME}"
    Delete "$DESKTOP\${APP_NAME}.lnk"

    DeleteRegKey HKLM "Software\${APP_SHORT}"
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_SHORT}"

    RMDir "$INSTDIR"
SectionEnd
