package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.chat.dto.SendMessageRequest;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.AssistantUnavailableException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assistant/chat")
public class ChatController {

    static final int MAX_TEXT_LENGTH = 2000;

    private final ChatService chat;
    private final ObjectProvider<AgentLlmClient> client;
    private final Clock clock;

    public ChatController(ChatService chat, ObjectProvider<AgentLlmClient> client, Clock clock) {
        this.chat = chat;
        this.client = client;
        this.clock = clock;
    }

    @GetMapping
    public List<ChatMessageDto> thread(@RequestParam(required = false) String date) {
        requireAi();
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(clock) : TimeFormats.parseDate(date, "date");
        return chat.thread(day);
    }

    @GetMapping("/days")
    public List<String> days() {
        requireAi();
        return chat.days();
    }

    @PostMapping("/messages")
    public ChatMessageDto send(@RequestBody SendMessageRequest request) {
        requireAi();
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            throw new InvalidRequestException("text is required");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("text must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        try {
            return chat.send(text);
        } catch (RestClientException e) {
            throw new AssistantUnavailableException("The assistant is unavailable right now", e);
        }
    }

    @PostMapping("/messages/{id}/apply")
    public ApplyEventsResponse apply(@PathVariable long id) {
        requireAi();
        return chat.apply(id);
    }

    @PostMapping("/messages/{id}/reject")
    public ChatMessageDto reject(@PathVariable long id) {
        requireAi();
        return chat.reject(id);
    }

    private void requireAi() {
        if (client.getIfAvailable() == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }
    }
}
