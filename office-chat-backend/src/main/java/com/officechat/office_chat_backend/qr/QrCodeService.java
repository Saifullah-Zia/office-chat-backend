package com.officechat.office_chat_backend.qr;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // No look-alikes (0/o, 1/l/i): the link may be typed by hand.
    private static final String ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";

    private static final String PNG_PREFIX = "data:image/png;base64,";
    private static final int MAX_LOGO_CHARS = 600_000;

    private final QrCodeRepository repository;
    private final AuditService auditService;

    /** Address printed inside managed codes. Must keep working for as long as the codes are in use. */
    @Value("${app.qr.base-url}")
    private String baseUrl;

    /** content = what the printed code holds. */
    public record View(Long id, String clientName, String targetUrl, boolean managed, boolean blocked, long scans,
                       Instant lastScanAt, Instant createdAt, String content, String logo) {}

    public List<View> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    public View create(Long adminId, String clientName, String url, boolean managed, String logo) {
        QrCode q = repository.save(QrCode.builder()
                .code(newCode()).clientName(cleanName(clientName)).targetUrl(cleanUrl(url))
                .managed(managed).logo(cleanLogo(logo)).createdAt(Instant.now()).build());
        auditService.log(adminId, "QR_CREATED", "QR_CODE", q.getId(), q.getClientName());
        return view(q);
    }

    /** Printed managed codes follow the new address straight away. logo: null leaves it as it is, "" removes it. */
    public View update(Long adminId, Long id, String clientName, String url, String logo) {
        QrCode q = find(id);
        String name = cleanName(clientName);
        String target = cleanUrl(url);
        if (!q.isManaged() && !target.equals(q.getTargetUrl())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A simple QR code holds its link in the print. Create a new one instead.");
        }
        repository.updateDetails(id, name, target, logo == null ? q.getLogo() : cleanLogo(logo));
        auditService.log(adminId, "QR_CHANGED", "QR_CODE", id, name);
        return view(find(id));
    }

    public View setBlocked(Long adminId, Long id, boolean blocked) {
        QrCode q = find(id);
        if (!q.isManaged()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A simple QR code goes straight to the client's website and can't be blocked.");
        }
        repository.updateBlocked(id, blocked);
        auditService.log(adminId, blocked ? "QR_BLOCKED" : "QR_UNBLOCKED", "QR_CODE", id, q.getClientName());
        return view(find(id));
    }

    public void delete(Long adminId, Long id) {
        QrCode q = find(id);
        repository.delete(q);
        auditService.log(adminId, "QR_DELETED", "QR_CODE", id, q.getClientName());
    }

    /** Someone scanned a managed code: where to send them, or null if it's blocked or unknown. */
    public String resolve(String code) {
        QrCode q = repository.findByCode(code).orElse(null);
        if (q == null || !q.isManaged() || q.isBlocked()) return null;
        try {
            repository.countScan(q.getId(), Instant.now());
        } catch (Exception e) {
            log.warn("QR scan not counted: {}", e.getMessage()); // never stops the visitor
        }
        return q.getTargetUrl();
    }

    private QrCode find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QR code not found"));
    }

    private View view(QrCode q) {
        return new View(q.getId(), q.getClientName(), q.getTargetUrl(), q.isManaged(), q.isBlocked(), q.getScans(),
                q.getLastScanAt(), q.getCreatedAt(), q.isManaged() ? base() + "/q/" + q.getCode() : q.getTargetUrl(), q.getLogo());
    }

    private String base() {
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private static String newCode() {
        StringBuilder b = new StringBuilder(8);
        for (int i = 0; i < 8; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Enter the client's name (up to 100 characters)");
        return n;
    }

    /** The page shrinks logos to a small PNG; anything else is refused. */
    static String cleanLogo(String logo) {
        if (logo == null || logo.isEmpty()) return null;
        try {
            if (!logo.startsWith(PNG_PREFIX) || logo.length() > MAX_LOGO_CHARS) throw new IllegalArgumentException();
            byte[] b = Base64.getDecoder().decode(logo.substring(PNG_PREFIX.length()));
            if (b.length < 33 || (b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') throw new IllegalArgumentException();
            return logo;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That logo can't be used. Try another picture.");
        }
    }

    /** A full http(s) address of a public website; "https://" is added if left out. */
    String cleanUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (!u.isEmpty() && !u.matches("(?i)^[a-z][a-z0-9+.-]*:(?!\\d).*")) u = "https://" + u;
        try {
            URI uri = new URI(u);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            boolean publicName = host != null && host.matches("(?i)^([a-z0-9-]+\\.)+[a-z][a-z0-9-]+$");
            String ascii = uri.toASCIIString();
            if (!web || !publicName || uri.getUserInfo() != null || ascii.length() > 500) throw new IllegalArgumentException();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equalsIgnoreCase(URI.create(base()).getHost()) && path.startsWith("/q/")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Use the client's own website, not another QR link");
            }
            return ascii;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a valid website link, e.g. https://example.com");
        }
    }
}
