package com.lifeorganizer.setup.api;

import com.lifeorganizer.setup.api.dto.SetupPayload;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;
    public SetupController(SetupService setupService) {
        this.setupService = setupService;
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
