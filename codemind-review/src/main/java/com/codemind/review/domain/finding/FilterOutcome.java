package com.codemind.review.domain.finding;

import java.util.List;

public record FilterOutcome(List<Finding> kept, List<String> degradations) {
}
