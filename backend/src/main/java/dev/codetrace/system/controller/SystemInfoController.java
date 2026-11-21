package dev.codetrace.system.controller;

import dev.codetrace.system.service.SystemInfoService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
@RequestMapping("/api/v1/system")
public class SystemInfoController {
    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public SystemInfoService.SystemInfo info() {
        return service.info();
    }
}
