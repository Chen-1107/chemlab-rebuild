# 架构说明 · ChemLab Analyzer

本文档面向接手维护的开发者，说明三层混合架构的**职责边界、跨语言契约、数据
模型与扩展方式**。使用与构建步骤见 [`README.md`](README.md)。

---

## 一、设计目标与取舍

| 目标 | 取舍 |
| --- | --- |
| 数值计算性能与可复用 | 核心算法全部下沉到 C++，以纯 C ABI 暴露，三类宿主（JNI / ctypes / CLI）共用 |
| 图像与归纳分析敏捷 | 光谱图像分析用 Python + OpenCV，算法迭代无需重编原生库 |
| 桌面体验与交付简单 | 界面用 Java Swing；不引入 Maven/Gradle，仅 `javac` + `cmake` 构建 |
| 单机单用户、免安装 | 数据用 SQLite 单文件；Python 用常驻子进程而非 HTTP，不占端口 |
| 单层故障不影响全局 | 每个跨语言调用点做可用性判断 + 异常捕获，失败即降级 |

**依赖方向**：`Java → C++`、`Java → Python → C++`。C++ 内核不依赖任何上层，
Python 服务不依赖 Java。上层可单独替换（如把 Swing 换成别的 UI）而不动内核。

---

## 二、分层职责

### 2.1 C++ 数值内核（`core-cpp/`）

- 唯一算法实现来源，**无全局可变状态、纯函数、可重入**。
- 源码划分：
  - `mathutil.cpp`：数值工具（排序、分位、t/F 分布近似、伽马函数等）
  - `stats.cpp`：描述统计、异常值检验、显著性检验、置信区间
  - `regression.cpp`：一元/加权线性回归、LOD/LOQ、残差
  - `chemistry.cpp`：浓度、稀释、标准溶液配制（直接法/标定法）、pH、缓冲、滴定、滴定曲线模拟、Beer–Lambert、误差传递
  - `signal.cpp`：滑动平均、Savitzky–Golay、基线校正（含 MAD 鲁棒多项式基线）、
    寻峰、梯形积分、归一化、色谱峰检测与分离度
- 三个构建目标：
  - `chemlab_core`（`chemlab_core.dll`）：C ABI 共享库，供 ctypes / CLI 使用
  - `chemlab_jni`（`chemlab_jni.dll`）：在核心源码上叠加 `jni/chemlab_jni.cpp`
  - `chemlab_cli`（`chemlab_cli.exe`）：自检与脚本入口

### 2.2 Python 分析服务（`analysis-python/`）

- `service.py`：常驻进程 + JSON 行协议主循环 + 方法注册表。
- `spectrum.py`：谱图图像分析流水线
  「读图 → 定位绘图区 → 分离曲线像素 → 逐列提取 → 平滑/基线/寻峰/积分」，
  坐标标定后把像素曲线映射为物理量。
- `notebook.py`：从记录自由文本抽取数值（正则 + 清洗），并按实验类型做分组
  统计、趋势回归与结论归纳。
- `cbridge.py`：`ctypes` 绑定 `chemlab_core.dll`，把「状态码 + 输出缓冲」
  包装为 Python 异常风格。

### 2.3 Java 主应用（`app-java/`）

- `Main`：启动编排（恢复上次主题 → 数据库 → Python 服务 → 主窗口 → 降级提示）。
- `ui/MainFrame`：深色渐变侧边栏导航 + 顶部标题条 + 卡片内容区 + 底部状态栏。
- `ui/panels/*`：10 个功能面板（含色谱分析、知识库浏览、ima 知识库配置与 Obsidian 同步）。
- `data/*`：SQLite 连接与 DAO。
- `core/NativeCore`：JNI 封装。
- `service/PythonBridge`：Python 常驻进程桥接。
- `service/ObsidianSync`：Markdown 双向同步引擎（记录/标定曲线双向、知识库单向导出）。
- `kb/*`：知识后端抽象与实现（见「知识库后端」一节）。
- `util/*`：路径解析、JSON 解析、数值文本解析、CSV/Excel 表格解析
  （`TableImport`，零依赖支持 `.xlsx` 的 zip + XML 解包）、零依赖 Word 生成器
  （`DocxWriter`，手写 OOXML）；`AppPaths` 另提供知识库与 Obsidian 配置路径。
- `ui/ImportDialog`：批量导入对话框（文件选择 → 表格预览 → 列映射 → 填充面板）。

#### 外观层（`ui/` 下的主题与自绘控件）

视觉体系集中在一处，面板只调用工厂方法、不写死颜色，改主题即可整体换肤：

- `Theme`：唯一配色 / 字体 / 圆角来源；`install()` 把自定义 UI 委托注册进
  `UIManager`，使面板中直接 `new` 出来的 `JTabbedPane` / `JComboBox` /
  `JTable` / `JScrollPane` 等也自动获得统一扁平外观；并集中提供
  卡片、按钮、输入框、滚动容器等控件工厂。
  - 字体统一走逻辑字体族 `SansSerif`（界面）与 `Monospaced`（结果区）。
    物理字体（如 Microsoft YaHei UI、Consolas）在缺字时**不会回退**，
    会把下标 `c₁`、`μ₀`、上下标 `10⁻⁵` 与箭头 `◀ ▶` 显示成豆腐块；
    逻辑字体族会自动回退补字，且在 Windows 上仍以现代无衬线渲染中文。
- `RoundedPanel`：圆角白卡片基类（含极浅投影）。
- `FlatButtonUI` / `FlatScrollBarUI` / `FlatTabbedPaneUI` / `FlatComboBoxUI` /
  `FlatCheckIcon`：分别覆盖按钮、滚动条、选项卡、下拉框、勾选框的
  `ComponentUI` / `Icon`，全部用 Java2D 自绘，不依赖图片或图标字体。
- `Icons`：导航与品牌矢量图标，颜色随组件前景色变化。
- `ChartPanel`：轻量自绘图表（折线 / 散点 / 柱状），配色取自 `Theme`。

> 新增面板时无需手动设置样式——只要使用 `Theme` 的工厂方法，外观自动统一。

#### 运行时主题切换

内置 4 套色板：`靛蓝`（默认浅色）、`墨夜`（深色）、`青碧`（青绿）、`暖阳`（琥珀）。
每套色板是 `Theme.Palette` 的一个实例，封装该主题的全部颜色（背景 / 卡片 /
边框 / 文字 / 强调色 / 侧边栏 / 语义色 / 图表 / 滚动条 / 阴影 / 提示气泡 / 数据序列）。
侧边栏在各主题下统一保持深色渐变，作为稳定的视觉锚点。

- `Theme` 的颜色字段为**可变静态字段**，`setTheme(index)` 通过 `applyColors()`
  一次性替换整套颜色，再调用 `install()` 重装全局 `UIManager` 外观。
  配套 API：`themeCount()` / `themeName(i)` / `themeAccent(i)` / `themeSidebar(i)` /
  `currentIndex()`。
- 面板与自绘控件一律在**绘制时**读取 `Theme` 字段（而非构造期缓存常量），
  因此滚动条滑块、卡片阴影、按钮、图表等都随主题自动适配；不写死颜色是前提。
- 切换入口在侧边栏底部（`MainFrame` 的 `ThemeSwatch` 圆点）：当前主题显示白环与
  对勾，悬停显示主题名，点击触发回调。
- Swing 组件在**构造期**捕获颜色引用，无法就地刷新，因此切换后由 `Main`
  **重建主窗口**（数据库与分析服务为长生命周期对象，跨窗口复用，不重复启动）；
  重建时保留原窗口位置与大小。
- 所选主题索引持久化到 `data/theme.txt`，下次启动由 `Main.loadThemeIndex()`
  读取恢复；文件缺失或损坏时回退默认主题。

### 2.4 桌面启动器（`launcher/`，交付层）

面向最终用户的双击入口，**不属于三层运行时**，只是把 Java 主应用「装配好参数
再拉起来」。刻意保持极薄：Python 解释器挑选、各层降级判断全部留在 Java/Python
侧，启动器不做任何业务决策。

- `launcher.c`：单文件 Win32 程序，职责为
  1. 以自身所在目录为工作根（可用 `CHEMLAB_HOME` 覆盖，便于调试）；
  2. 校验构建产物（`app-java/build/classes`、`dist/native/Release/*.dll`、
     `lib/sqlite-jdbc-*.jar`，jar 按通配匹配以免写死版本号）；
  3. 定位 Java：`JAVA_HOME` → `PATH` → 常见安装目录（`%ProgramFiles%\Java`
     等）兜底；
  4. 组装 `-Dchemlab.home` / `-Djava.library.path` / `-Dfile.encoding=UTF-8`
     / `-cp` 等参数，`CreateProcessW` 拉起 `com.chemlab.Main`，并等待子进程。
- `CMakeLists.txt`：独立于 `core-cpp` 的 CMake 工程，仅链接 `user32`，无第三方
  依赖；正式构建为窗口子系统（`WIN32`，输出 `ChemLab Analyzer.exe` 到项目
  根），调试构建为控制台子系统（`-DCHEMLAB_LAUNCHER_CONSOLE=ON`，入口
  `wmain`，子进程继承控制台以便实时观察输出）。
- 参数与 `run.ps1` 一致；差别在正式构建用 `CREATE_NO_WINDOW` 隐藏子进程控制台，
  并把子进程 stdout/stderr 重定向到 `data/launch.log`，失败（缺产物、找不到
  Java、`CreateProcess` 失败）统一弹窗提示而非静默退出。
- **不捆绑 JRE**：运行时要求机器已装 JDK 17+，与「零额外依赖」的构建约束一致。

---

## 三、跨语言契约

### 3.1 C ABI（C++ 对外唯一契约）

头文件 `core-cpp/include/chemlab_core.h`。

- 符号导出：Windows 用 `__declspec(dllexport)`，Linux 用
  `__attribute__((visibility("default")))`，统一宏 `CL_API`。
- 返回约定：`int` 状态码，`0` = `CL_OK`，负数为错误码：

  | 码 | 含义 |
  | --- | --- |
  | `CL_ERR_NULL (-1)` | 传入空指针 |
  | `CL_ERR_SIZE (-2)` | 样本量不足或长度非法 |
  | `CL_ERR_RANGE (-3)` | 参数超出定义域 |
  | `CL_ERR_DEGEN (-4)` | 退化输入（全等值、零方差） |
  | `CL_ERR_CONVERGE (-5)` | 数值迭代未收敛 |
  | `CL_ERR_CAPACITY (-6)` | 输出缓冲不足 |

- 内存约定：调用方提供输出缓冲；内核**不分配内存、不持有所有权**。
- 复合结果通过「一次调用输出多个 double」返回，布局在头文件注释中固定
  （例如 `cl_describe` 输出 8 个值，`cl_linreg` 输出 8 个值）。这样可避免
  多次跨界调用与结构体 ABI 对齐问题。

### 3.2 JNI（Java ↔ C++）

- `NativeCore` 静态块 `System.loadLibrary("chemlab_jni")`，失败不抛异常而是
  记录 `AVAILABLE=false` 与 `LOAD_ERROR`，供界面降级提示。
- 类型搬运约定：标量失败返回 `NaN`，数组失败返回 `null`；`double[]` 直接
  映射到内核的「指针 + 长度」。
- 本类**不含业务逻辑**，只做类型转换与错误归一。

### 3.3 JSON 行协议（Java ↔ Python）

- Java 侧 `PythonBridge.start()` 依次尝试解释器：
  `-Dchemlab.python` → `CHEMLAB_PYTHON` → `python` → `python3` → `py`。
  由于同一台机器可能装有多个 Python（如 Anaconda 与系统 Python），逐个启动
  后依据就绪报文中的 `vision` 字段（解释器是否含 OpenCV/NumPy）**优先选用
  具备光谱分析能力的解释器**；若都缺 OpenCV，则保留第一个可用的作降级候选，
  使记录本归纳仍可用。
- 以 `python -u service.py` 启动，工作目录设为脚本所在目录，环境注入
  `PYTHONIOENCODING=utf-8`、`PYTHONUTF8=1`。
- 协议：
  - 就绪信号：服务启动后先输出 `{"id":0,"ok":true,"result":{"ready":true,...}}`，
    Java 收到 `id==0` 即完成 `ready` future。
  - 请求 `{"id":N,"method":"...","params":{...}}`；成功
    `{"id":N,"ok":true,"result":{...}}`；失败
    `{"id":N,"ok":false,"error":"...","trace":"..."}`。
  - 退出：Java 写 `__quit__`，服务结束主循环。
- 并发模型：请求 `id` 自增，响应按 `id` 归还到 `ConcurrentHashMap<Long,
  CompletableFuture>`，因此可在后台线程并发调用；默认超时 60s。
- 健壮性：`service.py` 在导入分析模块时把 `sys.stdout` 临时重定向到
  `stderr`，防止 OpenCV/NumPy 导入期打印污染协议；`spectrum`（依赖
  OpenCV/NumPy）单独容错导入，缺失时仅禁用光谱分析方法，`notebook.*` 不受
  影响；单条请求异常被捕获后仅返回错误报文，进程继续服务。
- Java 侧另起 stderr 排空线程，保留最近 40 行作为诊断信息（`stderrSummary()`）。

### 3.4 ctypes（Python ↔ C++）

- `cbridge.py` 懒加载内核，路径探测顺序：
  `CHEMLAB_CORE_DLL` 环境变量 → `dist/native/Release` → `dist/native` →
  `dist/native/Debug` → 脚本目录。
- 为每个函数显式绑定 `restype` / `argtypes`，避免 64 位下默认 `int` 截断
  指针的隐患。
- 状态码非 0 时抛 `CoreError`，携带函数名与中文错误说明。

---

## 四、数据模型与持久化

SQLite 单文件，默认 `<home>/data/chemlab.db`；应用共用一个连接（单用户并发
极低，无需连接池），启用 `foreign_keys` 与 `WAL` 日志模式。

### `records`（实验记录）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `title` | TEXT | 标题 |
| `type` | TEXT | 实验类型（`titration` / `spectro` / `other` …） |
| `date` | TEXT | 实验日期 |
| `purpose` | TEXT | 目的 |
| `data_text` | TEXT | 原始数据/自由文本 |
| `conclusion` | TEXT | 结论 |
| `tags` | TEXT | 逗号分隔标签 |
| `result_value` | REAL | 主结果数值 |
| `created_at` / `updated_at` | TEXT | 时间戳 |

索引：`idx_records_type(type)`、`idx_records_date(date)`。

### `calibrations`（标定曲线）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `name` | TEXT | 名称 |
| `x_json` / `y_json` | TEXT | 标准点数组（JSON） |
| `slope` / `intercept` | REAL | 回归系数 |
| `r2` / `syx` | REAL | 决定系数 / 剩余标准差 |
| `lod` / `loq` | REAL | 检出限 / 定量限 |
| `created_at` | TEXT | 时间戳 |

### 知识库（`app-java/resources/kb/`）

多书目静态资源，随应用分发，不入 SQLite。`index.json` 结构：

```
{
  "version": 1,
  "books": [ { "id", "title", "author", "edition", "pages",
               "mode", "file", "chars", "toc": [[level, title, page], ...] } ],
  "ch":    [ { "id", "book", "t", "cat", "p", "lvl" } ],   // 章节目录
  "e":     [ { ... } ]                                      // Dean's 条目
}
```

- `books[].mode` 区分两种浏览方式：`toc`（按章节目录导航）与 `fulltext`
  （按页阅读）。
- 各书全文存于 `kb/<file>`，页间以换页符 `\f` 分隔；`KnowledgePanel` 按需
  读取整本并缓存为 `String[]`，避免启动时一次性加载数 MB 文本。
- Dean's 手册保留原有 230 条结构化条目（`e`），其余三本（Lange's、
  Mass Spectrometry、Physical Chemistry）为全文模式，合计约 480 万字符。
- Lange's 手册原 PDF 无嵌入书签，`tools/build_index.py` 以「数字标签 + 全大写
  标题」模式从正文识别章节起始页，生成其目录。

### 知识库后端（`com/chemlab/kb/`）

把「知识从哪来」与「界面怎么用」解耦，与项目既有的「能力探测 + 优雅降级」
约定一致：

```
KnowledgeBackend (id / name / available / search / readText)
├── LocalKbBackend   本地参考书：页级与条目级子串检索，分数按来源内最大值归一
└── ImaKbBackend     腾讯 ima：search_knowledge 语义召回，无分数时按名次给分
                     ▲
        HybridBackend ┘  按来源加权融合，默认「全部来源」
```

- `Hit`：统一命中项（`source / title / snippet / body / score / ref / kbName /
  page / readable`）。本地页级与 ima 条目级结构差异在此吸收，界面不认识
  任何具体来源。
- **融合**：两个来源的分数尺度不可直接比较，因此各自先在来源内归一化到
  `[0,1]`，再按 `0.4 × 本地 + 0.6 × ima` 加权；同分时本地优先（离线可读、
  无网络依赖）。结果按分数降序，稳定排序保证同分时仍保持来源内原有顺序。
- **降级**：`ImaKbBackend.available()` 只反映「凭据是否齐备」，不绑定最近一次
  连通——来源下拉项不应因一次网络抖动而消失；连通性失败在检索时逐次降级，
  由 `HybridBackend.lastNote()` 汇总为状态栏提示。凭据未配置时 `search`
  直接短路返回，**不发起任何网络请求**。
- **凭据**：落在 `data/ima.json`（与 `data/obsidian.json` 同级），读取优先级
  环境变量 → 配置文件；所有回显与日志一律经 `ImaConfig.mask()` 脱敏。
- **零依赖**：`ImaClient` 只用 JDK 原生 `HttpURLConnection` 发 HTTPS，请求体
  与响应经自带的 `util/Json.java` 读写，不引入任何 HTTP / JSON 第三方库。

### Obsidian 同步库（`<vault>/ChemLab/`）

`service/ObsidianSync` 以文件系统为唯一媒介，与 Obsidian 库双向同步：

- 目录结构：`ChemLab/{实验记录,标定曲线,知识库}/`，另加同步清单
  `.chemlab-sync.json` 与 `ChemLab 索引.md`（MOC）。
- 笔记格式：YAML frontmatter（`id` / `type` / `updated` 等）+ 正文；
  知识库笔记由章节/条目生成，文件名经 `safeName()` 清洗非法字符并去重。
- 编辑判定：Obsidian 改笔记只更新文件 mtime，不改 frontmatter，因此以
  「文件 mtime 明显晚于笔记 `updated` 时间戳（容差 3 秒）」判定库内编辑；
  导出后主动对齐文件 mtime 与笔记时间戳，避免秒级抖动误判。
- 同步语义：实验记录、标定曲线**双向**（库内新笔记导入、库内编辑回写、
  应用侧更新导出）；知识库**单向**导出为参考笔记。清单用于去重，保证幂等。

---

## 五、关键算法要点

- **Grubbs 检验**：单侧检验最大值，输出 G 统计量、临界值与离群点下标；
  `n<3` 返回 `CL_ERR_SIZE`，零方差返回 `CL_ERR_DEGEN`。
- **Dixon Q 检验**：按样本量自动选择 Q10/Q11/Q21/Q22 统计量，适用于 3~10 个小样本。
- **加权线性回归**：权重 `w = 1/s²`，用于异方差标定数据。
- **Savitzky–Golay**：最小二乘多项式拟合卷积，相比滑动平均更好保留峰高与峰形。
- **寻峰**：局部极大值 + 相对高度阈值 + 最小间距过滤。
- **误差传递**：加减法合成绝对不确定度 `√Σu²`；乘除法合成相对不确定度
  `√Σ(u/x)²`；一般函数式用中心差分估计偏导后合成。
- **正交表构造**：基于 `GF(p^k)` 射影点列生成标准表（L4/L8/L16/L9/L27/L25），
  列序与教科书一致，并对每对水平组合做强度-2 正交性校验。
- **谱图曲线提取**：定位坐标轴内框后按列分离曲线像素，k-means 颜色聚类
  区分多条曲线，再做坐标标定与信号处理。
- **滴定曲线模拟**（`cl_titration_curve`）：按体系类型（强酸/强碱/弱酸/弱碱/
  多元酸）计算逐点 pH，覆盖滴定前、缓冲区、突跃与等当点后；4 种体系关键点
  与教科书理论值偏差 < 0.006 pH。
- **色谱峰处理**（`cl_chrom_peaks`）：MAD 鲁棒迭代多项式基线扣除（残差中位数
  ±k·MAD 筛选基线点集，避免峰肩被拉入基线拟合）→ 寻峰 → 峰谷切分 → 梯形
  积分 → 相邻峰分离度（半峰宽法）。对双高斯峰 + 漂移基线的合成信号，
  峰高/面积/分离度与理论值偏差 < 5%。

---

## 六、错误处理与降级

1. **内核层**：纯函数返回状态码，不抛异常、不中断。
2. **Python 层**：`cbridge` 把状态码转 `CoreError`；`service.py` 捕获单条
   请求异常并返回错误报文，进程不退出。
3. **Java 层**：`NativeCore` 加载失败只记录不抛；`PythonBridge` 启动失败抛
   `PythonBridgeException`，由 `Main` 捕获后进入降级流程。
4. **界面层**：各面板在调用前判断 `NativeCore.isAvailable()` /
   `bridge.isAvailable()`，不可用时显示提示而非崩溃；`Main` 启动时统一弹出
   「降级提示」，列出缺失组件与受影响范围。

---

## 七、扩展指南

### 新增一个内核函数

1. 在 `core-cpp/include/chemlab_core.h` 声明（遵循状态码 + 输出缓冲约定）。
2. 在对应 `src/*.cpp` 实现。
3. JNI：在 `jni/chemlab_jni.cpp` 加桥接，在 `NativeCore.java` 加 `native` 声明。
4. ctypes：在 `cbridge.py` 的 `_bind()` 绑定签名并加包装方法。
5. 在 `cli.cpp` 的自检里补一条断言。

### 新增一个 Python 分析方法

1. 在 `service.py` 写 `_m_xxx(params)` 并注册进 `_METHODS`。
2. Java 侧通过 `bridge.call("xxx", params)` 调用，返回值按 `Map` 取用。

### 新增一个功能面板

1. 在 `app-java/src/com/chemlab/ui/panels/` 新建面板类。
2. 在 `MainFrame.buildPanels()` 注册卡片，并在 `buildSidebar()` 的标题数组
   中加一项（两处顺序须一致，`select(index)` 依赖此对应关系）。

---

## 八、验证清单

一键运行 `verify.ps1` 会依次执行下表全部检查并汇总；也可按行单独运行。
Java 侧冒烟测试源码位于 `tests/`（`JniSmoke.java`、`Smoke.java`、
`ObsidianSmoke.java`、`DocxSmoke.java`、`KbSmoke.java`）。

| 层级 | 命令 | 预期 |
| --- | --- | --- |
| 全部 | `verify.ps1` | 9 项检查全部 `[ OK ]` |
| C++ 内核 | `chemlab_cli.exe --selftest` | 107 项全部通过 |
| Python ↔ C++ | `python cbridge.py` | 打印内核路径与各算法结果 |
| Python 服务 | `python service.py --selftest` | 打印协议版本与归纳结论 |
| 光谱分析 | `python spectrum.py` | 输出绘图区、提取点数、峰与面积 |
| Java ↔ C++ | `tests/JniSmoke.java` | `JNI 冒烟测试通过`（含滴定曲线与色谱峰） |
| Java 全链路 | `tests/Smoke.java` | `SMOKE OK`（含 SQLite 与 Python 桥接） |
| Obsidian 同步 | `tests/ObsidianSmoke.java` | `OBSIDIAN OK`（17 项断言） |
| Word 报告 | `tests/DocxSmoke.java` | .docx 生成，zip 部件齐全，XML 良构，中文无损 |
| 知识库后端 | `tests/KbSmoke.java` | `KB OK`（60 项断言：本地加载/检索/正文回读/书目限定、凭据脱敏与持久化、ima 降级与融合权重；不发起网络请求） |
| 桌面应用 | `run.ps1` | 主窗口正常显示，状态栏三层均就绪 |
| 桌面启动器 | 双击 `ChemLab Analyzer.exe` | 无控制台窗口，主窗口正常显示，`data/launch.log` 仅含无害告警 |