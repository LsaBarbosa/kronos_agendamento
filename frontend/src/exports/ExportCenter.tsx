import {
    useEffect,
    useLayoutEffect,
    useRef,
    useState,
    type ReactElement,
} from 'react';
import { apiBaseUrl } from '../config';
import {
    exportStatuses,
    canDownloadExport,
    classifyCreateResult,
    isExportJob,
    isCurrentStatusRequest,
    isStrongJobEtag,
    isTerminalExportState,
    recoverIntentPhase,
    statusLabel,
    validateExportRequest,
    type ExportFormat,
    type ExportIntent,
    type ExportJob,
    type ExportRequest,
    type ExportStatus,
    type StoredExportContext,
} from './types';
import './exports.css';

const storagePrefix = 'agd044.export-context.v1:';
const jobPath = (exportId: string) =>
    `/v1/admin/exports/${encodeURIComponent(exportId)}`;

export type ExportCenterProps = {
    tenantId: string;
    csrf: { headerName: string; token: string } | null;
    onSessionInvalid: (status: number) => void;
};

function storageKey(tenantId: string) {
    return storagePrefix + encodeURIComponent(tenantId);
}

function removeStoredContext(tenantId: string) {
    if (typeof window === 'undefined') return;
    try {
        window.sessionStorage.removeItem(storageKey(tenantId));
    } catch {
        // The browser may disable storage; callers still clear in-memory state.
    }
}

function readStoredContext(tenantId: string): StoredExportContext | null {
    if (typeof window === 'undefined') return null;
    try {
        const raw = window.sessionStorage.getItem(storageKey(tenantId));
        if (!raw) return null;
        const value = JSON.parse(raw) as StoredExportContext;
        if (value.tenantId !== tenantId) return null;
        const intent = value.intent;
        if (
            intent &&
            (intent.tenantId !== tenantId ||
                typeof intent.key !== 'string' ||
                intent.key.length < 16 ||
                !['PREPARED', 'SENDING', 'UNCERTAIN'].includes(intent.phase) ||
                !intent.body ||
                !['CSV', 'PDF'].includes(intent.body.format) ||
                intent.body.category !== 'APPOINTMENTS' ||
                !intent.body.filters)
        ) {
            removeStoredContext(tenantId);
            return null;
        }
        return {
            tenantId,
            intent: intent
                ? {
                      ...intent,
                      phase: recoverIntentPhase(intent.phase),
                  }
                : undefined,
            exportId:
                typeof value.exportId === 'string' ? value.exportId : undefined,
        };
    } catch {
        removeStoredContext(tenantId);
        return null;
    }
}

function getErrorCode(body: unknown): string | undefined {
    if (!body || typeof body !== 'object') return undefined;
    const value = body as { code?: unknown; error?: { code?: unknown } };
    if (typeof value.error?.code === 'string') return value.error.code;
    if (typeof value.code === 'string') return value.code;
    return undefined;
}

function formatSize(size: number | null | undefined): string | null {
    if (size == null) return null;
    if (size < 1024) return `${size} bytes`;
    return `${(size / 1024 / 1024).toFixed(2)} MB`;
}

function formatDate(value: string | null | undefined): string | null {
    if (!value) return null;
    const date = new Date(value);
    return Number.isNaN(date.valueOf()) ? null : date.toLocaleString();
}

function safeFilename(response: Response, format: ExportFormat): string {
    const value = response.headers.get('Content-Disposition');
    const match = value?.match(/filename\*?=(?:UTF-8''|")?([^;"]+)/i);
    const proposed = match?.[1]?.trim();
    let filename = '';
    try {
        filename = proposed ? decodeURIComponent(proposed) : '';
    } catch {
        // Ignore a malformed optional filename and use the safe default below.
    }
    if (
        !filename ||
        filename.length > 200 ||
        filename.includes('/') ||
        filename.includes('\\')
    ) {
        return `exportacao-agendamentos.${format.toLowerCase() === 'CSV' ? 'csv' : 'pdf'}`;
    }
    return filename;
}

export function ExportCenter(props: ExportCenterProps): ReactElement {
    const { tenantId, csrf, onSessionInvalid } = props;
    const csrfContextKey = csrf ? `${csrf.headerName}\u0000${csrf.token}` : '';
    const [initialContext] = useState(() => readStoredContext(tenantId));
    const [format, setFormat] = useState<ExportFormat>('CSV');
    const [from, setFrom] = useState('');
    const [to, setTo] = useState('');
    const [statuses, setStatuses] = useState<ExportStatus[]>([]);
    const [serviceId, setServiceId] = useState('');
    const [job, setJob] = useState<ExportJob | null>(null);
    const [jobEtag, setJobEtag] = useState<string | null>(null);
    const [intent, setIntent] = useState<ExportIntent | null>(
        initialContext?.intent ?? null,
    );
    const [loading, setLoading] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [now, setNow] = useState(Date.now());
    const [pollGeneration, setPollGeneration] = useState(0);
    const [activeLookupRequired, setActiveLookupRequired] = useState(false);
    const contextEpoch = useRef(0);
    const contextRef = useRef(tenantId);
    const csrfRef = useRef(csrf);
    const csrfContextRef = useRef(csrfContextKey);
    const jobIdRef = useRef(initialContext?.exportId ?? '');
    const jobEtagRef = useRef<string | null>(null);
    const recoveryExportIdRef = useRef(initialContext?.exportId ?? '');
    const recoveryAllowedRef = useRef(true);
    const intentRef = useRef(intent);
    const requestLock = useRef(false);
    const statusRequestSequence = useRef(0);
    const downloadUrl = useRef<string | null>(null);
    const downloadCleanupTimer = useRef<number | null>(null);
    const requestControllers = useRef(new Set<AbortController>());

    function createRequestController() {
        const controller = new AbortController();
        requestControllers.current.add(controller);
        return controller;
    }

    function releaseRequestController(controller: AbortController) {
        requestControllers.current.delete(controller);
    }

    function abortRequests() {
        for (const controller of requestControllers.current) controller.abort();
        requestControllers.current.clear();
    }

    function persist(next: StoredExportContext): boolean {
        if (typeof window === 'undefined') return false;
        try {
            window.sessionStorage.setItem(
                storageKey(next.tenantId),
                JSON.stringify(next),
            );
            return true;
        } catch {
            setError(
                'Não foi possível guardar a recuperação desta exportação nesta sessão do navegador.',
            );
            return false;
        }
    }

    function setIntentForContext(
        next: ExportIntent | null,
        contextId: string,
    ): boolean {
        intentRef.current = next;
        setIntent(next);
        const current = readStoredContext(contextId) ?? { tenantId: contextId };
        return persist({
            ...current,
            tenantId: contextId,
            exportId: next ? undefined : current.exportId,
            intent: next ?? undefined,
        });
    }

    function isCurrent(
        contextId: string,
        epoch: number,
        exportId?: string,
        requestSequence?: number,
    ) {
        return (
            contextRef.current === contextId &&
            contextEpoch.current === epoch &&
            csrfRef.current !== null &&
            (!exportId || jobIdRef.current === exportId) &&
            (requestSequence === undefined ||
                isCurrentStatusRequest(
                    requestSequence,
                    statusRequestSequence.current,
                    contextRef.current === contextId,
                    !exportId || jobIdRef.current === exportId,
                    csrfRef.current !== null,
                ))
        );
    }

    function clearDownloadUrl() {
        if (downloadCleanupTimer.current !== null) {
            window.clearTimeout(downloadCleanupTimer.current);
            downloadCleanupTimer.current = null;
        }
        if (downloadUrl.current) URL.revokeObjectURL(downloadUrl.current);
        downloadUrl.current = null;
    }

    function applyJob(value: ExportJob, etag: string | null) {
        const strongEtag = isStrongJobEtag(etag) ? etag : null;
        jobIdRef.current = value.exportId;
        jobEtagRef.current = strongEtag;
        setJob(value);
        setJobEtag(strongEtag);
    }

    function clearJob(contextId?: string) {
        jobIdRef.current = '';
        jobEtagRef.current = null;
        setJob(null);
        setJobEtag(null);
        if (contextId) {
            const current = readStoredContext(contextId);
            if (current) {
                persist({
                    tenantId: contextId,
                    intent: current.intent,
                });
            }
        }
    }

    function invalidateContextEpoch() {
        contextEpoch.current++;
    }

    useLayoutEffect(() => {
        const previousTenant = contextRef.current;
        const contextChanged = previousTenant !== tenantId;
        const sessionChanged = csrfContextRef.current !== csrfContextKey;
        const loggedOut = csrf === null;
        contextRef.current = tenantId;
        csrfRef.current = csrf;
        csrfContextRef.current = csrfContextKey;
        contextEpoch.current++;
        if (contextChanged || sessionChanged || loggedOut) {
            abortRequests();
            recoveryAllowedRef.current = false;
            statusRequestSequence.current++;
            recoveryExportIdRef.current = '';
            if (previousTenant) removeStoredContext(previousTenant);
            if (contextChanged) removeStoredContext(tenantId);
            clearDownloadUrl();
            jobIdRef.current = '';
            intentRef.current = null;
            requestLock.current = false;
            setIntent(null);
            setJob(null);
            jobEtagRef.current = null;
            setJobEtag(null);
            setFormat('CSV');
            setFrom('');
            setTo('');
            setStatuses([]);
            setServiceId('');
            setBusy(false);
            setLoading(false);
            setActiveLookupRequired(false);
            setError('');
            setNotice('');
        }
        return invalidateContextEpoch;
        // csrfContextKey fully captures the header/token fields used by this effect.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantId, csrfContextKey]);

    useEffect(
        () => () => {
            abortRequests();
            clearDownloadUrl();
        },
        [],
    );

    useEffect(() => {
        const id = recoveryExportIdRef.current;
        if (!csrf || !recoveryAllowedRef.current || intentRef.current) return;
        const epoch = contextEpoch.current;
        const sequence = ++statusRequestSequence.current;
        const lookupPath = id ? jobPath(id) : '/v1/admin/exports/active';
        const controller = createRequestController();
        let cancelled = false;
        setLoading(true);
        void fetch(apiBaseUrl + lookupPath, {
            credentials: 'same-origin',
            cache: 'no-store',
            signal: controller.signal,
        })
            .then(async (response) => {
                if (
                    !isCurrent(tenantId, epoch, id || undefined, sequence) ||
                    sequence !== statusRequestSequence.current ||
                    cancelled
                )
                    return;
                if (response.status === 401 || response.status === 403) {
                    onSessionInvalid(response.status);
                    return;
                }
                if (response.status === 204 && !id) {
                    clearJob(tenantId);
                    setNotice(
                        'Nenhuma exportação ativa foi encontrada para esta sessão.',
                    );
                    return;
                }
                if (!response.ok)
                    throw new Error(
                        'Não foi possível recuperar a exportação anterior.',
                    );
                const value: unknown = await response.json();
                if (
                    !isCurrent(tenantId, epoch, id || undefined, sequence) ||
                    sequence !== statusRequestSequence.current ||
                    cancelled
                )
                    return;
                if (!isExportJob(value))
                    throw new Error(
                        'A resposta de recuperação não corresponde ao contrato de exportação.',
                    );
                applyJob(value, response.headers.get('ETag'));
                if (!id) updateStoredExportId(value.exportId, tenantId);
                setError('');
            })
            .catch((cause: unknown) => {
                if (
                    cancelled ||
                    !isCurrent(tenantId, epoch, id || undefined, sequence) ||
                    sequence !== statusRequestSequence.current
                )
                    return;
                setError(
                    cause instanceof Error
                        ? cause.message
                        : 'Não foi possível recuperar a exportação.',
                );
            })
            .finally(() => {
                releaseRequestController(controller);
                if (
                    !cancelled &&
                    isCurrent(tenantId, epoch, id || undefined, sequence) &&
                    sequence === statusRequestSequence.current
                )
                    setLoading(false);
            });
        return () => {
            cancelled = true;
            controller.abort();
            releaseRequestController(controller);
        };
        // Only the initial authenticated context may restore a stored operation.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantId, csrf === null]);

    useEffect(() => {
        if (!csrf || !job || isTerminalExportState(job.state)) return;
        const contextId = tenantId;
        const id = job.exportId;
        const epoch = contextEpoch.current;
        const sequence = ++statusRequestSequence.current;
        const controller = createRequestController();
        let cancelled = false;
        let timer = 0;
        const poll = async () => {
            try {
                const response = await fetch(apiBaseUrl + jobPath(id), {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    signal: controller.signal,
                });
                if (
                    !isCurrent(contextId, epoch, id, sequence) ||
                    sequence !== statusRequestSequence.current ||
                    cancelled
                )
                    return;
                if (response.status === 401 || response.status === 403) {
                    onSessionInvalid(response.status);
                    return;
                }
                if (response.ok) {
                    const value: unknown = await response.json();
                    if (
                        !isCurrent(contextId, epoch, id, sequence) ||
                        sequence !== statusRequestSequence.current ||
                        cancelled
                    )
                        return;
                    if (!isExportJob(value))
                        throw new Error('Resposta de estado inválida.');
                    applyJob(value, response.headers.get('ETag'));
                    setError('');
                } else {
                    setError(
                        'Não foi possível atualizar o estado. A consulta será repetida.',
                    );
                }
            } catch {
                if (
                    isCurrent(contextId, epoch, id, sequence) &&
                    sequence === statusRequestSequence.current &&
                    !cancelled
                ) {
                    setError(
                        'Falha de conexão ao consultar o estado. A consulta será repetida.',
                    );
                }
            } finally {
                if (
                    isCurrent(contextId, epoch, id, sequence) &&
                    sequence === statusRequestSequence.current &&
                    !cancelled
                ) {
                    timer = window.setTimeout(() => void poll(), 2500);
                }
            }
        };
        timer = window.setTimeout(() => void poll(), 1500);
        return () => {
            cancelled = true;
            window.clearTimeout(timer);
            controller.abort();
            releaseRequestController(controller);
        };
    }, [csrf, job, onSessionInvalid, pollGeneration, tenantId]);

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(timer);
    }, []);

    const expiredByClock =
        job?.state === 'AVAILABLE' &&
        !canDownloadExport(job.state, job.expiresAt, now);
    const displayState =
        expiredByClock && job?.state === 'AVAILABLE' ? 'EXPIRED' : job?.state;
    const canDownload =
        !!job && canDownloadExport(job.state, job.expiresAt, now);
    const currentIntent = intent;

    function makeBody(): ExportRequest {
        const filters: ExportRequest['filters'] = { from, to };
        if (statuses.length) filters.statuses = [...statuses].sort();
        if (serviceId.trim()) filters.serviceId = serviceId.trim();
        return { format, category: 'APPOINTMENTS', filters };
    }

    function updateStoredExportId(exportId: string, contextId: string) {
        jobIdRef.current = exportId;
        recoveryExportIdRef.current = exportId;
        const current = readStoredContext(contextId) ?? { tenantId: contextId };
        persist({
            ...current,
            tenantId: contextId,
            exportId,
            intent: undefined,
        });
        intentRef.current = null;
        setIntent(null);
    }

    async function discoverActive() {
        if (!csrf || intentRef.current) return;
        const contextId = tenantId;
        const epoch = contextEpoch.current;
        const sequence = ++statusRequestSequence.current;
        const controller = createRequestController();
        setLoading(true);
        setError('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/exports/active',
                {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    signal: controller.signal,
                },
            );
            if (!isCurrent(contextId, epoch, undefined, sequence)) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 204) {
                clearJob(contextId);
                setActiveLookupRequired(false);
                setNotice(
                    'Nenhuma exportação está ativa agora. Você pode iniciar uma nova solicitação quando quiser.',
                );
                return;
            }
            if (!response.ok)
                throw new Error(
                    `Não foi possível consultar a exportação ativa (HTTP ${response.status}).`,
                );
            const value: unknown = await response.json();
            if (!isCurrent(contextId, epoch, undefined, sequence)) return;
            if (!isExportJob(value))
                throw new Error(
                    'A consulta da exportação ativa retornou dados inválidos.',
                );
            applyJob(value, response.headers.get('ETag'));
            updateStoredExportId(value.exportId, contextId);
            setActiveLookupRequired(false);
            setNotice('Exportação ativa recuperada.');
        } catch (cause) {
            if (isCurrent(contextId, epoch, undefined, sequence)) {
                setError(
                    cause instanceof Error
                        ? cause.message
                        : 'Falha ao consultar a exportação ativa.',
                );
            }
        } finally {
            releaseRequestController(controller);
            if (isCurrent(contextId, epoch, undefined, sequence)) {
                setLoading(false);
                setPollGeneration((current) => current + 1);
            }
        }
    }

    async function requestExport(retryIntent?: ExportIntent) {
        if (
            !csrf ||
            loading ||
            activeLookupRequired ||
            requestLock.current ||
            !isCurrent(tenantId, contextEpoch.current)
        )
            return;
        const body = retryIntent?.body ?? makeBody();
        const validationError = validateExportRequest(body);
        if (validationError) {
            setError(validationError);
            return;
        }
        if (intentRef.current && !retryIntent) {
            setError(
                'Resolva primeiro a solicitação de resultado incerto usando a mesma chave e os mesmos filtros.',
            );
            return;
        }
        const activeIntent: ExportIntent = retryIntent ?? {
            tenantId,
            key: crypto.randomUUID(),
            body,
            phase: 'PREPARED',
        };
        if (activeIntent.tenantId !== tenantId) return;
        const contextId = tenantId;
        const epoch = contextEpoch.current;
        requestLock.current = true;
        setBusy(true);
        setError('');
        setNotice('');
        jobIdRef.current = '';
        recoveryExportIdRef.current = '';
        jobEtagRef.current = null;
        setJobEtag(null);
        statusRequestSequence.current++;
        setJob(null);
        if (
            !setIntentForContext(
                { ...activeIntent, phase: 'SENDING' },
                contextId,
            )
        ) {
            intentRef.current = null;
            setIntent(null);
            requestLock.current = false;
            setBusy(false);
            return;
        }
        const controller = createRequestController();
        try {
            const response = await fetch(apiBaseUrl + '/v1/admin/exports', {
                method: 'POST',
                credentials: 'same-origin',
                cache: 'no-store',
                signal: controller.signal,
                headers: {
                    'Content-Type': 'application/json',
                    [csrf.headerName]: csrf.token,
                    'Idempotency-Key': activeIntent.key,
                },
                body: JSON.stringify(activeIntent.body),
            });
            if (!isCurrent(contextId, epoch)) return;
            if (response.status === 401 || response.status === 403) {
                setIntentForContext(null, contextId);
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 202) {
                const value: unknown = await response.json().catch(() => null);
                if (!isCurrent(contextId, epoch)) return;
                if (!isExportJob(value)) {
                    setIntentForContext(
                        { ...activeIntent, phase: 'UNCERTAIN' },
                        contextId,
                    );
                    setNotice(
                        'A solicitação pode ter sido aceita, mas a resposta não permitiu confirmá-la. Recupere usando a mesma chave.',
                    );
                    return;
                }
                updateStoredExportId(value.exportId, contextId);
                applyJob(value, response.headers.get('ETag'));
                setNotice(
                    'Solicitação aceita. O arquivo ainda está sendo preparado.',
                );
                return;
            }
            const errorCode = getErrorCode(
                await response.json().catch(() => null),
            );
            if (!isCurrent(contextId, epoch)) return;
            const resultKind = classifyCreateResult(
                response.status,
                errorCode,
                false,
            );
            if (resultKind === 'UNCERTAIN') {
                setIntentForContext(
                    { ...activeIntent, phase: 'UNCERTAIN' },
                    contextId,
                );
                setNotice(
                    'O resultado da solicitação é incerto. Repita esta mesma intenção; não criaremos outra exportação lógica.',
                );
                return;
            }
            setIntentForContext(null, contextId);
            if (resultKind === 'ACTIVE_CONFLICT') {
                setActiveLookupRequired(true);
                setNotice(
                    'Já existe uma exportação ativa. Consultando o estado desse job.',
                );
                await discoverActive();
            } else if (resultKind === 'KEY_CONFLICT') {
                setError(
                    'A chave de idempotência já está associada a outro conteúdo. Revise os filtros antes de criar uma nova solicitação.',
                );
            } else if (resultKind === 'UNAVAILABLE') {
                setError(
                    'A exportação não foi aceita porque o armazenamento protegido está indisponível. Tente novamente mais tarde.',
                );
            } else if (response.status === 400) {
                setError(
                    'O servidor rejeitou os filtros. Revise os valores e tente novamente.',
                );
            } else if (response.status === 404) {
                setError('A solicitação não foi encontrada para esta sessão.');
            } else {
                setError(
                    `A solicitação foi rejeitada (HTTP ${response.status}). Revise os filtros antes de tentar novamente.`,
                );
            }
        } catch {
            if (!isCurrent(contextId, epoch)) return;
            setIntentForContext(
                { ...activeIntent, phase: 'UNCERTAIN' },
                contextId,
            );
            setNotice(
                'A resposta não chegou. O resultado é incerto; repita a mesma solicitação com a chave preservada.',
            );
        } finally {
            releaseRequestController(controller);
            if (isCurrent(contextId, epoch)) {
                requestLock.current = false;
                setBusy(false);
            }
        }
    }

    async function refreshJob() {
        if (!csrf || !job) return;
        const contextId = tenantId;
        const epoch = contextEpoch.current;
        const id = job.exportId;
        const sequence = ++statusRequestSequence.current;
        const controller = createRequestController();
        setLoading(true);
        setError('');
        try {
            const response = await fetch(apiBaseUrl + jobPath(id), {
                credentials: 'same-origin',
                cache: 'no-store',
                signal: controller.signal,
            });
            if (
                !isCurrent(contextId, epoch, id, sequence) ||
                sequence !== statusRequestSequence.current
            )
                return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (!response.ok) throw new Error(`Falha HTTP ${response.status}.`);
            const value: unknown = await response.json();
            if (
                !isCurrent(contextId, epoch, id, sequence) ||
                sequence !== statusRequestSequence.current
            )
                return;
            if (!isExportJob(value))
                throw new Error('Resposta de estado inválida.');
            applyJob(value, response.headers.get('ETag'));
        } catch (cause) {
            if (
                isCurrent(contextId, epoch, id, sequence) &&
                sequence === statusRequestSequence.current
            ) {
                setError(
                    cause instanceof Error
                        ? cause.message
                        : 'Falha ao consultar a exportação.',
                );
            }
        } finally {
            releaseRequestController(controller);
            if (
                isCurrent(contextId, epoch, id, sequence) &&
                sequence === statusRequestSequence.current
            ) {
                setLoading(false);
                setPollGeneration((current) => current + 1);
            }
        }
    }

    async function download() {
        if (!csrf || !job || !canDownload || requestLock.current) return;
        const contextId = tenantId;
        const epoch = contextEpoch.current;
        const id = job.exportId;
        const controller = createRequestController();
        requestLock.current = true;
        setBusy(true);
        setError('');
        try {
            const tokenResponse = await fetch(
                apiBaseUrl + jobPath(id) + '/download-token',
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    cache: 'no-store',
                    signal: controller.signal,
                    headers: { [csrf.headerName]: csrf.token },
                },
            );
            if (!isCurrent(contextId, epoch, id)) return;
            if (tokenResponse.status === 401 || tokenResponse.status === 403) {
                onSessionInvalid(tokenResponse.status);
                return;
            }
            if (tokenResponse.status === 410) {
                setJob((current) =>
                    current?.exportId === id
                        ? { ...current, state: 'EXPIRED' }
                        : current,
                );
                setError(
                    'O acesso ao arquivo expirou. A expiração do acesso não confirma a remoção física do objeto.',
                );
                return;
            }
            if (!tokenResponse.ok)
                throw new Error(
                    `Não foi possível autorizar o download (HTTP ${tokenResponse.status}).`,
                );
            const tokenBody: unknown = await tokenResponse.json();
            if (!isCurrent(contextId, epoch, id)) return;
            const handle =
                tokenBody && typeof tokenBody === 'object'
                    ? (tokenBody as { downloadHandle?: unknown }).downloadHandle
                    : undefined;
            if (
                typeof handle !== 'string' ||
                handle.length < 32 ||
                handle.length > 128
            ) {
                throw new Error(
                    'O servidor não retornou um handle de download válido.',
                );
            }
            const contentResponse = await fetch(
                apiBaseUrl + jobPath(id) + '/content',
                {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    signal: controller.signal,
                    headers: { 'X-Export-Download-Handle': handle },
                },
            );
            if (!isCurrent(contextId, epoch, id)) return;
            if (
                contentResponse.status === 401 ||
                contentResponse.status === 403
            ) {
                onSessionInvalid(contentResponse.status);
                return;
            }
            if (contentResponse.status === 410) {
                setJob((current) =>
                    current?.exportId === id
                        ? { ...current, state: 'EXPIRED' }
                        : current,
                );
                setError(
                    'O acesso ao arquivo expirou. A expiração do acesso não confirma a remoção física do objeto.',
                );
                return;
            }
            if (!contentResponse.ok)
                throw new Error(
                    `O conteúdo não pôde ser baixado (HTTP ${contentResponse.status}).`,
                );
            const blob = await contentResponse.blob();
            if (!isCurrent(contextId, epoch, id)) return;
            clearDownloadUrl();
            const url = URL.createObjectURL(blob);
            downloadUrl.current = url;
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = safeFilename(contentResponse, job.format);
            anchor.rel = 'noopener';
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            downloadCleanupTimer.current = window.setTimeout(() => {
                downloadCleanupTimer.current = null;
                clearDownloadUrl();
            }, 60_000);
            setNotice(
                'Download iniciado. O arquivo ficou somente na memória do navegador.',
            );
        } catch (cause) {
            if (isCurrent(contextId, epoch, id)) {
                setError(
                    cause instanceof Error
                        ? cause.message
                        : 'Falha ao baixar o arquivo.',
                );
            }
        } finally {
            releaseRequestController(controller);
            if (isCurrent(contextId, epoch, id)) {
                requestLock.current = false;
                setBusy(false);
            }
        }
    }

    async function expireEarly() {
        if (
            !csrf ||
            !job ||
            job.state !== 'AVAILABLE' ||
            expiredByClock ||
            !isStrongJobEtag(jobEtagRef.current) ||
            requestLock.current
        )
            return;
        const contextId = tenantId;
        const epoch = contextEpoch.current;
        const id = job.exportId;
        const etag = jobEtagRef.current;
        const controller = createRequestController();
        requestLock.current = true;
        setBusy(true);
        setError('');
        try {
            const response = await fetch(apiBaseUrl + jobPath(id), {
                method: 'DELETE',
                credentials: 'same-origin',
                cache: 'no-store',
                signal: controller.signal,
                headers: {
                    [csrf.headerName]: csrf.token,
                    'If-Match': etag,
                },
            });
            if (!isCurrent(contextId, epoch, id)) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 204) {
                jobEtagRef.current = null;
                setJobEtag(null);
                setJob((current) =>
                    current?.exportId === id
                        ? { ...current, state: 'EXPIRED' }
                        : current,
                );
                setNotice(
                    'O acesso a esta exportação foi encerrado. Isso não comprova a remoção física do objeto.',
                );
                return;
            }
            if (response.status === 412 || response.status === 409) {
                setNotice(
                    response.status === 412
                        ? 'A versão mudou antes da expiração. Estou recarregando o job; revise o estado antes de confirmar novamente.'
                        : 'O estado atual não permite encerrar o acesso. Estou recarregando o job.',
                );
                await refreshJob();
                return;
            }
            if (!response.ok)
                throw new Error(
                    `Não foi possível expirar o acesso (HTTP ${response.status}).`,
                );
        } catch (cause) {
            if (isCurrent(contextId, epoch, id)) {
                setError(
                    cause instanceof Error
                        ? cause.message
                        : 'Falha ao encerrar o acesso.',
                );
            }
        } finally {
            releaseRequestController(controller);
            if (isCurrent(contextId, epoch, id)) {
                requestLock.current = false;
                setBusy(false);
            }
        }
    }

    return (
        <section
            className="export-center"
            aria-labelledby="export-center-title"
        >
            <header className="export-center__header">
                <div>
                    <p className="export-center__eyebrow">
                        Dados administrativos
                    </p>
                    <h2 id="export-center-title">Exportar agendamentos</h2>
                    <p>
                        Gere um arquivo CSV ou PDF com filtros e acompanhe sua
                        preparação.
                    </p>
                </div>
            </header>
            {!csrf && (
                <output>
                    Entre novamente como administrador para solicitar ou
                    consultar exportações.
                </output>
            )}
            <form
                className="export-center__form"
                onSubmit={(event) => {
                    event.preventDefault();
                    void requestExport();
                }}
            >
                <label>
                    Formato
                    <select
                        value={format}
                        onChange={(event) =>
                            setFormat(event.target.value as ExportFormat)
                        }
                        disabled={busy || !!currentIntent}
                    >
                        <option value="CSV">CSV</option>
                        <option value="PDF">PDF</option>
                    </select>
                </label>
                <label>
                    Categoria
                    <select value="APPOINTMENTS" disabled>
                        <option value="APPOINTMENTS">Agendamentos</option>
                    </select>
                </label>
                <label>
                    Data inicial
                    <input
                        type="date"
                        required
                        value={from}
                        onChange={(event) => setFrom(event.target.value)}
                        disabled={busy || !!currentIntent}
                    />
                </label>
                <label>
                    Data final
                    <input
                        type="date"
                        required
                        value={to}
                        onChange={(event) => setTo(event.target.value)}
                        disabled={busy || !!currentIntent}
                    />
                </label>
                <label className="export-center__service">
                    ID do serviço (opcional)
                    <input
                        value={serviceId}
                        onChange={(event) => setServiceId(event.target.value)}
                        placeholder="UUID do serviço"
                        disabled={busy || !!currentIntent}
                    />
                </label>
                <fieldset
                    className="export-center__statuses"
                    disabled={busy || !!currentIntent}
                >
                    <legend>Status (opcional; sem seleção inclui todos)</legend>
                    {exportStatuses.map((status) => (
                        <label
                            className="export-center__check"
                            key={status.value}
                        >
                            <input
                                type="checkbox"
                                value={status.value}
                                checked={statuses.includes(status.value)}
                                onChange={(event) =>
                                    setStatuses((current) =>
                                        event.target.checked
                                            ? [...current, status.value]
                                            : current.filter(
                                                  (value) =>
                                                      value !== status.value,
                                              ),
                                    )
                                }
                            />
                            {status.label}
                        </label>
                    ))}
                </fieldset>
                <div className="export-center__actions">
                    <button
                        type="submit"
                        disabled={
                            !csrf ||
                            loading ||
                            activeLookupRequired ||
                            busy ||
                            !!currentIntent ||
                            (!!job && !isTerminalExportState(job.state))
                        }
                    >
                        {busy ? 'Enviando…' : 'Solicitar exportação'}
                    </button>
                    {currentIntent && (
                        <button
                            type="button"
                            disabled={!csrf || busy}
                            onClick={() => void requestExport(currentIntent)}
                        >
                            {busy
                                ? 'Recuperando…'
                                : 'Recuperar solicitação pendente'}
                        </button>
                    )}
                </div>
            </form>
            {error && (
                <p
                    className="export-center__message export-center__message--error"
                    role="alert"
                >
                    {error}
                </p>
            )}
            {notice && (
                <output className="export-center__message">{notice}</output>
            )}
            {loading && <output>Carregando estado da exportação…</output>}
            {!job && !currentIntent && csrf && (
                <button
                    type="button"
                    disabled={loading || busy}
                    onClick={() => void discoverActive()}
                >
                    Consultar exportação ativa
                </button>
            )}
            {!loading && !job && !currentIntent && !error && (
                <p className="export-center__empty">
                    Nenhuma exportação nesta sessão. Escolha os filtros para
                    começar.
                </p>
            )}
            {currentIntent && (
                <aside
                    className="export-center__pending"
                    aria-label="Solicitação com resultado incerto"
                >
                    <strong>Resultado ainda não confirmado</strong>
                    <p>
                        {currentIntent.body.format} ·{' '}
                        {currentIntent.body.filters.from} a{' '}
                        {currentIntent.body.filters.to}
                        {currentIntent.body.filters.statuses?.length
                            ? ` · ${currentIntent.body.filters.statuses.length} status selecionado(s)`
                            : ' · todos os status'}
                        {currentIntent.body.filters.serviceId
                            ? ' · serviço filtrado'
                            : ''}
                        .
                    </p>
                    <p>
                        A mesma chave e os mesmos filtros foram preservados
                        nesta sessão do navegador para recuperação segura após
                        recarregar.
                    </p>
                </aside>
            )}
            {job && (
                <article
                    className="export-center__job"
                    aria-labelledby="export-job-title"
                    aria-live="polite"
                >
                    <div className="export-center__job-heading">
                        <div>
                            <p className="export-center__eyebrow">
                                Exportação {job.format}
                            </p>
                            <h3 id="export-job-title">
                                {displayState
                                    ? statusLabel(displayState)
                                    : 'Estado desconhecido'}
                            </h3>
                        </div>
                        <span
                            className={`export-center__badge export-center__badge--${(displayState ?? 'unknown').toLowerCase()}`}
                        >
                            {displayState ?? 'Indisponível'}
                        </span>
                    </div>
                    <dl>
                        <div>
                            <dt>Período</dt>
                            <dd>
                                {job.filters.from} a {job.filters.to}
                            </dd>
                        </div>
                        {job.rowCount != null && (
                            <div>
                                <dt>Registros</dt>
                                <dd>{job.rowCount}</dd>
                            </div>
                        )}
                        {formatSize(job.artifactSize) && (
                            <div>
                                <dt>Tamanho</dt>
                                <dd>{formatSize(job.artifactSize)}</dd>
                            </div>
                        )}
                        {formatDate(job.snapshotAt) && (
                            <div>
                                <dt>Snapshot</dt>
                                <dd>{formatDate(job.snapshotAt)}</dd>
                            </div>
                        )}
                        {job.retryNumber != null && job.retryNumber > 0 && (
                            <div>
                                <dt>Tentativa</dt>
                                <dd>{job.retryNumber} de 3</dd>
                            </div>
                        )}
                        {job.nextAttemptAt && (
                            <div>
                                <dt>Próxima atualização prevista</dt>
                                <dd>{formatDate(job.nextAttemptAt)}</dd>
                            </div>
                        )}
                        {job.expiresAt && (
                            <div>
                                <dt>Disponível até</dt>
                                <dd>{formatDate(job.expiresAt)}</dd>
                            </div>
                        )}
                        {job.failureCode && (
                            <div>
                                <dt>Motivo</dt>
                                <dd>{job.failureCode}</dd>
                            </div>
                        )}
                    </dl>
                    {job.state === 'REQUESTED' ||
                    job.state === 'PROCESSING' ||
                    job.state === 'RETRY_WAIT' ? (
                        <output>
                            A solicitação foi aceita; o arquivo ainda não está
                            pronto.
                        </output>
                    ) : null}
                    {displayState === 'EXPIRED' && (
                        <output>
                            O acesso ao arquivo expirou. Isso não confirma a
                            exclusão física do objeto.
                        </output>
                    )}
                    {job.state === 'AVAILABLE' && !isStrongJobEtag(jobEtag) && (
                        <output>
                            ETag forte da versão do job indisponível. Atualize o
                            estado antes de encerrar o acesso.
                        </output>
                    )}
                    <div className="export-center__actions">
                        <button
                            type="button"
                            disabled={!csrf || loading || busy}
                            onClick={() => void refreshJob()}
                        >
                            {loading ? 'Atualizando…' : 'Atualizar estado'}
                        </button>
                        <button
                            type="button"
                            disabled={!csrf || !canDownload || busy}
                            onClick={() => void download()}
                        >
                            Baixar arquivo
                        </button>
                        <button
                            type="button"
                            className="export-center__secondary"
                            disabled={
                                !csrf ||
                                !canDownload ||
                                !isStrongJobEtag(jobEtag) ||
                                busy
                            }
                            onClick={() => void expireEarly()}
                        >
                            Encerrar acesso agora
                        </button>
                    </div>
                </article>
            )}
        </section>
    );
}
