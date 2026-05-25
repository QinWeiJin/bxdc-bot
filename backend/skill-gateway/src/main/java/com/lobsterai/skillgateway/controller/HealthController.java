package com.lobsterai.skillgateway.controller;
import com.lobsterai.skillgateway.util.StringUtils;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("service", "skill-gateway");
        result.put("timestamp", Instant.now().toString());
        result.put("uptimeSeconds", Math.round(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));
        return result;
    }
}
