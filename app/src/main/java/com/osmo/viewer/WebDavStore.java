package com.osmo.viewer;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * WebDAV：HTTP 上的文件协议，适合跨网络 / 反代 / HTTPS。
 *
 * 只用到 PROPFIND（列目录）与带 Range 的 GET（按偏移读）。底层走自带的
 * HttpMini —— Android 的 HttpURLConnection 不允许 PROPFIND 这种自定义方法，
 * 会直接抛 ProtocolException（v0.5.0 的日志就是这么挂的）。
 */
public class WebDavStore implements RemoteStore {

    private static final int TIMEOUT_MS = 20000;
    /** 单次 Range 请求最多读多少：块大小（1MB）之上留点余量，一次请求就取完一块。 */
    private static final int MAX_READ = 2 * 1024 * 1024;

    private final HttpMini http;
    /** 配置根目录在服务器上的路径，例如 "/dav"，用于把 href 折成相对路径。 */
    private final String rootPath;

    public WebDavStore(RemoteConfig cfg) {
        int port = cfg.port > 0 ? cfg.port : cfg.defaultPort();
        String base = cfg.path == null ? "" : cfg.path.trim();
        while (base.startsWith("/")) base = base.substring(1);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String encoded = encodePath(base);
        rootPath = encoded.isEmpty() ? "" : "/" + encoded;
        http = new HttpMini(cfg.host, port, cfg.https, cfg.user, cfg.password, TIMEOUT_MS);
        Diag.i("WebDav", "根路径 " + (cfg.https ? "https" : "http") + "://"
                + cfg.host + ":" + port + (rootPath.isEmpty() ? "/" : rootPath));
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    /**
     * @param trailingSlash 目录要带结尾斜杠（部分服务器只认这个形式）；
     *                      文件绝不能带，否则服务器当成目录直接 404。
     */
    private String urlPath(String rel, boolean trailingSlash) {
        String encoded = encodePath(rel == null ? "" : rel);
        String prefix = rootPath.isEmpty() ? "" : rootPath;
        String path = prefix + (encoded.isEmpty() ? "" : "/" + encoded);
        if (path.isEmpty()) path = "/";
        return trailingSlash && !path.endsWith("/") ? path + "/" : path;
    }

    @Override
    public List<Entry> list(String dir) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Depth", "1");
        headers.put("Content-Type", "application/xml; charset=utf-8");
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                + "<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>"
                + "</d:prop></d:propfind>";
        String path = urlPath(dir, true);
        HttpMini.Response r = http.request("PROPFIND", path, headers, body.getBytes("UTF-8"));
        Diag.i("WebDav", "PROPFIND " + path + " -> " + r.code);
        if (r.code == 401) throw new IOException("WebDAV 认证失败（用户名或密码不对）");
        if (r.code == 404) throw new IOException("WebDAV 路径不存在：" + path);
        if (r.code != 207 && r.code != 200) {
            throw new IOException("WebDAV PROPFIND 返回 " + r.code + " " + r.reason);
        }
        return parseMultiStatus(r.body, dir);
    }

    private List<Entry> parseMultiStatus(byte[] xml, String dir) throws IOException {
        final List<Entry> out = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            final String self = dir == null ? "" : dir.trim();
            parser.parse(new ByteArrayInputStream(xml), new DefaultHandler() {
                private final StringBuilder text = new StringBuilder();
                private String href;
                private boolean isDir;
                private long size = -1;
                private long modified;

                @Override
                public void startElement(String uri, String localName, String qName,
                                         Attributes attributes) {
                    String name = localName == null || localName.isEmpty() ? qName : localName;
                    text.setLength(0);
                    if ("response".equals(name)) {
                        href = null;
                        isDir = false;
                        size = -1;
                        modified = 0;
                    } else if ("collection".equals(name)) {
                        isDir = true;
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    text.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    String name = localName == null || localName.isEmpty() ? qName : localName;
                    String value = text.toString().trim();
                    if ("href".equals(name)) {
                        href = value;
                    } else if ("getcontentlength".equals(name)) {
                        try {
                            size = Long.parseLong(value);
                        } catch (Exception ignored) {
                        }
                    } else if ("getlastmodified".equals(name)) {
                        modified = parseHttpDate(value);
                    } else if ("response".equals(name)) {
                        String rel = relativize(href);
                        if (rel != null && !rel.isEmpty() && !rel.equals(self)) {
                            String fileName = RemoteStore.baseName(rel);
                            if (!fileName.isEmpty()) {
                                out.add(new Entry(rel, fileName, isDir, isDir ? 0 : size, modified));
                            }
                        }
                    }
                    text.setLength(0);
                }
            });
        } catch (Exception e) {
            throw new IOException("WebDAV 目录解析失败：" + e.getMessage(), e);
        }
        return out;
    }

    /** href 是服务器上的绝对路径（可能带协议主机），折成相对配置根目录的路径。 */
    private String relativize(String href) {
        if (href == null) return null;
        String h = href;
        int scheme = h.indexOf("://");
        if (scheme > 0) {
            int pathStart = h.indexOf('/', scheme + 3);
            h = pathStart < 0 ? "/" : h.substring(pathStart);
        }
        try {
            h = URLDecoder.decode(h, "UTF-8");
        } catch (Exception ignored) {
        }
        if (!rootPath.isEmpty()) {
            if (!h.startsWith(rootPath)) return null;   // 不在配置目录下
            h = h.substring(rootPath.length());
        }
        while (h.startsWith("/")) h = h.substring(1);
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    @Override
    public int readAt(String path, long pos, byte[] buf, int off, int len) throws IOException {
        int want = Math.min(len, MAX_READ);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Range", "bytes=" + pos + "-" + (pos + want - 1));
        HttpMini.Response r = http.request("GET", urlPath(path, false), headers, null);

        if (r.code == 416) return -1;
        if (r.code == 401) throw new IOException("WebDAV 认证失败");
        if (r.code == 404) throw new IOException("文件不存在：" + path);
        if (r.code != 206 && r.code != 200) {
            throw new IOException("WebDAV 读取返回 " + r.code + " " + r.reason);
        }

        // 服务器忽略 Range 时返回整文件（从 0 开始），得自己跳过前面的字节
        int skip = 0;
        if (r.code == 200) {
            skip = (int) Math.min(pos, Integer.MAX_VALUE);
        } else {
            long start = parseContentRangeStart(r.header("content-range"));
            if (start >= 0 && start != pos) skip = (int) Math.max(0, pos - start);
        }
        if (skip >= r.body.length) return -1;
        int n = Math.min(want, r.body.length - skip);
        if (n <= 0) return -1;
        System.arraycopy(r.body, skip, buf, off, n);
        return n;
    }

    @Override
    public long size(String path) throws IOException {
        HttpMini.Response head = http.request("HEAD", urlPath(path, false), null, null);
        if (head.code == 200 || head.code == 206) {
            long len = parseLong(head.header("content-length"), -1);
            if (len > 0) return len;
        }
        if (head.code == 401) throw new IOException("WebDAV 认证失败");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Range", "bytes=0-0");
        HttpMini.Response r = http.request("GET", urlPath(path, false), headers, null);
        if (r.code == 401) throw new IOException("WebDAV 认证失败");
        if (r.code == 404) throw new IOException("文件不存在：" + path);
        long total = parseContentRangeTotal(r.header("content-range"));
        if (total > 0) return total;
        return parseLong(r.header("content-length"), -1);
    }

    private static long parseContentRangeStart(String value) {
        if (value == null) return -1;
        int space = value.indexOf(' ');
        int dash = value.indexOf('-');
        if (dash < 0) return -1;
        String start = value.substring(space >= 0 ? space + 1 : 0, dash);
        return parseLong(start, -1);
    }

    private static long parseContentRangeTotal(String value) {
        if (value == null) return -1;
        int slash = value.lastIndexOf('/');
        if (slash < 0) return -1;
        String total = value.substring(slash + 1).trim();
        if (total.equals("*")) return -1;
        return parseLong(total, -1);
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseHttpDate(String text) {
        if (text == null) return 0;
        String[] patterns = {"EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
                java.util.Date d = f.parse(text.trim());
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    @Override
    public void upload(String path, java.io.InputStream in, long length, Progress cb)
            throws IOException {
        HttpMini.Response r = http.upload(urlPath(path, false), null, in, length,
                cb == null ? null : cb::onBytes);
        if (r.code == 401) throw new IOException("WebDAV 认证失败");
        if (r.code == 403) throw new IOException("WebDAV 拒绝写入（目录可能没有写权限）");
        if (r.code == 409) throw new IOException("WebDAV 目标目录不存在：" + path);
        if (r.code < 200 || r.code >= 300) {
            throw new IOException("WebDAV 上传返回 " + r.code + " " + r.reason);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        HttpMini.Response r = http.request("DELETE", urlPath(path, false), null, null);
        if (r.code == 401) throw new IOException("WebDAV 认证失败");
        if (r.code == 403) throw new IOException("WebDAV 拒绝删除（可能没有写权限）");
        if (r.code == 404) return;   // 本来就不在，视为成功
        if (r.code < 200 || r.code >= 300) {
            throw new IOException("WebDAV 删除返回 " + r.code + " " + r.reason);
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        HttpMini.Response r = http.request("HEAD", urlPath(path, false), null, null);
        if (r.code == 401) throw new IOException("WebDAV 认证失败");
        if (r.code == 404) return false;
        return r.code >= 200 && r.code < 300;
    }

    /** 逐级 MKCOL；405（已存在）当成功处理，各家 NAS 的返回码并不统一。 */
    @Override
    public void mkdirs(String dir) throws IOException {
        if (dir == null) return;
        String acc = "";
        for (String seg : dir.split("/")) {
            if (seg.isEmpty()) continue;
            acc = acc.isEmpty() ? seg : acc + "/" + seg;
            HttpMini.Response r = http.request("MKCOL", urlPath(acc, true), null, null);
            if (r.code == 401) throw new IOException("WebDAV 认证失败");
            if (r.code == 405 || r.code == 301) continue;   // 已经存在
            if (r.code == 409) throw new IOException("WebDAV 上级目录不存在：" + acc);
            if (r.code < 200 || r.code >= 300) {
                throw new IOException("WebDAV 建目录返回 " + r.code + " " + r.reason);
            }
        }
    }

    @Override
    public void close() {
        http.closeIdle();
    }
}
