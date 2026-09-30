package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentRunner;
import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.tools.ToolJson;
import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.chat.dto.ProposalDto;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.ChatMessageNotFoundException;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.plan.PlanDtoMapper;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The per-day chat thread. send() runs the agent OUTSIDE any transaction (a turn takes seconds of LLM calls) and
 * only then stores the turn; apply() replays a pending proposal of today's thread through the engine.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatStore store;
    private final ChatMessageRepository repository;
    private final AgentRunner runner;
    private final PlanService planService;
    private final Clock clock;

    public ChatService(ChatStore store, ChatMessageRepository repository, AgentRunner runner, PlanService planService,
                       Clock clock) {
        this.store = store;
        this.repository = repository;
        this.runner = runner;
        this.planService = planService;
        this.clock = clock;
    }

    public List<ChatMessageDto> thread(LocalDate date) {
        return store.thread(date).stream().map(ChatService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<String> days() {
        return repository.findChatDates().stream().map(LocalDate::toString).toList();
    }

    public ChatMessageDto send(String text) {
        LocalDate today = LocalDate.now(clock);
        List<AgentMessage> history = store.thread(today).stream().map(ChatService::toAgentMessage).toList();
        AgentTurnResult result = runner.run(history, text);
        return toDto(store.appendTurn(today, text, result, Instant.now(clock)));
    }

    @Transactional
    public ApplyEventsResponse apply(long messageId) {
        ChatMessageEntity message = pendingProposalOfToday(messageId);
        LocalDate today = LocalDate.now(clock);
        Proposal proposal = decode(message.getProposal());
        List<PlanEvent> events = proposal.events().stream().map(event -> event.toEvent(today)).toList();
        // Joins this transaction: if the engine refuses (e.g. a block no longer exists), the status stays PENDING.
        ApplyEventsResponse applied = PlanDtoMapper.toEventsResponse(planService.applyEvents(events, today));
        message.setProposalStatus(ProposalStatus.APPLIED.name());
        log.info("Applied chat proposal {} ({} events)", messageId, events.size());
        return applied;
    }

    @Transactional
    public ChatMessageDto reject(long messageId) {
        ChatMessageEntity message = pendingProposalOfToday(messageId);
        message.setProposalStatus(ProposalStatus.REJECTED.name());
        return toDto(message);
    }

    private ChatMessageEntity pendingProposalOfToday(long messageId) {
        ChatMessageEntity message = repository.findById(messageId)
            .orElseThrow(() -> new ChatMessageNotFoundException(messageId));
        if (message.getProposal() == null) {
            throw new ProposalNotAvailableException("This message has no proposal");
        }
        if (!message.getChatDate().equals(LocalDate.now(clock))) {
            throw new ProposalNotAvailableException("This proposal is from another day and can no longer be used");
        }
        if (!ProposalStatus.PENDING.name().equals(message.getProposalStatus())) {
            throw new ProposalNotAvailableException("This proposal is "
                + message.getProposalStatus().toLowerCase(Locale.ROOT) + " and can no longer be used");
        }
        return message;
    }

    /** History for the model: text only, plus a note about any proposal and what became of it. */
    private static AgentMessage toAgentMessage(ChatMessageEntity message) {
        if ("USER".equals(message.getRole())) {
            return AgentMessage.user(message.getText());
        }
        String text = message.getText();
        if (message.getProposal() != null) {
            text += "\n[proposal " + message.getProposalStatus() + ": " + decode(message.getProposal()).rationale() + "]";
        }
        return AgentMessage.assistant(text, List.of());
    }

    private static Proposal decode(String json) {
        return ToolJson.MAPPER.readValue(json, Proposal.class);
    }

    private static ChatMessageDto toDto(ChatMessageEntity message) {
        ProposalDto proposal = null;
        if (message.getProposal() != null) {
            Proposal decoded = decode(message.getProposal());
            proposal = new ProposalDto(decoded.rationale(), decoded.events(), decoded.changes(), decoded.preview(),
                message.getProposalStatus());
        }
        return new ChatMessageDto(message.getId(), message.getChatDate().toString(), message.getRole(),
            message.getText(), proposal, message.getCreatedAt().toString());
    }
}
