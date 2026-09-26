package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.SetupExtractionResult;

/** Turns a free-form brain dump into a raw, not-yet-validated setup. */
public interface SetupExtractionClient {

    SetupExtractionResult extract(String systemPrompt, String userMessage);
}
