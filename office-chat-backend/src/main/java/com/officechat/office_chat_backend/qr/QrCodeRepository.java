package com.officechat.office_chat_backend.qr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QrCodeRepository extends JpaRepository<QrCode, Long> {

    Optional<QrCode> findByCode(String code);

    List<QrCode> findAllByOrderByCreatedAtDesc();

    // Targeted updates: saving the whole row could overwrite scans counted meanwhile.
    @Modifying
    @Transactional
    @Query("update QrCode q set q.scans = q.scans + 1, q.lastScanAt = :at where q.id = :id")
    void countScan(@Param("id") Long id, @Param("at") Instant at);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update QrCode q set q.clientName = :name, q.targetUrl = :url where q.id = :id")
    void updateDetails(@Param("id") Long id, @Param("name") String name, @Param("url") String url);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update QrCode q set q.blocked = :blocked where q.id = :id")
    void updateBlocked(@Param("id") Long id, @Param("blocked") boolean blocked);
}
