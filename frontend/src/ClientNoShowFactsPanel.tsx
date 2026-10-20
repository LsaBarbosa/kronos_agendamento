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
    restrictionStatus: 'NOT_TRACKED';
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
    const pending = useRef<Pending | null>(null);
    const keys = useRef(new Map<string, string>());
    const searchGeneration = useRef(0);
    const factsGeneration = useRef(0);

    useEffect(
        () => () => {
            searchGeneration.current += 1;
            factsGeneration.current += 1;
            pending.current = null;
        },
        [],
    );

    async function searchClients() {
        if (!enabled || query.trim().length < 2) return;
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
        if (pending.current) return;
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
            if (generation !== factsGeneration.current) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                setSelected(null);
                setFacts(null);
                return;
            }
            if (!response.ok) throw new Error(await responseMessage(response));
            const page = (await response.json()) as Facts;
            if (
                generation !== factsGeneration.current ||
                page.clientId !== client.clientId
            )
                return;
            setFacts((previous) =>
                cursor && previous?.clientId === client.clientId
                    ? { ...page, facts: [...previous.facts, ...page.facts] }
                    : page,
            );
        } catch (loadError) {
            if (generation === factsGeneration.current) {
                setError(
                    loadError instanceof Error
                        ? loadError.message
                        : 'Falha ao consultar faltas.',
                );
            }
        } finally {
            if (generation === factsGeneration.current) setLoadingFacts(false);
        }
    }

    function chooseClient(client: Client) {
        if (pending.current) return;
        factsGeneration.current += 1;
        setSelected(client);
        setFacts(null);
        setNotice('');
        setError('');
        void loadFacts(client);
    }

    async function correct(fact: Fact) {
        if (!selected || !csrf) return;
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
                'Falta corrigida para concluída. Contagem e fatos atualizados; restrições permanecem NOT_TRACKED até AGD-033.',
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
                    disabled={locked}
                    onChange={(event) => setQuery(event.target.value)}
                />
                <button
                    type="submit"
                    disabled={
                        loadingClients || locked || query.trim().length < 2
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
                                disabled={locked}
                                onClick={() => chooseClient(client)}
                            >
                                {client.displayName}
                            </button>
                        </li>
                    ))}
                </ul>
            )}
            {selected && <h3>Fatos de {selected.displayName}</h3>}
            {loadingFacts && (
                <output aria-live="polite">Consultando fatos…</output>
            )}
            {facts && (
                <div aria-live="polite">
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
                        . Restrição automática: {facts.restrictionStatus}.
                    </p>
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
                                                    disabled={locked}
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
                                                        (locked &&
                                                            uncertainId !==
                                                                fact.appointmentId)
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
                            disabled={loadingFacts || locked}
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
