package com.officechat.office_chat_backend.audit;

import com.officechat.office_chat_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records who did what for admin review. Logging failures never break the action itself. */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long actorId, String action, String targetType, Long targetId, String details) {
        try {
            String actorName = actorId == null ? "System"
                    : userRepository.findById(actorId).map(u -> u.getName()).orElse("User #" + actorId);
            repository.save(AuditLog.builder()
                    .actorId(actorId)
                    .actorName(actorName)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .details(details)
                    .build());
        } catch (Exception e) {
            System.err.println("Audit log write failed: " + e.getMessage());
        }
    }

    public Page<AuditLog> list(String action, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return action == null || action.isBlank()
                ? repository.findAllByOrderByCreatedAtDesc(pr)
                : repository.findByActionOrderByCreatedAtDesc(action, pr);
    }
}
