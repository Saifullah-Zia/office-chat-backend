package com.officechat.office_chat_backend.backup;

import com.officechat.office_chat_backend.file.R2Storage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class DatabaseBackupJobTest {

    @Test
    void keysByDayAndMonth() {
        assertEquals("daily/office-chat-2026-11-27.dump", DatabaseBackupJob.dailyKey(LocalDate.of(2026, 11, 27)));
        assertEquals("monthly/office-chat-2026-11.dump", DatabaseBackupJob.monthlyKey(LocalDate.of(2026, 11, 27)));
    }

    @Test
    void offWithoutABucket() throws Exception {
        R2Storage r2 = Mockito.mock(R2Storage.class);
        Mockito.when(r2.enabled()).thenReturn(true);
        DatabaseBackupJob job = new DatabaseBackupJob(r2);
        ReflectionTestUtils.setField(job, "bucket", "");
        job.nightly();
        job.onStart();
        Mockito.verify(r2, Mockito.never()).putFile(any(), any(), any(), any());
        Mockito.verify(r2, Mockito.never()).exists(any(), any());
    }

    /** Runs only on a machine with PostgreSQL 18 installed and local-secrets.properties (the developer's PC). */
    @Test
    void realDumpIsUploaded() throws Exception {
        Path exe = Path.of("C:/Program Files/PostgreSQL/18/bin/pg_dump.exe");
        Path secrets = Path.of("local-secrets.properties");
        Assumptions.assumeTrue(Files.exists(exe) && Files.exists(secrets));
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(secrets)) { p.load(in); }

        R2Storage r2 = Mockito.mock(R2Storage.class);
        Mockito.when(r2.enabled()).thenReturn(true);
        long[] size = {0};
        byte[] head = new byte[5];
        Mockito.doAnswer(a -> {
            Path f = a.getArgument(2);
            size[0] = Files.size(f);
            try (InputStream in = Files.newInputStream(f)) { in.read(head); }
            return null;
        }).when(r2).putFile(eq("backups"), startsWith("daily/"), any(), any());

        DatabaseBackupJob job = new DatabaseBackupJob(r2);
        ReflectionTestUtils.setField(job, "bucket", "backups");
        ReflectionTestUtils.setField(job, "zone", "Asia/Karachi");
        ReflectionTestUtils.setField(job, "pgDump", exe.toString());
        ReflectionTestUtils.setField(job, "host", "localhost");
        ReflectionTestUtils.setField(job, "port", "5432");
        ReflectionTestUtils.setField(job, "database", "office_chat");
        ReflectionTestUtils.setField(job, "user", "postgres");
        ReflectionTestUtils.setField(job, "password", p.getProperty("PGPASSWORD"));
        job.runBackup();

        assertTrue(size[0] > 1000, "dump is " + size[0] + " bytes");
        assertEquals("PGDMP", new String(head)); // pg_dump custom-format signature
        Mockito.verify(r2).putFile(eq("backups"), startsWith("monthly/"), any(), any());
    }

    @Test
    void failedDumpUploadsNothing() {
        R2Storage r2 = Mockito.mock(R2Storage.class);
        DatabaseBackupJob job = new DatabaseBackupJob(r2);
        ReflectionTestUtils.setField(job, "bucket", "backups");
        ReflectionTestUtils.setField(job, "zone", "Asia/Karachi");
        ReflectionTestUtils.setField(job, "pgDump", "no-such-pg-dump-program");
        assertThrows(Exception.class, job::runBackup);
        Mockito.verifyNoInteractions(r2);
    }
}
