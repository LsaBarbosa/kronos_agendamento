// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { fireEvent } from '@testing-library/dom';
import {
    afterEach,
    beforeEach,
    describe,
    expect,
    it,
    vi,
    type Mock,
} from 'vitest';
import { ExportCenter } from './ExportCenter';
import {
    canDownloadExport,
    classifyCreateResult,
    isExportJob,
    isCurrentStatusRequest,
    isStrongJobEtag,
    isTerminalExportState,
    isUncertainCreateResult,
    recoverIntentPhase,
    validateExportRequest,
    type ExportJob,
    type ExportIntent,
    type ExportFormat,
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
        expect(isUncertainCreateResult(503, false)).toBe(true);
        expect(isUncertainCreateResult(502, false)).toBe(true);
        for (let status = 500; status <= 599; status++) {
            expect(isUncertainCreateResult(status, false)).toBe(true);
        }
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
        expect(classifyCreateResult(503, undefined, false)).toBe('UNCERTAIN');
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

type FetchMock = Mock<typeof fetch>;

const tenantId = 'tenant-ui-test';
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf-value' };
const serviceId = 'd64d150f-967e-49e5-b02b-31fdfaf40494';
const storedKey = `agd044.export-context.v1:${encodeURIComponent(tenantId)}`;

function jsonResponse(
    value: unknown,
    status = 200,
    headers: Record<string, string> = {},
) {
    return new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json', ...headers },
    });
}

function emptyResponse(status: number) {
    return new Response(null, { status });
}

function jobResponse(
    state: ExportJob['state'],
    etag: string | null = '"4"',
): Response {
    const responseHeaders: Record<string, string> = etag ? { ETag: etag } : {};
    return jsonResponse(
        {
            ...job,
            state,
            expiresAt: '2099-10-23T12:31:00Z',
            updatedAt: '2026-10-23T12:02:00Z',
        },
        200,
        responseHeaders,
    );
}

function button(container: HTMLElement, accessibleText: string) {
    const found = [...container.querySelectorAll('button')].find(
        (candidate) => candidate.textContent?.trim() === accessibleText,
    );
    if (!found) throw new Error(`Button not found: ${accessibleText}`);
    return found;
}

function control(container: HTMLElement, labelText: string) {
    const label = [...container.querySelectorAll('label')].find((candidate) =>
        candidate.textContent?.trim().startsWith(labelText),
    );
    const input = label?.querySelector('input, select');
    if (!(
        input instanceof HTMLInputElement || input instanceof HTMLSelectElement
    )) {
        throw new Error(`Form control not found: ${labelText}`);
    }
    return input;
}

async function changeControl(
    element: HTMLInputElement | HTMLSelectElement,
    value: string,
) {
    await act(async () => {
        fireEvent.change(element, { target: { value } });
    });
}

async function click(element: HTMLElement) {
    await act(async () => element.click());
}

async function flushPromises() {
    await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
        await Promise.resolve();
    });
}

describe('ExportCenter DOM interactions', () => {
    let container: HTMLDivElement;
    let root: Root;
    let fetchMock: FetchMock;
    let mounted = false;

    beforeEach(() => {
        vi.useFakeTimers();
        sessionStorage.clear();
        container = document.createElement('div');
        document.body.appendChild(container);
        fetchMock = vi.fn<typeof fetch>(async () =>
            emptyResponse(204),
        ) as FetchMock;
        vi.stubGlobal('fetch', fetchMock);
        vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
        root = createRoot(container);
    });

    afterEach(async () => {
        if (mounted) {
            await act(async () => root.unmount());
            mounted = false;
        }
        vi.clearAllTimers();
        vi.useRealTimers();
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
        container.remove();
    });

    async function mount(
        props: {
            tenantId?: string;
            csrf?: typeof csrf | null;
            onSessionInvalid?: (status: number) => void;
        } = {},
    ) {
        await act(async () => {
            root.render(
                createElement(ExportCenter, {
                    tenantId: props.tenantId ?? tenantId,
                    csrf: props.csrf === undefined ? csrf : props.csrf,
                    onSessionInvalid:
                        props.onSessionInvalid ??
                        vi.fn<(status: number) => void>(),
                }),
            );
        });
        mounted = true;
        await flushPromises();
    }

    async function fillRequiredDates() {
        await changeControl(control(container, 'Data inicial'), '2026-10-01');
        await changeControl(control(container, 'Data final'), '2026-10-23');
    }

    it('submits selected filters once on double click, polls to AVAILABLE, and expires with the exact ETag', async () => {
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith('/exports/active')) return emptyResponse(204);
            if (init?.method === 'POST' && url.endsWith('/exports')) {
                return jsonResponse({ ...job, state: 'REQUESTED' }, 202);
            }
            if (url.endsWith(`/exports/${job.exportId}`) && !init?.method) {
                return jobResponse('AVAILABLE', '"17"');
            }
            if (init?.method === 'DELETE') return emptyResponse(204);
            throw new Error(
                `Unexpected request ${init?.method ?? 'GET'} ${url}`,
            );
        });
        await mount();
        await changeControl(control(container, 'Formato'), 'PDF');
        await fillRequiredDates();
        await changeControl(control(container, 'ID do serviço'), serviceId);
        const completed = [...container.querySelectorAll('label')]
            .find((label) => label.textContent?.includes('Concluída'))
            ?.querySelector('input');
        if (!(completed instanceof HTMLInputElement))
            throw new Error('Status checkbox missing');
        await click(completed);
        await act(async () => {
            button(container, 'Solicitar exportação').click();
            button(container, 'Solicitar exportação').click();
        });
        await flushPromises();
        const postCalls = fetchMock.mock.calls.filter(
            ([url, init]) =>
                String(url).endsWith('/exports') && init?.method === 'POST',
        );
        expect(postCalls).toHaveLength(1);
        const sentBody = JSON.parse(String(postCalls[0][1]?.body));
        expect(sentBody).toEqual({
            format: 'PDF',
            category: 'APPOINTMENTS',
            filters: {
                from: '2026-10-01',
                to: '2026-10-23',
                statuses: ['COMPLETED'],
                serviceId,
            },
        });
        const idempotencyKey = new Headers(postCalls[0][1]?.headers).get(
            'Idempotency-Key',
        );
        expect(idempotencyKey).toHaveLength(36);

        await act(async () => vi.advanceTimersByTimeAsync(1500));
        await flushPromises();
        expect(container.textContent).toContain('Arquivo disponível');
        const jobReadCount = fetchMock.mock.calls.filter(([url]) =>
            String(url).endsWith(`/exports/${job.exportId}`),
        ).length;
        await act(async () => vi.advanceTimersByTimeAsync(5000));
        expect(
            fetchMock.mock.calls.filter(([url]) =>
                String(url).endsWith(`/exports/${job.exportId}`),
            ),
        ).toHaveLength(jobReadCount);
        await click(button(container, 'Encerrar acesso agora'));
        const deleteCall = fetchMock.mock.calls.find(
            ([, init]) => init?.method === 'DELETE',
        );
        expect(deleteCall?.[1]?.headers).toMatchObject({ 'If-Match': '"17"' });
        expect(
            fetchMock.mock.calls.some(([url]) =>
                String(url).includes('/content'),
            ),
        ).toBe(false);
    });

    it('queries /active after EXPORT_ALREADY_ACTIVE and follows the discovered job', async () => {
        let activeReads = 0;
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith('/exports/active')) {
                activeReads++;
                return activeReads === 1
                    ? emptyResponse(204)
                    : jobResponse('PROCESSING', '"8"');
            }
            if (init?.method === 'POST' && url.endsWith('/exports')) {
                return jsonResponse(
                    { error: { code: 'EXPORT_ALREADY_ACTIVE' } },
                    409,
                );
            }
            if (url.endsWith(`/exports/${job.exportId}`))
                return jobResponse('PROCESSING', '"8"');
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await fillRequiredDates();
        await click(button(container, 'Solicitar exportação'));
        await flushPromises();
        expect(activeReads).toBe(2);
        expect(container.textContent).toContain('Em processamento');
        expect(button(container, 'Solicitar exportação').disabled).toBe(true);
    });

    it('recovers an active job after reload when no local operation or uncertain intent exists', async () => {
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith('/exports/active'))
                return jobResponse('AVAILABLE', '"23"');
            if (init?.method === 'DELETE') return emptyResponse(204);
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        expect(
            fetchMock.mock.calls.some(([url]) =>
                String(url).endsWith('/v1/admin/exports/active'),
            ),
        ).toBe(true);
        expect(container.textContent).toContain('Arquivo disponível');
        await click(button(container, 'Encerrar acesso agora'));
        const deleteCall = fetchMock.mock.calls.find(
            ([, init]) => init?.method === 'DELETE',
        );
        expect(deleteCall?.[1]?.headers).toMatchObject({ 'If-Match': '"23"' });
    });

    it('does not create another request when /active returns 204 after an active-job conflict', async () => {
        let posts = 0;
        let activeReads = 0;
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith('/exports/active')) {
                activeReads++;
                return emptyResponse(204);
            }
            if (init?.method === 'POST' && url.endsWith('/exports')) {
                posts++;
                return jsonResponse({ code: 'EXPORT_ALREADY_ACTIVE' }, 409);
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await fillRequiredDates();
        await click(button(container, 'Solicitar exportação'));
        await flushPromises();
        expect(posts).toBe(1);
        expect(activeReads).toBe(2);
        expect(container.textContent).toContain(
            'Nenhuma exportação está ativa agora',
        );
        expect(button(container, 'Solicitar exportação').disabled).toBe(false);
    });

    it('keeps an uncertain intent ahead of /active after reload and retries the same body/key', async () => {
        const intent: ExportIntent = {
            tenantId,
            key: 'same-command-key-0001',
            body,
            phase: 'SENDING',
        };
        sessionStorage.setItem(storedKey, JSON.stringify({ tenantId, intent }));
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (init?.method === 'POST' && url.endsWith('/exports')) {
                return jsonResponse({ code: 'INVALID_EXPORT_REQUEST' }, 400);
            }
            throw new Error(
                `Unexpected request after uncertain reload: ${url}`,
            );
        });
        await mount();
        expect(container.textContent).toContain(
            'Resultado ainda não confirmado',
        );
        expect(fetchMock).not.toHaveBeenCalled();
        await click(button(container, 'Recuperar solicitação pendente'));
        await flushPromises();
        const [url, init] = fetchMock.mock.calls[0];
        expect(String(url)).toMatch(/\/exports$/);
        expect(new Headers(init?.headers).get('Idempotency-Key')).toBe(
            intent.key,
        );
        expect(JSON.parse(String(init?.body))).toEqual(intent.body);
        expect(
            fetchMock.mock.calls.some(([calledUrl]) =>
                String(calledUrl).endsWith('/exports/active'),
            ),
        ).toBe(false);
        expect(container.textContent).not.toContain(
            'Resultado ainda não confirmado',
        );
        expect(button(container, 'Solicitar exportação').disabled).toBe(false);
    });

    it.each([
        [
            'transport failure',
            async () => {
                throw new TypeError('offline');
            },
        ],
        ['408', async () => emptyResponse(408)],
        ['502', async () => emptyResponse(502)],
        ['503', async () => emptyResponse(503)],
        ['malformed 202', async () => jsonResponse({ broken: true }, 202)],
    ])(
        'preserves the original intent after %s and retries with the same key',
        async (_case, failingPost) => {
            let postCount = 0;
            fetchMock.mockImplementation(async (input, init) => {
                const url = String(input);
                if (url.endsWith('/exports/active')) return emptyResponse(204);
                if (init?.method === 'POST' && url.endsWith('/exports')) {
                    postCount++;
                    return postCount === 1
                        ? failingPost()
                        : jsonResponse({ ...job, state: 'REQUESTED' }, 202);
                }
                throw new Error(`Unexpected request ${url}`);
            });
            await mount();
            await fillRequiredDates();
            await click(button(container, 'Solicitar exportação'));
            await flushPromises();
            const persisted = JSON.parse(
                sessionStorage.getItem(storedKey) ?? 'null',
            );
            expect(persisted.intent.phase).toBe('UNCERTAIN');
            const originalKey = persisted.intent.key;
            const originalBody = persisted.intent.body;
            await click(button(container, 'Recuperar solicitação pendente'));
            await flushPromises();
            const retried = fetchMock.mock.calls.filter(
                ([, init]) => init?.method === 'POST',
            );
            expect(retried).toHaveLength(2);
            expect(
                new Headers(retried[1][1]?.headers).get('Idempotency-Key'),
            ).toBe(originalKey);
            expect(JSON.parse(String(retried[1][1]?.body))).toEqual(
                originalBody,
            );
            expect(container.textContent).not.toContain(
                'Resultado ainda não confirmado',
            );
        },
    );

    it('retries the same intent after a simulated commit whose response was lost', async () => {
        let postCount = 0;
        let logicalCreateCount = 0;
        const postBodies: string[] = [];
        const postKeys: string[] = [];
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith('/exports/active')) return emptyResponse(204);
            if (init?.method === 'POST' && url.endsWith('/exports')) {
                postCount++;
                postBodies.push(String(init.body));
                postKeys.push(
                    new Headers(init.headers).get('Idempotency-Key') ?? '',
                );
                if (postCount === 1) {
                    logicalCreateCount++;
                    throw new TypeError('response lost after server commit');
                }
                return jsonResponse({ ...job, state: 'REQUESTED' }, 202);
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await fillRequiredDates();
        await click(button(container, 'Solicitar exportação'));
        await flushPromises();
        expect(container.textContent).toContain(
            'Resultado ainda não confirmado',
        );

        await click(button(container, 'Recuperar solicitação pendente'));
        await flushPromises();

        expect(postCount).toBe(2);
        expect(logicalCreateCount).toBe(1);
        expect(postKeys[1]).toBe(postKeys[0]);
        expect(postBodies[1]).toBe(postBodies[0]);
        expect(JSON.parse(postBodies[0])).toEqual({
            format: 'CSV',
            category: 'APPOINTMENTS',
            filters: { from: '2026-10-01', to: '2026-10-23' },
        });
        expect(container.textContent).not.toContain(
            'Resultado ainda não confirmado',
        );
    });

    it('recovers a known export after reload and honors 412 without retrying DELETE', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let getCount = 0;
        const deleteHeaders: string[] = [];
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`) && !init?.method) {
                getCount++;
                return jobResponse('AVAILABLE', getCount === 1 ? '"4"' : '"5"');
            }
            if (init?.method === 'DELETE') {
                deleteHeaders.push(
                    new Headers(init.headers).get('If-Match') ?? '',
                );
                return jsonResponse({ code: 'VERSION_CONFLICT' }, 412);
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await flushPromises();
        expect(container.textContent).toContain('Arquivo disponível');
        await click(button(container, 'Encerrar acesso agora'));
        await flushPromises();
        expect(deleteHeaders).toEqual(['"4"']);
        expect(getCount).toBe(2);
        expect(container.textContent).toContain(
            'A versão mudou antes da expiração',
        );
        expect(button(container, 'Encerrar acesso agora').disabled).toBe(false);
        await click(button(container, 'Encerrar acesso agora'));
        expect(deleteHeaders).toEqual(['"4"', '"5"']);
        expect(getCount).toBe(3);
    });

    it('requires a fresh job read before enabling expiration when ETag is absent', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let reads = 0;
        let ifMatch: string | null = null;
        fetchMock.mockImplementation(async (input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`) && !init?.method) {
                reads++;
                return jobResponse('AVAILABLE', reads === 1 ? null : '"31"');
            }
            if (init?.method === 'DELETE') {
                ifMatch = new Headers(init.headers).get('If-Match');
                return emptyResponse(204);
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        const expireButton = button(container, 'Encerrar acesso agora');
        expect(expireButton.disabled).toBe(true);
        expect(reads).toBe(1);

        await click(button(container, 'Atualizar estado'));
        expect(reads).toBe(2);
        expect(button(container, 'Encerrar acesso agora').disabled).toBe(false);
        await click(button(container, 'Encerrar acesso agora'));
        expect(ifMatch).toBe('"31"');
    });

    it('downloads only after obtaining an in-memory handle and stops at 410', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        const createUrl = vi
            .spyOn(URL, 'createObjectURL')
            .mockReturnValue('blob:export-test');
        const revokeUrl = vi
            .spyOn(URL, 'revokeObjectURL')
            .mockImplementation(() => undefined);
        const anchorClick = vi
            .spyOn(HTMLAnchorElement.prototype, 'click')
            .mockImplementation(() => undefined);
        fetchMock.mockImplementation(async (input) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return jobResponse('AVAILABLE', '"4"');
            if (url.endsWith('/download-token'))
                return jsonResponse({
                    downloadHandle: 'h'.repeat(40),
                    expiresAt: job.expiresAt,
                });
            if (url.endsWith('/content')) return emptyResponse(410);
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await flushPromises();
        expect(
            fetchMock.mock.calls.some(
                ([url, init]) =>
                    String(url).endsWith('/content') &&
                    new Headers(init?.headers).get(
                        'X-Export-Download-Handle',
                    ) === 'h'.repeat(40),
            ),
        ).toBe(true);
        expect(createUrl).not.toHaveBeenCalled();
        expect(anchorClick).not.toHaveBeenCalled();
        expect(container.textContent).toContain(
            'não confirma a remoção física',
        );
        expect(revokeUrl).not.toHaveBeenCalled();
    });

    it.each([
        {
            name: 'CSV without Content-Disposition',
            format: 'CSV' as ExportFormat,
            contentDisposition: null,
            expected: 'exportacao-agendamentos.csv',
        },
        {
            name: 'PDF without Content-Disposition',
            format: 'PDF' as ExportFormat,
            contentDisposition: null,
            expected: 'exportacao-agendamentos.pdf',
        },
        {
            name: 'sanitization-rejected filename',
            format: 'CSV' as ExportFormat,
            contentDisposition: 'attachment; filename="../relatorio.csv"',
            expected: 'exportacao-agendamentos.csv',
        },
        {
            name: 'valid filename from Content-Disposition',
            format: 'PDF' as ExportFormat,
            contentDisposition: 'attachment; filename="relatorio-final.pdf"',
            expected: 'relatorio-final.pdf',
        },
    ])(
        'uses the correct safe filename for $name',
        async ({ format, contentDisposition, expected }) => {
            sessionStorage.setItem(
                storedKey,
                JSON.stringify({ tenantId, exportId: job.exportId }),
            );
            vi.spyOn(URL, 'createObjectURL').mockReturnValue(
                'blob:filename-test',
            );
            const clickedFilenames: string[] = [];
            const anchorClick = vi
                .spyOn(HTMLAnchorElement.prototype, 'click')
                .mockImplementation(function (this: HTMLAnchorElement) {
                    clickedFilenames.push(this.download);
                });
            fetchMock.mockImplementation(async (input) => {
                const url = String(input);
                if (url.endsWith(`/exports/${job.exportId}`))
                    return jsonResponse(
                        {
                            ...job,
                            format,
                            state: 'AVAILABLE',
                            expiresAt: '2099-10-23T12:31:00Z',
                            updatedAt: '2026-10-23T12:02:00Z',
                        },
                        200,
                        { ETag: '"4"' },
                    );
                if (url.endsWith('/download-token'))
                    return jsonResponse({ downloadHandle: 'h'.repeat(40) });
                if (url.endsWith('/content')) {
                    const headers: Record<string, string> = {
                        'Content-Type':
                            format === 'CSV' ? 'text/csv' : 'application/pdf',
                    };
                    if (contentDisposition)
                        headers['Content-Disposition'] = contentDisposition;
                    return new Response('synthetic export content', {
                        status: 200,
                        headers,
                    });
                }
                throw new Error(`Unexpected request ${url}`);
            });
            await mount();
            await click(button(container, 'Baixar arquivo'));
            await flushPromises();

            expect(anchorClick).toHaveBeenCalledOnce();
            expect(clickedFilenames).toEqual([expected]);
        },
    );

    it('streams an authorized download from memory and revokes its temporary URL', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        const createUrl = vi
            .spyOn(URL, 'createObjectURL')
            .mockReturnValue('blob:export-success');
        const revokeUrl = vi
            .spyOn(URL, 'revokeObjectURL')
            .mockImplementation(() => undefined);
        const anchorClick = vi
            .spyOn(HTMLAnchorElement.prototype, 'click')
            .mockImplementation(() => undefined);
        fetchMock.mockImplementation(async (input) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return jobResponse('AVAILABLE', '"4"');
            if (url.endsWith('/download-token'))
                return jsonResponse({ downloadHandle: 'h'.repeat(40) });
            if (url.endsWith('/content'))
                return new Response('appointment-id\n1', {
                    status: 200,
                    headers: {
                        'Content-Type': 'text/csv',
                        'Content-Disposition':
                            'attachment; filename=export.csv',
                    },
                });
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await flushPromises();
        expect(createUrl).toHaveBeenCalledOnce();
        expect(anchorClick).toHaveBeenCalledOnce();
        expect(container.textContent).toContain('Download iniciado');
        await act(async () => vi.advanceTimersByTimeAsync(60_000));
        expect(revokeUrl).toHaveBeenCalledWith('blob:export-success');
    });

    it('revokes a temporary object URL and clears timers on component unmount', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        const revokeUrl = vi
            .spyOn(URL, 'revokeObjectURL')
            .mockImplementation(() => undefined);
        vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:unmount-test');
        vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(
            () => undefined,
        );
        fetchMock.mockImplementation(async (input) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return jobResponse('AVAILABLE');
            if (url.endsWith('/download-token'))
                return jsonResponse({ downloadHandle: 'h'.repeat(40) });
            if (url.endsWith('/content')) return new Response('csv bytes');
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await flushPromises();
        expect(vi.getTimerCount()).toBeGreaterThan(0);
        await act(async () => root.unmount());
        mounted = false;
        expect(revokeUrl).toHaveBeenCalledWith('blob:unmount-test');
        expect(vi.getTimerCount()).toBe(0);
    });

    it('reports a content-storage failure without starting a download', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        const createUrl = vi.spyOn(URL, 'createObjectURL');
        const anchorClick = vi
            .spyOn(HTMLAnchorElement.prototype, 'click')
            .mockImplementation(() => undefined);
        let contentAttempts = 0;
        fetchMock.mockImplementation(async (input) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return jobResponse('AVAILABLE', '"4"');
            if (url.endsWith('/download-token'))
                return jsonResponse({ downloadHandle: 'h'.repeat(40) });
            if (url.endsWith('/content')) {
                contentAttempts++;
                return contentAttempts === 1
                    ? emptyResponse(503)
                    : new Response('valid retry content', {
                          status: 200,
                          headers: { 'Content-Type': 'text/csv' },
                      });
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await flushPromises();
        expect(container.textContent).toContain('HTTP 503');
        expect(container.textContent).toContain('Arquivo disponível');
        expect(button(container, 'Baixar arquivo').disabled).toBe(false);
        expect(createUrl).not.toHaveBeenCalled();
        expect(anchorClick).not.toHaveBeenCalled();

        await click(button(container, 'Baixar arquivo'));
        await flushPromises();
        expect(contentAttempts).toBe(2);
        expect(createUrl).toHaveBeenCalledOnce();
        expect(anchorClick).toHaveBeenCalledOnce();
    });

    it('ignores an older polling response after a newer manual refresh wins', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let releasePoll: ((response: Response) => void) | undefined;
        let jobReads = 0;
        fetchMock.mockImplementation((input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`)) {
                jobReads++;
                if (jobReads === 1)
                    return Promise.resolve(jobResponse('PROCESSING'));
                if (jobReads === 2)
                    return new Promise((resolve) => {
                        releasePoll = resolve;
                    });
                return Promise.resolve(jobResponse('AVAILABLE', '"9"'));
            }
            throw new Error(`Unexpected request ${url} ${init?.method}`);
        });
        await mount();
        await act(async () => vi.advanceTimersByTimeAsync(1500));
        await click(button(container, 'Atualizar estado'));
        await flushPromises();
        expect(container.textContent).toContain('Arquivo disponível');
        releasePoll?.(jobResponse('PROCESSING', '"7"'));
        await flushPromises();
        expect(container.textContent).toContain('Arquivo disponível');
    });

    it('clears request locks and private UI when tenant changes during an unresolved create', async () => {
        let resolvePost: ((response: Response) => void) | undefined;
        let postSignal: AbortSignal | undefined;
        fetchMock.mockImplementation((input, init) => {
            const url = String(input);
            if (url.endsWith('/exports/active'))
                return Promise.resolve(emptyResponse(204));
            if (init?.method === 'POST') {
                postSignal = init.signal ?? undefined;
                return new Promise((resolve) => {
                    resolvePost = resolve;
                });
            }
            return Promise.resolve(emptyResponse(204));
        });
        await mount();
        await fillRequiredDates();
        await click(button(container, 'Solicitar exportação'));
        const tenantAStorage = sessionStorage.getItem(storedKey);
        expect(tenantAStorage).not.toBeNull();
        await mount({ tenantId: 'tenant-ui-other' });
        expect(postSignal?.aborted).toBe(true);
        resolvePost?.(jsonResponse({ ...job, state: 'AVAILABLE' }, 202));
        await flushPromises();
        expect(container.textContent).not.toContain('Arquivo disponível');
        expect(sessionStorage.getItem(storedKey)).toBeNull();
        expect(button(container, 'Solicitar exportação').disabled).toBe(false);
    });

    it('aborts a polling request and does not restore job data after logout', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let pollSignal: AbortSignal | undefined;
        let releasePoll: ((response: Response) => void) | undefined;
        let reads = 0;
        fetchMock.mockImplementation((input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`)) {
                reads++;
                if (reads === 1)
                    return Promise.resolve(jobResponse('PROCESSING'));
                pollSignal = init?.signal ?? undefined;
                return new Promise((resolve) => {
                    releasePoll = resolve;
                });
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await act(async () => vi.advanceTimersByTimeAsync(1500));
        await mount({ csrf: null });
        expect(pollSignal?.aborted).toBe(true);
        releasePoll?.(jobResponse('AVAILABLE'));
        await flushPromises();
        expect(container.textContent).not.toContain('Arquivo disponível');
        expect(sessionStorage.getItem(storedKey)).toBeNull();
        await mount();
        expect(reads).toBe(2);
        expect(container.textContent).not.toContain('Arquivo disponível');
    });

    it('does not send content or create a URL when logout interrupts handle issuance', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let resolveHandle: ((response: Response) => void) | undefined;
        let tokenSignal: AbortSignal | undefined;
        const createUrl = vi.spyOn(URL, 'createObjectURL');
        fetchMock.mockImplementation((input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return Promise.resolve(jobResponse('AVAILABLE'));
            if (url.endsWith('/download-token')) {
                tokenSignal = init?.signal ?? undefined;
                return new Promise((resolve) => {
                    resolveHandle = resolve;
                });
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await mount({ csrf: null });
        expect(tokenSignal?.aborted).toBe(true);
        resolveHandle?.(jsonResponse({ downloadHandle: 'h'.repeat(40) }));
        await flushPromises();
        expect(
            fetchMock.mock.calls.some(([url]) =>
                String(url).endsWith('/content'),
            ),
        ).toBe(false);
        expect(createUrl).not.toHaveBeenCalled();
        expect(container.textContent).not.toContain('Arquivo disponível');
    });

    it('does not start a delayed download when logout interrupts the content response', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let resolveContent: ((response: Response) => void) | undefined;
        let contentSignal: AbortSignal | undefined;
        const createUrl = vi.spyOn(URL, 'createObjectURL');
        const anchorClick = vi
            .spyOn(HTMLAnchorElement.prototype, 'click')
            .mockImplementation(() => undefined);
        fetchMock.mockImplementation((input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return Promise.resolve(jobResponse('AVAILABLE'));
            if (url.endsWith('/download-token'))
                return Promise.resolve(
                    jsonResponse({ downloadHandle: 'h'.repeat(40) }),
                );
            if (url.endsWith('/content')) {
                contentSignal = init?.signal ?? undefined;
                return new Promise((resolve) => {
                    resolveContent = resolve;
                });
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await flushPromises();
        expect(contentSignal?.aborted).toBe(false);

        await mount({ csrf: null });
        expect(contentSignal?.aborted).toBe(true);
        resolveContent?.(
            new Response('private export contents', {
                status: 200,
                headers: { 'Content-Type': 'text/csv' },
            }),
        );
        await flushPromises();

        expect(createUrl).not.toHaveBeenCalled();
        expect(anchorClick).not.toHaveBeenCalled();
        expect(container.textContent).not.toContain('Download iniciado');
        expect(sessionStorage.getItem(storedKey)).toBeNull();
    });

    it('aborts a pending request on unmount and ignores its late response', async () => {
        sessionStorage.setItem(
            storedKey,
            JSON.stringify({ tenantId, exportId: job.exportId }),
        );
        let resolveHandle: ((response: Response) => void) | undefined;
        let requestSignal: AbortSignal | undefined;
        const createUrl = vi.spyOn(URL, 'createObjectURL');
        fetchMock.mockImplementation((input, init) => {
            const url = String(input);
            if (url.endsWith(`/exports/${job.exportId}`))
                return Promise.resolve(jobResponse('AVAILABLE'));
            if (url.endsWith('/download-token')) {
                requestSignal = init?.signal ?? undefined;
                return new Promise((resolve) => {
                    resolveHandle = resolve;
                });
            }
            throw new Error(`Unexpected request ${url}`);
        });
        await mount();
        await click(button(container, 'Baixar arquivo'));
        await act(async () => root.unmount());
        mounted = false;
        expect(requestSignal?.aborted).toBe(true);
        resolveHandle?.(jsonResponse({ downloadHandle: 'h'.repeat(40) }));
        await flushPromises();
        expect(
            fetchMock.mock.calls.some(([url]) =>
                String(url).endsWith('/content'),
            ),
        ).toBe(false);
        expect(createUrl).not.toHaveBeenCalled();
    });

    it('uses only strong quoted job ETags for the early-expiration command', () => {
        expect(isStrongJobEtag('"4"')).toBe(true);
        expect(isStrongJobEtag('W/"4"')).toBe(false);
        expect(isStrongJobEtag('*')).toBe(false);
        expect(isStrongJobEtag('"4", "5"')).toBe(false);
        expect(isStrongJobEtag(null)).toBe(false);
    });
});
