# ChemLab Analyzer · 分析化学实验数据处理平台

用 **C++ / Python / Java 三层混合技术** 重建的分析化学实验数据处理桌面应用。
C++ 承担高性能数值内核，Python 承担光谱图像与记录本归纳分析，Java 承担桌面
界面与数据持久化；三者通过明确的跨语言契约协作，任一层缺失时上层自动降级，
不影响其余功能。

> 目录：本文件（使用与构建）+ [`ARCHITECTURE.md`](ARCHITECTURE.md)（架构与接口细节）

---

## 一、功能模块

主界面左侧为导航栏，右侧为对应功能面板，共 10 个模块：

| 模块 | 说明 | 依赖层 |
| --- | --- | --- |
| 实验记录本 | 记录的增删改查、按类型/关键字检索、数值抽取与归纳统计、CSV 导出、一键生成 Word 实验报告 | Java + SQLite + Python |
| 基础计算 | 浓度、稀释、标准溶液配制（基准物质库、直接法/标定法两用、纯度校正、称大样）、pH（强/弱酸弱碱、缓冲）、滴定、滴定曲线模拟、Beer–Lambert、误差传递 | C++ 内核 |
| 统计分析 | 描述统计、Grubbs / Dixon 异常值检验、t / F 检验、置信区间，支持 CSV/Excel 批量导入 | C++ 内核 |
| 标定曲线 | 一元/加权线性回归、LOD/LOQ、反算浓度、残差图，支持 CSV/Excel 批量导入 | C++ 内核 + SQLite |
| 光谱分析 | 谱图截图 → 曲线提取 → 平滑/基线校正/寻峰/积分 | Python + C++ 内核 |
| 色谱分析 | 色谱峰自动检测、保留时间、峰面积（MAD 鲁棒基线扣除）与分离度 | C++ 内核 |
| 实验设计 | 正交表生成（L4/L8/L16/L9/L27/L25）、等影响误差分配、重复次数估算 | Java |
| 知识库 | 4 本参考书（51 章节 / 230 条目 / 约 480 万字全文）的浏览与跨书全文检索 | Java |
| ima 知识库 | 用自有 API 凭据接入腾讯 ima 知识库，与本地参考书多来源融合检索；凭据未配置或不可达时自动降级为仅本地 | Java + 网络 |
| Obsidian 同步 | 实验记录、标定曲线与知识库参考导出为 Markdown 笔记；记录与标定曲线双向同步 | Java + 文件系统 |

> 界面内置 4 套主题（靛蓝 · 墨夜 · 青碧 · 暖阳），可在侧边栏底部的色板圆点上一键切换，
> 切换后即时全局换肤；所选主题写入 `data/theme.txt`，下次启动自动恢复。

---

## 二、三层架构

```
┌────────────────────────────────────────────────────────────┐
│  app-java   Java 21 + Swing 桌面主应用                       │
│  · 界面与导航   · SQLite 持久化   · 业务编排                  │
│  · JNI 调用 C++ 内核（core/NativeCore.java）                 │
│  · 常驻子进程调用 Python 服务（service/PythonBridge.java）    │
└───────────────┬───────────────────────────┬────────────────┘
                │ JNI (chemlab_jni.dll)      │ JSON 行协议 (stdin/stdout)
                ▼                            ▼
┌──────────────────────────────┐  ┌──────────────────────────────┐
│  core-cpp  C++17 数值内核      │  │  analysis-python  Python 3    │
│  · 纯 C ABI 函数（无全局状态） │◄─┤  · spectrum.py  OpenCV 曲线提取 │
│  · 统计/回归/化学/信号处理     │  │  · notebook.py  数值抽取与归纳  │
│  · 另出 CLI 用于自检           │  │  · cbridge.py   ctypes 调内核   │
└──────────────────────────────┘  └──────────────────────────────┘
        chemlab_core.dll  ←── ctypes 调用
```

- **C++ 内核**（`core-cpp/`）：所有数值算法的唯一实现，编译为
  `chemlab_core.dll`（C ABI）与 `chemlab_jni.dll`（JNI 封装），另含
  `chemlab_cli.exe` 用于自检。
- **Python 分析服务**（`analysis-python/`）：常驻进程，用 OpenCV 从谱图
  截图中提取曲线，用规则/统计做记录本归纳；需要数值计算时通过 `ctypes`
  回调 C++ 内核。
- **Java 主应用**（`app-java/`）：Swing 界面 + SQLite 持久化 + 跨语言桥接。

三层各自独立可编译、可测试；详细职责边界与接口契约见
[`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 三、目录结构

```
chemlab-rebuild/
├── ChemLab Analyzer.exe       # 桌面启动器（构建产物，双击启动）
├── build-all.ps1              # 一键构建（C++ 内核 + Java 应用 + 启动器）
├── run.ps1                    # 一键启动桌面应用（脚本方式）
├── verify.ps1                 # 全链路自检（9 项跨语言检查）
├── README.md
├── ARCHITECTURE.md
├── tests/                     # 冒烟测试源码
│   ├── JniSmoke.java          #   Java → C++（JNI）
│   ├── Smoke.java             #   Java → SQLite + Python 桥接
│   ├── ObsidianSmoke.java     #   Obsidian 双向同步（离线）
│   ├── DocxSmoke.java         #   Word 报告导出（生成 + XML 良构校验）
│   └── KbSmoke.java           #   知识库后端（本地加载/检索 + ima 降级与融合，离线）
├── tools/                     # 知识库构建脚本（一次性）
│   ├── extract_kb.py          #   PyMuPDF 全文 / 书签提取
│   └── build_index.py         #   合并生成 kb/index.json
├── core-cpp/                  # C++17 数值内核
│   ├── include/chemlab_core.h #   对外 C ABI 契约（唯一头文件）
│   ├── src/                   #   mathutil / stats / regression / chemistry / signal
│   ├── jni/chemlab_jni.cpp    #   JNI 桥接实现
│   ├── src/cli.cpp            #   自检命令行
│   └── CMakeLists.txt
├── analysis-python/           # Python 分析服务
│   ├── service.py             #   JSON 行协议主循环 + 方法分发
│   ├── spectrum.py            #   光谱图像分析（OpenCV）
│   ├── notebook.py            #   记录本数值抽取与归纳
│   └── cbridge.py             #   ctypes 绑定 C++ 内核
├── launcher/                  # 原生桌面启动器（Win32，无第三方依赖）
│   ├── launcher.c             #   定位 java + 装配类路径/原生库 + 拉起主程序
│   └── CMakeLists.txt
├── app-java/                  # Java 桌面主应用
│   ├── src/com/chemlab/
│   │   ├── Main.java          #   入口
│   │   ├── core/NativeCore.java       # JNI 封装
│   │   ├── service/PythonBridge.java  # Python 常驻进程桥接
│   │   ├── service/ObsidianSync.java  # Obsidian 双向同步引擎
│   │   ├── data/              #   Database / RecordDao / CalibrationDao
│   │   ├── model/             #   Record / Calibration
│   │   ├── util/              #   AppPaths / Json / DataParse / TableImport / DocxWriter
│   │   ├── kb/                #   KnowledgeBackend / LocalKbBackend / ImaConfig / ImaClient / ImaKbBackend / HybridBackend / Hit
│   │   └── ui/                #   MainFrame / Theme / 自绘外观(Flat*·RoundedPanel·Icons) / ChartPanel / ImportDialog / panels/*
│   ├── lib/sqlite-jdbc-3.46.1.3.jar   # 唯一外部依赖
│   └── resources/kb/                  # 知识库（4 本书全文 + index.json）
├── build-core/                # CMake 构建目录（生成物）
└── dist/native/Release/       # 原生库输出
    ├── chemlab_core.dll
    ├── chemlab_jni.dll
    └── chemlab_cli.exe
```

---

## 四、环境要求

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17+（实测 21） | 编译并运行 Java 应用、提供 `jni.h` |
| CMake | 3.16+ | 配置 C++ 内核构建 |
| Visual Studio C++ 工具链 | MSVC（含 Windows SDK） | 编译 C++ 内核 |
| Python | 3.9+（实测 3.10） | 运行分析服务 |
| Python 包 | `numpy`、`opencv-python`（`cv2`） | 光谱图像分析 |

构建脚本会自动从 `javac` 定位 JDK 并传入 CMake，无需手动设置 `JAVA_HOME`。

---

## 五、构建与运行

在 `chemlab-rebuild/` 目录下执行：

```powershell
# 1. 一键构建：C++ 内核（含 JNI）+ Java 应用 + 桌面启动器
powershell -ExecutionPolicy Bypass -File build-all.ps1

# 2a. 启动：双击项目根目录的「ChemLab Analyzer.exe」（推荐，无控制台窗口）
# 2b. 或以脚本方式启动
powershell -ExecutionPolicy Bypass -File run.ps1

# 3.（可选）全链路自检：9 项跨语言检查
powershell -ExecutionPolicy Bypass -File verify.ps1
```

构建产物：

- 原生库 → `dist/native/Release/chemlab_core.dll`、`chemlab_jni.dll`
- Java 类 → `app-java/build/classes`
- 桌面启动器 → `ChemLab Analyzer.exe`（项目根目录，双击启动）
- 运行时数据 → `data/chemlab.db`（SQLite 单文件，首次启动自动创建）

### 桌面启动器（ChemLab Analyzer.exe）

`launcher/` 是一个极薄的 Win32 启动器，编译后输出到项目根目录。它只做三件事：

1. 校验构建产物（`app-java/build/classes`、`dist/native/Release/*.dll`、`lib/sqlite-jdbc-*.jar`）；
2. 定位 Java 运行环境（`JAVA_HOME` → `PATH` → 常见安装目录兜底）；
3. 装配 `-Dchemlab.home` / `-Djava.library.path` / `-cp` 等参数并拉起 `com.chemlab.Main`。

设计要点：

- **不捆绑 JRE**：保持「零额外依赖」，运行时需要机器上已安装 JDK 17+；
- **窗口子系统**：双击不弹控制台窗口；子进程 stdout/stderr 重定向到
  `data/launch.log`，便于排查启动问题；
- **失败可读**：构建产物缺失、找不到 Java 等情况弹窗提示，而不是静默退出；
- **调试模式**：`cmake -S launcher -B build-launcher-debug -DCHEMLAB_LAUNCHER_CONSOLE=ON`
  可构建带控制台输出的调试版，实时查看 Java 输出。

> 启动器与 `run.ps1` 等价：前者双击即用，后者便于在终端看日志与传参。

### 启动参数（可选）

`run.ps1` 会传入以下系统属性，手动启动时可自行覆盖：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-Dchemlab.home` | 项目根目录 | 工作根目录，用于定位数据、Python 服务与知识库 |
| `-Djava.library.path` | `dist/native/Release` | JNI 原生库搜索路径 |
| `-Dchemlab.db` | `<home>/data/chemlab.db` | 自定义数据库文件路径 |
| `-Dchemlab.python` | 自动探测 | 指定 Python 解释器路径 |

Python 解释器探测顺序：`-Dchemlab.python` → 环境变量 `CHEMLAB_PYTHON`
→ `python` → `python3` → `py`。当机器上存在多个 Python 时（例如同时装了
Anaconda 与系统 Python），会**优先选择能 `import cv2` 的解释器**；若都缺少
OpenCV/NumPy，则退而选择可用的那个，此时光谱分析降级、记录本归纳仍可用。
`run.ps1` 会自动完成这一挑选并通过 `-Dchemlab.python` 传给应用。

---

## 六、跨语言接口一览

### C++ 内核（C ABI）

头文件 `core-cpp/include/chemlab_core.h` 是唯一契约，共 8 类、30+ 个纯函数：

| 类别 | 代表函数 |
| --- | --- |
| 描述统计 | `cl_describe`、`cl_mean`、`cl_stddev`、`cl_rsd` |
| 异常值检验 | `cl_grubbs_max`、`cl_dixon_q` |
| 显著性检验 | `cl_t_test_one`、`cl_t_test_two`、`cl_f_test`、`cl_ci_mean` |
| 回归与标定 | `cl_linreg`、`cl_linreg_weighted`、`cl_lod_loq`、`cl_calib_inverse` |
| 化学计算 | `cl_molarity`、`cl_ph_weak_acid`、`cl_ph_buffer`、`cl_titration`、`cl_titration_curve`、`cl_beer_lambert_*` |
| 误差传递 | `cl_unc_add`、`cl_unc_mul`、`cl_unc_power`、`cl_unc_general` |
| 信号处理 | `cl_savgol`、`cl_baseline_*`、`cl_find_peaks`、`cl_trapz`、`cl_normalize` |
| 色谱分析 | `cl_chrom_peaks`（MAD 鲁棒基线 + 寻峰 + 梯形积分 + 分离度） |

约定：返回 `int` 状态码（`0` 成功，负数为错误码 `CL_ERR_*`）；数组参数为
「指针 + 元素个数」；调用方提供输出缓冲，内核不分配内存。

### Java ↔ C++（JNI）

`app-java/src/com/chemlab/core/NativeCore.java` 封装 `chemlab_jni.dll`，
把「状态码 + 输出缓冲」包装为 Java 的 `double[]` 返回风格，并在类加载失败时
记录 `loadError()`，供界面降级提示。

### Java ↔ Python（JSON 行协议）

`app-java/src/com/chemlab/service/PythonBridge.java` 启动
`analysis-python/service.py` 常驻进程，按「一行一个 JSON 报文」通信：

```
请求  {"id": 1, "method": "spectrum.analyze", "params": {...}}
成功  {"id": 1, "ok": true,  "result": {...}}
失败  {"id": 1, "ok": false, "error": "...", "trace": "..."}
```

请求带自增 `id`，响应按 `id` 归还，可安全并发调用。服务启动时会先发一条
`id=0` 的就绪报文，其中携带 `vision` 字段（解释器是否含 OpenCV/NumPy）；
`PythonBridge` 据此在多个候选解释器中优先选用具备光谱分析能力的那个。
当前暴露的方法：

| 方法 | 作用 |
| --- | --- |
| `ping` | 健康检查，返回 Python 版本与内核可用性 |
| `core.info` | 查询 C++ 内核版本与路径 |
| `spectrum.analyze` | 单张谱图分析（ROI、平滑、基线、寻峰、积分） |
| `spectrum.compare` | 多张谱图对比 |
| `notebook.summarize` | 记录本归纳统计与趋势结论 |
| `notebook.search` | 记录本关键字检索 |
| `notebook.extractNumbers` | 从自由文本抽取数值 |

### Python ↔ C++（ctypes）

`analysis-python/cbridge.py` 通过 `ctypes` 加载 `chemlab_core.dll`，把 C 风格
接口包装成 Python 风格（返回标量/列表，失败抛 `CoreError`）。内核路径按
`CHEMLAB_CORE_DLL` 环境变量 → `dist/native/{Release,Debug}` 顺序探测。

### 知识库与 Obsidian 同步（文件系统）

- **知识库**：`app-java/resources/kb/` 下的多书目数据。`index.json` 汇总书目
  元数据、章节目录（`ch`）与 Dean's 条目（`e`）；各书全文以 `kb/<book>.txt`
  保存（页间以换页符 `\f` 分隔），按需惰性加载并缓存。`KnowledgePanel`
  提供书目切换、章节导航、全文翻页与跨书关键字检索。
- **ima 知识库（可选增强）**：在「ima 知识库」面板填入自有 API 凭据后，检索会
  额外调用腾讯 ima 的 `search_knowledge` 接口，把文件解析、分块与向量化交给
  ima 完成——因此**无需自研 OCR / PDF 解析**。本地参考书与 ima 是两个同级的
  `KnowledgeBackend` 实现，由 `HybridBackend` 融合：各来源先内部归一化到
  `[0,1]`，再按 `0.4 × 本地 + 0.6 × ima` 加权，同分时本地优先。凭据未配置、
  网络不可达或接口报错时**静默降级为仅本地**，并在状态栏给出原因。
  凭据落在 `data/ima.json`（与 `data/obsidian.json` 同级，不随安装包分发），
  也可用环境变量 `IMA_OPENAPI_CLIENTID` / `IMA_OPENAPI_APIKEY` 覆盖；
  界面与日志中的凭据一律经 `ImaConfig.mask()` 脱敏为 `****末四位`。
- **Obsidian 同步**：`service/ObsidianSync` 把实验记录、标定曲线与知识库参考
  导出为 Markdown 笔记，并对记录与标定曲线做双向同步。库内固定结构为
  `<vault>/ChemLab/{实验记录,标定曲线,知识库}/` + 同步清单
  `.chemlab-sync.json` + `ChemLab 索引.md`。库路径在应用内「Obsidian 同步」
  面板配置，导出/导入/双向同步均在后台线程执行并实时回显日志。

---

## 七、降级策略

三层解耦，任一层不可用时上层给出明确提示并继续运行其余功能：

| 缺失层 | 影响 | 表现 |
| --- | --- | --- |
| C++ 内核 | 数值计算类模块不可用 | 状态栏标红，相关面板提示内核未加载 |
| Python 服务 | 光谱分析、记录本归纳不可用 | 状态栏提示服务不可用，面板降级提示 |
| Python 缺少 OpenCV/NumPy | 仅光谱分析不可用 | 状态栏提示「缺少 OpenCV/NumPy（光谱分析已禁用）」，记录本归纳仍可用 |
| SQLite jar | 无法启动 | 启动时弹窗提示缺少驱动 |
| ima 凭据未配置 / 网络不可达 | 仅 ima 来源不可用 | 知识库面板状态栏提示原因，检索自动回落为仅本地参考书 |

启动时会统一弹出「降级提示」对话框，列出具体缺失组件与受影响范围。

---

## 八、自检与验证

各层均内置自检入口。最省事的方式是直接跑 `verify.ps1`，它会依次执行全部
9 项检查并汇总结果：

```powershell
# 一键全链路自检（推荐）
powershell -ExecutionPolicy Bypass -File verify.ps1

# 也可单独运行各层自检：
dist/native/Release/chemlab_cli.exe --selftest        # C++ 内核（107 项）
python analysis-python/cbridge.py                     # Python ↔ C++ 桥接
python analysis-python/service.py --selftest          # Python 分析服务
python analysis-python/spectrum.py                    # 光谱分析（合成谱图）
```

Java 侧冒烟测试源码位于 `tests/`（`JniSmoke.java`、`Smoke.java`、
`ObsidianSmoke.java`、`DocxSmoke.java`、`KbSmoke.java`），由 `verify.ps1`
自动编译并运行。

实测验证结果（本机 JDK 21 / Python 3.10 / CMake 4.4 / MSVC）：

| 链路 | 结果 |
| --- | --- |
| C++ 内核自检 | 通过 107 项，失败 0 项（含滴定曲线 18 项、色谱峰 13 项、标准溶液配制 13 项、基线校正 2 项） |
| Python → C++（ctypes） | 描述统计、回归、pH、寻峰均正确 |
| Python 服务协议 | `ping` / `notebook.summarize` 往返正确 |
| Java → C++（JNI） | `NativeCore` 全部接口返回正确（含 `titrationCurve`、`chromPeaks`） |
| Java → Python 桥接 | 启动、调用、关闭正常 |
| Java 数据层（SQLite） | 增删改查与检索正常 |
| 光谱分析（合成谱图） | 曲线提取、寻峰、积分正确 |
| Obsidian 双向同步 | 导出、幂等、库内编辑回写、新笔记导入、知识库导出全部通过 |
| Word 报告导出 | .docx 生成、zip 部件齐全、XML 良构、中文字符 UTF-8 往返无损 |
| 知识库后端 | 本地索引加载/页级与条目级检索/正文回读/书目限定、凭据脱敏与持久化往返、ima 未配置时降级与融合权重，共 60 项全过（全程不发起网络请求） |
| 桌面应用启动 | 主窗口正常显示，无异常输出 |

---

## 九、设计取舍

- **不引入 Maven/Gradle**：仅用 `javac` + `cmake` 即可完成全链路构建，降低
  分发与启动成本；唯一外部依赖是 SQLite JDBC 单个 jar。
- **不引入第三方 JSON/图表库**：自带极简 JSON 解析器（`util/Json.java`）与
  自绘图表组件（`ui/ChartPanel.java`），避免依赖膨胀。
- **不引入 Apache POI 等 Office 库**：Word 报告由 `util/DocxWriter.java` 用
  JDK `ZipOutputStream` 直接手写 OOXML 生成；CSV/Excel 导入由
  `util/TableImport.java` 原生解析，均保持零第三方依赖。
- **ima 接入不引入 HTTP / JSON 库**：`kb/ImaClient.java` 用 JDK 原生
  `HttpURLConnection` 发 HTTPS，请求与响应经自带的 `util/Json.java` 读写，
  凭据只落在本地 `data/ima.json`；文件解析与向量化交给 ima，避免自研
  OCR / PDF 解析。ima 与本地参考书同级，缺失即降级，不构成硬依赖。
- **Python 用常驻进程而非 HTTP**：不占端口、不与其他软件冲突，且省去每次
  分析重复加载 OpenCV/NumPy 的开销（约 1 秒）。
- **C++ 内核用纯 C ABI**：同一份算法可被 JNI、ctypes、CLI 三类调用方复用，
  无全局状态、可重入。