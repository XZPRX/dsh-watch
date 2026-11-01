package com.dsh.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电脑端 dsh-watch 插件的最小客户端。
 *
 * 只做三件事：一条常驻 SSE 长连接收事件 / 几个 POST 发指令与审批 / 断线自动重连。
 * 不引入任何第三方库（HttpURLConnection + org.json 都是系统自带），包体才能压到几十 KB。
 */
final class Net {
    interface Listener {
        /** state: 0 离线，1 在线空闲，2 在线运行。 */
        void onStatus(int state, String detail);

        void onEvent(Event event);

        void onSession(String id, String title, boolean running);

        void onApproval(Approval approval);

        void onApprovalResolved(String id);

        void onLog(String line);

        /** 电脑报了外网地址（配了隧道时才有）。 */
        void onRemote(String url);

        /** 刚连上时补一段历史事件，避免重连后屏幕是空的。 */
        void onBackfill(java.util.List<Event> events);
    }

    /** 会话选择屏用的一条摘要。 */
    static final class SessionInfo {
        String id = "";
        String title = "";
        String cwd = "";
        boolean live;
        boolean running;
    }

    interface SessionsCallback {
        void done(boolean ok, String message, List<SessionInfo> sessions);
    }

    /** 配对结果：成功时带回长期令牌。 */
    interface PairCallback {
        void done(boolean ok, String message, String token, String[] addresses);
    }

    interface Callback {
        void done(boolean ok, String message);
    }

    private final Listener listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 每次 connect/disconnect 自增；旧循环发现代号不对就退出，避免多条 SSE 同时跑。 */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile String base = "";
    private volatile String token = "";
    private volatile long lastSeq = -1;
    private volatile String[] endpoints = new String[0];
    private volatile int endpointIndex = 0;
    private int failuresOnEndpoint = 0;
    private volatile HttpURLConnection sseConn;
    private Thread sseThread;
    private Thread pollThread;
    /** SSE 连续连不上几次后，改用轮询拿事件（外网隧道会把 SSE 缓冲掉）。 */
    private volatile boolean pollMode = false;
    private int sseConnectFailures = 0;

    Net(Listener listener) {
        this.listener = listener;
    }

    boolean isRunning() {
        return running.get();
    }

    static String normalize(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return "";
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    void connect(String host, String tokenValue) {
        connect(new String[]{host}, tokenValue);
    }

    /**
     * 连一组候选地址：配对时电脑会回多个局域网地址（同网段的排前面）。
     * 当前地址一直连不上会自动换下一个 —— 拔掉 USB 后 127.0.0.1 失效时会自己切到 WiFi 地址。
     */
    void connect(String[] hosts, String tokenValue) {
        disconnect();
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<String>();
        if (hosts != null) {
            for (String host : hosts) {
                String normalized = normalize(host);
                if (!normalized.isEmpty()) unique.add(normalized);
            }
        }
        endpoints = unique.toArray(new String[0]);
        endpointIndex = 0;
        base = endpoints.length > 0 ? endpoints[0] : "";
        token = tokenValue == null ? "" : tokenValue.trim();
        if (base.isEmpty() || token.isEmpty()) {
            listener.onStatus(0, "未配置服务器");
            return;
        }
        running.set(true);
        lastSeq = -1;
        final int gen = generation.incrementAndGet();
        sseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                sseLoop(gen);
            }
        }, "dsh-watch-sse");
        sseThread.setDaemon(true);
        sseThread.start();
        // 轮询线程：局域网里 SSE 正常时只是每 15 秒对一次状态；
        // 走外网隧道（SSE 被缓冲）时自动升到 2 秒一次，靠快照增量把事件补上。
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop(gen);
            }
        }, "dsh-watch-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        refreshState();
        listener.onLog("连接 " + base + (endpoints.length > 1 ? "（共 " + endpoints.length + " 个候选地址）" : ""));
    }

    private void pollLoop(int gen) {
        while (running.get() && generation.get() == gen) {
            long started = System.currentTimeMillis();
            try {
                refreshStateBlocking();
            } catch (Throwable error) {
                android.util.Log.w("dsh-watch", "poll failed: " + shortError(error));
            }
            long elapsed = System.currentTimeMillis() - started;
            long interval = pollMode ? 2000L : 15000L;
            long sleep = Math.max(500L, interval - elapsed);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    /** 当前实际在用的地址（含协议前缀）。 */
    String currentBase() {
        return base;
    }

    /** 运行中追加一个候选地址（比如电脑报的外网隧道），下一次重试就会用上。 */
    void addEndpoint(String url) {
        String normalized = normalize(url);
        if (normalized.isEmpty()) return;
        synchronized (this) {
            for (String existing : endpoints) {
                if (existing.equals(normalized)) return;
            }
            String[] next = new String[endpoints.length + 1];
            System.arraycopy(endpoints, 0, next, 0, endpoints.length);
            next[endpoints.length] = normalized;
            endpoints = next;
        }
        listener.onLog("记下备用地址：" + normalized);
    }

    private void rotateEndpoint() {
        if (endpoints.length <= 1) return;
        endpointIndex = (endpointIndex + 1) % endpoints.length;
        base = endpoints[endpointIndex];
        listener.onLog("换地址重试：" + base);
    }

    void disconnect() {
        running.set(false);
        // 让还在退避 sleep 里的旧循环也失效，避免 connect 之后新旧两个循环同时跑
        generation.incrementAndGet();
        HttpURLConnection c = sseConn;
        sseConn = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
        sseThread = null;
        Thread poller = pollThread;
        pollThread = null;
        if (poller != null) {
            try {
                poller.interrupt();
            } catch (Throwable ignored) {
            }
        }
        listener.onStatus(0, "已断开");
    }

    // ---- SSE ---------------------------------------------------------------

    private void sseLoop(int gen) {
        int backoff = 1000;
        while (running.get() && generation.get() == gen) {
            HttpURLConnection conn = null;
            boolean connected = false;
            try {
                String url = base + "/api/events?token=" + enc(token) + "&since=" + lastSeq;
                conn = open(url, "GET");
                // 服务端每 20s 发一次心跳；超过 45s 没有任何数据就当作死连接重连，
                // 否则拔网线/进程被杀时 readLine 会永远挂住，地址也切不过去。
                conn.setReadTimeout(45000);
                conn.setConnectTimeout(5000);
                sseConn = conn;
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IllegalStateException("HTTP " + code);
                }
                listener.onLog("事件流已连接");
                connected = true;
                backoff = 1000;
                sseConnectFailures = 0;
                if (pollMode) {
                    pollMode = false;
                    listener.onLog("事件流恢复，切回实时推送");
                }
                dispatchHello();
                failuresOnEndpoint = 0;
                readStream(conn.getInputStream());
                if (!running.get()) break;
                throw new IllegalStateException("事件流结束");
            } catch (Throwable error) {
                if (!running.get()) break;
                listener.onStatus(0, "重连中：" + shortError(error));
                listener.onLog("断线 " + shortError(error) + " @ " + base);
                if (!connected) {
                    sseConnectFailures++;
                    if (sseConnectFailures >= 3 && !pollMode) {
                        // 典型场景：外网隧道把 SSE 响应缓冲住，握手成功但一个字节都收不到。
                        // 这时别死等流，改走 2 秒一次的轮询，体验差一点但能用。
                        pollMode = true;
                        listener.onLog("事件流不可用，改用轮询同步（外网隧道常见）");
                    }
                    // 这个地址压根连不上（换了网络/隧道换地址）：立刻试下一个，别等退避。
                    rotateEndpoint();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    continue;
                }
                // 连上过又断了：同一个地址先重试一次，再换（避免网络抖一下就切走）。
                if (error instanceof java.net.SocketTimeoutException && !pollMode) {
                    // 握手成功却长时间收不到数据 —— 外网隧道缓冲 SSE 的典型症状。
                    sseConnectFailures++;
                    if (sseConnectFailures >= 2) {
                        pollMode = true;
                        listener.onLog("事件流长时间无数据，改用轮询同步");
                    }
                }
                failuresOnEndpoint++;
                if (failuresOnEndpoint >= 2) {
                    failuresOnEndpoint = 0;
                    rotateEndpoint();
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ignored) {
                    break;
                }
                backoff = Math.min(15000, backoff * 2);
            } finally {
                // 每次重连前一定把旧连接关掉，否则服务端会看到一堆僵尸订阅者。
                sseConn = null;
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void readStream(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String pendingEvent = "";
        StringBuilder data = new StringBuilder();
        String line;
        while (running.get() && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                handleFrame(pendingEvent, data.toString());
                pendingEvent = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                pendingEvent = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            } else if (line.startsWith("id:")) {
                try {
                    long id = Long.parseLong(line.substring(3).trim());
                    if (id > lastSeq) lastSeq = id;
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void handleFrame(String event, String data) {
        if (data.isEmpty()) return;
        try {
            if ("ev".equals(event)) {
                JSONObject o = new JSONObject(data);
                Event e = Event.from(o);
                if (e.seq > lastSeq) lastSeq = e.seq;
                if ("approval".equals(e.kind)) {
                    // 详情镜像也当审批卡（老客户端兜底）
                    listener.onEvent(e);
                } else {
                    listener.onEvent(e);
                    if ("status".equals(e.kind)) {
                        listener.onStatus(e.running ? 2 : 1, e.running ? "运行中" : "空闲");
                    }
                }
            } else if ("approval".equals(event)) {
                listener.onApproval(Approval.from(new JSONObject(data)));
                listener.onStatus(2, "等待审批");
            } else if ("approval-resolved".equals(event)) {
                listener.onApprovalResolved(new JSONObject(data).optString("id", ""));
            } else if ("hello".equals(event)) {
                JSONObject o = new JSONObject(data);
                if (o.has("lastSeq")) lastSeq = Math.max(lastSeq, o.optLong("lastSeq", -1));
            } else if ("selected".equals(event)) {
                refreshState();
            }
        } catch (Throwable error) {
            android.util.Log.w("dsh-watch", "frame failed: " + error, error);
            listener.onLog("帧解析失败 " + shortError(error));
        }
    }

    private void dispatchHello() {
        // hello 帧本身由 readStream 解析；这里只把 UI 标成在线。
        listener.onStatus(1, "在线");
    }

    // ---- 请求 --------------------------------------------------------------

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("x-dsh-watch-token", token);
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        return conn;
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection conn = open(base + path + (path.contains("?") ? "&" : "?") + "token=" + enc(token), "GET");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + body);
            return new JSONObject(body);
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject postJson(String path, JSONObject payload) throws Exception {
        HttpURLConnection conn = open(base + path, "POST");
        try {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) throw new IllegalStateException(body.isEmpty() ? "HTTP " + code : body);
            return new JSONObject(body);
        } finally {
            conn.disconnect();
        }
    }

    /** 主线程也可以直接调：真正的网络请求丢到线程池，避免 NetworkOnMainThreadException。 */
    void refreshState() {
        if (!running.get() || base.isEmpty()) return;
        pool.execute(this::refreshStateBlocking);
    }

    private void refreshStateBlocking() {
        if (!running.get() || base.isEmpty()) return;
        try {
            JSONObject state = getJson("/api/state");
            String remote = state.optString("remote", "");
            if (!remote.isEmpty()) listener.onRemote(remote);
            JSONArray history = state.optJSONArray("events");
            if (history != null && history.length() > 0) {
                List<Event> past = new ArrayList<Event>();
                for (int i = 0; i < history.length(); i++) {
                    JSONObject item = history.optJSONObject(i);
                    if (item == null) continue;
                    Event event = Event.from(item);
                    if (event.visible()) past.add(event);
                }
                if (!past.isEmpty()) listener.onBackfill(past);
            }
            JSONObject selected = state.optJSONObject("selected");
            if (selected != null) {
                listener.onSession(
                        selected.optString("id", ""),
                        selected.optString("title", ""),
                        selected.optBoolean("running", false));
                listener.onStatus(selected.optBoolean("running", false) ? 2 : 1, selected.optBoolean("running", false) ? "运行中" : "空闲");
            } else {
                listener.onStatus(1, "在线（无活动会话）");
            }
            JSONArray pending = state.optJSONArray("pending");
            if (pending != null) {
                for (int i = 0; i < pending.length(); i++) {
                    listener.onApproval(Approval.from(pending.getJSONObject(i)));
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("dsh-watch", "refreshState failed: " + error, error);
            listener.onLog("状态读取失败 " + shortError(error));
            listener.onStatus(0, "连接异常");
        }
    }

    void sendPrompt(final String text, final boolean steer, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", text);
                if (steer) body.put("mode", "steer");
                JSONObject result = postJson("/api/prompt", body);
                callback.done(result.optBoolean("ok", true), steer ? "已插入" : "已发送");
            } catch (Throwable error) {
                callback.done(false, "发送失败：" + shortError(error));
            }
        });
    }

    void decide(final String approvalId, final boolean allow, final Callback callback) {
        // 立即在本地判掉，避免网络抖动时卡片挂着
        pool.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("id", approvalId);
                body.put("decision", allow ? "allow" : "deny");
                postJson("/api/approval", body);
                callback.done(true, allow ? "已允许" : "已拒绝");
            } catch (Throwable error) {
                callback.done(false, "审批回传失败：" + shortError(error));
            }
        });
    }

    void cancelTurn(final Callback callback) {
        pool.execute(() -> {
            try {
                postJson("/api/cancel", new JSONObject());
                callback.done(true, "已请求停止");
            } catch (Throwable error) {
                callback.done(false, "停止失败：" + shortError(error));
            }
        });
    }

    void newSession(final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject result = postJson("/api/new", new JSONObject());
                callback.done(true, result.optString("title", "已新建会话"));
            } catch (Throwable error) {
                callback.done(false, "新建失败：" + shortError(error));
            }
        });
    }

    /** 拉会话列表（含持久化会话），左滑进来的选择屏用。 */
    void listSessions(final SessionsCallback callback) {
        pool.execute(() -> {
            try {
                JSONObject result = getJson("/api/sessions?all=1");
                JSONArray array = result.optJSONArray("sessions");
                List<SessionInfo> list = new ArrayList<SessionInfo>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.getJSONObject(i);
                        SessionInfo info = new SessionInfo();
                        info.id = item.optString("id", "");
                        info.title = item.optString("title", "");
                        info.cwd = item.optString("cwd", "");
                        info.live = item.optBoolean("live", false);
                        info.running = item.optBoolean("running", false);
                        if (!info.id.isEmpty()) list.add(info);
                    }
                }
                callback.done(true, "", list);
            } catch (Throwable error) {
                callback.done(false, shortError(error), null);
            }
        });
    }

    /** 切换到某个已有会话（冷会话由插件负责 resume）。 */
    void selectSession(final String sessionId, final Callback callback) {
        pool.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("sessionId", sessionId);
                postJson("/api/select", body);
                callback.done(true, "已切换会话");
            } catch (Throwable error) {
                callback.done(false, "切换失败：" + shortError(error));
            }
        });
    }

    /**
     * 用 6 位配对码换长期令牌（不需要预置令牌）。
     * host 支持 "192.168.1.5:8765" 这种形式。
     */
    void pair(final String host, final String code, final PairCallback callback) {
        pool.execute(() -> {
            String baseUrl = normalize(host);
            if (baseUrl.isEmpty()) {
                callback.done(false, "服务器地址为空", "", null);
                return;
            }
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("code", code == null ? "" : code.trim());
                conn = (HttpURLConnection) new URL(baseUrl + "/api/pair").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream out = conn.getOutputStream();
                out.write(bytes);
                out.flush();
                out.close();
                int status = conn.getResponseCode();
                InputStream in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
                String text = readAll(in);
                JSONObject json = new JSONObject(text.isEmpty() ? "{}" : text);
                if (status < 200 || status >= 300 || !json.optBoolean("ok", false)) {
                    String message = json.optString("error", "");
                    callback.done(false, message.isEmpty() ? "配对失败：HTTP " + status : message, "", null);
                    return;
                }
                String issued = json.optString("token", "");
                if (issued.isEmpty()) {
                    callback.done(false, "服务器没返回令牌", "", null);
                    return;
                }
                // 电脑会回一组候选地址（同网段优先），连不上会自动换下一个。
                JSONArray list = json.optJSONArray("addresses");
                List<String> addresses = new ArrayList<String>();
                addresses.add(host);
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        String item = list.optString(i, "");
                        if (!item.isEmpty() && !addresses.contains(item)) addresses.add(item);
                    }
                }
                callback.done(true, json.optString("host", ""), issued, addresses.toArray(new String[0]));
            } catch (Throwable error) {
                callback.done(false, "配对失败：" + shortError(error), "", null);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ---- 小工具 ------------------------------------------------------------

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();
        return sb.toString();
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    static String shortError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
        return message.length() > 90 ? message.substring(0, 90) + "…" : message;
    }
}
