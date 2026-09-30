package com.lifeorganizer.ai.agent;

/** What one user turn produced: the assistant's reply and, optionally, one proposal. */
public record AgentTurnResult(String reply, Proposal proposal) {
}
