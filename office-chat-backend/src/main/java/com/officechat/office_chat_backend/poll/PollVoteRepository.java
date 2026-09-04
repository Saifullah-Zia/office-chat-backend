package com.officechat.office_chat_backend.poll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PollVoteRepository extends JpaRepository<PollVote, Long> {
    List<PollVote> findByPollId(Long pollId);
    List<PollVote> findByPollIdIn(List<Long> pollIds);
    Optional<PollVote> findByPollIdAndOptionIdAndUserId(Long pollId, Long optionId, Long userId);
    List<PollVote> findByPollIdAndUserId(Long pollId, Long userId);
    void deleteByPollIdAndOptionIdAndUserId(Long pollId, Long optionId, Long userId);
    void deleteByPollIdAndUserId(Long pollId, Long userId);
}
