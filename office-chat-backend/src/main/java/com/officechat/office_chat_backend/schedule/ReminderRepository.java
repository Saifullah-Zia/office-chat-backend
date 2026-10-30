package com.officechat.office_chat_backend.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(Reminder.Status status, Instant now);

    List<Reminder> findByUserIdAndStatusInOrderByRemindAtAsc(Long userId, Collection<Reminder.Status> statuses);

    long countByUserIdAndStatus(Long userId, Reminder.Status status);
}
