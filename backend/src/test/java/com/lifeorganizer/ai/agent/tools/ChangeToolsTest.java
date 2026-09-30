package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeToolsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Block GYM = new Block("gym-1", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
        BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
    private static final Block GYM_CANCELLED = new Block("gym-1", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
        BlockKind.GOAL, "health-gym", BlockStatus.CANCELLED, 0, false, null);
    private static final String CANCEL_GYM = "{\"rationale\":\"Gym is off\",\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"gym-1\"}]}";

    private PlanService plans;
    private SimulateChangesTool simulate;
    private ProposeChangesTool propose;

    private static JsonNode args(String json) {
        return ToolJson.MAPPER.readTree(json);
    }

    @BeforeEach
    void setUp() {
        plans = mock(PlanService.class);
        when(plans.getOrGenerate(TODAY)).thenReturn(new DayPlan(TODAY, List.of(GYM), List.of()));
        when(plans.simulateEvents(List.of(new CancelBlock("gym-1")), TODAY))
            .thenReturn(new AppliedEvent(new DayPlan(TODAY, List.of(GYM_CANCELLED), List.of()),
                List.of("Cancelled Gym - slot is now free")));
        ChangeSimulator simulator = new ChangeSimulator(plans);
        simulate = new SimulateChangesTool(simulator);
        propose = new ProposeChangesTool(simulator);
    }

    @Test
    void simulateShowsTheDiffAndEngineMessagesWithoutProposing() {
        TurnContext context = new TurnContext(TODAY);

        JsonNode result = ToolJson.MAPPER.readTree(simulate.execute(args(CANCEL_GYM), context));

        assertThat(result.path("diff").get(0).asString()).isEqualTo("Gym 07:00 -> CANCELLED 07:00");
        assertThat(result.path("engineMessages").get(0).asString()).isEqualTo("Cancelled Gym - slot is now free");
        assertThat(context.proposal()).isEmpty();
    }

    @Test
    void proposeRecordsTheProposalOnTheTurn() {
        TurnContext context = new TurnContext(TODAY);

        String result = propose.execute(args(CANCEL_GYM), context);

        Proposal proposal = context.proposal().orElseThrow();
        assertThat(proposal.rationale()).isEqualTo("Gym is off");
        assertThat(proposal.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CANCEL_BLOCK");
            assertThat(event.blockId()).isEqualTo("gym-1");
        });
        assertThat(proposal.changes()).containsExactly("Cancelled Gym - slot is now free");
        assertThat(proposal.preview().blocks().get(0).status()).isEqualTo("CANCELLED");
        assertThat(result).contains("\"recorded\":true");
    }

    @Test
    void aSecondProposalInTheSameTurnIsRefused() {
        TurnContext context = new TurnContext(TODAY);
        propose.execute(args(CANCEL_GYM), context);

        assertThatThrownBy(() -> propose.execute(args(CANCEL_GYM), context)).isInstanceOf(ToolException.class)
            .hasMessage("a proposal was already made in this turn; reply to the user now");
    }

    @Test
    void proposeNeedsARationale() {
        assertThatThrownBy(() -> propose.execute(args("{\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"gym-1\"}]}"),
            new TurnContext(TODAY))).isInstanceOf(ToolException.class).hasMessage("rationale is required");
    }

    @Test
    void eventsMustBeAnArrayOfObjects() {
        assertThatThrownBy(() -> simulate.execute(args("{\"events\":\"cancel gym\"}"), new TurnContext(TODAY)))
            .isInstanceOf(ToolException.class).hasMessage("events must be an array of event objects");
    }

    @Test
    void invalidEventsNeverReachTheEngine() {
        assertThatThrownBy(() -> simulate.execute(args("{\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"nope\"}]}"),
            new TurnContext(TODAY))).isInstanceOf(InvalidRequestException.class);
        verify(plans, never()).simulateEvents(anyList(), org.mockito.ArgumentMatchers.any());
    }
}
