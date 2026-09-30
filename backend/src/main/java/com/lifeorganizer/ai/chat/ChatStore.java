package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.tools.ToolJson;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Short database transactions around the chat thread (the agent turn itself runs outside any transaction). */
@Component
public class ChatStore {

    private final ChatMessageRepository repository;

    public ChatStore(ChatMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageEntity> thread(LocalDate date) {
        return repository.findByChatDateOrderByPositionAsc(date);
    }

    /** Stores the user message and the assistant reply, expiring any proposal still pending in that thread. */
    @Transactional
    public ChatMessageEntity appendTurn(LocalDate date, String userText, AgentTurnResult result, Instant now) {
        for (ChatMessageEntity pending : repository.findByChatDateAndProposalStatus(date, ProposalStatus.PENDING.name())) {
            pending.setProposalStatus(ProposalStatus.EXPIRED.name());
        }
        int next = repository.findByChatDateOrderByPositionAsc(date).size();
        repository.save(new ChatMessageEntity(date, next, "USER", userText, null, null, now));
        Proposal proposal = result.proposal();
        return repository.save(new ChatMessageEntity(date, next + 1, "ASSISTANT", result.reply(),
            proposal == null ? null : ToolJson.write(proposal),
            proposal == null ? null : ProposalStatus.PENDING.name(), now));
    }
}
