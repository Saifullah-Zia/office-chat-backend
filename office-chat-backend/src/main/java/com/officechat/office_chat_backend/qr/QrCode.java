package com.officechat.office_chat_backend.qr;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A QR code made for a client. Managed: the printed code holds our short link (/q/{code}), so it can be blocked
 * or pointed elsewhere after printing. Simple: it holds the client's own address, so we have no control over it.
 */
@Entity
@Table(name = "qr_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String code;

    @Column(name = "client_name", nullable = false, length = 100)
    private String clientName;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @Column(nullable = false)
    private boolean managed;

    @Column(nullable = false)
    private boolean blocked;

    @Column(nullable = false)
    private long scans;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
