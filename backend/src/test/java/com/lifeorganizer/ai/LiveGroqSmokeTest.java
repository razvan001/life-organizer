package com.lifeorganizer.ai;

import com.lifeorganizer.ai.client.GroqCommandClient;
import com.lifeorganizer.ai.model.LlmCommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Makes one real call to the Groq API. Runs only when a real key is present, and never in CI without
 * one - this is the only place in the suite that spends real tokens or needs real credentials.
 */
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class LiveGroqSmokeTest {

    @Test
    void aRealCallUnderstandsAnObviousCommand() {
        String apiKey = System.getenv("GROQ_API_KEY");
        RestClient restClient = RestClient.builder()
            .baseUrl("https://api.groq.com/openai/v1/chat/completions")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
        GroqCommandClient sut = new GroqCommandClient(restClient, "openai/gpt-oss-120b");

        String prompt = "Today's blocks:\n- id=gym-1 title=\"Gym\" kind=GOAL status=PLANNED start=07:00 end=08:00\n"
            + "\nUser's text: mark the gym block done";

        LlmCommandResult result = sut.interpret(
            "You interpret plan commands. Respond with understood=true and a MARK_DONE event using blockId gym-1 "
                + "if the text asks to mark the gym done; otherwise understood=false.",
            prompt);

        org.assertj.core.api.Assertions.assertThat(result.understood()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.events()).isNotEmpty();
    }
}
