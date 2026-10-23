package com.codemind.review.domain.publication;

import java.util.List;

/** 待登记到 memory 的指纹集合（M10）。 */
public record FingerprintRegistration(String taskId, String repo, int prNumber, List<String> fingerprints) {
}
