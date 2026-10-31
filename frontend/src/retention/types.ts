export type RetentionCount = {
    value: number | null;
    status: 'EXACT' | 'AT_LEAST_LIMIT';
    limit: number;
};

export type RetentionCategory = {
    code: string;
    evaluationStatus: 'ASSESSED' | 'SOURCE_UNAVAILABLE' | 'NOT_EVALUATED';
    sourceStatus: 'AVAILABLE' | 'UNAVAILABLE' | 'NOT_QUERIED';
    observedAt: string | null;
    counts: Record<string, RetentionCount>;
    facts: Record<string, string>;
    reasons: string[];
};

export type RetentionAssessment = {
    referenceAt: string;
    startedAt: string;
    completedAt: string;
    readConsistency: 'NON_ATOMIC_READ_COMMITTED';
    informationalOnly: true;
    tenantLifecycleStatus: 'ACTIVE' | 'SUSPENDED' | 'UNKNOWN';
    countLimit: number;
    sourceQueryTimeoutSeconds: number;
    policy: {
        initialPostCancellationOrSuspensionRetentionStatus: string;
        initialPostCancellationOrSuspensionMonths: number;
        startMarker: string;
        categoryPolicies: string;
        legalBases: string;
        holdAuthority: string;
        holdInventory: string;
    };
    categories: RetentionCategory[];
    blockers: string[];
};

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isStringRecord(value: unknown): value is Record<string, string> {
    return (
        isRecord(value) &&
        Object.values(value).every((item) => typeof item === 'string')
    );
}

function isCount(value: unknown): value is RetentionCount {
    if (!isRecord(value)) return false;
    return (
        (value.value === null ||
            (typeof value.value === 'number' &&
                Number.isInteger(value.value) &&
                value.value >= 0)) &&
        (value.status === 'EXACT' || value.status === 'AT_LEAST_LIMIT') &&
        typeof value.limit === 'number' &&
        Number.isInteger(value.limit) &&
        value.limit >= 0
    );
}

function isCountRecord(
    value: unknown,
): value is Record<string, RetentionCount> {
    return isRecord(value) && Object.values(value).every(isCount);
}

function isCategory(value: unknown): value is RetentionCategory {
    if (!isRecord(value)) return false;
    return (
        typeof value.code === 'string' &&
        (value.evaluationStatus === 'ASSESSED' ||
            value.evaluationStatus === 'SOURCE_UNAVAILABLE' ||
            value.evaluationStatus === 'NOT_EVALUATED') &&
        (value.sourceStatus === 'AVAILABLE' ||
            value.sourceStatus === 'UNAVAILABLE' ||
            value.sourceStatus === 'NOT_QUERIED') &&
        (value.observedAt === null || typeof value.observedAt === 'string') &&
        isCountRecord(value.counts) &&
        isStringRecord(value.facts) &&
        Array.isArray(value.reasons) &&
        value.reasons.every((reason) => typeof reason === 'string')
    );
}

export function isRetentionAssessment(
    value: unknown,
): value is RetentionAssessment {
    if (!isRecord(value) || !isRecord(value.policy)) return false;
    const policy = value.policy;
    return (
        typeof value.referenceAt === 'string' &&
        Number.isFinite(Date.parse(value.referenceAt)) &&
        typeof value.startedAt === 'string' &&
        Number.isFinite(Date.parse(value.startedAt)) &&
        typeof value.completedAt === 'string' &&
        Number.isFinite(Date.parse(value.completedAt)) &&
        value.readConsistency === 'NON_ATOMIC_READ_COMMITTED' &&
        value.informationalOnly === true &&
        (value.tenantLifecycleStatus === 'ACTIVE' ||
            value.tenantLifecycleStatus === 'SUSPENDED' ||
            value.tenantLifecycleStatus === 'UNKNOWN') &&
        typeof value.countLimit === 'number' &&
        Number.isInteger(value.countLimit) &&
        typeof value.sourceQueryTimeoutSeconds === 'number' &&
        Number.isInteger(value.sourceQueryTimeoutSeconds) &&
        typeof policy.initialPostCancellationOrSuspensionRetentionStatus ===
            'string' &&
        typeof policy.initialPostCancellationOrSuspensionMonths === 'number' &&
        typeof policy.startMarker === 'string' &&
        typeof policy.categoryPolicies === 'string' &&
        typeof policy.legalBases === 'string' &&
        typeof policy.holdAuthority === 'string' &&
        typeof policy.holdInventory === 'string' &&
        Array.isArray(value.categories) &&
        value.categories.every(isCategory) &&
        Array.isArray(value.blockers) &&
        value.blockers.every((blocker) => typeof blocker === 'string')
    );
}
