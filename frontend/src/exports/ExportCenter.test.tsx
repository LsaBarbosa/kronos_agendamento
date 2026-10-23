import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ExportCenter } from './ExportCenter';
import {
    canDownloadExport,
    classifyCreateResult,
    isExportJob,
    isCurrentStatusRequest,
    isTerminalExportState,
    isUncertainCreateResult,
    recoverIntentPhase,
    validateExportRequest,
    type ExportJob,
    type ExportRequest,
} from './types';

const body: ExportRequest = {
    format: 'CSV',
    category: 'APPOINTMENTS',
    filters: { from: '2026-10-01', to: '2026-10-23', statuses: ['COMPLETED'] },
};

const job: ExportJob = {
    exportId: 'e4f13daa-46ee-4e50-bec4-23de50e091bc',
    operationId: '38bf3832-cce6-4b4e-8fd6-2363ba8a501e',
    format: 'CSV',
    category: 'APPOINTMENTS',
    state: 'AVAILABLE',
    filters: body.filters,
    snapshotAt: '2026-10-23T12:00:00Z',
    retryNumber: 0,
    nextAttemptAt: null,
    availableAt: '2026-10-23T12:01:00Z',
    expiresAt: '2026-10-23T12:31:00Z',
    rowCount: 4,
    artifactSize: 900,
    artifactDigest: 'sha256:synthetic-test-digest',
    failureCode: null,
    version: 1,
    createdAt: '2026-10-23T12:00:00Z',
};

describe('ExportCenter contract helpers', () => {
    it('validates required date range and optional service UUID', () => {
        expect(validateExportRequest(body)).toBeNull();
        expect(
            validateExportRequest({
                ...body,
                filters: { from: '2026-10-24', to: '2026-10-23' },
            }),
        ).toContain('anterior');
        expect(
            validateExportRequest({
                ...body,
                filters: { ...body.filters, serviceId: 'invalid' },
            }),
        ).toContain('identificador');
    });

    it('preserves the indeterminate classification for timeout, 5xx and malformed acceptance', () => {
        expect(isUncertainCreateResult(null, false)).toBe(true);
        expect(isUncertainCreateResult(503, false)).toBe(false);
        expect(isUncertainCreateResult(502, false)).toBe(true);
        expect(isUncertainCreateResult(202, false)).toBe(true);
        expect(isUncertainCreateResult(400, false)).toBe(false);
    });

    it('distinguishes acceptance, active-job conflicts, key conflicts, and definite rejection', () => {
        expect(classifyCreateResult(202, undefined, true)).toBe('ACCEPTED');
        expect(classifyCreateResult(409, 'EXPORT_ALREADY_ACTIVE', false)).toBe(
            'ACTIVE_CONFLICT',
        );
        expect(classifyCreateResult(409, 'IDEMPOTENCY_KEY_REUSED', false)).toBe(
            'KEY_CONFLICT',
        );
        expect(classifyCreateResult(503, undefined, false)).toBe('UNAVAILABLE');
        expect(classifyCreateResult(400, undefined, false)).toBe('REJECTED');
    });

    it('recovers a request that was in flight at reload as uncertain with its body and key intact', () => {
        const intent = {
            tenantId: 'tenant-a',
            key: '1234567890123456',
            body,
            phase: 'SENDING' as const,
        };
        const restored = { ...intent, phase: recoverIntentPhase(intent.phase) };
        expect(restored.phase).toBe('UNCERTAIN');
        expect(restored.key).toBe(intent.key);
        expect(restored.body).toEqual(body);
    });

    it('validates minimized job data and identifies terminal states', () => {
        expect(isExportJob(job)).toBe(true);
        expect(isExportJob({ ...job, state: 'UNKNOWN' })).toBe(false);
        expect(isTerminalExportState('AVAILABLE')).toBe(true);
        expect(isTerminalExportState('PROCESSING')).toBe(false);
    });

    it('blocks downloads at expiration and rejects stale status responses after sequence or context changes', () => {
        expect(
            canDownloadExport(
                'AVAILABLE',
                job.expiresAt,
                Date.parse(job.expiresAt!) - 1,
            ),
        ).toBe(true);
        expect(
            canDownloadExport(
                'AVAILABLE',
                job.expiresAt,
                Date.parse(job.expiresAt!),
            ),
        ).toBe(false);
        expect(
            canDownloadExport(
                'EXPIRED',
                job.expiresAt,
                Date.parse(job.expiresAt!) - 1,
            ),
        ).toBe(false);
        expect(isCurrentStatusRequest(3, 3, true, true, true)).toBe(true);
        expect(isCurrentStatusRequest(2, 3, true, true, true)).toBe(false);
        expect(isCurrentStatusRequest(3, 3, false, true, true)).toBe(false);
        expect(isCurrentStatusRequest(3, 3, true, false, true)).toBe(false);
        expect(isCurrentStatusRequest(3, 3, true, true, false)).toBe(false);
    });

    it('renders accessible creation fields without treating acceptance as readiness', () => {
        const markup = renderToStaticMarkup(
            createElement(ExportCenter, {
                tenantId: 'tenant-context-test',
                csrf: {
                    headerName: 'X-CSRF-TOKEN',
                    token: 'synthetic-csrf-only',
                },
                onSessionInvalid: () => undefined,
            }),
        );
        expect(markup).toContain('aria-labelledby="export-center-title"');
        expect(markup).toContain('Data inicial');
        expect(markup).toContain('Status (opcional; sem seleção inclui todos)');
        expect(markup).toContain('Solicitar exportação');
        expect(markup).not.toContain('tenant-context-test');
        expect(markup).not.toContain('synthetic-csrf-only');
    });
});
