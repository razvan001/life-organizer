package com.lifeorganizer.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByChatDateOrderByPositionAsc(LocalDate chatDate);

    List<ChatMessageEntity> findByChatDateAndProposalStatus(LocalDate chatDate, String proposalStatus);

    @Query("select distinct m.chatDate from ChatMessageEntity m order by m.chatDate desc")
    List<LocalDate> findChatDates();
}
