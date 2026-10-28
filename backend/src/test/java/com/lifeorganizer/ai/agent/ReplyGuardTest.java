package com.lifeorganizer.ai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "I propose raising Padel.\n\n{\n  \"changes\": [{\"goalId\": \"padel\", \"priority\": 5}],\n  \"rationale\": \"x\"\n}",
        "{\"rationale\":\"Gym is off\",\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"gym-1\"}]}",
    })
    void toolArgumentsWrittenAsTextAreAProblemWithoutAProposal(String reply) {
        assertThat(ReplyGuard.problem(reply, false)).hasValueSatisfying(correction ->
            assertThat(correction).contains("tool arguments as text").contains("propose_changes"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Got it - your new goal priorities have been applied.",
        "I've added a padel session from 18:00 to 19:30 today.",
        "I’ve cancelled the gym.",
        "I have moved Study to the evening.",
        "The plan has been updated.",
        "It's done.",
        "You can press Apply to keep it.",
        "Let me know if you'd like to Apply or Reject it.",
        "Click **Apply** when ready.",
    })
    void claimsWithoutAProposalAreAProblem(String reply) {
        assertThat(ReplyGuard.problem(reply, false)).hasValueSatisfying(correction ->
            assertThat(correction).contains("No proposal was recorded in this turn"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I've added a padel session from 18:00 to 19:30 today. You can press Apply to keep it.",
        "Your new goal priorities have been applied.",
    })
    void aDoneClaimNextToAProposalIsAProblem(String reply) {
        assertThat(ReplyGuard.problem(reply, true)).hasValueSatisfying(correction ->
            assertThat(correction).contains("It is only proposed"));
    }

    @Test
    void jsonNextToAProposalIsAProblem() {
        assertThat(ReplyGuard.problem("Here: {\"rationale\":\"x\",\"events\":[]}", true)).hasValueSatisfying(correction ->
            assertThat(correction).contains("without JSON"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I'd cancel Padel and add a short walk. You can press Apply to keep it.",
        "I suggest raising Study to 5 because the exam is on Friday. Press Apply if you agree.",
    })
    void anHonestProposalSummaryIsFine(String reply) {
        assertThat(ReplyGuard.problem(reply, true)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "You have free time before 17:00. Chess could fit from 16:00 to 17:00. Which one would you like?",
        "I suggest dropping Padel today if you are tired.",
        "You applied for the course last week according to your notes.",
        "If you moved Padel to 20:00 you would still have time for dinner.",
        "Use {curly} braces however you like.",
        // a weekly summary reports what already happened to the plan
        "Gym has been postponed twice and Chess was cancelled on Tuesday.",
        "Two Study sessions have been moved to the evening this week.",
    })
    void ordinaryAnswersAreFine(String reply) {
        assertThat(ReplyGuard.problem(reply, false)).isEmpty();
    }

    @Test
    void cleanRemovesMarkdownEmphasisOnly() {
        assertThat(ReplyGuard.clean("I'd add a **Padel** session. Press __Apply__ or **Reject**."))
            .isEqualTo("I'd add a Padel session. Press Apply or Reject.");
        assertThat(ReplyGuard.clean("2 * 3 = 6 and snake_case stay")).isEqualTo("2 * 3 = 6 and snake_case stay");
    }
}
