import { useCallback, useEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';

type ClientAppointment = {
    appointmentId: string;
    serviceName: string;
    startsAt: string;
    serviceEndsAt: string;
    timeZone: string;
    status: string;
    version: number;
};

type AppointmentPage = {
    items: ClientAppointment[];
    nextCursor: string | null;
    hasMore: boolean;
};

type CancelIntent = {
    appointmentId: string;
    body: { expectedVersion: number };
    key: string;
};

function formatAppointmentTime(value: string, timeZone: string): string {
    return new Intl.DateTimeFormat('pt-BR', {
        dateStyle: 'full',
        timeStyle: 'short',
        timeZone,
    }).format(new Date(value));
}

async function apiError(response: Response): Promise<string> {
    const body = (await response.json().catch(() => null)) as {
        error?: { code?: string; message?: string };
    } | null;
    return (
        body?.error?.code ?? body?.error?.message ?? `HTTP_${response.status}`
    );
}

export function ClientAppointmentsArea({
    onLogout,
    onSessionEnded,
}: {
    onLogout: () => Promise<void>;
    onSessionEnded: () => void;
}) {
    const [items, setItems] = useState<ClientAppointment[]>([]);
    const [selected, setSelected] = useState<ClientAppointment | null>(null);
    const [filter, setFilter] = useState<'future' | 'all'>('future');
    const [cursor, setCursor] = useState<string | null>(null);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [confirming, setConfirming] = useState<ClientAppointment | null>(
        null,
    );
    const [cancelIntent, setCancelIntent] = useState<CancelIntent | null>(null);
    const [cancelSaving, setCancelSaving] = useState(false);
    const [cancelUncertain, setCancelUncertain] = useState(false);
    const cancelGuard = useRef(false);
    const listRequestSequence = useRef(0);
    const detailRequestSequence = useRef(0);
    const refreshSequence = useRef(0);
    const loadMoreSequence = useRef(0);
    const active = useRef(true);
    const sessionValid = useRef(true);

    const endClientSession = useCallback(() => {
        sessionValid.current = false;
        listRequestSequence.current++;
        detailRequestSequence.current++;
        refreshSequence.current++;
        loadMoreSequence.current++;
        setItems([]);
        setSelected(null);
        setLoading(false);
        setLoadingMore(false);
        setCancelIntent(null);
        setCancelUncertain(false);
        onSessionEnded();
    }, [onSessionEnded]);

    const loadAppointments = useCallback(
        async (append: boolean, nextCursor?: string | null) => {
            const sequence = ++listRequestSequence.current;
            const operationSequence = append
                ? ++loadMoreSequence.current
                : ++refreshSequence.current;
            if (append) setLoadingMore(true);
            else {
                setLoading(true);
                setError('');
            }
            const query = new URLSearchParams();
            if (filter === 'future') query.set('status', 'future');
            const effectiveCursor = append ? nextCursor : null;
            if (effectiveCursor) query.set('cursor', effectiveCursor);
            try {
                const response = await fetch(
                    `${apiBaseUrl}/v1/client/appointments?${query.toString()}`,
                    { credentials: 'same-origin', cache: 'no-store' },
                );
                if (
                    !active.current ||
                    !sessionValid.current ||
                    sequence !== listRequestSequence.current
                )
                    return;
                if (response.status === 401 || response.status === 403) {
                    endClientSession();
                    return;
                }
                if (!response.ok) {
                    setError(
                        'Não foi possível carregar suas reservas. Tente novamente.',
                    );
                    return;
                }
                const page = (await response.json()) as AppointmentPage;
                if (
                    !active.current ||
                    !sessionValid.current ||
                    sequence !== listRequestSequence.current
                )
                    return;
                if (
                    !Array.isArray(page.items) ||
                    typeof page.hasMore !== 'boolean' ||
                    !(
                        page.nextCursor === null ||
                        typeof page.nextCursor === 'string'
                    )
                ) {
                    setError(
                        'A resposta da agenda é inválida. Tente novamente.',
                    );
                    return;
                }
                setItems((current) =>
                    append ? [...current, ...page.items] : page.items,
                );
                setCursor(page.nextCursor);
                setHasMore(page.hasMore);
            } catch {
                if (
                    active.current &&
                    sessionValid.current &&
                    sequence === listRequestSequence.current
                )
                    setError(
                        'Serviço indisponível. Suas reservas não foram alteradas.',
                    );
            } finally {
                if (active.current && sessionValid.current) {
                    if (
                        append &&
                        operationSequence === loadMoreSequence.current
                    )
                        setLoadingMore(false);
                    if (
                        !append &&
                        operationSequence === refreshSequence.current
                    )
                        setLoading(false);
                }
            }
        },
        [endClientSession, filter],
    );

    useEffect(() => {
        const activeRequest = active;
        activeRequest.current = true;
        void Promise.resolve().then(() => {
            if (activeRequest.current) return loadAppointments(false);
            return undefined;
        });
        return () => {
            activeRequest.current = false;
        };
    }, [loadAppointments]);

    async function openDetails(appointmentId: string) {
        setError('');
        setNotice('');
        if (!sessionValid.current) return;
        const sequence = ++detailRequestSequence.current;
        try {
            const response = await fetch(
                `${apiBaseUrl}/v1/client/appointments/${encodeURIComponent(appointmentId)}`,
                { credentials: 'same-origin', cache: 'no-store' },
            );
            if (
                !active.current ||
                !sessionValid.current ||
                sequence !== detailRequestSequence.current
            )
                return;
            if (response.status === 401 || response.status === 403) {
                endClientSession();
                return;
            }
            if (response.status === 404) {
                setError('Reserva não encontrada. Atualize a lista.');
                return;
            }
            if (!response.ok) throw new Error('details');
            const detail = (await response.json()) as ClientAppointment;
            if (
                !active.current ||
                !sessionValid.current ||
                sequence !== detailRequestSequence.current
            )
                return;
            setSelected(detail);
        } catch {
            if (
                active.current &&
                sessionValid.current &&
                sequence === detailRequestSequence.current
            )
                setError(
                    'Não foi possível carregar os detalhes. Tente novamente.',
                );
        }
    }

    function requestCancellation(appointment: ClientAppointment) {
        if (
            cancelGuard.current ||
            cancelIntent ||
            cancelUncertain ||
            cancelSaving
        )
            return;
        setConfirming({ ...appointment });
        setError('');
        setNotice('');
    }

    async function confirmCancellation() {
        if (cancelGuard.current || !confirming) return;
        cancelGuard.current = true;
        const intent: CancelIntent = {
            appointmentId: confirming.appointmentId,
            body: { expectedVersion: confirming.version },
            key: crypto.randomUUID(),
        };
        setCancelIntent(intent);
        setConfirming(null);
        await sendCancellation(intent);
    }

    async function retryCancellation() {
        if (cancelGuard.current || !cancelIntent || !cancelUncertain) return;
        cancelGuard.current = true;
        await sendCancellation(cancelIntent);
    }

    async function sendCancellation(intent: CancelIntent) {
        setCancelSaving(true);
        setCancelUncertain(false);
        setError('');
        setNotice('Cancelando reserva…');
        try {
            const csrfResponse = await fetch(apiBaseUrl + '/v1/csrf', {
                credentials: 'same-origin',
                cache: 'no-store',
            });
            if (!csrfResponse.ok) throw new Error('uncertain');
            const csrf = (await csrfResponse.json()) as {
                headerName?: unknown;
                token?: unknown;
            };
            if (
                typeof csrf.headerName !== 'string' ||
                typeof csrf.token !== 'string'
            )
                throw new Error('uncertain');
            const response = await fetch(
                `${apiBaseUrl}/v1/client/appointments/${encodeURIComponent(intent.appointmentId)}/cancel`,
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': intent.key,
                    },
                    body: JSON.stringify(intent.body),
                },
            );
            if (response.status === 401 || response.status === 403) {
                endClientSession();
                setCancelIntent(null);
                return;
            }
            if (!response.ok) {
                const code = await apiError(response);
                if (
                    code === 'OPERATION_IN_PROGRESS' ||
                    response.status >= 500
                ) {
                    setCancelUncertain(true);
                    setNotice(
                        'O resultado ainda é incerto. Repita a mesma tentativa; não inicie outro cancelamento.',
                    );
                    return;
                }
                setCancelIntent(null);
                setCancelUncertain(false);
                setError(
                    code === 'CANCELLATION_WINDOW_CLOSED'
                        ? 'O prazo para cancelar terminou. Entre em contato com o estabelecimento.'
                        : code === 'VERSION_CONFLICT'
                          ? 'A reserva mudou. Atualize a agenda antes de decidir novamente.'
                          : code === 'APPOINTMENT_NOT_CANCELLABLE'
                            ? 'Esta reserva não está mais confirmada.'
                            : 'Não foi possível cancelar a reserva.',
                );
                return;
            }
            const receipt = (await response.json().catch(() => null)) as {
                appointmentId?: unknown;
                status?: unknown;
                version?: unknown;
            } | null;
            if (!active.current || !sessionValid.current) return;
            if (
                !receipt ||
                receipt.appointmentId !== intent.appointmentId ||
                receipt.status !== 'CANCELLED_BY_CLIENT' ||
                typeof receipt.version !== 'number'
            ) {
                setCancelUncertain(true);
                setNotice(
                    'A resposta não pôde ser confirmada. Repita a mesma tentativa.',
                );
                return;
            }
            setItems((current) =>
                current.map((item) =>
                    item.appointmentId === intent.appointmentId
                        ? {
                              ...item,
                              status: 'CANCELLED_BY_CLIENT',
                              version: receipt.version as number,
                          }
                        : item,
                ),
            );
            if (selected?.appointmentId === intent.appointmentId) {
                setSelected({
                    ...selected,
                    status: 'CANCELLED_BY_CLIENT',
                    version: receipt.version,
                });
            }
            setCancelIntent(null);
            setCancelUncertain(false);
            setNotice('Reserva cancelada.');
            await loadAppointments(false);
        } catch {
            setCancelUncertain(true);
            setNotice(
                'Não foi possível confirmar o resultado. Repita a mesma tentativa; ela usará a mesma chave.',
            );
        } finally {
            if (active.current && sessionValid.current) setCancelSaving(false);
            cancelGuard.current = false;
        }
    }

    return (
        <section
            className="client-appointments"
            aria-labelledby="client-appointments-title"
        >
            <div className="client-appointments__heading">
                <div>
                    <p className="client-access__eyebrow">Área do cliente</p>
                    <h2 id="client-appointments-title">Suas reservas</h2>
                </div>
                <button
                    type="button"
                    onClick={() => void onLogout()}
                    disabled={
                        cancelSaving || Boolean(cancelIntent) || cancelUncertain
                    }
                >
                    Sair
                </button>
            </div>
            <label htmlFor="client-appointment-filter">Exibir</label>
            <select
                id="client-appointment-filter"
                value={filter}
                disabled={
                    Boolean(cancelIntent) || cancelSaving || cancelUncertain
                }
                onChange={(event) => {
                    setSelected(null);
                    setItems([]);
                    setCursor(null);
                    setFilter(event.target.value as 'future' | 'all');
                }}
            >
                <option value="future">Próximas</option>
                <option value="all">Todas</option>
            </select>
            <button
                type="button"
                disabled={
                    loading ||
                    Boolean(cancelIntent) ||
                    cancelSaving ||
                    cancelUncertain
                }
                onClick={() => void loadAppointments(false)}
            >
                Atualizar reservas
            </button>
            {notice && <output aria-live="polite">{notice}</output>}
            {error && <p role="alert">{error}</p>}
            {cancelUncertain && cancelIntent && (
                <div role="alert">
                    <p>
                        O cancelamento da reserva selecionada ainda pode ter
                        sido confirmado.
                    </p>
                    <button
                        type="button"
                        disabled={cancelSaving}
                        onClick={() => void retryCancellation()}
                    >
                        {cancelSaving
                            ? 'Verificando…'
                            : 'Repetir a mesma tentativa'}
                    </button>
                </div>
            )}
            {loading && items.length === 0 ? (
                <output>Carregando reservas…</output>
            ) : items.length === 0 ? (
                <p>Nenhuma reserva encontrada.</p>
            ) : (
                <ul className="client-appointments__list">
                    {items.map((appointment) => (
                        <li
                            key={appointment.appointmentId}
                            data-appointment-id={appointment.appointmentId}
                        >
                            <article>
                                <h3>{appointment.serviceName}</h3>
                                <p>
                                    {formatAppointmentTime(
                                        appointment.startsAt,
                                        appointment.timeZone,
                                    )}
                                </p>
                                <p>Status: {appointment.status}</p>
                                <button
                                    type="button"
                                    disabled={
                                        Boolean(cancelIntent) ||
                                        cancelSaving ||
                                        cancelUncertain
                                    }
                                    onClick={() =>
                                        void openDetails(
                                            appointment.appointmentId,
                                        )
                                    }
                                >
                                    Ver detalhes
                                </button>
                                {appointment.status === 'CONFIRMED' && (
                                    <button
                                        type="button"
                                        disabled={
                                            Boolean(cancelIntent) ||
                                            cancelSaving ||
                                            cancelUncertain
                                        }
                                        onClick={() =>
                                            requestCancellation(appointment)
                                        }
                                    >
                                        Cancelar
                                    </button>
                                )}
                            </article>
                        </li>
                    ))}
                </ul>
            )}
            {loading && items.length > 0 && (
                <output aria-live="polite">Atualizando reservas…</output>
            )}
            {hasMore && cursor && !loading && (
                <button
                    type="button"
                    disabled={
                        loadingMore ||
                        Boolean(cancelIntent) ||
                        cancelSaving ||
                        cancelUncertain
                    }
                    onClick={() => void loadAppointments(true, cursor)}
                >
                    {loadingMore ? 'Carregando…' : 'Carregar mais'}
                </button>
            )}
            {selected && (
                <section aria-labelledby="client-appointment-detail-title">
                    <h3 id="client-appointment-detail-title">
                        Detalhes da reserva
                    </h3>
                    <p>{selected.serviceName}</p>
                    <p>
                        Início:{' '}
                        {formatAppointmentTime(
                            selected.startsAt,
                            selected.timeZone,
                        )}
                    </p>
                    <p>
                        Fim do atendimento:{' '}
                        {formatAppointmentTime(
                            selected.serviceEndsAt,
                            selected.timeZone,
                        )}
                    </p>
                    <p>Fuso horário: {selected.timeZone}</p>
                    <p>Status: {selected.status}</p>
                    <p>Versão: {selected.version}</p>
                    {selected.status === 'CONFIRMED' && (
                        <button
                            type="button"
                            disabled={
                                Boolean(cancelIntent) ||
                                cancelSaving ||
                                cancelUncertain
                            }
                            onClick={() => requestCancellation(selected)}
                        >
                            Cancelar esta reserva
                        </button>
                    )}
                    <button
                        type="button"
                        disabled={
                            Boolean(cancelIntent) ||
                            cancelSaving ||
                            cancelUncertain
                        }
                        onClick={() => setSelected(null)}
                    >
                        Fechar detalhes
                    </button>
                </section>
            )}
            {confirming && (
                <dialog open aria-labelledby="client-cancel-confirm-title">
                    <h3 id="client-cancel-confirm-title">
                        Confirmar cancelamento?
                    </h3>
                    <p>
                        Esta ação cancela somente a reserva selecionada ({' '}
                        {confirming.serviceName}, versão {confirming.version}).
                    </p>
                    <button
                        type="button"
                        onClick={() => void confirmCancellation()}
                    >
                        Confirmar cancelamento
                    </button>
                    <button type="button" onClick={() => setConfirming(null)}>
                        Voltar
                    </button>
                </dialog>
            )}
        </section>
    );
}
