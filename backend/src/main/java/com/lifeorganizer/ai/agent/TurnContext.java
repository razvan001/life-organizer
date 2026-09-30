package com.lifeorganizer.ai.agent;

import com.lifeorganizer.ai.agent.tools.ToolException;

import java.time.LocalDate;
import java.util.Optional;

/** State shared by the tools during one user turn: today's date and the (at most one) proposal. */
public final class TurnContext {

    private final LocalDate today;
    private Proposal proposal;

    public TurnContext(LocalDate today) {
        this.today = today;
    }

    public LocalDate today() {
        return today;
    }

    public Optional<Proposal> proposal() {
        return Optional.ofNullable(proposal);
    }

    public void propose(Proposal next) {
        if (proposal != null) {
            throw new ToolException("a proposal was already made in this turn; reply to the user now");
        }
        proposal = next;
    }
}
