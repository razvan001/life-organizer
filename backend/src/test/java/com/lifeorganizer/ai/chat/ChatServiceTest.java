package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentRunner;
import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.PriorityChange;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.chat.dto.ProposalDto;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.error.ChatMessageNotFoundException;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ChatService.class, ChatStore.class, SetupStore.class, ChatServiceTest.Beans.class})
class ChatServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    ChatService chat;

    @Autowired
    ChatMessageRepository repository;

    @Autowired
    SetupStore setupStore;

    @MockitoBean
    AgentRunner runner;

    @MockitoBean
    PlanService planService;

    private static final ApplyEventRequest CANCEL_GYM =
        new ApplyEventRequest("CANCEL_BLOCK", "gym-1", null, null, null, null, null, null);
    private static final Proposal PROPOSAL = new Proposal("Gym is off", List.of(CANCEL_GYM),
        List.of("Cancelled Gym - slot is now free"), new TodayPlanResponse("2026-09-30", List.of(), List.of()));

    private void agentAnswers(String reply, Proposal proposal) {
        when(runner.run(anyList(), anyString())).thenReturn(new AgentTurnResult(reply, proposal));
    }

    @Test
    void sendStoresBothMessagesAndReturnsTheAssistantOne() {
        agentAnswers("I'd cancel the gym.", PROPOSAL);

        ChatMessageDto reply = chat.send("gym is canceled");

        assertThat(reply.role()).isEqualTo("ASSISTANT");
        assertThat(reply.text()).isEqualTo("I'd cancel the gym.");
        assertThat(reply.date()).isEqualTo("2026-09-30");
        assertThat(reply.proposal().status()).isEqualTo("PENDING");
        assertThat(reply.proposal().events()).containsExactly(CANCEL_GYM);
        assertThat(chat.thread(TODAY)).extracting(ChatMessageDto::role, ChatMessageDto::text).containsExactly(
            org.assertj.core.groups.Tuple.tuple("USER", "gym is canceled"),
            org.assertj.core.groups.Tuple.tuple("ASSISTANT", "I'd cancel the gym."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAgentSeesEarlierMessagesOfTodaysThread() {
        agentAnswers("First.", null);
        chat.send("one");
        agentAnswers("Second.", null);

        chat.send("two");

        ArgumentCaptor<List<AgentMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(runner).run(history.capture(), eq("two"));
        assertThat(history.getValue()).extracting(AgentMessage::content).containsExactly("one", "First.");
    }

    @Test
    void aNewMessageExpiresTheOlderPendingProposal() {
        agentAnswers("Proposal.", PROPOSAL);
        long first = chat.send("cancel gym").id();
        agentAnswers("Just chatting.", null);

        chat.send("never mind");

        assertThat(chat.thread(TODAY).get(1).proposal().status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> chat.apply(first)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void applyReplaysTheEventsAndMarksTheProposalApplied() {
        agentAnswers("Proposal.", PROPOSAL);
        long id = chat.send("cancel gym").id();
        when(planService.applyEvents(List.of(new CancelBlock("gym-1")), TODAY))
            .thenReturn(new AppliedEvent(new DayPlan(TODAY, List.of(), List.of()), List.of("Cancelled Gym")));

        ApplyEventsResponse applied = chat.apply(id);

        assertThat(applied.changes()).containsExactly("Cancelled Gym");
        assertThat(chat.thread(TODAY).get(1).proposal().status()).isEqualTo("APPLIED");
        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(ProposalNotAvailableException.class);
    }

    // Weekly reviews no longer propose priorities, but threads saved before that still hold such proposals.
    private static final Proposal PRIORITIES = new Proposal(Proposal.Kind.PRIORITIES, "Exam week", List.of(),
        List.of("Would re-plan Thu 2026-10-01"), null,
        List.of(new PriorityChange("career-system-design", "System design", 3, 5, "exam Friday")));

    @Test
    void applyingAPriorityProposalIsNotAvailable() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints()); // the proposal is not stale
        agentAnswers("I'd raise System design.", PRIORITIES);
        long id = chat.send("review my week").id();

        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(ProposalNotAvailableException.class);

        verify(planService, never()).applyEvents(anyList(), any());
        verify(planService, never()).getOrGenerate(any());
    }

    @Test
    void anOldPriorityProposalStillLoads() {
        agentAnswers("I'd raise System design.", PRIORITIES);
        long id = chat.send("review my week").id();
        chat.reject(id);

        ProposalDto loaded = chat.thread(TODAY).get(1).proposal();

        assertThat(loaded.kind()).isEqualTo("PRIORITIES");
        assertThat(loaded.status()).isEqualTo("REJECTED");
        assertThat(loaded.priorityChanges()).extracting(PriorityChange::goalName).containsExactly("System design");
    }

    @Test
    void aSliceOneProposalStillApplies() {
        ChatMessageEntity old = repository.save(new ChatMessageEntity(TODAY, 0, "ASSISTANT", "old",
            "{\"rationale\":\"x\",\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"gym-1\"}],\"changes\":[],\"preview\":null}",
            "PENDING", Instant.EPOCH));
        when(planService.applyEvents(List.of(new CancelBlock("gym-1")), TODAY))
            .thenReturn(new AppliedEvent(new DayPlan(TODAY, List.of(), List.of()), List.of("Cancelled Gym")));

        assertThat(chat.apply(old.getId()).changes()).containsExactly("Cancelled Gym");
    }

    @Test
    void anEngineRefusalLeavesTheProposalPending() {
        agentAnswers("Proposal.", PROPOSAL);
        long id = chat.send("cancel gym").id();
        when(planService.applyEvents(anyList(), eq(TODAY))).thenThrow(new UnknownBlockException("No block with id 'gym-1'"));

        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(UnknownBlockException.class);

        assertThat(repository.findById(id).orElseThrow().getProposalStatus()).isEqualTo("PENDING");
    }

    @Test
    void rejectMarksTheProposalRejected() {
        agentAnswers("Proposal.", PROPOSAL);
        long id = chat.send("cancel gym").id();

        assertThat(chat.reject(id).proposal().status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> chat.reject(id)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void yesterdaysProposalCanNoLongerBeAppliedOrRejected() {
        ChatMessageEntity old = repository.save(new ChatMessageEntity(TODAY.minusDays(1), 1, "ASSISTANT", "old",
            "{\"rationale\":\"x\",\"events\":[],\"changes\":[],\"preview\":null}", "PENDING", Instant.EPOCH));

        assertThatThrownBy(() -> chat.apply(old.getId())).isInstanceOf(ProposalNotAvailableException.class);
        assertThatThrownBy(() -> chat.reject(old.getId())).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void aMessageWithoutAProposalCannotBeApplied() {
        agentAnswers("Hi!", null);
        long id = chat.send("hello").id();

        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void unknownMessagesAreNotFound() {
        assertThatThrownBy(() -> chat.apply(999)).isInstanceOf(ChatMessageNotFoundException.class);
        assertThatThrownBy(() -> chat.reject(999)).isInstanceOf(ChatMessageNotFoundException.class);
    }

    @Test
    void aFailedAgentTurnSavesNothing() {
        when(runner.run(anyList(), anyString())).thenThrow(new ResourceAccessException("down"));

        assertThatThrownBy(() -> chat.send("hello")).isInstanceOf(ResourceAccessException.class);

        assertThat(chat.thread(TODAY)).isEmpty();
    }

    @Test
    void daysListsThreadsNewestFirst() {
        repository.save(new ChatMessageEntity(TODAY.minusDays(2), 0, "USER", "a", null, null, Instant.EPOCH));
        repository.save(new ChatMessageEntity(TODAY.minusDays(1), 0, "USER", "b", null, null, Instant.EPOCH));

        assertThat(chat.days()).containsExactly("2026-09-29", "2026-09-28");
        assertThat(chat.thread(TODAY.minusDays(1))).extracting(ChatMessageDto::text).containsExactly("b");
    }
}
