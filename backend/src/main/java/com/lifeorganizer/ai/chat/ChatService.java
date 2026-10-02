package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentRunner;
import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.PriorityChange;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.tools.ToolJson;
import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.chat.dto.ProposalDto;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.ChatMessageNotFoundException;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.memory.history.DaySummarizer;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.plan.AppliedEvent;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The per-day chat thread. send() runs the agent OUTSIDE any transaction (a turn takes seconds of LLM calls) and
 * only then stores the turn; apply() replays a pending plan proposal through the engine, or saves a priorities
 * proposal and re-plans untouched days.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatStore store;
    private final ChatMessageRepository repository;
    private final AgentRunner runner;
    private final PlanService planService;
    private final SetupStore setupStore;
    private final Clock clock;

    public ChatService(ChatStore store, ChatMessageRepository repository, AgentRunner runner, PlanService planService,
                       SetupStore setupStore, Clock clock) {
        this.store = store;
        this.repository = repository;
        this.runner = runner;
        this.planService = planService;
        this.setupStore = setupStore;
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

    /**
     * Applies a pending proposal of today's thread. noRollbackFor keeps the EXPIRED status of a stale priorities
     * proposal when the 409 is thrown; the stale check runs before anything else is written.
     */
    @Transactional(noRollbackFor = ProposalNotAvailableException.class)
    public ApplyEventsResponse apply(long messageId) {
        ChatMessageEntity message = pendingProposalOfToday(messageId);
        LocalDate today = LocalDate.now(clock);
        Proposal proposal = decode(message.getProposal());
        ApplyEventsResponse applied = proposal.kind() == Proposal.Kind.PRIORITIES
            ? applyPriorities(message, proposal, today)
            : applyPlan(proposal, today);
        message.setProposalStatus(ProposalStatus.APPLIED.name());
        log.info("Applied chat proposal {} ({})", messageId, proposal.kind());
        return applied;
    }

    private ApplyEventsResponse applyPlan(Proposal proposal, LocalDate today) {
        List<PlanEvent> events = proposal.events().stream().map(event -> event.toEvent(today)).toList();
        // Joins this transaction: if the engine refuses (e.g. a block no longer exists), the status stays PENDING.
        return PlanDtoMapper.toEventsResponse(planService.applyEvents(events, today));
    }

    private ApplyEventsResponse applyPriorities(ChatMessageEntity message, Proposal proposal, LocalDate today) {
        Map<String, Integer> current = setupStore.loadTree().goals().stream()
            .collect(Collectors.toMap(Goal::id, Goal::priority));
        boolean stale = proposal.priorityChanges().stream()
            .anyMatch(change -> !Integer.valueOf(change.from()).equals(current.get(change.goalId())));
        if (stale) {
            message.setProposalStatus(ProposalStatus.EXPIRED.name());
            throw new ProposalNotAvailableException("Your goals changed since this proposal");
        }
        Map<String, Integer> next = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();
        for (PriorityChange change : proposal.priorityChanges()) {
            next.put(change.goalId(), change.to());
            changes.add(change.goalName() + ": priority " + change.from() + " -> " + change.to());
        }
        setupStore.updatePriorities(next);
        List<LocalDate> replanned = planService.replanUntouchedDays(today);
        if (!replanned.isEmpty()) {
            changes.add("Re-planned " + replanned.stream().map(DaySummarizer::title).collect(Collectors.joining(", ")));
        }
        return PlanDtoMapper.toEventsResponse(new AppliedEvent(planService.getOrGenerate(today), changes));
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
            proposal = new ProposalDto(decoded.kind().name(), decoded.rationale(), decoded.events(), decoded.changes(),
                decoded.preview(), decoded.priorityChanges(), message.getProposalStatus());
        }
        return new ChatMessageDto(message.getId(), message.getChatDate().toString(), message.getRole(),
            message.getText(), proposal, message.getCreatedAt().toString());
    }
}
