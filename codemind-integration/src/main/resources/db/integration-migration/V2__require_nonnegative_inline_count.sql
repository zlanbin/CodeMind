-- V1 may already be applied; preserve its checksum and validate existing rows without repair/baseline.
alter table integration.review_publication
    add constraint review_publication_inline_count_nonnegative check (inline_count >= 0);
