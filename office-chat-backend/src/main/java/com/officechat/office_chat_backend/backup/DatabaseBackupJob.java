package com.officechat.office_chat_backend.backup;

import com.officechat.office_chat_backend.file.R2Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nightly copy of the whole database, kept in R2 (outside Railway, so it survives losing the database volume).
 * Keys: daily/office-chat-YYYY-MM-DD.dump every day, plus monthly/office-chat-YYYY-MM.dump on the 1st. How long
 * each is kept is set by lifecycle rules on the bucket. Restore with pg_restore (see DEPLOY-RAILWAY.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseBackupJob {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final R2Storage r2;
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${app.backup.bucket:}")
    private String bucket;
    @Value("${app.backup.zone:Asia/Karachi}")
    private String zone;
    @Value("${app.backup.pg-dump:pg_dump}")
    private String pgDump;

    @Value("${PGHOST:localhost}")
    private String host;
    @Value("${PGPORT:5432}")
    private String port;
    @Value("${PGDATABASE:office_chat}")
    private String database;
    @Value("${PGUSER:postgres}")
    private String user;
    @Value("${spring.datasource.password:}")
    private String password;

    private boolean on() {
        return r2.enabled() && bucket != null && !bucket.isBlank();
    }

    /** After a deploy: make today's copy now if there isn't one, so a fresh setup can be checked at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (!on()) {
            log.info("Database backups are off (set R2_BACKUP_BUCKET to turn them on)");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (!r2.exists(bucket.trim(), dailyKey(today()))) runBackup();
            } catch (Exception e) {
                log.error("DATABASE BACKUP FAILED: {}", e.getMessage());
            }
        }, "db-backup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "${app.backup.cron:0 0 3 * * *}", zone = "${app.backup.zone:Asia/Karachi}")
    public void nightly() {
        if (!on()) return;
        try {
            runBackup();
        } catch (Exception e) {
            log.error("DATABASE BACKUP FAILED: {}", e.getMessage());
        }
    }

    void runBackup() throws IOException, InterruptedException {
        if (!running.compareAndSet(false, true)) return;
        Path dump = Files.createTempFile("office-chat-", ".dump");
        try {
            long started = System.currentTimeMillis();
            dump(dump);
            LocalDate day = today();
            String target = bucket.trim();
            r2.putFile(target, dailyKey(day), dump, "application/octet-stream");
            if (day.getDayOfMonth() == 1 || !r2.exists(target, monthlyKey(day))) {
                r2.putFile(target, monthlyKey(day), dump, "application/octet-stream");
            }
            log.info("Database backup saved: {} ({} KB, {} s)", dailyKey(day), Files.size(dump) / 1024,
                    (System.currentTimeMillis() - started) / 1000);
        } finally {
            Files.deleteIfExists(dump);
            running.set(false);
        }
    }

    /** pg_dump's custom format: compressed, and pg_restore can bring back the whole database or single tables. */
    private void dump(Path out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(List.of(pgDump, "--format=custom", "--no-owner", "--no-acl",
                "--host=" + host, "--port=" + port, "--username=" + user, "--dbname=" + database, "--file=" + out));
        pb.environment().put("PGPASSWORD", password == null ? "" : password);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("pg_dump took longer than 30 minutes");
        }
        if (p.exitValue() != 0 || Files.size(out) == 0) {
            throw new IOException("pg_dump exit " + p.exitValue() + ": " + output);
        }
    }

    private LocalDate today() {
        return ZonedDateTime.now(ZoneId.of(zone)).toLocalDate();
    }

    static String dailyKey(LocalDate day) {
        return "daily/office-chat-" + DAY.format(day) + ".dump";
    }

    static String monthlyKey(LocalDate day) {
        return "monthly/office-chat-" + MONTH.format(day) + ".dump";
    }
}
