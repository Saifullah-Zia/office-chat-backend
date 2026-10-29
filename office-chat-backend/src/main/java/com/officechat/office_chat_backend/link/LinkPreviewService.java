package com.officechat.office_chat_backend.link;

import com.officechat.office_chat_backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Open Graph metadata for a URL so the chat can show a preview card.
 *
 * Because the server fetches arbitrary user-supplied URLs, it guards against SSRF:
 * only http/https on default ports, every hop's host must resolve to public addresses only,
 * redirects are followed manually (max 3) and re-validated, and responses are size/time bounded.
 */
@Service
public class LinkPreviewService {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final int MAX_CACHE = 2000;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private record Cached(Map<String, String> data, Instant at) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public Map<String, String> preview(String rawUrl) {
        URI uri = parse(rawUrl);
        String key = uri.toString();

        Cached hit = cache.get(key);
        if (hit != null && hit.at().plus(CACHE_TTL).isAfter(Instant.now())) return hit.data();

        Map<String, String> data = fetch(uri);
        if (cache.size() >= MAX_CACHE) cache.clear();
        cache.put(key, new Cached(data, Instant.now()));
        return data;
    }

    private URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.length() > 2048) throw bad("Invalid URL");
        try {
            URI uri = new URI(rawUrl.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) throw bad("Only http and https links are supported");
            if (uri.getHost() == null || uri.getUserInfo() != null) throw bad("Invalid URL");
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) throw bad("Unsupported port");
            return uri;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("Invalid URL");
        }
    }

    private void assertPublicHost(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs.length == 0) throw bad("Host not found");
            for (InetAddress a : addrs) {
                if (!isPublic(a)) throw bad("Links to internal addresses can't be previewed");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("Host not found");
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0) return false;                               // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;    // 100.64.0.0/10 CGNAT
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;   // benchmarking
            if (b0 >= 224) return false;                             // multicast/reserved
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                 // fc00::/7 unique local
            // IPv4-mapped (::ffff:a.b.c.d) — check the embedded IPv4 address
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<String, String> fetch(URI start) {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            assertPublicHost(current.getHost());
            HttpRequest req = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "OfficeChatLinkPreview/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            HttpResponse<InputStream> res;
            try {
                res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw bad("Couldn't reach that site");
            }

            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                closeQuietly(res.body());
                String location = res.headers().firstValue("location").orElse(null);
                if (location == null) throw bad("Bad redirect");
                current = parse(current.resolve(location).toString());
                continue;
            }
            if (code >= 400) {
                closeQuietly(res.body());
                throw bad("That page returned an error");
            }
            String type = res.headers().firstValue("content-type").orElse("").toLowerCase();
            if (!type.contains("html")) {
                closeQuietly(res.body());
                return basic(current);
            }
            return parseHtml(current, readBounded(res.body()));
        }
        throw bad("Too many redirects");
    }

    private String readBounded(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1 && out.size() < MAX_BYTES) {
                out.write(buf, 0, Math.min(n, MAX_BYTES - out.size()));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> parseHtml(URI base, String html) {
        Map<String, String> data = basic(base);
        String title = firstNonBlank(meta(html, "og:title"), meta(html, "twitter:title"), tag(html, "title"));
        String desc = firstNonBlank(meta(html, "og:description"), meta(html, "twitter:description"), meta(html, "description"));
        String image = firstNonBlank(meta(html, "og:image"), meta(html, "twitter:image"));
        String site = meta(html, "og:site_name");

        if (title != null) data.put("title", clip(title, 200));
        if (desc != null) data.put("description", clip(desc, 300));
        if (site != null) data.put("siteName", clip(site, 80));
        if (image != null) {
            try {
                URI img = base.resolve(unescape(image.trim()));
                String scheme = img.getScheme() == null ? "" : img.getScheme().toLowerCase();
                if (scheme.equals("https") || scheme.equals("http")) data.put("image", img.toString());
            } catch (Exception ignored) {}
        }
        return data;
    }

    private Map<String, String> basic(URI uri) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("url", uri.toString());
        data.put("domain", uri.getHost().replaceFirst("^www\\.", ""));
        return data;
    }

    private static String meta(String html, String name) {
        String n = Pattern.quote(name);
        Pattern p1 = Pattern.compile("<meta[^>]+(?:property|name)\\s*=\\s*[\"']" + n + "[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Pattern p2 = Pattern.compile("<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*(?:property|name)\\s*=\\s*[\"']" + n + "[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p1.matcher(html);
        if (m.find()) return unescape(m.group(1));
        m = p2.matcher(html);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String tag(String html, String tagName) {
        Matcher m = Pattern.compile("<" + tagName + "[^>]*>([^<]*)</" + tagName + ">", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void closeQuietly(InputStream in) {
        try { in.close(); } catch (Exception ignored) {}
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }
}
