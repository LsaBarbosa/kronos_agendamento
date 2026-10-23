package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportJobView;
import java.util.UUID;

public record ExportRequestResult(UUID operationId, ExportJobView job, boolean replayed) {}
