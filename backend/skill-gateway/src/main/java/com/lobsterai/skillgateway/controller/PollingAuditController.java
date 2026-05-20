package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.entity.AsyncPollingAuditLog;
import com.lobsterai.skillgateway.service.AsyncPollingAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/polling-audit")
public class PollingAuditController {

    private final AsyncPollingAuditService auditService;

    public PollingAuditController(AsyncPollingAuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping("/events")
    public ResponseEntity<?> receiveEvents(@RequestBody List<AsyncPollingAuditLog> entries) {
        if (entries == null || entries.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "count", 0));
        }
        auditService.batchLog(entries);
        return ResponseEntity.ok(Map.of("ok", true, "count", entries.size()));
    }
}
