package com.osmo.viewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/1.1 客户端：只为实现 WebDAV。
 *
 * 不用 HttpURLConnection 的原因：Android 那份实现把方法名限制在
 * [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH]，PROPFIND 会直接抛
 * ProtocolException（v0.5.0 的日志就是这么挂的）。这里自己发请求行即可。
 *
 * 只实现 WebDAV 需要的部分：Basic 认证、Content-Length / chunked、有限重定向。
 * 纯 Java，不依赖 Android，便于离线用真实服务器回归。
 *
 * v0.5.3 起连接复用（keep-alive）。之前每个请求都带 Connection: close，
 * 读 256KB 就要重新握手一次：WiFi 上 RTT 10~20ms，握手一两个往返 + TCP
 * 慢启动从很小的拥塞窗口起算，实测吞吐只有带宽上限的三分之一。现在响应
 * 完整读完（Content-Length 对齐或 chunked 正常收尾）就把 socket 放回池子，
 * 下次直接复用，慢启动的窗口也保住了。
 */
public final class HttpMini {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BODY = 32 * 1024 * 1024;
    /** 空闲连接池上限：双路解码 + 预读，两条就够，留点余量。 */
    private static final int POOL_SIZE = 4;
    /** 空闲连接最多留这么久，超过就当它已经被服务器关了。 */
    private static final long IDLE_TTL_MS = 20000;
    /**
     * 内核接收缓冲。默认值在 WiFi 抖动大、RTT 高时会让窗口涨不上去；
     * 512KB 足以吃下 10MB/s × 50ms 的 BDP。
     */
    private static final int RECV_BUF = 512 * 1024;

    /** 诊断计数：复用了多少次、新建了多少次、复用连接失效重试了多少次。 */
    public static volatile long reusedConnections;
    public static volatile long newConnections;
    public static volatile long staleRetries;

    public static String stats() {
        return "连接 复用=" + reusedConnections + " 新建=" + newConnections
                + " 失效重试=" + staleRetries;
    }

    /** 上传进度回调；返回 false 表示用户取消。 */
    public interface Progress {
        boolean onBytes(long done, long total);
    }

    public static final class Response {
        public int code;
        public String reason = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        /** 响应体是否按 Content-Length / chunked 恰好读完；没读完的连接不能复用。 */
        boolean bodyComplete;
        /** 这条连接能否放回池子。 */
        boolean keepAlive;

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    private static final class Idle {
        final Socket socket;
        final long since;

        Idle(Socket socket, long since) {
            this.socket = socket;
            this.since = since;
        }
    }

    private final String host;
    private final int port;
    private final boolean tls;
    private final String user;
    private final String password;
    private final int timeoutMs;
    private final Deque<Idle> pool = new ArrayDeque<>();

    public HttpMini(String host, int port, boolean tls, String user, String password, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.user = user;
        this.password = password;
        this.timeoutMs = timeoutMs;
    }

    /**
     * @param method 方法名，允许 PROPFIND / PROPPATCH 这类非标准方法
     * @param path   以 / 开头的路径（已做百分号编码），可含查询串
     */
    public Response request(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        String current = path;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            Response r = requestOnce(method, current, headers, body);
            if (r.code == 301 || r.code == 302 || r.code == 307 || r.code == 308) {
                String location = r.header("location");
                if (location == null) return r;
                current = toPath(location);
                continue;
            }
            return r;
        }
        throw new IOException("重定向次数过多");
    }

    private String toPath(String location) throws IOException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            int scheme = location.indexOf("://");
            int slash = location.indexOf('/', scheme + 3);
            return slash < 0 ? "/" : location.substring(slash);
        }
        return location.startsWith("/") ? location : "/" + location;
    }

    private Response requestOnce(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        boolean retryable = isIdempotent(method);
        IOException last = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = attempt == 0 ? takeIdle() : null;
            boolean reused = socket != null;
            boolean keep = false;
            try {
                if (socket == null) {
                    socket = connect();
                } else {
                    socket.setSoTimeout(timeoutMs);
                    reusedConnections++;
                }
                Response r = exchange(socket, method, path, headers, body);
                keep = r.keepAlive;
                return r;
            } catch (IOException e) {
                last = e;
                // 池子里的连接可能已经被对端关掉了：换个新连接重来。只重试幂等方法。
                if (!reused || !retryable) throw e;
                staleRetries++;
            } finally {
                if (socket != null) {
                    if (keep) release(socket);
                    else closeQuietly(socket);
                }
            }
        }
        throw last == null ? new IOException("请求失败") : last;
    }

    private Socket connect() throws IOException {
        Socket socket;
        if (tls) {
            socket = SSLSocketFactory.getDefault().createSocket();
        } else {
            socket = new Socket();
        }
        try {
            // 关掉 Nagle：一个请求/响应的往返不该等 40ms 的延迟确认。
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(RECV_BUF);
        } catch (Exception ignored) {
        }
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        newConnections++;
        return socket;
    }

    private Socket takeIdle() {
        long now = System.currentTimeMillis();
        synchronized (pool) {
            while (!pool.isEmpty()) {
                Idle idle = pool.pollFirst();
                if (now - idle.since > IDLE_TTL_MS || idle.socket.isClosed()) {
                    closeQuietly(idle.socket);
                    continue;
                }
                return idle.socket;
            }
        }
        return null;
    }

    private void release(Socket socket) {
        synchronized (pool) {
            if (pool.size() < POOL_SIZE) {
                pool.addLast(new Idle(socket, System.currentTimeMillis()));
                return;
            }
        }
        closeQuietly(socket);
    }

    /** 关闭池子里所有空闲连接（切文件 / 退出播放时调用）。 */
    public void closeIdle() {
        synchronized (pool) {
            while (!pool.isEmpty()) closeQuietly(pool.pollFirst().socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean isIdempotent(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "PROPFIND".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private Response exchange(Socket socket, String method, String path,
                              Map<String, String> headers, byte[] body) throws IOException {
        int len = body == null ? 0 : body.length;
        OutputStream out = socket.getOutputStream();
        out.write(buildHead(method, path, headers, len, false));
        if (len > 0) out.write(body);
        out.flush();
        return readResponse(socket.getInputStream(), method);
    }

    /**
     * 带请求体的上传（WebDAV PUT 之类）。
     *
     * 特意不用池里的空闲连接：写到一半发现连接已断的话，body 已经被消费掉了，
     * 没法像 GET 那样悄悄换个连接重试。传完连接照常放回池子。
     *
     * @param length   >= 0 用固定长度；< 0 走 chunked
     * @param progress 返回 false 表示取消
     */
    public Response upload(String path, Map<String, String> headers,
                           InputStream body, long length, Progress progress) throws IOException {
        Socket socket = connect();
        boolean keep = false;
        try {
            OutputStream out = socket.getOutputStream();
            out.write(buildHead("PUT", path, headers, length, length < 0));
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int n;
            while ((n = body.read(buf)) > 0) {
                if (length < 0) {
                    // chunked：每块单独带长度，服务器不知道总大小也能收
                    out.write((Integer.toHexString(n) + "\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.write(buf, 0, n);
                    out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    out.write(buf, 0, n);
                }
                done += n;
                if (progress != null && !progress.onBytes(done, length)) {
                    throw new IOException("传输已取消");
                }
            }
            if (length < 0) {
                out.write("0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.flush();
            Response r = readResponse(socket.getInputStream(), "PUT");
            keep = r.keepAlive;
            return r;
        } finally {
            if (keep) release(socket);
            else closeQuietly(socket);
        }
    }

    private byte[] buildHead(String method, String path, Map<String, String> headers,
                             long contentLength, boolean chunked) {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path.isEmpty() ? "/" : path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host);
        if (port != (tls ? 443 : 80)) head.append(':').append(port);
        head.append("\r\n");
        head.append("Connection: keep-alive\r\n");
        head.append("User-Agent: OsvViewer/1.0\r\n");
        head.append("Accept: */*\r\n");
        if (user != null && !user.isEmpty()) {
            String raw = user + ":" + (password == null ? "" : password);
            head.append("Authorization: Basic ")
                    .append(java.util.Base64.getEncoder().encodeToString(
                            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        boolean needLength = contentLength > 0
                || "PROPFIND".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method);
        if (chunked) {
            head.append("Transfer-Encoding: chunked\r\n");
        } else if (needLength) {
            head.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        head.append("\r\n");
        return head.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Response readResponse(InputStream in, String method) throws IOException {
        Response r = new Response();

        String status = readLine(in);
        if (status == null) throw new IOException("服务器没有返回状态行");
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) throw new IOException("无法解析状态行：" + status);
        r.code = parseInt(parts[1], -1);
        r.reason = parts.length > 2 ? parts[2] : "";

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }

        r.body = readBody(in, r, "HEAD".equalsIgnoreCase(method));

        String conn = r.header("connection");
        boolean serverClose = conn != null && conn.toLowerCase(Locale.US).contains("close");
        r.keepAlive = r.bodyComplete && !serverClose;
        return r;
    }

    private static byte[] readBody(InputStream in, Response r, boolean headRequest) throws IOException {
        // HEAD 只回头不回体：服务器给的 Content-Length 是资源大小，照它去读会一直等到超时。
        if (headRequest || r.code == 204 || r.code == 304 || r.code == 100) {
            r.bodyComplete = true;
            return new byte[0];
        }
        String te = r.header("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(in, r);
        }
        String cl = r.header("content-length");
        if (cl != null) {
            int len = parseInt(cl, 0);
            if (len <= 0) {
                r.bodyComplete = true;
                return new byte[0];
            }
            if (len > MAX_BODY) {
                // 超长响应体（服务器忽略了 Range 且文件很大）：读一部分就放弃，别把内存撑爆。
                r.bodyComplete = false;
                return readN(in, MAX_BODY);
            }
            byte[] data = readN(in, len);
            r.bodyComplete = data.length == len;
            return data;
        }
        // 没有长度：读到连接关闭为止，这条连接不能复用
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) break;
        }
        r.bodyComplete = false;
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in, Response r) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int semi = sizeLine.indexOf(';');
            if (semi > 0) sizeLine = sizeLine.substring(0, semi);
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (Exception e) {
                break;
            }
            if (size == 0) {
                readLine(in);       // 结尾的空行
                r.bodyComplete = true;
                break;
            }
            byte[] part = readN(in, size);
            out.write(part);
            if (part.length < size) break;
            readLine(in);           // 每块后的 CRLF
        }
        return out.toByteArray();
    }

    private static byte[] readN(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(data, got, len - got);
            if (n < 0) break;
            got += n;
        }
        if (got == len) return data;
        byte[] shorter = new byte[got];
        System.arraycopy(data, 0, shorter, 0, got);
        return shorter;
    }

    /** 按 CRLF 读一行；返回 null 表示连接已结束。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String s = out.toString("UTF-8");
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            out.write(c);
            if (out.size() > 16 * 1024) break;
        }
        return out.size() == 0 ? null : out.toString("UTF-8");
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 便于诊断：列出响应里的关键头。 */
    public static List<String> debugHeaders(Response r) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }
}
