package com.motorsizer.app.controller;

import com.motorsizer.app.common.ApiResponse;
import com.motorsizer.app.dto.TemplateResponse;
import com.motorsizer.app.service.MotorTemplateService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** A1 获取字段模板。 */
@RestController
@RequestMapping("/api/motor-templates")
public class MotorTemplateController {

    private final MotorTemplateService templateService;

    public MotorTemplateController(MotorTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TemplateResponse>> getTemplates() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(ApiResponse.ok(templateService.getTemplates()));
    }
}
