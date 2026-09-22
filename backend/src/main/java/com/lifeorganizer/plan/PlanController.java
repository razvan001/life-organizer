package com.lifeorganizer.plan;

import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.ApplyEventResponse;
import com.lifeorganizer.plan.dto.ApplyEventsRequest;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;
    private final Clock clock;

    public PlanController(PlanService planService, Clock clock) {
        this.planService = planService;
        this.clock = clock;
    }

    @GetMapping("/today")
    public TodayPlanResponse today() {
        return PlanDtoMapper.toResponse(planService.getOrGenerate(LocalDate.now(clock)));
    }

    @PostMapping("/today/events")
    public ApplyEventResponse applyEvent(@RequestBody ApplyEventRequest request) {
        LocalDate today = LocalDate.now(clock);
        PlanEvent event = request.toEvent(today);
        return PlanDtoMapper.toResponse(planService.applyEvent(event, today));
    }

    @PostMapping("/today/events/batch")
    public ApplyEventsResponse applyEvents(@RequestBody ApplyEventsRequest request) {
        if (request.events() == null || request.events().isEmpty()) {
            throw new InvalidRequestException("events must not be empty");
        }
        LocalDate today = LocalDate.now(clock);
        List<PlanEvent> events = request.events().stream().map(event -> event.toEvent(today)).toList();
        return PlanDtoMapper.toEventsResponse(planService.applyEvents(events, today));
    }
}
