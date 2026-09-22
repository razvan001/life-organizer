package com.lifeorganizer.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.lifeorganizer.ai.client.AnthropicCommandClient;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes one real call to the Anthropic API. Runs only when a real key is present, and never in CI
 * without one - this is the only place in the suite that spends real tokens or needs real credentials.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveAnthropicSmokeTest {

    @Test
    void aRealCallUnderstandsAnObviousCommand() {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        AnthropicCommandClient sut = new AnthropicCommandClient(client, "claude-sonnet-5");

        Block gym = new Block("gym-1", java.time.LocalDate.now(), LocalTime.of(7, 0), LocalTime.of(8, 0),
            "Gym", BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
        String prompt = "Today's blocks:\n- id=gym-1 title=\"Gym\" kind=GOAL status=PLANNED start=07:00 end=08:00\n"
            + "\nUser's text: mark the gym block done";

        ClaudeCommandResult result = sut.interpret(
            "You interpret plan commands. Respond with understood=true and a MARK_DONE event using blockId gym-1 "
                + "if the text asks to mark the gym done; otherwise understood=false.",
            prompt);

        assertThat(result.understood()).isTrue();
        assertThat(result.events()).isNotEmpty();
        client.close();
    }
}
