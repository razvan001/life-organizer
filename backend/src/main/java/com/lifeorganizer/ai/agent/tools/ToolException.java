package com.lifeorganizer.ai.agent.tools;

/** A problem with a tool call that the model can correct; its message goes back to the model. */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
