package com.codemind.memory.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.memory.application.TaskFingerprintService;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.FingerprintsResponse;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.RecordFingerprintsRequest;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.RecordFingerprintsResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** L1 已发布 Finding 指纹的 internal 端点（M8，M10 增量评审使用）。无鉴权，同 {@link MemoryController}。 */
@RestController
@RequestMapping("/internal/task-fingerprints")
public class TaskFingerprintController {

    private final TaskFingerprintService fingerprintService;

    public TaskFingerprintController(TaskFingerprintService fingerprintService) {
        this.fingerprintService = fingerprintService;
    }

    @PostMapping
    public Result<RecordFingerprintsResponse> record(@RequestBody RecordFingerprintsRequest request) {
        if (request.prNumber() == null) {
            throw new IllegalArgumentException("prNumber 不能为空");
        }
        return Result.ok(new RecordFingerprintsResponse(
                fingerprintService.record(request.repo(), request.prNumber(), request.fingerprints())));
    }

    @GetMapping
    public Result<FingerprintsResponse> published(@RequestParam String repo, @RequestParam int prNumber) {
        return Result.ok(new FingerprintsResponse(List.copyOf(fingerprintService.published(repo, prNumber))));
    }
}
