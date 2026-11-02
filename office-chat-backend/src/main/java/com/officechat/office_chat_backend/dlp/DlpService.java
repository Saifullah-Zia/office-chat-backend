package com.officechat.office_chat_backend.dlp;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import com.officechat.office_chat_backend.settings.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data-loss prevention: spots card numbers, ID numbers, passwords and secret keys in outgoing text.
 * Mode (admin setting): "off", "warn" (sender must confirm) or "block".
 */
@Service
@RequiredArgsConstructor
public class DlpService {

    public static final String MODE_KEY = "dlp.mode";
    public static final Set<String> MODES = Set.of("off", "warn", "block");
    /** HTTP status the app recognises as "confirm before sending". */
    public static final HttpStatus WARN_STATUS = HttpStatus.PRECONDITION_REQUIRED;

    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    // Pakistani CNIC: 13 digits, written with or without dashes (35202-1234567-1 / 3520212345671)
    private static final Pattern CNIC = Pattern.compile("(?<![\\d-])\\d{5}-?\\d{7}-?\\d(?![\\d-])");
    // A number (6+ digits) right after an ID label ("CNIC: ...", "passport number AB1234567")
    private static final Pattern LABELLED_ID = Pattern.compile(
            "(?i)\\b(cnic|nic|nicop|id\\s*card|national\\s*id|passport|ssn)\\b\\W{0,12}(?:no\\.?|number|#)?\\W{0,5}[A-Z]{0,2}\\d[\\d -]{5,}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b");
    private static final Pattern PASSWORD = Pattern.compile("(?i)\\b(password|passwd|pwd|passcode|pin)\\s*(?:is|:|=)\\s*\\S{4,}");
    private static final Pattern SECRET = Pattern.compile(
            "AKIA[0-9A-Z]{16}|\\bsk-[A-Za-z0-9_-]{20,}|\\bghp_[A-Za-z0-9]{36}\\b|\\bxox[bpa]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private final SettingsService settings;
    private final AuditService auditService;

    public String mode() {
        String m = settings.get(MODE_KEY, "warn");
        return MODES.contains(m) ? m : "warn";
    }

    public void setMode(String mode, Long adminId) {
        if (!MODES.contains(mode)) throw new ApiException(HttpStatus.BAD_REQUEST, "Mode must be off, warn or block");
        settings.set(MODE_KEY, mode);
        auditService.log(adminId, "DLP_MODE_CHANGED", "SETTING", null, "Data-loss prevention: " + mode);
    }

    /** What sensitive data the text appears to contain, in plain words. */
    public List<String> findings(String text) {
        List<String> found = new ArrayList<>();
        if (text == null || text.isBlank()) return found;
        if (anyMatch(CARD, text, m -> luhn(m.replaceAll("[ -]", "")))) found.add("a card number");
        if (CNIC.matcher(text).find() || SSN.matcher(text).find() || LABELLED_ID.matcher(text).find()) found.add("an ID number");
        if (anyMatch(IBAN, text, m -> ibanValid(m.replace(" ", "")))) found.add("a bank account (IBAN)");
        if (PASSWORD.matcher(text).find()) found.add("a password");
        if (SECRET.matcher(text).find()) found.add("a secret key");
        return found;
    }

    /** Block mode: refuse. Applied on every path that creates or edits message text. */
    public void enforceBlock(String text, Long userId, Long conversationId) {
        if (!"block".equals(mode())) return;
        List<String> found = findings(text);
        if (found.isEmpty()) return;
        auditService.log(userId, "DLP_BLOCKED", "CONVERSATION", conversationId, "Blocked: " + String.join(", ", found));
        throw new ApiException(HttpStatus.FORBIDDEN,
                "Not sent: this looks like it contains " + String.join(" and ", found) + ". Sharing it here isn't allowed.");
    }

    /** Warn mode: ask the sender to confirm (the app re-sends with dlpConfirmed=true). */
    public void warnUnlessConfirmed(String text, Boolean confirmed, Long userId, Long conversationId) {
        if (!"warn".equals(mode()) || Boolean.TRUE.equals(confirmed)) {
            if ("warn".equals(mode()) && Boolean.TRUE.equals(confirmed) && !findings(text).isEmpty()) {
                auditService.log(userId, "DLP_WARNING_OVERRIDDEN", "CONVERSATION", conversationId,
                        "Sent anyway: " + String.join(", ", findings(text)));
            }
            return;
        }
        List<String> found = findings(text);
        if (!found.isEmpty()) {
            throw new ApiException(WARN_STATUS, "This looks like it contains " + String.join(" and ", found)
                    + ". Are you sure you want to send it?");
        }
    }

    private interface Check { boolean ok(String match); }

    private static boolean anyMatch(Pattern p, String text, Check check) {
        Matcher m = p.matcher(text);
        while (m.find()) if (check.ok(m.group())) return true;
        return false;
    }

    private static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl && (d *= 2) > 9) d -= 9;
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static boolean ibanValid(String iban) {
        if (iban.length() < 15 || iban.length() > 34) return false;
        String moved = iban.substring(4) + iban.substring(0, 4);
        StringBuilder num = new StringBuilder();
        for (char c : moved.toCharArray()) num.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        return new BigInteger(num.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
