package com.appagendamento.exports.api;

import java.time.Instant;
import java.util.UUID;

/** Read-only, minimized export-job counts for retention assessment. */
public interface ExportRetentionFactsQuery {

  Facts query(UUID tenantId, Instant referenceAt);

  record Count(int value, boolean atLeastLimit) {}

  record Facts(Count jobs, Count activeJobs, Count availableArtifacts, Count cleanupPending) {}
}
