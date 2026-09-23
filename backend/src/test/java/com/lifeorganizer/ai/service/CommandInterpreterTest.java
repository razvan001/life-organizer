package com.lifeorganizer.ai.service;

import com.lifeorganizer.ai.client.LlmCommandClient;
import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.ai.model.LlmCommandResult;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.ai.model.ProposedEventType;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.error.AiNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandInterpreterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final Block PADEL = new Block("padel-2026-09-22", TODAY, LocalTime.of(19, 0), LocalTime.of(20, 0),
        "Padel", BlockKind.GOAL, "health-padel", BlockStatus.PLANNED, 0, false, null);

    @SuppressWarnings("unchecked")
    private static CommandInterpreter interpreterFor(LlmCommandClient fake) {
        ObjectProvider<LlmCommandClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fake);
        return new CommandInterpreter(provider);
    }

    @Test
    void turnsAWellFormedReplyIntoAnUnderstoodCommand() {
        ProposedEvent cancel = new ProposedEvent(ProposedEventType.CANCEL_BLOCK, "padel-2026-09-22",
            null, null, null, null, null, null);
        LlmCommandResult reply = new LlmCommandResult(true, "Padel is cancelled", null,
            List.of(cancel));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("padel got canceled", TODAY, List.of(PADEL));

        assertThat(result.understood()).isTrue();
        assertThat(result.rationale()).isEqualTo("Padel is cancelled");
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).type()).isEqualTo("CANCEL_BLOCK");
        assertThat(result.events().get(0).blockId()).isEqualTo("padel-2026-09-22");
    }

    @Test
    void downgradesToNotUnderstoodWhenTheModelSaysSo() {
        LlmCommandResult reply = new LlmCommandResult(false, null,
            "I couldn't find a matching block", List.of());
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("do something vague", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
        assertThat(result.message()).isEqualTo("I couldn't find a matching block");
    }

    @Test
    void downgradesToNotUnderstoodWhenTheBlockIdIsNotInTodaysPlan() {
        ProposedEvent cancel = new ProposedEvent(ProposedEventType.CANCEL_BLOCK, "no-such-block",
            null, null, null, null, null, null);
        LlmCommandResult reply = new LlmCommandResult(true, "Cancelling it", null,
            List.of(cancel));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("cancel soccer", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenThereAreMoreThanFiveEvents() {
        ProposedEvent event = new ProposedEvent(ProposedEventType.MARK_DONE, "padel-2026-09-22",
            null, null, null, null, null, null);
        LlmCommandResult reply = new LlmCommandResult(true, "many things", null,
            List.of(event, event, event, event, event, event));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("do everything", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenThereAreNoEventsAtAll() {
        LlmCommandResult reply = new LlmCommandResult(true, "nothing to do", null, List.of());
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("hmm", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenAnEventFailsEngineValidation() {
        // ADD_ADHOC with no title - ApplyEventRequest.toEvent rejects it
        ProposedEvent badAdhoc = new ProposedEvent(ProposedEventType.ADD_ADHOC, null, null,
            "2026-09-22", null, "19:00", "20:00", null);
        LlmCommandResult reply = new LlmCommandResult(true, "adding it", null,
            List.of(badAdhoc));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("add something", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenAddAdhocTargetsADifferentDate() {
        ProposedEvent futureAdhoc = new ProposedEvent(ProposedEventType.ADD_ADHOC, null, null,
            TODAY.plusDays(1).toString(), "Padel", "19:00",
            "20:00", null);
        LlmCommandResult reply = new LlmCommandResult(true, "adding it tomorrow", null,
            List.of(futureAdhoc));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("add padel tomorrow", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
        assertThat(result.message()).isEqualTo("I can only add events to today's plan.");
    }

    @Test
    void includesTodaysDateAndEveryBlockInThePromptGivenToTheClient() {
        List<String> capturedUserMessages = new ArrayList<>();
        LlmCommandClient fake = (system, user) -> {
            capturedUserMessages.add(user);
            return new LlmCommandResult(false, null, "n/a", List.of());
        };
        CommandInterpreter sut = interpreterFor(fake);

        sut.interpret("anything", TODAY, List.of(PADEL));

        assertThat(capturedUserMessages).hasSize(1);
        String prompt = capturedUserMessages.get(0);
        assertThat(prompt).contains("2026-09-22");
        assertThat(prompt).contains("padel-2026-09-22");
        assertThat(prompt).contains("Padel");
        assertThat(prompt).contains("19:00");
    }

    @SuppressWarnings("unchecked")
    @Test
    void throwsAiNotConfiguredWhenNoClientIsAvailable() {
        ObjectProvider<LlmCommandClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CommandInterpreter sut = new CommandInterpreter(provider);

        assertThatThrownBy(() -> sut.interpret("x", TODAY, List.of(PADEL)))
            .isInstanceOf(AiNotConfiguredException.class);
    }

    @Test
    void downgradesToNotUnderstoodWhenTheReplyIsMalformed() {
        CommandInterpreter sut = interpreterFor((system, user) -> {
            throw new MalformedLlmReplyException("Groq returned no usable content");
        });

        InterpretedCommand result = sut.interpret("do something", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
        assertThat(result.message()).isEqualTo("I couldn't understand that.");
    }

    @Test
    void propagatesAConnectivityFailureUnchanged() {
        CommandInterpreter sut = interpreterFor((system, user) -> {
            throw new ResourceAccessException("boom");
        });

        assertThatThrownBy(() -> sut.interpret("do something", TODAY, List.of(PADEL)))
            .isInstanceOf(RestClientException.class);
    }
}
