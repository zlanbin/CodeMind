package com.codemind.integration.application.port;

import java.util.List;

/** SCM review publication and submitted-review lookup port. */
public interface ScmReviewPublisher {

    /** Returns the complete submitted-review list, or fails rather than returning a partial list. */
    List<ExistingReview> listReviews(String repo, int pr);

    /** Submits one COMMENT review containing the summary and all inline comments at the given commit. */
    ExistingReview createReview(String repo, int pr, ReviewDraft draft);
}
