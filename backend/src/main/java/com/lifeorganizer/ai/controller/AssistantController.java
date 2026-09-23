package com.lifeorganizer.ai.controller;

import com.lifeorganizer.ai.dto.CommandRequest;
import com.lifeorganizer.ai.dto.CommandResponse;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.service.CommandInterpreter;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.AssistantUnavailableException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.PlanService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final int MAX_TEXT_LENGTH = 2000;

    private final CommandInterpreter commandInterpreter;
    private final PlanService planService;
    private final Clock clock;

    public AssistantController(CommandInterpreter commandInterpreter, PlanService planService, Clock clock) {
        this.commandInterpreter = commandInterpreter;
        this.planService = planService;
        this.clock = clock;
    }

    @PostMapping("/command")
    public CommandResponse command(@RequestBody CommandRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new InvalidRequestException("text is required");
        }
        if (request.text().length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("text must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        LocalDate today = LocalDate.now(clock);
        DayPlan plan = planService.getOrGenerate(today);

        InterpretedCommand interpreted;
        try {
            interpreted = commandInterpreter.interpret(request.text(), today, plan.blocksInOrder());
        } catch (RestClientException e) {
            throw new AssistantUnavailableException("The assistant is unavailable right now", e);
        }

        if (!interpreted.understood()) {
            return CommandResponse.notUnderstood(interpreted.message());
        }
        List<PlanEvent> events = interpreted.events().stream().map(event -> event.toEvent(today)).toList();
        return CommandResponse.understood(interpreted.rationale(), interpreted.events(),
            planService.simulateEvents(events, today));
    }
}
