package com.lifeorganizer.ai.agent;

import com.lifeorganizer.ai.agent.tools.ToolJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalJsonTest {

    @Test
    void aSliceOneProposalWithoutKindReadsAsAPlanProposal() {
        Proposal proposal = ToolJson.MAPPER.readValue(
            "{\"rationale\":\"x\",\"events\":[],\"changes\":[\"Cancelled Gym\"],\"preview\":null}", Proposal.class);

        assertThat(proposal.kind()).isEqualTo(Proposal.Kind.PLAN);
        assertThat(proposal.priorityChanges()).isEmpty();
        assertThat(proposal.changes()).containsExactly("Cancelled Gym");
    }

    @Test
    void aPriorityProposalRoundTrips() {
        // nothing creates these any more; threads saved before still hold them
        Proposal proposal = new Proposal(Proposal.Kind.PRIORITIES, "Exam on Friday", List.of(),
            List.of("Would re-plan Thu 2026-10-01"), null,
            List.of(new PriorityChange("career-system-design", "System design", 3, 5, "exam Friday")));

        Proposal read = ToolJson.MAPPER.readValue(ToolJson.write(proposal), Proposal.class);

        assertThat(read).isEqualTo(proposal);
        assertThat(read.kind()).isEqualTo(Proposal.Kind.PRIORITIES);
        assertThat(read.events()).isEmpty();
        assertThat(read.preview()).isNull();
    }

    @Test
    void theShortConstructorStillMeansAPlanProposal() {
        Proposal proposal = new Proposal("Gym is off", List.of(), List.of(), null);

        assertThat(proposal.kind()).isEqualTo(Proposal.Kind.PLAN);
        assertThat(proposal.priorityChanges()).isEmpty();
    }
}
