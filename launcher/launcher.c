// launcher.c - ChemLab Analyzer 桌面启动器
//
// 职责：定位 Java 运行环境，装配类路径、原生库路径与工作根目录，随后拉起
// Java 主程序 com.chemlab.Main。刻意保持极薄——Python 解释器的挑选、各层
// 降级判断都留在 Java/Python 侧，启动器只做「找到 java 并正确传参」。
//
// 正式构建为窗口子系统：双击不弹控制台；子进程 stdout/stderr 重定向到
// data\launch.log，便于排查；致命错误用弹窗提示。
// 调试构建（CHEMLAB_CONSOLE_DEBUG）为控制台程序：子进程直接继承控制台，
// 可实时看到 Java 输出。

#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

#define APP_TITLE L"ChemLab Analyzer"

// 路径缓冲保持较小：默认线程栈约 1MB，开十几个 64KB 缓冲会栈溢出。
#define PATHBUF 4096
// 命令行 / 类路径缓冲放静态区，避免占用栈。
#define CMDLINE 32768

static void fatal(const wchar_t *msg) {
#ifdef CHEMLAB_CONSOLE_DEBUG
    fwprintf(stderr, L"[FATAL] %ls\n", msg);
    ExitProcess(2);
#else
    MessageBoxW(NULL, msg, APP_TITLE, MB_OK | MB_ICONERROR);
    ExitProcess(2);
#endif
}

static int fileExists(const wchar_t *path) {
    DWORD a = GetFileAttributesW(path);
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

static int dirExists(const wchar_t *path) {
    DWORD a = GetFileAttributesW(path);
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}

// 追加一个参数；quote 为真时用双引号包裹（路径可能含空格与中文）。
// 首个参数前不加空格，避免命令行以空白开头。
static void appendArg(wchar_t *cmd, size_t cap, const wchar_t *arg, int quote) {
    size_t len = wcslen(cmd);
    size_t need = wcslen(arg) + (quote ? 2u : 0u) + 1u;
    if (len + need + 1u >= cap) {
        return;
    }
    if (len > 0) {
        cmd[len++] = L' ';
    }
    if (quote) {
        cmd[len++] = L'"';
    }
    wcscpy(cmd + len, arg);
    len = wcslen(cmd);
    if (quote) {
        cmd[len++] = L'"';
    }
    cmd[len] = L'\0';
}

// 在 lib 目录里找 sqlite-jdbc-*.jar，避免把版本号写死
static int findSqliteJar(const wchar_t *libDir, wchar_t *out, size_t cap) {
    wchar_t pattern[PATHBUF];
    swprintf_s(pattern, PATHBUF, L"%ls\\sqlite-jdbc-*.jar", libDir);
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return 0;
    }
    swprintf_s(out, cap, L"%ls\\%ls", libDir, fd.cFileName);
    FindClose(h);
    return 1;
}

// 在若干常见安装位置里找 java.exe（JAVA_HOME / PATH 都不可用时的兜底）
static int findJavaInCommonRoots(wchar_t *out, size_t cap) {
    static const wchar_t *roots[] = {
        L"%ProgramFiles%\\Java",
        L"%ProgramFiles%\\Eclipse Adoptium",
        L"%ProgramFiles%\\Microsoft",
        L"%ProgramFiles%\\Amazon Corretto",
        L"%LocalAppData%\\Programs\\Microsoft",
    };
    for (size_t i = 0; i < sizeof(roots) / sizeof(roots[0]); i++) {
        wchar_t base[PATHBUF];
        if (ExpandEnvironmentStringsW(roots[i], base, PATHBUF) == 0) {
            continue;
        }
        if (!dirExists(base)) {
            continue;
        }
        wchar_t pattern[PATHBUF];
        swprintf_s(pattern, PATHBUF, L"%ls\\*\\bin\\java.exe", base);
        WIN32_FIND_DATAW fd;
        HANDLE h = FindFirstFileW(pattern, &fd);
        if (h == INVALID_HANDLE_VALUE) {
            continue;
        }
        // 取最后一个匹配项（通常版本号最大的目录排在后面）
        wchar_t best[PATHBUF];
        swprintf_s(best, PATHBUF, L"%ls\\%ls", base, fd.cFileName);
        while (FindNextFileW(h, &fd)) {
            swprintf_s(best, PATHBUF, L"%ls\\%ls", base, fd.cFileName);
        }
        FindClose(h);
        if (fileExists(best)) {
            wcscpy(out, best);
            return 1;
        }
    }
    return 0;
}

static int resolveJava(wchar_t *javaExe, size_t cap) {
    wchar_t javaHome[PATHBUF];
    DWORD jh = GetEnvironmentVariableW(L"JAVA_HOME", javaHome, PATHBUF);
    if (jh > 0 && jh < PATHBUF) {
        wchar_t cand[PATHBUF];
        swprintf_s(cand, PATHBUF, L"%ls\\bin\\java.exe", javaHome);
        if (fileExists(cand)) {
            wcscpy(javaExe, cand);
            return 1;
        }
    }

    wchar_t found[PATHBUF];
    DWORD r = SearchPathW(NULL, L"java.exe", NULL, PATHBUF, found, NULL);
    if (r > 0 && r < PATHBUF) {
        wcscpy(javaExe, found);
        return 1;
    }

    if (findJavaInCommonRoots(javaExe, cap)) {
        return 1;
    }
    return 0;
}

#ifdef CHEMLAB_CONSOLE_DEBUG
int wmain(void) {
#else
int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, PWSTR cmdArg, int show) {
    (void) hInst;
    (void) hPrev;
    (void) cmdArg;
    (void) show;
#endif

    // 工作根目录 = 启动器所在目录
    wchar_t root[PATHBUF];
    DWORD n = GetModuleFileNameW(NULL, root, PATHBUF);
    if (n == 0 || n >= PATHBUF) {
        fatal(L"无法确定启动器自身路径。");
    }
    wchar_t *slash = wcsrchr(root, L'\\');
    if (slash == NULL) {
        fatal(L"无法解析程序目录。");
    }
    *slash = L'\0';

    // 允许用 CHEMLAB_HOME 覆盖工作根目录：调试构建的 exe 落在构建目录里，
    // 需要显式指回项目根；正式构建放在项目根，无需设置。
    {
        wchar_t override[PATHBUF];
        DWORD ov = GetEnvironmentVariableW(L"CHEMLAB_HOME", override, PATHBUF);
        if (ov > 0 && ov < PATHBUF && dirExists(override)) {
            wcscpy(root, override);
        }
    }

    // ---- 校验构建产物 ----
    wchar_t classes[PATHBUF], libDir[PATHBUF], nativeDir[PATHBUF];
    wchar_t coreDll[PATHBUF], jniDll[PATHBUF], jarPath[PATHBUF];
    swprintf_s(classes, PATHBUF, L"%ls\\app-java\\build\\classes", root);
    swprintf_s(libDir, PATHBUF, L"%ls\\app-java\\lib", root);
    swprintf_s(nativeDir, PATHBUF, L"%ls\\dist\\native\\Release", root);
    swprintf_s(coreDll, PATHBUF, L"%ls\\chemlab_core.dll", nativeDir);
    swprintf_s(jniDll, PATHBUF, L"%ls\\chemlab_jni.dll", nativeDir);

    if (!dirExists(classes) || !fileExists(coreDll) || !fileExists(jniDll)) {
        fatal(L"尚未构建，或构建产物不完整。\n\n"
              L"请先在项目根目录运行：\n"
              L"    powershell -ExecutionPolicy Bypass -File build-all.ps1");
    }
    if (!findSqliteJar(libDir, jarPath, PATHBUF)) {
        fatal(L"未找到 app-java\\lib\\sqlite-jdbc-*.jar，请先运行 build-all.ps1。");
    }

    // ---- 定位 Java ----
    wchar_t javaExe[PATHBUF];
    if (!resolveJava(javaExe, PATHBUF)) {
        fatal(L"未找到 Java 运行环境。\n\n"
              L"请安装 JDK 17 或更高版本，并设置 JAVA_HOME，\n"
              L"或把 java.exe 所在目录加入 PATH。");
    }

    // ---- 组装命令行 ----
    static wchar_t cmd[CMDLINE];
    static wchar_t arg[CMDLINE];
    static wchar_t cp[CMDLINE];
    cmd[0] = L'\0';
    appendArg(cmd, CMDLINE, javaExe, 1);

    swprintf_s(arg, CMDLINE, L"-Dchemlab.home=%ls", root);
    appendArg(cmd, CMDLINE, arg, 1);
    swprintf_s(arg, CMDLINE, L"-Djava.library.path=%ls", nativeDir);
    appendArg(cmd, CMDLINE, arg, 1);
    appendArg(cmd, CMDLINE, L"-Dfile.encoding=UTF-8", 1);
    appendArg(cmd, CMDLINE, L"-Dstdout.encoding=UTF-8", 1);
    appendArg(cmd, CMDLINE, L"-Dsun.stdout.encoding=UTF-8", 1);

    swprintf_s(cp, CMDLINE, L"%ls;%ls", classes, jarPath);
    appendArg(cmd, CMDLINE, L"-cp", 0);
    appendArg(cmd, CMDLINE, cp, 1);
    appendArg(cmd, CMDLINE, L"com.chemlab.Main", 0);

    // 让 C++ 内核可被 JNI 之外的调用方按路径加载
    SetEnvironmentVariableW(L"CHEMLAB_CORE_DLL", coreDll);

#ifdef CHEMLAB_CONSOLE_DEBUG
    fwprintf(stderr, L"[dbg] root=%ls\n[dbg] java=%ls\n[dbg] cmd=%ls\n",
             root, javaExe, cmd);
#endif

    // ---- 子进程标准句柄 ----
    SECURITY_ATTRIBUTES sa;
    sa.nLength = sizeof(sa);
    sa.lpSecurityDescriptor = NULL;
    sa.bInheritHandle = TRUE;

    HANDLE hLog = INVALID_HANDLE_VALUE;
    HANDLE hNul = INVALID_HANDLE_VALUE;

    STARTUPINFOW si;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);

    DWORD flags = 0;
#ifndef CHEMLAB_CONSOLE_DEBUG
    // 正式构建：不弹控制台窗口，子进程输出写入 data\launch.log
    flags |= CREATE_NO_WINDOW;

    wchar_t dataDir[PATHBUF], logPath[PATHBUF];
    swprintf_s(dataDir, PATHBUF, L"%ls\\data", root);
    CreateDirectoryW(dataDir, NULL);
    swprintf_s(logPath, PATHBUF, L"%ls\\launch.log", dataDir);

    hLog = CreateFileW(logPath, GENERIC_WRITE, FILE_SHARE_READ, &sa,
                       CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    hNul = CreateFileW(L"NUL", GENERIC_READ,
                       FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                       OPEN_EXISTING, 0, NULL);
    if (hLog != INVALID_HANDLE_VALUE) {
        si.dwFlags |= STARTF_USESTDHANDLES;
        si.hStdOutput = hLog;
        si.hStdError = hLog;
        si.hStdInput = (hNul != INVALID_HANDLE_VALUE) ? hNul : NULL;
    }
#endif

    PROCESS_INFORMATION pi;
    ZeroMemory(&pi, sizeof(pi));

    // lpApplicationName 显式给出，lpCommandLine 首 token 仍放 java.exe 路径：
    // 这是 CreateProcess 的标准用法，子进程 argv[0] 即该路径。
    BOOL ok = CreateProcessW(javaExe, cmd, NULL, NULL, TRUE,
                             flags, NULL, root, &si, &pi);
    if (!ok) {
        wchar_t msg[2048];
        swprintf_s(msg, 2048, L"无法启动 Java 进程（错误码 %lu）。\n\n"
                              L"命令行：\n%ls", GetLastError(), cmd);
        fatal(msg);
    }

    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);

    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    if (hLog != INVALID_HANDLE_VALUE) {
        CloseHandle(hLog);
    }
    if (hNul != INVALID_HANDLE_VALUE) {
        CloseHandle(hNul);
    }
    return (int) code;
}