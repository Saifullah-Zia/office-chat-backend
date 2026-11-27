package com.officechat.office_chat_backend.file;

import com.officechat.office_chat_backend.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class FileStorageServiceR2Test {

    private final R2Storage r2 = Mockito.mock(R2Storage.class);
    private final FileStorageService service = new FileStorageService(r2);

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "uploadDir", dir.toString());
    }

    @Test
    void withR2OffFilesGoToDisk() throws Exception {
        var stored = service.store(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3}));
        assertTrue(Files.exists(dir.resolve(stored.storedFileName())));
        assertEquals(3, service.load(stored.storedFileName()).contentLength());
        Mockito.verify(r2, Mockito.never()).put(anyString(), any(InputStream.class), anyLong(), any());
    }

    @Test
    void withR2OnUploadsSkipTheDisk() throws Exception {
        Mockito.when(r2.enabled()).thenReturn(true);
        var stored = service.store(new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1, 2, 3, 4}));
        Mockito.verify(r2).put(eq(stored.storedFileName()), any(InputStream.class), eq(4L), eq("application/pdf"));
        assertFalse(Files.exists(dir.resolve(stored.storedFileName())));
        assertEquals("report.pdf", stored.originalName());
    }

    @Test
    void failedR2UploadIsAnError() {
        Mockito.when(r2.enabled()).thenReturn(true);
        Mockito.doThrow(new RuntimeException("down")).when(r2).put(anyString(), any(InputStream.class), anyLong(), any());
        assertThrows(ApiException.class, () -> service.store(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1})));
    }

    @Test
    void readsFromR2ThenFallsBackToDisk() throws Exception {
        Mockito.when(r2.enabled()).thenReturn(true);
        Resource remote = new ByteArrayResource(new byte[]{9, 9});
        Mockito.when(r2.get("in-r2.png")).thenReturn(remote);
        assertSame(remote, service.load("in-r2.png"));

        Files.write(dir.resolve("old.png"), new byte[]{1, 2, 3, 4, 5});
        Mockito.when(r2.get("old.png")).thenReturn(null);
        assertEquals(5, service.load("old.png").contentLength());

        Mockito.when(r2.get("broken.png")).thenThrow(new RuntimeException("network"));
        Files.write(dir.resolve("broken.png"), new byte[]{1});
        assertEquals(1, service.load("broken.png").contentLength());

        assertThrows(ApiException.class, () -> service.load("missing.png"));
        assertThrows(ApiException.class, () -> service.load("../secret.txt"));
        Mockito.verify(r2, Mockito.never()).get("../secret.txt");
    }

    @Test
    void deleteRemovesBothCopies() throws Exception {
        Mockito.when(r2.enabled()).thenReturn(true);
        Files.write(dir.resolve("x.png"), new byte[]{1});
        service.delete("x.png");
        Mockito.verify(r2).delete("x.png");
        assertFalse(Files.exists(dir.resolve("x.png")));
    }
}
