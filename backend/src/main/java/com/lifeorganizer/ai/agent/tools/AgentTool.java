package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.ToolSpec;
import com.lifeorganizer.ai.agent.TurnContext;
import tools.jackson.databind.JsonNode;

/**
 * One capability the agent may call. Tools only read or simulate - they never change the plan. A problem the model
 * can fix (bad arguments, unknown block) is thrown as ToolException or InvalidRequestException; the runner turns it
 * into an "error: ..." tool result.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema of the arguments object. */
    String parametersJson();

    /** Runs the tool; the returned text is exactly what the model sees. */
    String execute(JsonNode arguments, TurnContext context);

    /**
     * Non-null for a tool whose result every turn needs: the runner runs it up front and puts the result, under this
     * label, into the model's context instead of offering the tool. That saves a model call (and its tokens) per turn.
     */
    default String contextLabel() {
        return null;
    }

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parametersJson());
    }
}
