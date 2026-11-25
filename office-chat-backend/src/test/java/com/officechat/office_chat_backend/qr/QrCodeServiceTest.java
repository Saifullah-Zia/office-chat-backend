package com.officechat.office_chat_backend.qr;

import com.officechat.office_chat_backend.audit.AuditService;
import com.officechat.office_chat_backend.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeServiceTest {

    private final QrCodeRepository repo = Mockito.mock(QrCodeRepository.class);
    private final QrCodeService service = new QrCodeService(repo, Mockito.mock(AuditService.class));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "https://go.example.org/");
    }

    private QrCode code(boolean managed, boolean blocked) {
        return QrCode.builder().id(1L).code("abc").clientName("C").targetUrl("https://client.com").managed(managed).blocked(blocked).build();
    }

    @Test
    void acceptsWebsites() {
        assertEquals("https://client.com", service.cleanUrl(" client.com "));
        assertEquals("http://client.com/a?b=c", service.cleanUrl("http://client.com/a?b=c"));
        assertEquals("https://client.com:8443/x", service.cleanUrl("client.com:8443/x"));
    }

    @Test
    void refusesUnsafeLinks() {
        for (String bad : new String[]{"", null, "javascript:alert(1)", "data:text/html,x", "ftp://client.com", "localhost", "http://127.0.0.1",
                "https://user:pw@client.com", "https://go.example.org/q/abc", "not a link", "mailto:a@b.com"}) {
            assertThrows(ApiException.class, () -> service.cleanUrl(bad), String.valueOf(bad));
        }
    }

    @Test
    void scanFollowsBlocking() {
        Mockito.when(repo.findByCode("abc")).thenReturn(Optional.of(code(true, false)));
        assertEquals("https://client.com", service.resolve("abc"));
        Mockito.verify(repo).countScan(Mockito.eq(1L), Mockito.any());
        Mockito.when(repo.findByCode("abc")).thenReturn(Optional.of(code(true, true)));
        assertNull(service.resolve("abc"));
        assertNull(service.resolve("nope"));
    }

    @Test
    void logoMustBeAPng() {
        assertNull(QrCodeService.cleanLogo(null));
        assertNull(QrCodeService.cleanLogo(""));
        byte[] png = new byte[40];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        String ok = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        assertEquals(ok, QrCodeService.cleanLogo(ok));
        for (String bad : new String[]{"data:image/svg+xml;base64,AAAA", "data:image/png;base64,AAAA", "data:image/png;base64,\"/><script>", "https://x.com/a.png"}) {
            assertThrows(ApiException.class, () -> QrCodeService.cleanLogo(bad), bad);
        }
    }

    @Test
    void simpleCodesCannotBeBlocked() {
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(code(false, false)));
        assertThrows(ApiException.class, () -> service.setBlocked(9L, 1L, true));
    }
}
