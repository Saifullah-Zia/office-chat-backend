package com.officechat.office_chat_backend.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findAllByOrderByCreatedAtDesc();

    List<Report> findByStatusOrderByCreatedAtDesc(Report.Status status);

    long countByStatus(Report.Status status);

    boolean existsByReporterIdAndMessageIdAndStatus(Long reporterId, Long messageId, Report.Status status);
}
