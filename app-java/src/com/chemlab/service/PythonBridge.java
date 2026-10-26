package com.chemlab.service;

import com.chemlab.util.AppPaths;
import com.chemlab.util.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 主应用与 Python 分析服务之间的常驻进程桥接。
 *
 * <p>启动 {@code analysis-python/service.py} 后，双方以「一行一个 JSON 报文」
 * 通信。相比每次分析都新起进程，常驻可省去反复加载 OpenCV/NumPy 的开销
 * （实测约 1 秒）；相比 HTTP 服务，不占用端口、不会与其他软件冲突。
 *
 * <p>调用模型：请求带自增 id，响应按 id 归还给对应的 {@link CompletableFuture}，
 * 因此可安全地在后台线程并发调用。传输失败或方法报错统一抛
 * {@link PythonBridgeException}，由界面层捕获后降级提示。
 */
public final class PythonBridge implements AutoCloseable {

    /** 分析服务相关错误。 */
    public static class PythonBridgeException extends RuntimeException {
        public PythonBridgeException(String message) {
            super(message);
        }

        public PythonBridgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final int READY_TIMEOUT_SEC = 40;
    private static final int STDERR_KEEP = 40;

    private final Process process;
    private final BufferedWriter writer;
    private final Thread reader;
    private final Thread stderrDrain;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Long, CompletableFuture<Map<String, Object>>> pending =
            new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final String pythonExe;

    private volatile boolean closed;
    private volatile boolean visionAvailable;

    private PythonBridge(Process process, String pythonExe) {
        this.process = process;
        this.pythonExe = pythonExe;
        this.writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new Thread(this::readLoop, "chemlab-python-reader");
        this.reader.setDaemon(true);
        this.stderrDrain = new Thread(this::stderrLoop, "chemlab-python-stderr");
        this.stderrDrain.setDaemon(true);
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    private static final String[] FALLBACK_EXES = {"python", "python3", "py"};

    /**
     * 启动分析服务。若 Python 或脚本不可用，抛 {@link PythonBridgeException}，
     * 调用方应捕获后以「光谱分析不可用」降级，而不是让整个应用启动失败。
     */
    public static PythonBridge start() {
        Path service = AppPaths.pythonService();
        if (service == null) {
            throw new PythonBridgeException(
                    "未找到 analysis-python/service.py，无法启用 Python 分析服务");
        }

        List<String> candidates = new ArrayList<>();
        String prop = System.getProperty("chemlab.python");
        if (prop != null && !prop.isEmpty()) {
            candidates.add(prop);
        }
        String env = System.getenv("CHEMLAB_PYTHON");
        if (env != null && !env.isEmpty()) {
            candidates.add(env);
        }
        for (String exe : FALLBACK_EXES) {
            if (!candidates.contains(exe)) {
                candidates.add(exe);
            }
        }

        String lastError = "未尝试任何解释器";
        PythonBridge fallback = null;
        for (String exe : candidates) {
            PythonBridge bridge = null;
            try {
                bridge = spawn(exe, service);
                bridge.awaitReady();
                if (bridge.visionAvailable()) {
                    if (fallback != null) {
                        fallback.close();
                    }
                    return bridge;
                }
                // 服务可用但缺少 OpenCV/NumPy：先留作降级候选，继续寻找能力更全的解释器
                if (fallback == null) {
                    fallback = bridge;
                } else {
                    bridge.close();
                }
            } catch (Exception e) {
                if (bridge != null) {
                    bridge.close();
                }
                lastError = exe + " → " + e.getMessage();
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new PythonBridgeException("无法启动 Python 分析服务：" + lastError);
    }

    private static PythonBridge spawn(String exe, Path service) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                exe, "-u", service.toString());
        pb.directory(service.getParent().toFile());
        Map<String, String> env = pb.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        pb.redirectErrorStream(false);

        Process p = pb.start();
        PythonBridge bridge = new PythonBridge(p, exe);
        bridge.reader.start();
        bridge.stderrDrain.start();
        return bridge;
    }

    private void awaitReady() throws IOException, TimeoutException, InterruptedException {
        try {
            ready.get(READY_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException("分析服务启动后立即退出：" + stderrSummary(), e);
        } catch (TimeoutException e) {
            close();
            throw new IOException("分析服务在 " + READY_TIMEOUT_SEC + " 秒内未就绪");
        }
    }

    public boolean isAvailable() {
        return !closed && process.isAlive() && ready.isDone() && !ready.isCompletedExceptionally();
    }

    /** 分析服务是否具备光谱图像分析能力（解释器内含 OpenCV/NumPy）。 */
    public boolean visionAvailable() {
        return isAvailable() && visionAvailable;
    }

    public String interpreter() {
        return pythonExe;
    }

    /** 供界面显示的诊断信息。 */
    public String stderrSummary() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "(无输出)";
            }
            return String.join(" | ", stderrTail);
        }
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    public Map<String, Object> call(String method, Map<String, Object> params) {
        return call(method, params, DEFAULT_TIMEOUT_MS);
    }

    public Map<String, Object> call(String method, Map<String, Object> params, long timeoutMs) {
        if (closed) {
            throw new PythonBridgeException("分析服务已关闭");
        }
        if (!process.isAlive()) {
            throw new PythonBridgeException("分析服务进程已退出：" + stderrSummary());
        }

        long id = ids.getAndIncrement();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", id);
        req.put("method", method);
        req.put("params", params == null ? new LinkedHashMap<String, Object>() : params);

        try {
            synchronized (writeLock) {
                writer.write(Json.write(req));
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            throw new PythonBridgeException("向分析服务发送请求失败：" + e.getMessage(), e);
        }

        Map<String, Object> resp;
        try {
            resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new PythonBridgeException("分析服务响应超时（" + timeoutMs + " ms）");
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new PythonBridgeException("调用分析服务被中断");
        } catch (ExecutionException e) {
            pending.remove(id);
            throw new PythonBridgeException("分析服务调用失败：" + e.getCause().getMessage(), e);
        }

        if (!Json.bool(resp.get("ok"), false)) {
            throw new PythonBridgeException("分析服务返回错误：" + Json.str(resp.get("error"), "未知错误"));
        }
        Object result = resp.get("result");
        if (result instanceof Map) {
            return Json.asObject(result);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", result);
        return wrapped;
    }

    // ------------------------------------------------------------------
    // 后台线程
    // ------------------------------------------------------------------

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Map<String, Object> msg;
                try {
                    msg = Json.asObject(Json.parse(line));
                } catch (RuntimeException e) {
                    System.err.println("[chemlab] 分析服务输出非 JSON：" + line);
                    continue;
                }
                long id = (long) Json.num(msg.get("id"), -1);
                if (id == 0) {
                    Object result = msg.get("result");
                    if (result instanceof Map) {
                        visionAvailable = Json.bool(Json.asObject(result).get("vision"), false);
                    }
                    ready.complete(null);
                    continue;
                }
                CompletableFuture<Map<String, Object>> f = pending.remove(id);
                if (f != null) {
                    f.complete(msg);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                System.err.println("[chemlab] 读取分析服务输出失败：" + e.getMessage());
            }
        } finally {
            failAllPending("分析服务进程已结束：" + stderrSummary());
            if (!ready.isDone()) {
                ready.completeExceptionally(new IOException("分析服务未就绪即退出"));
            }
        }
    }

    private void stderrLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > STDERR_KEEP) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束时读取中断属正常情况
        }
    }

    private void failAllPending(String reason) {
        for (Long id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<Map<String, Object>> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(new IOException(reason));
            }
        }
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            synchronized (writeLock) {
                writer.write("__quit__\n");
                writer.flush();
            }
        } catch (IOException ignored) {
            // 进程可能已退出，忽略
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        failAllPending("分析服务已关闭");
    }
}