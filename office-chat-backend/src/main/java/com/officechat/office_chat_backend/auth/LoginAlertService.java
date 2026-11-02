package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.mail.EmailService;
import com.officechat.office_chat_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/** Emails someone when their account is signed in from a device it hasn't seen before. */
@Service
@RequiredArgsConstructor
public class LoginAlertService {

    private final EmailService emailService;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${app.timezone:UTC}")
    private String timezone;

    public void newDevice(User user, String userAgent, Instant at) {
        if (!emailService.isEnabled()) return;
        String device = describe(userAgent);
        ZoneId zone;
        try { zone = ZoneId.of(timezone); } catch (Exception e) { zone = ZoneId.of("UTC"); }
        String when = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a z").withZone(zone).format(at);
        String link = frontendUrl.replaceAll("/+$", "") + "/";

        String html = "<div style=\"font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#0f172a\">"
                + "<h2 style=\"margin:0 0 12px\">Office Chat</h2>"
                + "<p>Hi " + esc(user.getName()) + ",</p>"
                + "<p>Your account was just signed in on a <b>new device</b>:</p>"
                + "<table style=\"border-collapse:collapse;margin:8px 0 16px\">"
                + "<tr><td style=\"padding:4px 12px 4px 0;color:#64748b\">Device</td><td><b>" + esc(device) + "</b></td></tr>"
                + "<tr><td style=\"padding:4px 12px 4px 0;color:#64748b\">Time</td><td><b>" + esc(when) + "</b></td></tr>"
                + "</table>"
                + "<p>If this was you, you can ignore this email.</p>"
                + "<p><b>If it wasn't you:</b> open Office Chat, change your password, and use "
                + "<i>Settings → Security → Where you're signed in → Sign out all others</i>.</p>"
                + "<p style=\"margin:20px 0\"><a href=\"" + link + "\" style=\"background:#4f46e5;color:#fff;padding:10px 18px;"
                + "border-radius:8px;text-decoration:none;font-weight:bold\">Open Office Chat</a></p></div>";
        String text = "Your Office Chat account was signed in on a new device.\nDevice: " + device + "\nTime: " + when
                + "\n\nIf this wasn't you, change your password and sign out other devices: " + link;

        // In the background so signing in isn't slowed down by the email.
        CompletableFuture.runAsync(() -> emailService.send(user.getEmail(), "New sign-in to your Office Chat account", html, text));
    }

    /** "Chrome on Windows" style label from a User-Agent. */
    static String describe(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("OPR/") || ua.contains("Opera") ? "Opera"
                : ua.contains("SamsungBrowser") ? "Samsung Internet"
                : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") || ua.contains("CriOS") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : "Browser";
        String os = ua.contains("Windows") ? "Windows"
                : ua.contains("iPhone") ? "iPhone"
                : ua.contains("iPad") ? "iPad"
                : ua.contains("Android") ? "Android"
                : ua.contains("Mac OS X") ? "Mac"
                : ua.contains("Linux") ? "Linux"
                : "unknown system";
        return browser + " on " + os;
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
