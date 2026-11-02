package com.officechat.office_chat_backend.dlp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DlpServiceTest {

    // findings() is pure; settings/audit aren't touched.
    private final DlpService dlp = new DlpService(null, null);

    @Test
    void detectsSensitiveData() {
        assertEquals(List.of("a card number"), dlp.findings("card 4111 1111 1111 1111 exp 12/27"));
        assertEquals(List.of("an ID number"), dlp.findings("my CNIC is 35202-1234567-1"));
        assertEquals(List.of("an ID number"), dlp.findings("1560217890297"));          // CNIC without dashes
        assertEquals(List.of("an ID number"), dlp.findings("CNIC: 1560217890297"));
        assertEquals(List.of("an ID number"), dlp.findings("passport number AB1234567"));
        assertEquals(List.of("an ID number"), dlp.findings("SSN 123-45-6789"));
        assertEquals(List.of("a bank account (IBAN)"), dlp.findings("IBAN GB82 WEST 1234 5698 7654 32"));
        assertEquals(List.of("a password"), dlp.findings("the wifi password: Summer2026!"));
        assertEquals(List.of("a secret key"), dlp.findings("key AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void ignoresOrdinaryText() {
        assertTrue(dlp.findings("Meeting at 3pm in room 204, call 0300-1234567").isEmpty());
        assertTrue(dlp.findings("call me on 03001234567 or +923001234567").isEmpty()); // phone numbers
        assertTrue(dlp.findings("Order 1234 5678 9012 3456 shipped").isEmpty()); // fails the Luhn check
        assertTrue(dlp.findings("I forgot my password again").isEmpty());
        assertTrue(dlp.findings("").isEmpty());
    }
}
