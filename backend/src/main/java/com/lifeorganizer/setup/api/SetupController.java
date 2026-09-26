package com.lifeorganizer.setup.api;

import com.lifeorganizer.error.AssistantUnavailableException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.SetupExtractionRequest;
import com.lifeorganizer.setup.api.dto.SetupParseRequest;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    // A brain dump is longer than one command sentence, but still bounded.
    private static final int MAX_TEXT_LENGTH = 4000;

    private final SetupService setupService;
    private final SetupExtractionService setupExtractionService;

    public SetupController(SetupService setupService, SetupExtractionService setupExtractionService) {
        this.setupService = setupService;
        this.setupExtractionService = setupExtractionService;
    }

    @PostMapping("/extract")
    public SetupParseResponse extract(@RequestBody SetupExtractionRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new InvalidRequestException("text is required");
        }
        if (request.text().length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("text must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        try {
            return setupExtractionService.extract(request.text());
        } catch (RestClientException e) {
            throw new AssistantUnavailableException("The assistant is unavailable right now", e);
        }
    }

    @PostMapping("/parse")
    public SetupParseResponse parse(@RequestBody SetupParseRequest request) {
        return setupService.parse(request);
    }

    @GetMapping
    public SetupPayload get() {
        return setupService.load();
    }

    @PutMapping
    public SetupPayload put(@RequestBody SetupPayload payload) {
        setupService.save(payload);
        return setupService.load();
    }
}
