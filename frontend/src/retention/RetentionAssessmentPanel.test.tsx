import {
    act,
    cleanup,
    fireEvent,
    render,
    screen,
    waitFor,
} from '@testing-library/react';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RetentionAssessmentPanel } from './RetentionAssessmentPanel';
import type { RetentionAssessment } from './types';

const payload: RetentionAssessment = {
    referenceAt: '2026-10-26T14:00:00Z',
    startedAt: '2026-10-26T14:00:00Z',
    completedAt: '2026-10-26T14:00:00.087Z',
    readConsistency: 'NON_ATOMIC_READ_COMMITTED',
    informationalOnly: true,
    tenantLifecycleStatus: 'ACTIVE',
    countLimit: 10_000,
    sourceQueryTimeoutSeconds: 2,
    policy: {
        initialPostCancellationOrSuspensionRetentionStatus:
            'REQUIREMENT_DEFINED',
        initialPostCancellationOrSuspensionMonths: 12,
        startMarker: 'UNRESOLVED',
        categoryPolicies: 'NOT_CONFIGURED',
        legalBases: 'NOT_CONFIGURED',
        holdAuthority: 'UNDEFINED',
        holdInventory: 'NOT_EVALUATED',
    },
    categories: [
        {
            code: 'APPOINTMENTS',
            evaluationStatus: 'ASSESSED',
            sourceStatus: 'AVAILABLE',
            observedAt: '2026-10-26T14:00:00.021Z',
            counts: {
                records: { value: 0, status: 'EXACT', limit: 10_000 },
                futureConfirmed: {
                    value: 10_000,
                    status: 'AT_LEAST_LIMIT',
                    limit: 10_000,
                },
            },
            facts: { unapprovedPrivateField: 'PRIVATE_SENTINEL' },
            reasons: ['COUNT_LIMIT_REACHED'],
        },
        {
            code: 'CLIENT_RECORDS',
            evaluationStatus: 'SOURCE_UNAVAILABLE',
            sourceStatus: 'UNAVAILABLE',
            observedAt: '2026-10-26T14:00:00.030Z',
            counts: {},
            facts: {},
            reasons: ['SOURCE_UNAVAILABLE'],
        },
        {
            code: 'AUDIT_EVENTS',
            evaluationStatus: 'NOT_EVALUATED',
            sourceStatus: 'NOT_QUERIED',
            observedAt: null,
            counts: {},
            facts: {},
            reasons: ['CATEGORY_OUTSIDE_INITIAL_SCOPE'],
        },
    ],
    blockers: [
        'FUTURE_APPOINTMENTS_PRESENT',
        'RETENTION_START_MARKER_UNRESOLVED',
        'CATEGORY_POLICY_NOT_CONFIGURED',
        'LEGAL_BASE_NOT_CONFIGURED',
        'HOLD_AUTHORITY_UNDEFINED',
        'HOLD_INVENTORY_NOT_EVALUATED',
        'ASSESSMENT_ONLY_NO_DISCARD_AUTHORIZATION',
    ],
};

function responseFor(body: unknown, status = 200): Response {
    return {
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
    } as Response;
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((done) => {
        resolve = done;
    });
    return { promise, resolve };
}

function renderPanel(
    props: Partial<ComponentProps<typeof RetentionAssessmentPanel>> = {},
) {
    return render(
        <RetentionAssessmentPanel
            enabled
            tenantId="tenant-context-a"
            onSessionInvalid={() => undefined}
            {...props}
        />,
    );
}

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

describe('RetentionAssessmentPanel', () => {
    it('separa contagens exatas, limitadas, zero, indisponíveis e não consultadas', async () => {
        const fetchMock = vi
            .fn<typeof fetch>()
            .mockResolvedValue(responseFor(payload));
        vi.stubGlobal('fetch', fetchMock);

        renderPanel();

        expect(await screen.findByText('0 (exata)')).toBeTruthy();
        expect(screen.getByText('Pelo menos 10.000')).toBeTruthy();
        expect(
            screen.getByText(
                'A fonte não respondeu. Isso não representa zero registros.',
            ),
        ).toBeTruthy();
        expect(
            screen.getByText('Nenhuma consulta foi feita para esta categoria.'),
        ).toBeTruthy();
        expect(screen.getByText('Fonte indisponível')).toBeTruthy();
        expect(screen.getByText('Não avaliada')).toBeTruthy();
        expect(screen.getAllByText('NOT_CONFIGURED')).toHaveLength(2);
        expect(screen.getByText('UNDEFINED')).toBeTruthy();
        expect(screen.getByText(/não formam um snapshot atômico/)).toBeTruthy();
        expect(
            screen.getAllByText(/não autoriza descarte/).length,
        ).toBeGreaterThan(0);
        expect(
            screen.getAllByText(/26 de out\. de 2026/).length,
        ).toBeGreaterThan(0);
        expect(screen.queryByText('PRIVATE_SENTINEL')).toBeNull();
        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(fetchMock.mock.calls[0][0]).toBe(
            '/api/v1/admin/retention/assessment',
        );
        expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain(
            'tenant-context-a',
        );
        expect(fetchMock.mock.calls[0][1]).toMatchObject({
            credentials: 'same-origin',
            cache: 'no-store',
        });
    });

    it('recupera de falha de consulta por atualização manual', async () => {
        const fetchMock = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(responseFor({}, 503))
            .mockResolvedValueOnce(responseFor(payload));
        vi.stubGlobal('fetch', fetchMock);
        renderPanel();

        expect((await screen.findByRole('alert')).textContent).toContain(
            'HTTP 503',
        );
        fireEvent.click(
            screen.getByRole('button', { name: 'Atualizar avaliação' }),
        );

        expect(await screen.findByText('Pelo menos 10.000')).toBeTruthy();
        expect(screen.queryByRole('alert')).toBeNull();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('descarta resposta atrasada ao trocar de tenant e não envia o tenant na requisição', async () => {
        const first = deferred<Response>();
        const second = deferred<Response>();
        const fetchMock = vi
            .fn<typeof fetch>()
            .mockReturnValueOnce(first.promise)
            .mockReturnValueOnce(second.promise);
        vi.stubGlobal('fetch', fetchMock);
        const view = renderPanel({ tenantId: 'tenant-a' });
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

        view.rerender(
            <RetentionAssessmentPanel
                enabled
                tenantId="tenant-b"
                onSessionInvalid={() => undefined}
            />,
        );
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

        const oldPayload = {
            ...payload,
            referenceAt: '2025-01-01T00:00:00Z',
            categories: [
                {
                    ...payload.categories[0],
                    code: 'OLD_TENANT_PRIVATE_CATEGORY',
                },
            ],
        };
        await act(async () => first.resolve(responseFor(oldPayload)));
        expect(screen.queryByText('OLD_TENANT_PRIVATE_CATEGORY')).toBeNull();

        const currentPayload = {
            ...payload,
            categories: [
                {
                    ...payload.categories[0],
                    code: 'TENANT_B_ASSESSMENT',
                },
            ],
        };
        await act(async () => second.resolve(responseFor(currentPayload)));
        expect(await screen.findByText('TENANT_B_ASSESSMENT')).toBeTruthy();
        expect(screen.queryByText('OLD_TENANT_PRIVATE_CATEGORY')).toBeNull();
        expect(JSON.stringify(fetchMock.mock.calls)).not.toContain('tenant-a');
        expect(JSON.stringify(fetchMock.mock.calls)).not.toContain('tenant-b');
    });

    it('não restaura resultado após logout enquanto o JSON da resposta está pendente', async () => {
        const jsonResult = deferred<unknown>();
        const jsonStarted = deferred<void>();
        const fetchMock = vi.fn<typeof fetch>().mockResolvedValue({
            ok: true,
            status: 200,
            json: () => {
                jsonStarted.resolve();
                return jsonResult.promise;
            },
        } as unknown as Response);
        vi.stubGlobal('fetch', fetchMock);
        const view = renderPanel();
        await act(async () => jsonStarted.promise);

        view.rerender(
            <RetentionAssessmentPanel
                enabled={false}
                tenantId={null}
                onSessionInvalid={() => undefined}
            />,
        );
        await act(async () => jsonResult.resolve(payload));

        expect(screen.queryByText('Pelo menos 10.000')).toBeNull();
        expect(
            screen.getByText(/após a validação da sessão e do tenant/),
        ).toBeTruthy();
    });

    it('aborta a consulta e ignora o JSON que chega depois do unmount', async () => {
        const jsonResult = deferred<unknown>();
        const jsonStarted = deferred<void>();
        const fetchMock = vi.fn<typeof fetch>().mockResolvedValue({
            ok: true,
            status: 200,
            json: () => {
                jsonStarted.resolve();
                return jsonResult.promise;
            },
        } as unknown as Response);
        vi.stubGlobal('fetch', fetchMock);
        const view = renderPanel();
        await act(async () => jsonStarted.promise);
        const signal = fetchMock.mock.calls[0][1]?.signal;

        view.unmount();
        expect(signal?.aborted).toBe(true);
        await act(async () => jsonResult.resolve(payload));
        expect(document.body.textContent).not.toContain('Pelo menos 10.000');
    });

    it('impede refresh concorrente enquanto a consulta atual está pendente', async () => {
        const pending = deferred<Response>();
        const fetchMock = vi
            .fn<typeof fetch>()
            .mockReturnValue(pending.promise);
        vi.stubGlobal('fetch', fetchMock);
        renderPanel();

        const refresh = await screen.findByRole('button', {
            name: 'Consultando…',
        });
        expect(refresh.hasAttribute('disabled')).toBe(true);
        fireEvent.click(refresh);
        expect(fetchMock).toHaveBeenCalledTimes(1);

        await act(async () => pending.resolve(responseFor(payload)));
        expect(await screen.findByText('0 (exata)')).toBeTruthy();
    });

    it('encerra a sessão administrativa quando o servidor responde 401', async () => {
        const onSessionInvalid = vi.fn<(status: number) => void>();
        vi.stubGlobal(
            'fetch',
            vi.fn<typeof fetch>().mockResolvedValue(responseFor({}, 401)),
        );
        renderPanel({ onSessionInvalid });

        expect((await screen.findByRole('alert')).textContent).toContain(
            'não está mais autorizada',
        );
        expect(onSessionInvalid).toHaveBeenCalledWith(401);
        expect(screen.queryByText('0 (exata)')).toBeNull();
    });
});
