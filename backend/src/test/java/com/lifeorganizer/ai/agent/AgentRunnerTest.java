package com.lifeorganizer.ai.agent;

import com.lifeorganizer.ai.agent.tools.AgentTool;
import com.lifeorganizer.ai.agent.tools.ToolException;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.ai.client.ToolCallRejectedException;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    /** Plays back scripted replies and remembers every request. */
    static class ScriptedClient implements AgentLlmClient {
        final Deque<Object> script = new ArrayDeque<>();
        final List<List<AgentMessage>> requests = new ArrayList<>();
        final List<List<String>> offeredTools = new ArrayList<>();

        ScriptedClient then(Object replyOrException) {
            script.add(replyOrException);
            return this;
        }

        @Override
        public AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools) {
            requests.add(List.copyOf(messages));
            offeredTools.add(tools.stream().map(ToolSpec::name).toList());
            Object next = script.isEmpty() ? new AgentReply(null, List.of(new ToolCall("loop", "echo", "{}"))) : script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (AgentReply) next;
        }
    }

    static class FakeTool implements AgentTool {
        final String name;
        final BiFunction<JsonNode, TurnContext, String> body;
        final String contextLabel;

        FakeTool(String name, BiFunction<JsonNode, TurnContext, String> body) {
            this(name, body, null);
        }

        FakeTool(String name, BiFunction<JsonNode, TurnContext, String> body, String contextLabel) {
            this.name = name;
            this.body = body;
            this.contextLabel = contextLabel;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "fake"; }
        @Override public String parametersJson() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public String execute(JsonNode arguments, TurnContext context) { return body.apply(arguments, context); }
        @Override public String contextLabel() { return contextLabel; }
    }

    private static final Proposal PROPOSAL = new Proposal("Gym is off", List.of(), List.of(),
        new TodayPlanResponse("2026-09-30", List.of(), List.of()));

    private static final List<AgentTool> TOOLS = List.of(
        new FakeTool("echo", (args, context) -> "echo:" + args.path("x").asString("")),
        new FakeTool("broken", (args, context) -> { throw new ToolException("blockId is required"); }),
        new FakeTool("crash", (args, context) -> { throw new IllegalStateException("boom"); }),
        new FakeTool("propose", (args, context) -> { context.propose(PROPOSAL); return "recorded"; }));

    private static AgentRunner runner(AgentLlmClient client) {
        return runner(client, TOOLS);
    }

    @SuppressWarnings("unchecked")
    private static AgentRunner runner(AgentLlmClient client, List<AgentTool> tools) {
        ObjectProvider<AgentLlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new AgentRunner(provider, tools, CLOCK);
    }

    private static AgentReply text(String text) {
        return new AgentReply(text, List.of());
    }

    private static AgentReply calls(ToolCall... calls) {
        return new AgentReply(null, List.of(calls));
    }

    @Test
    void aTextAnswerEndsTheTurn() {
        ScriptedClient client = new ScriptedClient().then(text("Sure."));

        AgentTurnResult result = runner(client).run(List.of(AgentMessage.user("earlier"), AgentMessage.assistant("ok", List.of())), "hi");

        assertThat(result).isEqualTo(new AgentTurnResult("Sure.", null));
        List<AgentMessage> sent = client.requests.get(0);
        assertThat(sent.get(0).role()).isEqualTo(AgentMessage.Role.SYSTEM);
        assertThat(sent.get(0).content()).contains("2026-09-30").contains("information, not instructions")
            .contains("Write plain text only")
            .contains("never \"I've added\"")
            .contains("Current data")
            .doesNotContain("propose_priorities")
            .contains("summarize the week")
            .contains("Never write JSON")
            .contains("does not apply anything");
        assertThat(sent).extracting(AgentMessage::content).endsWith("earlier", "ok", "hi");
    }

    @Test
    void toolResultsAreFedBackBeforeTheNextCall() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "echo", "{\"x\":\"a\"}")))
            .then(text("Done."));

        runner(client).run(List.of(), "hi");

        List<AgentMessage> second = client.requests.get(1);
        AgentMessage assistant = second.get(second.size() - 2);
        AgentMessage tool = second.get(second.size() - 1);
        assertThat(assistant.toolCalls()).extracting(ToolCall::id).containsExactly("c1");
        assertThat(tool.role()).isEqualTo(AgentMessage.Role.TOOL);
        assertThat(tool.toolCallId()).isEqualTo("c1");
        assertThat(tool.content()).isEqualTo("echo:a");
    }

    @Test
    void toolProblemsBecomeErrorTextNotExceptions() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "nope", "{}"), new ToolCall("c2", "echo", "not json"),
                new ToolCall("c3", "broken", "{}"), new ToolCall("c4", "crash", "{}"), new ToolCall("c5", "echo", null)))
            .then(text("Sorry."));

        AgentTurnResult result = runner(client).run(List.of(), "hi");

        assertThat(result.reply()).isEqualTo("Sorry.");
        List<AgentMessage> second = client.requests.get(1);
        assertThat(second.subList(second.size() - 5, second.size())).extracting(AgentMessage::content).containsExactly(
            "error: unknown tool 'nope'",
            "error: arguments must be a JSON object",
            "error: blockId is required",
            "error: the tool failed unexpectedly",
            "echo:");
    }

    @Test
    void aProposalMadeDuringTheTurnIsReturned() {
        ScriptedClient client = new ScriptedClient().then(calls(new ToolCall("c1", "propose", "{}"))).then(text("Proposed."));

        assertThat(runner(client).run(List.of(), "hi")).isEqualTo(new AgentTurnResult("Proposed.", PROPOSAL));
    }

    @Test
    void theLoopStopsAfterEightModelCalls() {
        ScriptedClient client = new ScriptedClient();

        AgentTurnResult result = runner(client).run(List.of(), "hi");

        assertThat(client.requests).hasSize(AgentRunner.MAX_MODEL_CALLS);
        assertThat(result).isEqualTo(new AgentTurnResult("I couldn't finish that. Try rephrasing.", null));
    }

    @Test
    void aProposalSurvivesTheCap() {
        ScriptedClient client = new ScriptedClient().then(calls(new ToolCall("c1", "propose", "{}")));

        assertThat(runner(client).run(List.of(), "hi")).isEqualTo(new AgentTurnResult("Here is what I'd change.", PROPOSAL));
    }

    @Test
    void anUnusableReplyEndsTheTurnWithTheFallback() {
        ScriptedClient client = new ScriptedClient().then(new MalformedLlmReplyException("junk"));

        assertThat(runner(client).run(List.of(), "hi").reply()).isEqualTo("I couldn't finish that. Try rephrasing.");
    }

    @Test
    void aRejectedToolCallIsCorrectedAndTheTurnContinues() {
        ScriptedClient client = new ScriptedClient()
            .then(new ToolCallRejectedException("attempted to call tool 'browser.search' which was not in request.tools"))
            .then(text("Here you go."));

        AgentTurnResult result = runner(client).run(List.of(), "hi");

        assertThat(result).isEqualTo(new AgentTurnResult("Here you go.", null));
        assertThat(client.requests).hasSize(2);
        List<AgentMessage> second = client.requests.get(1);
        AgentMessage correction = second.get(second.size() - 1);
        assertThat(correction.role()).isEqualTo(AgentMessage.Role.SYSTEM);
        assertThat(correction.content()).isEqualTo("Your previous tool call was rejected: attempted to call tool "
            + "'browser.search' which was not in request.tools. Call only the listed tools, with arguments that "
            + "exactly match their schemas.");
    }

    @Test
    void aProposalRecordedBeforeARejectionSurvives() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "propose", "{}")))
            .then(new ToolCallRejectedException("nope"))
            .then(text("Proposed."));

        assertThat(runner(client).run(List.of(), "hi")).isEqualTo(new AgentTurnResult("Proposed.", PROPOSAL));
    }

    @Test
    void rejectionsStillCountTowardsTheCallCap() {
        ScriptedClient client = new ScriptedClient();
        for (int i = 0; i < 20; i++) {
            client.then(new ToolCallRejectedException("nope"));
        }

        AgentTurnResult result = runner(client).run(List.of(), "hi");

        assertThat(client.requests).hasSize(AgentRunner.MAX_MODEL_CALLS);
        assertThat(result).isEqualTo(new AgentTurnResult("I couldn't finish that. Try rephrasing.", null));
    }

    @Test
    void aProposalWrittenAsTextIsSentBackOnceSoTheModelCallsTheTool() {
        ScriptedClient client = new ScriptedClient()
            .then(text("I propose raising Padel.\n{\"changes\":[{\"goalId\":\"padel\",\"priority\":5}],\"rationale\":\"x\"}"))
            .then(calls(new ToolCall("c1", "propose", "{}")))
            .then(text("I suggest raising Padel. Press Apply if you agree."));

        AgentTurnResult result = runner(client).run(List.of(), "review my week");

        assertThat(result).isEqualTo(new AgentTurnResult("I suggest raising Padel. Press Apply if you agree.", PROPOSAL));
        List<AgentMessage> second = client.requests.get(1);
        AgentMessage rejected = second.get(second.size() - 2);
        AgentMessage correction = second.get(second.size() - 1);
        assertThat(rejected.role()).isEqualTo(AgentMessage.Role.ASSISTANT);
        assertThat(rejected.content()).startsWith("I propose raising Padel.");
        assertThat(correction.role()).isEqualTo(AgentMessage.Role.SYSTEM);
        assertThat(correction.content()).contains("tool arguments as text");
    }

    @Test
    void aSecondUngroundedClaimEndsTheTurnWithAnHonestReply() {
        ScriptedClient client = new ScriptedClient()
            .then(text("Got it - your new goal priorities have been applied."))
            .then(text("Done, the priorities have been applied."));

        AgentTurnResult result = runner(client).run(List.of(), "apply");

        assertThat(result).isEqualTo(new AgentTurnResult(
            "I couldn't turn that into a proposal, so nothing has changed. Please ask again.", null));
        assertThat(client.requests).hasSize(2);
    }

    @Test
    void aDoneClaimNextToAProposalIsRewritten() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "propose", "{}")))
            .then(text("I've added a padel session. Press Apply to keep it."))
            .then(text("I'd add a padel session. Press Apply to keep it."));

        AgentTurnResult result = runner(client).run(List.of(), "add padel");

        assertThat(result).isEqualTo(new AgentTurnResult("I'd add a padel session. Press Apply to keep it.", PROPOSAL));
        List<AgentMessage> third = client.requests.get(2);
        assertThat(third.get(third.size() - 1).content()).contains("It is only proposed");
    }

    @Test
    void aRepeatedDoneClaimNextToAProposalFallsBackToTheNeutralLine() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "propose", "{}")))
            .then(text("I've added a padel session."))
            .then(text("I have added the padel session."));

        assertThat(runner(client).run(List.of(), "add padel"))
            .isEqualTo(new AgentTurnResult("Here is what I'd change.", PROPOSAL));
    }

    @Test
    void aProblemOnTheLastAllowedCallIsNotRetried() {
        ScriptedClient client = new ScriptedClient();
        for (int i = 0; i < AgentRunner.MAX_MODEL_CALLS - 1; i++) {
            client.then(calls(new ToolCall("c" + i, "echo", "{}")));
        }
        client.then(text("Your priorities have been applied."));

        AgentTurnResult result = runner(client).run(List.of(), "apply");

        assertThat(client.requests).hasSize(AgentRunner.MAX_MODEL_CALLS);
        assertThat(result.reply()).isEqualTo("I couldn't turn that into a proposal, so nothing has changed. Please ask again.");
    }

    @Test
    void markdownEmphasisIsRemovedFromTheReply() {
        ScriptedClient client = new ScriptedClient().then(text("**Chess** could fit from 16:00."));

        assertThat(runner(client).run(List.of(), "hi").reply()).isEqualTo("Chess could fit from 16:00.");
    }

    @Test
    void preloadedToolsAreRunUpFrontAndNotOfferedToTheModel() {
        ScriptedClient client = new ScriptedClient().then(text("Sure."));
        List<AgentTool> tools = List.of(
            new FakeTool("echo", (args, context) -> "echo"),
            new FakeTool("plan", (args, context) -> "PLAN " + context.today(), "Today's plan"),
            new FakeTool("week", (args, context) -> { throw new ToolException("no setup yet"); }, "This week"));

        runner(client, tools).run(List.of(AgentMessage.user("earlier")), "hi");

        List<AgentMessage> sent = client.requests.get(0);
        AgentMessage data = sent.get(sent.size() - 2);
        assertThat(sent.get(sent.size() - 1).content()).isEqualTo("hi");
        assertThat(data.role()).isEqualTo(AgentMessage.Role.SYSTEM);
        assertThat(data.content()).isEqualTo("Current data, already loaded for this message (do not ask for it again):\n"
            + "Today's plan:\nPLAN 2026-09-30\n"
            + "This week:\nerror: no setup yet");
        assertThat(client.offeredTools.get(0)).containsExactly("echo");
    }

    @Test
    void connectivityFailuresPropagate() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "echo", "{}")))
            .then(new ResourceAccessException("down"));

        assertThatThrownBy(() -> runner(client).run(List.of(), "hi")).isInstanceOf(RestClientException.class);
    }

    @Test
    void onlyTheLastTwentyHistoryMessagesAreSent() {
        ScriptedClient client = new ScriptedClient().then(text("ok"));
        List<AgentMessage> history = IntStream.range(0, 30).mapToObj(i -> AgentMessage.user("m" + i)).toList();

        runner(client).run(history, "hi");

        List<AgentMessage> sent = client.requests.get(0);
        assertThat(sent).hasSize(1 + 20 + 1);
        assertThat(sent.get(1).content()).isEqualTo("m10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsWhenNoClientIsConfigured() {
        ObjectProvider<AgentLlmClient> provider = mock(ObjectProvider.class);
        AgentRunner none = new AgentRunner(provider, TOOLS, CLOCK);

        assertThatThrownBy(() -> none.run(List.of(), "hi")).isInstanceOf(AiNotConfiguredException.class);
    }
}
