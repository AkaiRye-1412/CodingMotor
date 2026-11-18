package com.motorsizer.app.controller;

import com.motorsizer.app.common.ApiResponse;
import com.motorsizer.app.dto.CalculationRequest;
import com.motorsizer.app.dto.CalculationResponse;
import com.motorsizer.app.service.CalculationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B1 执行计算：一次请求返回规划 + 理论 + 判定 + 应用值 + 采样。
 *
 * <p>工况不可解时 HTTP 仍为 200，失败信息在 {@code data.stage}（04 §1.1 例外）。
 */
@RestController
@RequestMapping("/api/calculations")
public class CalculationController {

    private final CalculationService calculationService;

    public CalculationController(CalculationService calculationService) {
        this.calculationService = calculationService;
    }

    @PostMapping
    public ApiResponse<CalculationResponse> calculate(@RequestBody CalculationRequest request) {
        return ApiResponse.ok(calculationService.calculate(request));
    }
}
