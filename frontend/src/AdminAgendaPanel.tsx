import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { apiBaseUrl } from './config';

type Appointment = {
    appointmentId: string;
    serviceId: string;
    clientName: string;
    serviceName: string;
    startsAt: string;
    serviceEndsAt: string;
    occupiedUntil: string;
    timeZone: string;
    durationMinutes: number;
    bufferAfterMinutes: number;
    status: string;
    version: number;
};
type AppointmentPage = {
    timeZone: string;
    items: Appointment[];
    nextCursor: string | null;
    hasMore: boolean;
};
type Block = {
    blockId: string;
    startsAt: string;
    endsAt: string;
    timeZone: string;
    reason: string;
    status: 'ACTIVE' | 'CANCELLED';
};
type Service = {
    serviceId: string;
    name: string;
    status: 'ACTIVE' | 'INACTIVE';
};
type HistoryEntry = {
    fromStatus: string | null;
    toStatus: string;
    actorType: string;
    actorRef: string;
    reasonCode: string;
    occurredAt: string;
};
type Details = Appointment & { history: HistoryEntry[] };

function addDateDays(date: string, days: number) {
    const value = new Date(`${date}T12:00:00Z`);
    value.setUTCDate(value.getUTCDate() + days);
    return value.toISOString().slice(0, 10);
}

function weekStart(date: string) {
    const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
    return addDateDays(date, -((weekday + 6) % 7));
}

function datesBetween(from: string, to: string) {
    const dates: string[] = [];
    for (let date = from; date <= to; date = addDateDays(date, 1)) {
        dates.push(date);
    }
    return dates;
}

function dateInZone(instant: string, timeZone: string) {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).formatToParts(new Date(instant));
    const values = Object.fromEntries(
        parts.map((part) => [part.type, part.value]),
    );
    return `${values.year}-${values.month}-${values.day}`;
}

function dateLabel(date: string) {
    return new Intl.DateTimeFormat('pt-BR', {
        weekday: 'long',
        day: '2-digit',
        month: 'long',
        year: 'numeric',
        timeZone: 'UTC',
    }).format(new Date(`${date}T12:00:00Z`));
}

function formatInstant(instant: string, timeZone: string, withDate = false) {
    return new Intl.DateTimeFormat('pt-BR', {
        ...(withDate
            ? { day: '2-digit', month: '2-digit', year: 'numeric' }
            : {}),
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
        timeZone,
        timeZoneName: 'short',
    }).format(new Date(instant));
}

async function errorMessage(response: Response) {
    try {
        const body = (await response.json()) as {
            error?: { message?: string };
        };
        return body.error?.message ?? `Falha HTTP ${response.status}.`;
    } catch {
        return `Falha HTTP ${response.status}.`;
    }
}

async function readJson<T>(response: Response): Promise<T> {
    if (!response.ok) throw new Error(await errorMessage(response));
    return (await response.json()) as T;
}

export function AdminAgendaPanel({
    enabled,
    onSessionInvalid,
}: {
    enabled: boolean;
    onSessionInvalid: (status: number) => void;
}) {
    const [view, setView] = useState<'day' | 'week'>('day');
    const [selectedDate, setSelectedDate] = useState('');
    const [timeZone, setTimeZone] = useState('');
    const [appointments, setAppointments] = useState<Appointment[]>([]);
    const [blocks, setBlocks] = useState<Block[]>([]);
    const [services, setServices] = useState<Service[]>([]);
    const [serviceFilter, setServiceFilter] = useState('');
    const [statusFilter, setStatusFilter] = useState('');
    const [cursorStack, setCursorStack] = useState<Array<string | null>>([
        null,
    ]);
    const [pageIndex, setPageIndex] = useState(0);
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [details, setDetails] = useState<Details | null>(null);
    const [selectedAppointmentId, setSelectedAppointmentId] = useState('');
    const [detailsLoading, setDetailsLoading] = useState(false);
    const [detailsError, setDetailsError] = useState('');
    const sequence = useRef(0);
    const detailSequence = useRef(0);
    const sessionInvalidRef = useRef(onSessionInvalid);
    const [nowInstant] = useState(() => new Date().toISOString());
    const today = timeZone
        ? dateInZone(nowInstant, timeZone)
        : nowInstant.slice(0, 10);
    const anchorDate = selectedDate || today;
    const from = view === 'day' ? anchorDate : weekStart(anchorDate);
    const to = view === 'day' ? anchorDate : addDateDays(from, 6);
    const visibleDates = useMemo(() => datesBetween(from, to), [from, to]);
    const currentCursor = cursorStack[pageIndex] ?? null;

    useEffect(() => {
        sessionInvalidRef.current = onSessionInvalid;
    }, [onSessionInvalid]);

    const resetPagination = useCallback(() => {
        setCursorStack([null]);
        setPageIndex(0);
    }, []);

    useEffect(() => {
        if (!enabled) return;
        void fetch(apiBaseUrl + '/v1/admin/services', {
            credentials: 'same-origin',
            cache: 'no-store',
        })
            .then(async (response) => {
                if (response.status === 401 || response.status === 403) {
                    sessionInvalidRef.current(response.status);
                    return;
                }
                const result = await readJson<Service[]>(response);
                setServices(result);
            })
            .catch(() =>
                setError(
                    'Não foi possível carregar os serviços para o filtro.',
                ),
            );
    }, [enabled]);

    const load = useCallback(async () => {
        if (!enabled) return;
        const requestSequence = ++sequence.current;
        setLoading(true);
        setError('');
        const query = new URLSearchParams({ from, to, limit: '50' });
        if (statusFilter) query.set('status', statusFilter);
        if (serviceFilter) query.set('serviceId', serviceFilter);
        if (currentCursor) query.set('cursor', currentCursor);
        try {
            const [appointmentResponse, blockResponse] = await Promise.all([
                fetch(apiBaseUrl + '/v1/admin/appointments?' + query, {
                    credentials: 'same-origin',
                    cache: 'no-store',
                }),
                fetch(
                    apiBaseUrl +
                        '/v1/admin/blocked-periods?' +
                        new URLSearchParams({ from, to }),
                    {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    },
                ),
            ]);
            if (
                appointmentResponse.status === 401 ||
                appointmentResponse.status === 403
            ) {
                sessionInvalidRef.current(appointmentResponse.status);
                return;
            }
            if (blockResponse.status === 401 || blockResponse.status === 403) {
                sessionInvalidRef.current(blockResponse.status);
                return;
            }
            const [page, loadedBlocks] = await Promise.all([
                readJson<AppointmentPage>(appointmentResponse),
                readJson<Block[]>(blockResponse),
            ]);
            if (requestSequence !== sequence.current) return;
            setTimeZone(page.timeZone);
            setAppointments(page.items);
            setBlocks(loadedBlocks);
            setNextCursor(page.nextCursor);
            setHasMore(page.hasMore);
        } catch (cause) {
            if (requestSequence === sequence.current) {
                setError(
                    cause instanceof Error
                        ? cause.message
                        : 'Não foi possível carregar a agenda.',
                );
            }
        } finally {
            if (requestSequence === sequence.current) setLoading(false);
        }
    }, [currentCursor, enabled, from, serviceFilter, statusFilter, to]);

    useEffect(() => {
        const requestSequence = sequence;
        const timer = window.setTimeout(() => void load(), 0);
        return () => {
            window.clearTimeout(timer);
            requestSequence.current++;
        };
    }, [load]);

    function changeFilter(setter: (value: string) => void, value: string) {
        setter(value);
        setPageIndex(0);
        setCursorStack([null]);
        setDetails(null);
    }

    function movePeriod(amount: number) {
        setSelectedDate(
            addDateDays(anchorDate, amount * (view === 'day' ? 1 : 7)),
        );
        resetPagination();
        setDetails(null);
    }

    async function openDetails(appointmentId: string) {
        const requestSequence = ++detailSequence.current;
        setSelectedAppointmentId(appointmentId);
        setDetails(null);
        setDetailsLoading(true);
        setDetailsError('');
        try {
            const [appointmentResponse, historyResponse] = await Promise.all([
                fetch(apiBaseUrl + '/v1/admin/appointments/' + appointmentId, {
                    credentials: 'same-origin',
                    cache: 'no-store',
                }),
                fetch(
                    apiBaseUrl +
                        '/v1/admin/appointments/' +
                        appointmentId +
                        '/history',
                    {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    },
                ),
            ]);
            if (
                [appointmentResponse.status, historyResponse.status].some(
                    (status) => status === 401 || status === 403,
                )
            ) {
                sessionInvalidRef.current(
                    appointmentResponse.status === 401 ||
                        appointmentResponse.status === 403
                        ? appointmentResponse.status
                        : historyResponse.status,
                );
                return;
            }
            const [appointment, history] = await Promise.all([
                readJson<Appointment>(appointmentResponse),
                readJson<{ items: HistoryEntry[] }>(historyResponse),
            ]);
            if (requestSequence === detailSequence.current)
                setDetails({ ...appointment, history: history.items });
        } catch (cause) {
            if (requestSequence === detailSequence.current) {
                setDetailsError(
                    cause instanceof Error
                        ? cause.message
                        : 'Não foi possível carregar os detalhes.',
                );
            }
        } finally {
            if (requestSequence === detailSequence.current)
                setDetailsLoading(false);
        }
    }

    function appointmentsForDate(date: string) {
        if (!timeZone) return [];
        return appointments.filter((item) => {
            const lastOccupiedDate = dateInZone(
                new Date(
                    new Date(item.occupiedUntil).getTime() - 1,
                ).toISOString(),
                timeZone,
            );
            return (
                dateInZone(item.startsAt, timeZone) <= date &&
                lastOccupiedDate >= date
            );
        });
    }

    function blocksForDate(date: string) {
        if (!timeZone) return [];
        return blocks.filter((item) => {
            const lastBlockDate = dateInZone(
                new Date(new Date(item.endsAt).getTime() - 1).toISOString(),
                timeZone,
            );
            return (
                dateInZone(item.startsAt, timeZone) <= date &&
                lastBlockDate >= date
            );
        });
    }

    return (
        <section className="admin-agenda" aria-labelledby="admin-agenda-title">
            <h2 id="admin-agenda-title">Agenda</h2>
            {enabled ? (
                <>
                    <p>
                        Horários exibidos no fuso do tenant:{' '}
                        <strong>{timeZone || 'carregando…'}</strong>.
                    </p>
                    <div
                        className="admin-agenda__controls"
                        aria-label="Controles da agenda"
                    >
                        <fieldset className="admin-agenda__view">
                            <legend>Visão</legend>
                            <button
                                type="button"
                                aria-pressed={view === 'day'}
                                onClick={() => {
                                    setView('day');
                                    resetPagination();
                                }}
                            >
                                Dia
                            </button>
                            <button
                                type="button"
                                aria-pressed={view === 'week'}
                                onClick={() => {
                                    setView('week');
                                    resetPagination();
                                }}
                            >
                                Semana
                            </button>
                        </fieldset>
                        <button type="button" onClick={() => movePeriod(-1)}>
                            Período anterior
                        </button>
                        <button
                            type="button"
                            onClick={() => {
                                setSelectedDate(today);
                                resetPagination();
                            }}
                        >
                            Hoje
                        </button>
                        <button type="button" onClick={() => movePeriod(1)}>
                            Próximo período
                        </button>
                        <label>
                            Data de referência
                            <input
                                type="date"
                                value={anchorDate}
                                onChange={(event) => {
                                    setSelectedDate(event.target.value);
                                    resetPagination();
                                }}
                            />
                        </label>
                        <label>
                            Serviço
                            <select
                                value={serviceFilter}
                                onChange={(event) =>
                                    changeFilter(
                                        setServiceFilter,
                                        event.target.value,
                                    )
                                }
                            >
                                <option value="">Todos os serviços</option>
                                {services.map((service) => (
                                    <option
                                        key={service.serviceId}
                                        value={service.serviceId}
                                    >
                                        {service.name}
                                        {service.status === 'INACTIVE'
                                            ? ' (inativo)'
                                            : ''}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label>
                            Status
                            <select
                                value={statusFilter}
                                onChange={(event) =>
                                    changeFilter(
                                        setStatusFilter,
                                        event.target.value,
                                    )
                                }
                            >
                                <option value="">Todos os status</option>
                                <option value="CONFIRMED">Confirmada</option>
                            </select>
                        </label>
                        <button
                            type="button"
                            onClick={() => void load()}
                            disabled={loading}
                        >
                            Atualizar
                        </button>
                    </div>
                    {error && (
                        <p role="alert">
                            {error}{' '}
                            <button type="button" onClick={() => void load()}>
                                Tentar novamente
                            </button>
                        </p>
                    )}
                    {loading && (
                        <output aria-live="polite">Carregando agenda…</output>
                    )}
                    {!loading &&
                        !error &&
                        appointments.length === 0 &&
                        blocks.length === 0 && (
                            <p>Nenhum atendimento ou bloqueio neste período.</p>
                        )}
                    {hasMore && (
                        <output aria-live="polite">
                            Exibindo uma página parcial. Avance para consultar
                            os próximos itens; ausência nesta página não
                            significa horário livre.
                        </output>
                    )}
                    <div className="admin-agenda__days">
                        {visibleDates.map((date) => {
                            const dayAppointments = appointmentsForDate(date);
                            const dayBlocks = blocksForDate(date);
                            return (
                                <section
                                    className="admin-agenda__day"
                                    key={date}
                                    aria-labelledby={`agenda-${date}`}
                                >
                                    <h3 id={`agenda-${date}`}>
                                        {dateLabel(date)}
                                    </h3>
                                    {dayAppointments.length === 0 &&
                                    dayBlocks.length === 0 ? (
                                        <p>
                                            Nenhum atendimento ou bloqueio
                                            carregado nesta data.
                                        </p>
                                    ) : (
                                        <ul>
                                            {dayAppointments.map(
                                                (appointment) => {
                                                    const startsBefore =
                                                        dateInZone(
                                                            appointment.startsAt,
                                                            timeZone,
                                                        ) < date;
                                                    return (
                                                        <li
                                                            className="admin-agenda__appointment"
                                                            data-appointment-id={
                                                                appointment.appointmentId
                                                            }
                                                            key={
                                                                appointment.appointmentId
                                                            }
                                                        >
                                                            <strong>
                                                                {
                                                                    appointment.serviceName
                                                                }
                                                            </strong>{' '}
                                                            —{' '}
                                                            {
                                                                appointment.clientName
                                                            }
                                                            <p>
                                                                {startsBefore
                                                                    ? 'Continuação da ocupação · '
                                                                    : ''}
                                                                {formatInstant(
                                                                    appointment.startsAt,
                                                                    timeZone,
                                                                    true,
                                                                )}
                                                                –
                                                                {formatInstant(
                                                                    appointment.serviceEndsAt,
                                                                    timeZone,
                                                                )}{' '}
                                                                atendimento (
                                                                {
                                                                    appointment.durationMinutes
                                                                }{' '}
                                                                min)
                                                            </p>
                                                            <p className="admin-agenda__buffer">
                                                                Buffer até{' '}
                                                                {formatInstant(
                                                                    appointment.occupiedUntil,
                                                                    timeZone,
                                                                    true,
                                                                )}{' '}
                                                                (
                                                                {
                                                                    appointment.bufferAfterMinutes
                                                                }{' '}
                                                                min);{' '}
                                                                {
                                                                    appointment.status
                                                                }
                                                            </p>
                                                            <button
                                                                type="button"
                                                                onClick={() =>
                                                                    void openDetails(
                                                                        appointment.appointmentId,
                                                                    )
                                                                }
                                                            >
                                                                Detalhes e
                                                                história
                                                            </button>
                                                        </li>
                                                    );
                                                },
                                            )}
                                            {dayBlocks.map((block) => (
                                                <li
                                                    className={`admin-agenda__block admin-agenda__block--${block.status.toLowerCase()}`}
                                                    key={block.blockId}
                                                >
                                                    <strong>
                                                        {block.status ===
                                                        'ACTIVE'
                                                            ? 'Bloqueio ativo'
                                                            : 'Bloqueio cancelado'}
                                                    </strong>
                                                    <p>
                                                        {formatInstant(
                                                            block.startsAt,
                                                            timeZone,
                                                            true,
                                                        )}
                                                        –
                                                        {formatInstant(
                                                            block.endsAt,
                                                            timeZone,
                                                            true,
                                                        )}{' '}
                                                        · {block.reason}
                                                    </p>
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                </section>
                            );
                        })}
                    </div>
                    <nav
                        className="admin-agenda__pagination"
                        aria-label="Paginação da agenda"
                    >
                        <button
                            type="button"
                            disabled={pageIndex === 0 || loading}
                            onClick={() =>
                                setPageIndex((index) => Math.max(0, index - 1))
                            }
                        >
                            Página anterior
                        </button>
                        <span>
                            Página {pageIndex + 1}
                            {hasMore ? ' · há mais resultados' : ''}
                        </span>
                        <button
                            type="button"
                            disabled={!hasMore || !nextCursor || loading}
                            onClick={() => {
                                if (!nextCursor) return;
                                setCursorStack((current) => [
                                    ...current.slice(0, pageIndex + 1),
                                    nextCursor,
                                ]);
                                setPageIndex((index) => index + 1);
                            }}
                        >
                            Próxima página
                        </button>
                    </nav>
                    {details && (
                        <section
                            className="admin-agenda__details"
                            aria-labelledby="agenda-details-title"
                        >
                            <button
                                type="button"
                                onClick={() => setDetails(null)}
                            >
                                Fechar detalhes
                            </button>
                            <h3 id="agenda-details-title">
                                Detalhes da reserva
                            </h3>
                            <p>
                                {details.clientName} · {details.serviceName} ·{' '}
                                {details.status} · versão {details.version}
                            </p>
                            <p>
                                Atendimento:{' '}
                                {formatInstant(
                                    details.startsAt,
                                    details.timeZone,
                                    true,
                                )}
                                –
                                {formatInstant(
                                    details.serviceEndsAt,
                                    details.timeZone,
                                )}{' '}
                                ({details.durationMinutes} min).
                            </p>
                            <p>
                                Ocupação com buffer até{' '}
                                {formatInstant(
                                    details.occupiedUntil,
                                    details.timeZone,
                                )}{' '}
                                ({details.bufferAfterMinutes} min). Fuso do
                                snapshot: {details.timeZone}.
                            </p>
                            <h4>História registrada</h4>
                            {details.history.length === 0 ? (
                                <p>Não há eventos de história registrados.</p>
                            ) : (
                                <ol>
                                    {details.history.map((item, index) => (
                                        <li key={`${item.occurredAt}-${index}`}>
                                            {item.fromStatus
                                                ? `${item.fromStatus} → `
                                                : ''}
                                            {item.toStatus} · {item.reasonCode}{' '}
                                            ·{' '}
                                            {formatInstant(
                                                item.occurredAt,
                                                details.timeZone,
                                                true,
                                            )}
                                        </li>
                                    ))}
                                </ol>
                            )}
                        </section>
                    )}
                    {detailsLoading && (
                        <output aria-live="polite">
                            Carregando detalhes e história…
                        </output>
                    )}
                    {detailsError && (
                        <p role="alert">
                            {detailsError}{' '}
                            <button
                                type="button"
                                onClick={() =>
                                    void openDetails(selectedAppointmentId)
                                }
                            >
                                Recarregar detalhes
                            </button>
                        </p>
                    )}
                </>
            ) : (
                <p>Entre como administrador para consultar a agenda.</p>
            )}
        </section>
    );
}
