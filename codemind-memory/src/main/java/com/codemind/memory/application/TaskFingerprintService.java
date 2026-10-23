package com.codemind.memory.application;

import com.codemind.memory.domain.fingerprint.TaskFingerprintRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;

/** L1 任务记忆：已发布 Finding 指纹的登记与查询。 */
@Service
public class TaskFingerprintService {

    /** 与 memory.task_fingerprint.fingerprint 列宽一致。 */
    public static final int MAX_FINGERPRINT_LENGTH = 128;
    public static final int MAX_BATCH = 1000;

    private final TaskFingerprintRepository repository;

    public TaskFingerprintService(TaskFingerprintRepository repository) {
        this.repository = repository;
    }

    public int record(String repo, int prNumber, Collection<String> fingerprints) {
        requireTask(repo, prNumber);
        if (fingerprints == null || fingerprints.isEmpty()) {
            return 0;
        }
        if (fingerprints.size() > MAX_BATCH) {
            throw new IllegalArgumentException("单次最多登记 " + MAX_BATCH + " 个指纹");
        }
        for (String fp : fingerprints) {
            if (fp == null || fp.isBlank() || fp.length() > MAX_FINGERPRINT_LENGTH) {
                throw new IllegalArgumentException("指纹必须非空且不超过 " + MAX_FINGERPRINT_LENGTH + " 字符");
            }
        }
        return repository.addAll(repo, prNumber, fingerprints);
    }

    public Set<String> published(String repo, int prNumber) {
        requireTask(repo, prNumber);
        return repository.findAll(repo, prNumber);
    }

    private static void requireTask(String repo, int prNumber) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo 不能为空");
        }
        if (prNumber < 1) {
            throw new IllegalArgumentException("prNumber 必须为正：" + prNumber);
        }
    }
}
