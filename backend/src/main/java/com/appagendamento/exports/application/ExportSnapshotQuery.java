package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportSnapshot;
import java.util.UUID;

public interface ExportSnapshotQuery {

  ExportSnapshot capture(UUID tenantId, ExportFilters filters);
}
