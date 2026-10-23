package com.codemind.review.domain.publication;

import com.codemind.common.event.ReviewCompleted;

public record OutboxDelivery(ReviewCompleted event, String leaseToken) { }
