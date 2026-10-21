import { useEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';

type Client = { clientId: string; displayName: string };
type Fact = {
    historyId: string;
    appointmentId: string;
    recordedAt: string;
    currentStatus: string;
    appointmentVersion: number;
    contributes: boolean;
    correctionHistoryId: string | null;
    correctedAt: string | null;
    correctionReason: string | null;
};
type Facts = {
    clientId: string;
    windowStart: string;
    asOf: string;
    timeZone: string;
    count: number;
    totalFacts: number;
    policyStatus: 'CONFIGURED' | 'NOT_CONFIGURED';
    threshold: number | null;
    policyVersion: number;
    restrictionStatus: 'NOT_CONFIGURED' | 'CLEAR' | 'RESTRICTED';
    activeRestriction: {
        restrictionId: string;
        status: 'RESTRICTED';
        evaluatedAt: string;
        countAtActivation: number;
        thresholdAtActivation: number;
        policyVersion: number;
        version: number;
    } | null;
    restrictionHistory: {
        historyId: string;
        restrictionId: string;
        eventType: 'ACTIVATED' | 'LIFTED' | 'RECONCILED';
        actorType: 'SYSTEM' | 'ADMIN';
        occurredAt: string;
        countAtDecision: number;
        thresholdAtDecision: number;
        policyVersion: number;
        restrictionVersion: number;
        reason: string | null;
    }[];
    facts: Fact[];
    nextCursor: string | null;
    hasMore: boolean;
};
type Csrf = { headerName: string; token: string };
type Pending = {
    appointmentId: string;
    body: { expectedVersion: number; reason: string };
    key: string;
};
type PendingLift = Readonly<{
    clientId: string;
    restrictionId: string;
    body: Readonly<{ expectedVersion: number; reason: string }>;
    key: string;
}>;
type PendingReconciliation = Readonly<{
    clientId: string;
    body: Readonly<{ expectedPolicyVersion: number }>;
    key: string;
}>;

async function responseMessage(response: Response): Promise<string> {
    try {
        const body = (await response.json()) as {
            error?: { message?: string };
        };
        return body.error?.message ?? `Falha HTTP ${response.status}.`;
    } catch {
        return `Falha HTTP ${response.status}.`;
    }
}

export function ClientNoShowFactsPanel({
    enabled,
    csrf,
    onSessionInvalid,
}: {
    enabled: boolean;
    csrf: Csrf | null;
    onSessionInvalid: (status: number) => void;
}) {
    const [query, setQuery] = useState('');
    const [clients, setClients] = useState<Client[]>([]);
    const [selected, setSelected] = useState<Client | null>(null);
    const [facts, setFacts] = useState<Facts | null>(null);
    const [reasons, setReasons] = useState<Record<string, string>>({});
    const [loadingClients, setLoadingClients] = useState(false);
    const [loadingFacts, setLoadingFacts] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [savingId, setSavingId] = useState<string | null>(null);
    const [uncertainId, setUncertainId] = useState<string | null>(null);
    const [locked, setLocked] = useState(false);
    const [liftReason, setLiftReason] = useState('');
    const [liftSaving, setLiftSaving] = useState(false);
    const [liftUncertain, setLiftUncertain] = useState(false);
    const [liftIntentLocked, setLiftIntentLocked] = useState(false);
    const [reconcileSaving, setReconcileSaving] = useState(false);
    const [reconcileUncertain, setReconcileUncertain] = useState(false);
    const pending = useRef<Pending | null>(null);
    const pendingLift = useRef<PendingLift | null>(null);
    const pendingReconciliation = useRef<PendingReconciliation | null>(null);
    const selectedClient = useRef<Client | null>(null);
    const liftRequestInFlight = useRef(false);
    const reconciliationRequestInFlight = useRef(false);
    const keys = useRef(new Map<string, string>());
    const searchGeneration = useRef(0);
    const factsGeneration = useRef(0);

    useEffect(
        () => () => {
            searchGeneration.current += 1;
            factsGeneration.current += 1;
            pending.current = null;
            pendingLift.current = null;
            pendingReconciliation.current = null;
            selectedClient.current = null;
        },
        [],
    );

    async function searchClients() {
        if (
            !enabled ||
            pending.current ||
            pendingLift.current ||
            pendingReconciliation.current ||
            query.trim().length < 2
        )
            return;
        const generation = ++searchGeneration.current;
        setLoadingClients(true);
        setError('');
        try {
            const response = await fetch(
                `${apiBaseUrl}/v1/admin/clients?query=${encodeURIComponent(query.trim())}`,
                { credentials: 'same-origin', cache: 'no-store' },
            );
            if (generation !== searchGeneration.current) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                selectedClient.current = null;
                setSelected(null);
                setFacts(null);
                return;
            }
            if (!response.ok) throw new Error(await responseMessage(response));
            setClients((await response.json()) as Client[]);
        } catch (loadError) {
            if (generation === searchGeneration.current) {
                setError(
                    loadError instanceof Error
                        ? loadError.message
                        : 'Falha na busca.',
                );
            }
        } finally {
            if (generation === searchGeneration.current)
                setLoadingClients(false);
        }
    }

    async function loadFacts(client: Client, cursor?: string | null) {
        if (
            pending.current ||
            pendingLift.current ||
            pendingReconciliation.current
        )
            return;
        const generation = ++factsGeneration.current;
        setLoadingFacts(true);
        setError('');
        try {
            const params = new URLSearchParams({ limit: '50' });
            if (cursor) params.set('cursor', cursor);
            const response = await fetch(
                `${apiBaseUrl}/v1/admin/clients/${encodeURIComponent(client.clientId)}/no-shows?${params}`,
                { credentials: 'same-origin', cache: 'no-store' },
            );
            if (
                generation !== factsGeneration.current ||
                selectedClient.current?.clientId !== client.clientId
            )
                return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                selectedClient.current = null;
                setSelected(null);
                setFacts(null);
                return;
            }
            if (!response.ok) throw new Error(await responseMessage(response));
            const page = (await response.json()) as Facts;
            if (
                generation !== factsGeneration.current ||
                selectedClient.current?.clientId !== client.clientId ||
                page.clientId !== client.clientId
            )
                return;
            setFacts((previous) =>
                cursor && previous?.clientId === client.clientId
                    ? { ...page, facts: [...previous.facts, ...page.facts] }
                    : page,
            );
        } catch (loadError) {
            if (
                generation === factsGeneration.current &&
                selectedClient.current?.clientId === client.clientId
            ) {
                setError(
                    loadError instanceof Error
                        ? loadError.message
                        : 'Falha ao consultar faltas.',
                );
            }
        } finally {
            if (
                generation === factsGeneration.current &&
                selectedClient.current?.clientId === client.clientId
            )
                setLoadingFacts(false);
        }
    }

    function chooseClient(client: Client) {
        if (
            pending.current ||
            pendingLift.current ||
            pendingReconciliation.current
        )
            return;
        searchGeneration.current += 1;
        setLoadingClients(false);
        factsGeneration.current += 1;
        selectedClient.current = client;
        setSelected(client);
        setFacts(null);
        setNotice('');
        setError('');
        void loadFacts(client);
    }

    async function correct(fact: Fact) {
        if (
            !selected ||
            !csrf ||
            pendingLift.current ||
            pendingReconciliation.current
        )
            return;
        const existing = pending.current;
        if (
            existing &&
            (existing.appointmentId !== fact.appointmentId ||
                uncertainId !== fact.appointmentId)
        ) {
            return;
        }
        const body = existing?.body ?? {
            expectedVersion: fact.appointmentVersion,
            reason: reasons[fact.appointmentId]?.trim() ?? '',
        };
        if (!existing && !body.reason) {
            setError('Informe o motivo obrigatório da correção.');
            return;
        }
        const key =
            existing?.key ??
            commandKey(
                keys.current,
                `correct-no-show:${fact.appointmentId}`,
                body,
            );
        const appointmentId = existing?.appointmentId ?? fact.appointmentId;
        pending.current = { appointmentId, body, key };
        setLocked(true);
        setSavingId(appointmentId);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                `${apiBaseUrl}/v1/admin/appointments/${encodeURIComponent(appointmentId)}/correct-no-show`,
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': key,
                    },
                    body: JSON.stringify(body),
                },
            );
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 408 || response.status >= 500) {
                setUncertainId(fact.appointmentId);
                setError(
                    'Resultado incerto. Recupere a mesma correção sem alterar motivo ou versão.',
                );
                return;
            }
            if (!response.ok) {
                const message = await responseMessage(response);
                pending.current = null;
                setLocked(false);
                setUncertainId(null);
                setError(message);
                void loadFacts(selected);
                return;
            }
            const result = (await response.json()) as {
                appointmentId: string;
                status: string;
                version: number;
            };
            if (
                result.appointmentId !== appointmentId ||
                result.status !== 'COMPLETED' ||
                result.version !== body.expectedVersion + 1
            ) {
                setUncertainId(fact.appointmentId);
                setError(
                    'Resposta inválida; mantenha a intenção e tente recuperar novamente.',
                );
                return;
            }
            pending.current = null;
            setLocked(false);
            setUncertainId(null);
            setNotice(
                'Falta corrigida para concluída. Contagem e fatos atualizados; a situação da restrição é consultada separadamente.',
            );
            await loadFacts(selected);
        } catch {
            setUncertainId(fact.appointmentId);
            setError(
                'Resultado incerto. Repita a mesma correção para recuperar o receipt original.',
            );
        } finally {
            setSavingId(null);
        }
    }

    async function liftRestriction() {
        if (liftRequestInFlight.current || !csrf) return;
        const existing = pendingLift.current;
        if (
            locked ||
            pending.current ||
            pendingReconciliation.current ||
            (!existing && (!selected || !facts?.activeRestriction))
        )
            return;
        const active = facts?.activeRestriction;
        const body =
            existing?.body ??
            Object.freeze({
                expectedVersion: active!.version,
                reason: liftReason.trim(),
            });
        if (!existing && !body.reason) {
            setError('Informe o motivo obrigatório do desbloqueio.');
            return;
        }
        const restrictionId = existing?.restrictionId ?? active!.restrictionId;
        const clientId = existing?.clientId ?? selected!.clientId;
        const key =
            existing?.key ??
            commandKey(
                keys.current,
                `lift-client-restriction:${restrictionId}`,
                body,
            );
        const intention =
            existing ?? Object.freeze({ clientId, restrictionId, body, key });
        pendingLift.current = intention;
        liftRequestInFlight.current = true;
        setLiftIntentLocked(true);
        setLiftSaving(true);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                `${apiBaseUrl}/v1/admin/clients/${encodeURIComponent(intention.clientId)}/restrictions/${encodeURIComponent(intention.restrictionId)}/lift`,
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': intention.key,
                    },
                    body: JSON.stringify(intention.body),
                },
            );
            if (response.status === 401 || response.status === 403) {
                pendingLift.current = null;
                setLiftUncertain(false);
                setLiftIntentLocked(false);
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 408 || response.status >= 500) {
                setLiftUncertain(true);
                setError(
                    'Resultado do desbloqueio incerto. Recupere a mesma operação sem alterar motivo ou versão.',
                );
                return;
            }
            if (!response.ok) {
                const message = await responseMessage(response);
                pendingLift.current = null;
                setLiftUncertain(false);
                setLiftIntentLocked(false);
                setError(message);
                if (
                    response.status === 409 &&
                    selectedClient.current?.clientId === intention.clientId
                )
                    void loadFacts(selectedClient.current);
                return;
            }
            const receipt = (await response.json()) as {
                restrictionId: string;
                status: string;
                version: number;
            };
            if (
                receipt.restrictionId !== intention.restrictionId ||
                receipt.status !== 'LIFTED' ||
                receipt.version !== intention.body.expectedVersion + 1
            ) {
                setLiftUncertain(true);
                setError(
                    'Resposta inválida; recupere a mesma operação de desbloqueio.',
                );
                return;
            }
            pendingLift.current = null;
            setLiftUncertain(false);
            setLiftReason('');
            setNotice(
                'Autoagendamento desbloqueado. Os fatos de falta foram preservados; a situação foi atualizada.',
            );
            const originalClient = selectedClient.current;
            if (originalClient?.clientId === intention.clientId)
                await loadFacts(originalClient);
        } catch {
            setLiftUncertain(true);
            setError(
                'Resultado incerto. Repita o mesmo desbloqueio para recuperar a operação original.',
            );
        } finally {
            liftRequestInFlight.current = false;
            setLiftSaving(false);
            if (!pendingLift.current) setLiftIntentLocked(false);
        }
    }

    async function reconcileRestriction() {
        if (reconciliationRequestInFlight.current || !csrf) return;
        const existing = pendingReconciliation.current;
        if (
            locked ||
            pending.current ||
            pendingLift.current ||
            (!existing &&
                (!selected || !facts || facts.policyStatus !== 'CONFIGURED'))
        )
            return;
        const clientId = existing?.clientId ?? selected!.clientId;
        const body =
            existing?.body ??
            Object.freeze({ expectedPolicyVersion: facts!.policyVersion });
        const key =
            existing?.key ??
            commandKey(
                keys.current,
                `reconcile-client-restriction:${clientId}`,
                body,
            );
        const intention = existing ?? Object.freeze({ clientId, body, key });
        pendingReconciliation.current = intention;
        reconciliationRequestInFlight.current = true;
        setReconcileSaving(true);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                `${apiBaseUrl}/v1/admin/clients/${encodeURIComponent(intention.clientId)}/restrictions/reconcile`,
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': intention.key,
                    },
                    body: JSON.stringify(intention.body),
                },
            );
            if (response.status === 401 || response.status === 403) {
                pendingReconciliation.current = null;
                setReconcileUncertain(false);
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 408 || response.status >= 500) {
                setReconcileUncertain(true);
                setError(
                    'Resultado incerto. Recupere a mesma reconciliação sem alterar cliente ou versão da política.',
                );
                return;
            }
            if (!response.ok) {
                const message = await responseMessage(response);
                pendingReconciliation.current = null;
                setReconcileUncertain(false);
                setError(message);
                if (
                    response.status === 409 &&
                    selectedClient.current?.clientId === intention.clientId
                )
                    void loadFacts(selectedClient.current);
                return;
            }
            const receipt = (await response.json()) as {
                clientId: string;
                decision: string;
                policyVersion: number;
                count: number;
            };
            if (
                receipt.clientId !== intention.clientId ||
                receipt.policyVersion !==
                    intention.body.expectedPolicyVersion ||
                ![
                    'ACTIVATED',
                    'ALREADY_RESTRICTED',
                    'BELOW_THRESHOLD',
                    'NO_UNUSED_CAUSAL_FACT',
                ].includes(receipt.decision) ||
                !Number.isInteger(receipt.count) ||
                receipt.count < 0
            ) {
                setReconcileUncertain(true);
                setError(
                    'Resposta inválida; recupere a mesma reconciliação para conferir o resultado original.',
                );
                return;
            }
            pendingReconciliation.current = null;
            setReconcileUncertain(false);
            setNotice(
                `Reconciliação da política v${receipt.policyVersion}: ${receipt.decision}; ${receipt.count} falta(s) na janela.`,
            );
            const originalClient = selectedClient.current;
            if (originalClient?.clientId === intention.clientId)
                await loadFacts(originalClient);
        } catch {
            setReconcileUncertain(true);
            setError(
                'Resultado incerto. Repita a mesma reconciliação para recuperar o receipt original.',
            );
        } finally {
            reconciliationRequestInFlight.current = false;
            setReconcileSaving(false);
        }
    }

    const interactionsLocked =
        locked ||
        liftSaving ||
        liftUncertain ||
        liftIntentLocked ||
        reconcileSaving ||
        reconcileUncertain;

    if (!enabled) return null;
    return (
        <section
            className="admin-agenda"
            aria-labelledby="client-no-shows-title"
        >
            <h2 id="client-no-shows-title">Faltas de clientes</h2>
            <form
                onSubmit={(event) => {
                    event.preventDefault();
                    void searchClients();
                }}
            >
                <label htmlFor="no-show-client-search">
                    Buscar cliente por nome
                </label>
                <input
                    id="no-show-client-search"
                    value={query}
                    disabled={interactionsLocked}
                    onChange={(event) => setQuery(event.target.value)}
                />
                <button
                    type="submit"
                    disabled={
                        loadingClients ||
                        interactionsLocked ||
                        query.trim().length < 2
                    }
                >
                    {loadingClients ? 'Buscando…' : 'Buscar'}
                </button>
            </form>
            {clients.length > 0 && (
                <ul aria-label="Resultados de clientes">
                    {clients.map((client) => (
                        <li key={client.clientId}>
                            <button
                                type="button"
                                disabled={interactionsLocked}
                                onClick={() => chooseClient(client)}
                            >
                                {client.displayName}
                            </button>
                        </li>
                    ))}
                </ul>
            )}
            {selected && (
                <h3
                    data-testid="selected-no-show-client"
                    data-client-id={selected.clientId}
                >
                    Fatos de {selected.displayName}
                </h3>
            )}
            {loadingFacts && (
                <output aria-live="polite">Consultando fatos…</output>
            )}
            {facts && (
                <div
                    aria-live="polite"
                    data-testid="client-no-show-facts"
                    data-client-id={facts.clientId}
                >
                    <p>
                        Janela:{' '}
                        {new Date(facts.windowStart).toLocaleString('pt-BR', {
                            timeZone: facts.timeZone,
                        })}{' '}
                        até{' '}
                        {new Date(facts.asOf).toLocaleString('pt-BR', {
                            timeZone: facts.timeZone,
                        })}{' '}
                        (fim exclusivo). Fuso: {facts.timeZone}.
                    </p>
                    <p>
                        Faltas que contam: {facts.count}. Política:{' '}
                        {facts.policyStatus === 'CONFIGURED'
                            ? `${facts.threshold} (v${facts.policyVersion})`
                            : 'NOT_CONFIGURED, sem limite padrão'}
                        . Situação do autoagendamento: {facts.restrictionStatus}
                        .
                    </p>
                    {facts.activeRestriction && (
                        <section aria-label="Restrição ativa de autoagendamento">
                            <p>
                                Restrição ativa desde{' '}
                                {new Date(
                                    facts.activeRestriction.evaluatedAt,
                                ).toLocaleString('pt-BR', {
                                    timeZone: facts.timeZone,
                                })}
                                . Decisão:{' '}
                                {facts.activeRestriction.countAtActivation} de{' '}
                                {facts.activeRestriction.thresholdAtActivation}{' '}
                                faltas, política v
                                {facts.activeRestriction.policyVersion},
                                restrição v{facts.activeRestriction.version}.
                            </p>
                            <label htmlFor="restriction-lift-reason">
                                Motivo obrigatório para desbloquear
                            </label>
                            <textarea
                                id="restriction-lift-reason"
                                maxLength={500}
                                value={liftReason}
                                disabled={interactionsLocked}
                                onChange={(event) =>
                                    setLiftReason(event.target.value)
                                }
                            />
                            <button
                                type="button"
                                disabled={
                                    liftUncertain
                                        ? liftSaving || locked
                                        : interactionsLocked ||
                                          !liftReason.trim()
                                }
                                onClick={() => void liftRestriction()}
                            >
                                {liftSaving
                                    ? 'Desbloqueando…'
                                    : liftUncertain
                                      ? 'Recuperar mesmo desbloqueio'
                                      : 'Desbloquear autoagendamento'}
                            </button>
                        </section>
                    )}
                    {facts.policyStatus === 'CONFIGURED' && (
                        <button
                            type="button"
                            disabled={
                                reconcileUncertain
                                    ? reconcileSaving
                                    : interactionsLocked
                            }
                            onClick={() => void reconcileRestriction()}
                        >
                            {reconcileSaving
                                ? 'Reconciliando…'
                                : reconcileUncertain
                                  ? 'Recuperar mesma reconciliação'
                                  : 'Reavaliar política para este cliente'}
                        </button>
                    )}
                    {facts.restrictionHistory.length > 0 && (
                        <ul aria-label="Histórico da restrição">
                            {facts.restrictionHistory.map((item) => (
                                <li key={item.historyId}>
                                    {item.eventType === 'ACTIVATED'
                                        ? 'Bloqueio ativado'
                                        : item.eventType === 'LIFTED'
                                          ? 'Bloqueio removido'
                                          : 'Bloqueio ativado por reconciliação'}{' '}
                                    em{' '}
                                    {new Date(item.occurredAt).toLocaleString(
                                        'pt-BR',
                                        { timeZone: facts.timeZone },
                                    )}
                                    {' — '}contagem {item.countAtDecision},
                                    limite {item.thresholdAtDecision}, política
                                    v{item.policyVersion}, versão v
                                    {item.restrictionVersion}
                                    {item.reason
                                        ? ` — motivo: ${item.reason}`
                                        : ''}
                                    .
                                </li>
                            ))}
                        </ul>
                    )}
                    {facts.facts.length === 0 ? (
                        <p>Nenhum fato de falta na janela.</p>
                    ) : (
                        <ul aria-label="Fatos de falta">
                            {facts.facts.map((fact) => (
                                <li
                                    key={fact.historyId}
                                    data-testid={`no-show-${fact.appointmentId}`}
                                >
                                    <p>
                                        {new Date(
                                            fact.recordedAt,
                                        ).toLocaleString('pt-BR', {
                                            timeZone: facts.timeZone,
                                        })}{' '}
                                        —{' '}
                                        {fact.contributes
                                            ? 'conta na janela'
                                            : 'corrigida; não conta'}
                                        ; reserva {fact.currentStatus}, versão{' '}
                                        {fact.appointmentVersion}.
                                    </p>
                                    {fact.correctedAt && (
                                        <p>
                                            Correção registrada em{' '}
                                            {new Date(
                                                fact.correctedAt,
                                            ).toLocaleString('pt-BR', {
                                                timeZone: facts.timeZone,
                                            })}
                                            : {fact.correctionReason}
                                        </p>
                                    )}
                                    {fact.currentStatus === 'NO_SHOW' &&
                                        fact.contributes && (
                                            <>
                                                <label
                                                    htmlFor={`no-show-reason-${fact.appointmentId}`}
                                                >
                                                    Motivo obrigatório para
                                                    corrigir
                                                </label>
                                                <textarea
                                                    id={`no-show-reason-${fact.appointmentId}`}
                                                    maxLength={500}
                                                    value={
                                                        reasons[
                                                            fact.appointmentId
                                                        ] ?? ''
                                                    }
                                                    disabled={
                                                        interactionsLocked
                                                    }
                                                    onChange={(event) =>
                                                        setReasons(
                                                            (current) => ({
                                                                ...current,
                                                                [fact.appointmentId]:
                                                                    event.target
                                                                        .value,
                                                            }),
                                                        )
                                                    }
                                                />
                                                <button
                                                    type="button"
                                                    disabled={
                                                        savingId !== null ||
                                                        (uncertainId !==
                                                            fact.appointmentId &&
                                                            interactionsLocked)
                                                    }
                                                    onClick={() =>
                                                        void correct(fact)
                                                    }
                                                >
                                                    {savingId ===
                                                    fact.appointmentId
                                                        ? 'Salvando…'
                                                        : uncertainId ===
                                                            fact.appointmentId
                                                          ? 'Recuperar mesma correção'
                                                          : 'Corrigir para concluída'}
                                                </button>
                                            </>
                                        )}
                                </li>
                            ))}
                        </ul>
                    )}
                    {facts.hasMore && facts.nextCursor && (
                        <button
                            type="button"
                            disabled={loadingFacts || interactionsLocked}
                            onClick={() =>
                                void loadFacts(selected!, facts.nextCursor)
                            }
                        >
                            Carregar mais fatos
                        </button>
                    )}
                </div>
            )}
            {error && <p role="alert">{error}</p>}
            {notice && <output aria-live="polite">{notice}</output>}
        </section>
    );
}
