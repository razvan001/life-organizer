package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.PriorityChange;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProposePrioritiesToolTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Category CAREER = new Category("career", "Career");
    private static final Goal STUDY = new Goal("career-study", CAREER, "Study", 3, null, 60, Energy.HIGH, PreferredTime.ANY);
    private static final Goal CHESS = new Goal("career-chess", CAREER, "Chess", 2, null, 30, Energy.LOW, PreferredTime.ANY);

    private PlanService plans;
    private ProposePrioritiesTool tool;

    @BeforeEach
    void setUp() {
        SetupStore setup = mock(SetupStore.class);
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(CAREER), List.of(STUDY, CHESS)));
        plans = mock(PlanService.class);
        when(plans.untouchedFutureDays(TODAY)).thenReturn(List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)));
        tool = new ProposePrioritiesTool(setup, plans);
    }

    private static JsonNode args(String json) {
        return ToolJson.MAPPER.readTree(json);
    }

    private static String change(String goalId, String priority, String reason) {
        return "{\"goalId\":\"" + goalId + "\",\"priority\":" + priority + ",\"reason\":\"" + reason + "\"}";
    }

    private static String call(String... changes) {
        return "{\"rationale\":\"Exam on Friday\",\"changes\":[" + String.join(",", changes) + "]}";
    }

    @Test
    void recordsAPriorityProposal() {
        TurnContext context = new TurnContext(TODAY);

        JsonNode result = ToolJson.MAPPER.readTree(tool.execute(args(call(change("career-study", "5", "exam Friday"),
            change("career-chess", "1", "no time"))), context));

        Proposal proposal = context.proposal().orElseThrow();
        assertThat(proposal.kind()).isEqualTo(Proposal.Kind.PRIORITIES);
        assertThat(proposal.rationale()).isEqualTo("Exam on Friday");
        assertThat(proposal.priorityChanges()).containsExactly(
            new PriorityChange("career-study", "Study", 3, 5, "exam Friday"),
            new PriorityChange("career-chess", "Chess", 2, 1, "no time"));
        assertThat(proposal.changes()).containsExactly("Would re-plan Thu 2026-10-01, Fri 2026-10-02");
        assertThat(result.path("recorded").asBoolean()).isTrue();
        assertThat(result.path("changes").get(0).asString()).isEqualTo("Study: 3 -> 5 (exam Friday)");
    }

    @Test
    void saysWhenNoDayNeedsReplanning() {
        when(plans.untouchedFutureDays(TODAY)).thenReturn(List.of());
        TurnContext context = new TurnContext(TODAY);

        tool.execute(args(call(change("career-study", "5", "exam"))), context);

        assertThat(context.proposal().orElseThrow().changes())
            .containsExactly("No planned days later this week need re-planning");
    }

    @Test
    void rejectsBadChanges() {
        assertError("{\"changes\":[" + change("career-study", "5", "x") + "]}", "rationale is required");
        assertError("{\"rationale\":\"r\",\"changes\":[]}", "changes must be an array of 1 to 8 change objects");
        assertError("{\"rationale\":\"r\",\"changes\":\"all\"}", "changes must be an array of 1 to 8 change objects");
        assertError(call("1"), "each change must be an object");
        assertError(call(change("nope", "5", "x")), "unknown goalId 'nope'; use a goal id from Current data or list_goals");
        assertError(call(change("career-study", "5", "x"), change("career-study", "4", "y")),
            "goal 'career-study' appears more than once");
        assertError(call(change("career-study", "6", "x")), "priority must be a whole number from 1 to 5");
        assertError(call(change("career-study", "\"5\"", "x")), "priority must be a whole number from 1 to 5");
        assertError(call(change("career-study", "4.5", "x")), "priority must be a whole number from 1 to 5");
        assertError(call(change("career-study", "4294967301", "x")), "priority must be a whole number from 1 to 5");
        assertError(call(change("career-study", "3", "x")), "Study already has priority 3; leave it out");
        assertError(call("{\"goalId\":\"career-study\",\"priority\":5}"), "reason is required");
    }

    @Test
    void atMostEightChanges() {
        String nine = String.join(",", java.util.Collections.nCopies(9, change("career-study", "5", "x")));
        assertError("{\"rationale\":\"r\",\"changes\":[" + nine + "]}", "changes must be an array of 1 to 8 change objects");
    }

    @Test
    void onlyOneProposalPerTurn() {
        TurnContext context = new TurnContext(TODAY);
        tool.execute(args(call(change("career-study", "5", "x"))), context);

        assertThatThrownBy(() -> tool.execute(args(call(change("career-chess", "1", "y"))), context))
            .isInstanceOf(ToolException.class)
            .hasMessage("a proposal was already made in this turn; reply to the user now");
    }

    @Test
    void aPlanProposalAlreadyMadeThisTurnRefusesPriorities() {
        TurnContext context = new TurnContext(TODAY);
        Proposal plan = new Proposal("Gym is off", List.of(), List.of(), null);
        context.propose(plan);

        assertThatThrownBy(() -> tool.execute(args(call(change("career-study", "5", "x"))), context))
            .isInstanceOf(ToolException.class)
            .hasMessage("a proposal was already made in this turn; reply to the user now");
        assertThat(context.proposal()).containsSame(plan);
        assertThat(context.proposal().orElseThrow().kind()).isEqualTo(Proposal.Kind.PLAN);
    }

    @Test
    void hasTheAgreedNameAndAnObjectSchema() {
        assertThat(tool.name()).isEqualTo("propose_priorities");
        assertThat(ToolJson.MAPPER.readTree(tool.parametersJson()).path("type").asString()).isEqualTo("object");
    }

    private void assertError(String json, String message) {
        TurnContext context = new TurnContext(TODAY);
        assertThatThrownBy(() -> tool.execute(args(json), context)).isInstanceOf(ToolException.class).hasMessage(message);
        assertThat(context.proposal()).isEmpty();
    }
}
