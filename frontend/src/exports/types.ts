export type ExportFormat = 'CSV' | 'PDF';
export type ExportStatus =
    | 'CONFIRMED'
    | 'CANCELLED_BY_PROVIDER'
    | 'CANCELLED_BY_CLIENT'
    | 'COMPLETED'
    | 'NO_SHOW';
export type ExportState =
    | 'REQUESTED'
    | 'PROCESSING'
    | 'RETRY_WAIT'
    | 'AVAILABLE'
    | 'FAILED_FINAL'
    | 'EXPIRED';

export type ExportFilters = {
    from: string;
    to: string;
    statuses?: ExportStatus[];
    serviceId?: string;
};

export type ExportRequest = {
    format: ExportFormat;
    category: 'APPOINTMENTS';
    filters: ExportFilters;
};

export type ExportJob = {
    exportId: string;
    operationId: string;
    format: ExportFormat;
    category: 'APPOINTMENTS';
    state: ExportState;
    filters: ExportFilters;
    snapshotAt: string | null;
    retryNumber?: number;
    nextAttemptAt?: string | null;
    availableAt?: string | null;
    expiresAt?: string | null;
    rowCount?: number | null;
    artifactSize?: number | null;
    artifactDigest?: string | null;
    failureCode?: string | null;
    version: number;
    createdAt: string;
    updatedAt?: string;
};

export type ExportIntent = {
    tenantId: string;
    key: string;
    body: ExportRequest;
    phase: 'PREPARED' | 'SENDING' | 'UNCERTAIN';
};

export type StoredExportContext = {
    tenantId: string;
    intent?: ExportIntent;
    exportId?: string;
};

export function recoverIntentPhase(
    phase: ExportIntent['phase'],
): ExportIntent['phase'] {
    return phase === 'SENDING' ? 'UNCERTAIN' : phase;
}

export const exportStatuses: { value: ExportStatus; label: string }[] = [
    { value: 'CONFIRMED', label: 'Confirmada' },
    { value: 'CANCELLED_BY_PROVIDER', label: 'Cancelada pelo prestador' },
    { value: 'CANCELLED_BY_CLIENT', label: 'Cancelada pelo cliente' },
    { value: 'COMPLETED', label: 'Concluída' },
    { value: 'NO_SHOW', label: 'Não compareceu' },
];

export function validateExportRequest(body: ExportRequest): string | null {
    if (!body.filters.from || !body.filters.to) {
        return 'Informe as datas inicial e final.';
    }
    if (body.filters.from > body.filters.to) {
        return 'A data inicial deve ser anterior ou igual à data final.';
    }
    if (
        body.filters.serviceId &&
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
            body.filters.serviceId,
        )
    ) {
        return 'Informe um identificador de serviço válido.';
    }
    return null;
}

export function isExportJob(value: unknown): value is ExportJob {
    if (!value || typeof value !== 'object') return false;
    const job = value as Partial<ExportJob>;
    return (
        typeof job.exportId === 'string' &&
        typeof job.operationId === 'string' &&
        (job.format === 'CSV' || job.format === 'PDF') &&
        job.category === 'APPOINTMENTS' &&
        [
            'REQUESTED',
            'PROCESSING',
            'RETRY_WAIT',
            'AVAILABLE',
            'FAILED_FINAL',
            'EXPIRED',
        ].includes(job.state ?? '') &&
        !!job.filters &&
        typeof job.version === 'number' &&
        typeof job.createdAt === 'string'
    );
}

export function isUncertainCreateResult(
    status: number | null,
    responseBodyValid: boolean,
): boolean {
    return (
        status === null ||
        status === 408 ||
        status >= 500 ||
        (status === 202 && !responseBodyValid)
    );
}

export type CreateResultKind =
    'ACCEPTED' | 'UNCERTAIN' | 'ACTIVE_CONFLICT' | 'KEY_CONFLICT' | 'REJECTED';

export function classifyCreateResult(
    status: number | null,
    code: string | undefined,
    responseBodyValid: boolean,
): CreateResultKind {
    if (isUncertainCreateResult(status, responseBodyValid)) return 'UNCERTAIN';
    if (status === 202) return 'ACCEPTED';
    if (status === 409 && code === 'EXPORT_ALREADY_ACTIVE') {
        return 'ACTIVE_CONFLICT';
    }
    if (status === 409 && code === 'IDEMPOTENCY_KEY_REUSED') {
        return 'KEY_CONFLICT';
    }
    return 'REJECTED';
}

export function canDownloadExport(
    state: ExportState,
    expiresAt: string | null | undefined,
    now: number,
): boolean {
    if (state !== 'AVAILABLE' || !expiresAt) return false;
    const expiration = Date.parse(expiresAt);
    return Number.isFinite(expiration) && now < expiration;
}

export function isStrongJobEtag(value: string | null): value is string {
    return value !== null && /^"[0-9]+"$/.test(value);
}

export function isCurrentStatusRequest(
    requestSequence: number,
    latestSequence: number,
    tenantMatches: boolean,
    exportMatches: boolean,
    sessionActive: boolean,
): boolean {
    return (
        requestSequence === latestSequence &&
        tenantMatches &&
        exportMatches &&
        sessionActive
    );
}

export function statusLabel(state: ExportState): string {
    switch (state) {
        case 'REQUESTED':
            return 'Solicitada';
        case 'PROCESSING':
            return 'Em processamento';
        case 'RETRY_WAIT':
            return 'Aguardando nova tentativa';
        case 'AVAILABLE':
            return 'Arquivo disponível';
        case 'FAILED_FINAL':
            return 'Falha definitiva';
        case 'EXPIRED':
            return 'Acesso expirado';
    }
}

export function isTerminalExportState(state: ExportState): boolean {
    return ['AVAILABLE', 'FAILED_FINAL', 'EXPIRED'].includes(state);
}
