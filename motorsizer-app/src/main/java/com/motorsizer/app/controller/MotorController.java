package com.motorsizer.app.controller;

import com.motorsizer.app.common.ApiResponse;
import com.motorsizer.app.common.PageResult;
import com.motorsizer.app.dto.CreatedMotorDto;
import com.motorsizer.app.dto.MotorDetailDto;
import com.motorsizer.app.dto.MotorListItemDto;
import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.app.service.MotorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C1–C4 电机库接口。Controller 不承载公式与 SQL。 */
@RestController
@RequestMapping("/api/motors")
public class MotorController {

    private final MotorService motorService;

    public MotorController(MotorService motorService) {
        this.motorService = motorService;
    }

    @GetMapping
    public ApiResponse<PageResult<MotorListItemDto>> list(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String motorType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String structure,
            @RequestParam(required = false) String cooling) {
        return ApiResponse.ok(motorService.list(page, size, motorType, keyword, structure, cooling));
    }

    @GetMapping("/{id}")
    public ApiResponse<MotorDetailDto> detail(@PathVariable Long id) {
        return ApiResponse.ok(motorService.get(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreatedMotorDto>> create(
            @RequestBody MotorUpsertRequest request) {
        Long motorId = motorService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("已入库", new CreatedMotorDto(motorId)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @RequestBody MotorUpsertRequest request) {
        motorService.update(id, request);
        return ApiResponse.<Void>ok("已更新", null);
    }

    /** 清空电机库全部数据（第 69 轮）。前端需二次确认后才调用。 */
    @DeleteMapping
    public ApiResponse<Void> clearAll() {
        int removed = motorService.clearAll();
        return ApiResponse.ok("已清空 " + removed + " 台电机", null);
    }
}
