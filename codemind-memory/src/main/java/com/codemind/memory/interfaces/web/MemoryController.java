package com.codemind.memory.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.memory.application.MemoryService;
import com.codemind.memory.domain.memory.MemoryRepository.UpsertResult;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.ArchiveResponse;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.MemoryView;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.RecallRequest;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.RecallResponse;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.RecalledMemory;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.WriteMemoryRequest;
import com.codemind.memory.interfaces.web.dto.MemoryDtos.WriteMemoryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * L2 记忆的 internal 端点（M8）。供 review / integration 经服务发现调用，**无鉴权**，
 * 与 M7a 的 internal 端点同一缺口：网关暴露到本机之外前必须封掉 {@code /internal/**}。
 */
@RestController
@RequestMapping("/internal/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 新建返回 201，强化已有记忆返回 200。 */
    @PostMapping
    public ResponseEntity<Result<WriteMemoryResponse>> write(@RequestBody WriteMemoryRequest request) {
        UpsertResult result = memoryService.write(request.project(), request.kind(), request.content(),
                request.importance());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Result.ok(new WriteMemoryResponse(MemoryView.of(result.memory()), result.created())));
    }

    @PostMapping("/recall")
    public Result<RecallResponse> recall(@RequestBody RecallRequest request) {
        var ranked = memoryService.recall(request.project(), request.query(), request.kind(), request.topK(),
                request.minScore());
        return Result.ok(new RecallResponse(ranked.stream().map(RecalledMemory::of).toList()));
    }

    @PostMapping("/archive")
    public Result<ArchiveResponse> archive(@RequestParam String project) {
        return Result.ok(new ArchiveResponse(memoryService.archiveStale(project)));
    }

    /** 人工删除错误记忆，物理删除。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return memoryService.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
