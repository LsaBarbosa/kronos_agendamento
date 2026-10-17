import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';
import { validLocalTimeOffsets } from './localTimeOffsets';

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
    durationMinutes: number;
    bufferAfterMinutes: number;
};
type HistoryEntry = {
    fromStatus: string | null;
    toStatus: string;
    actorType: string;
    actorRef: string;
    reasonCode: string;
    reason: string | null;
    occurredAt: string;
    previousStartsAt: string | null;
    previousOccupiedUntil: string | null;
    newStartsAt: string | null;
    newOccupiedUntil: string | null;
    versionBefore: number | null;
    versionAfter: number | null;
};
type Details = Appointment & { history: HistoryEntry[] };
type OutcomeAction = 'cancel' | 'complete' | 'mark-no-show';
type PendingOutcome = {
    appointmentId: string;
    action: OutcomeAction;
    body: { expectedVersion: number; reason?: string };
    key: string;
};
type Csrf = { headerName: string; token: string };
type WeeklySchedule = {
    configured: boolean;
    timeZone: string;
    version: number;
    scheduleRevision: number;
    periods: Array<{
        weekday: string;
        localStart: string;
        localEnd: string;
    }>;
};
type AvailabilityPolicy = {
    configured: boolean;
    slotStepMinutes: number | null;
    minimumNoticeHours: number | null;
    bookingHorizonDays: number | null;
};
type SpecialException = {
    kind: 'CLOSED' | 'SPECIAL';
    periods: Array<{ localStart: string; localEnd: string }>;
};
type ClientOption = { clientId: string; displayName: string };

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
    csrf,
    onSessionInvalid,
}: {
    enabled: boolean;
    csrf: Csrf | null;
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
    const [rescheduleDate, setRescheduleDate] = useState('');
    const [rescheduleTime, setRescheduleTime] = useState('');
    const [rescheduleOffset, setRescheduleOffset] = useState('');
    const [rescheduleError, setRescheduleError] = useState('');
    const [rescheduleNotice, setRescheduleNotice] = useState('');
    const [rescheduleSaving, setRescheduleSaving] = useState(false);
    const [rescheduleUncertain, setRescheduleUncertain] = useState(false);
    const [outcomeSaving, setOutcomeSaving] = useState(false);
    const [outcomeUncertain, setOutcomeUncertain] = useState(false);
    const [outcomeError, setOutcomeError] = useState('');
    const [outcomeConflict, setOutcomeConflict] = useState(false);
    const [outcomeNotice, setOutcomeNotice] = useState('');
    const [outcomeAction, setOutcomeAction] = useState<OutcomeAction | null>(
        null,
    );
    const [outcomeReason, setOutcomeReason] = useState('');
    const [pendingOutcome, setPendingOutcome] = useState<PendingOutcome | null>(
        null,
    );
    const [rescheduleOpen, setRescheduleOpen] = useState(false);
    const [reschedulePending, setReschedulePending] = useState<{
        appointmentId: string;
        body: {
            expectedVersion: number;
            expectedScheduleRevision: number;
            startsAt: string;
        };
        key: string;
    } | null>(null);
    const rescheduleKeys = useRef(new Map<string, string>());
    const outcomeKeys = useRef(new Map<string, string>());
    const rescheduleRequestGuard = useRef(false);
    const rescheduleSubmissionInFlight = useRef(false);
    const outcomeRequestGuard = useRef(false);
    const commandInFlightOrUncertain =
        rescheduleSaving ||
        rescheduleUncertain ||
        outcomeSaving ||
        outcomeUncertain;
    const rescheduleLocked = () =>
        rescheduleRequestGuard.current ||
        rescheduleSaving ||
        rescheduleUncertain ||
        outcomeRequestGuard.current ||
        outcomeSaving ||
        outcomeUncertain;
    const [schedule, setSchedule] = useState<WeeklySchedule | null>(null);
    const [availabilityPolicy, setAvailabilityPolicy] =
        useState<AvailabilityPolicy | null>(null);
    const [createOpen, setCreateOpen] = useState(false);
    const [createSaving, setCreateSaving] = useState(false);
    const [createError, setCreateError] = useState('');
    const [createNotice, setCreateNotice] = useState('');
    const [createUncertain, setCreateUncertain] = useState(false);
    const [scheduleConflict, setScheduleConflict] = useState(false);
    const [scheduleRefreshing, setScheduleRefreshing] = useState(false);
    const [createServiceId, setCreateServiceId] = useState('');
    const [clientMode, setClientMode] = useState<'existing' | 'new'>(
        'existing',
    );
    const [clientQuery, setClientQuery] = useState('');
    const [clientOptions, setClientOptions] = useState<ClientOption[]>([]);
    const [selectedClientId, setSelectedClientId] = useState('');
    const [newClientName, setNewClientName] = useState('');
    const [newClientPhone, setNewClientPhone] = useState('');
    const [bookingDate, setBookingDate] = useState('');
    const [bookingTime, setBookingTime] = useState('09:00');
    const [bookingOffset, setBookingOffset] = useState('');
    const [dateException, setDateException] = useState<SpecialException | null>(
        null,
    );
    const [exceptionDateLoaded, setExceptionDateLoaded] = useState('');
    const [overrideSchedule, setOverrideSchedule] = useState(false);
    const [overrideNotice, setOverrideNotice] = useState(false);
    const [overrideReason, setOverrideReason] = useState('');
    const createRequestGuard = useRef(false);
    const createKeys = useRef(new Map<string, string>());
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
    const rescheduleOffsets = details
        ? validLocalTimeOffsets(
              rescheduleDate,
              rescheduleTime,
              details.timeZone,
          )
        : [];

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

    useEffect(() => {
        if (!enabled) return;
        let active = true;
        void Promise.all([
            fetch(apiBaseUrl + '/v1/admin/schedule/weekly-periods', {
                credentials: 'same-origin',
                cache: 'no-store',
            }),
            fetch(apiBaseUrl + '/v1/admin/availability-policy', {
                credentials: 'same-origin',
                cache: 'no-store',
            }),
        ])
            .then(async ([weeklyResponse, policyResponse]) => {
                if (
                    [weeklyResponse.status, policyResponse.status].some(
                        (status) => status === 401 || status === 403,
                    )
                ) {
                    sessionInvalidRef.current(
                        weeklyResponse.status === 401 ||
                            weeklyResponse.status === 403
                            ? weeklyResponse.status
                            : policyResponse.status,
                    );
                    return;
                }
                const [weekly, policy] = await Promise.all([
                    readJson<WeeklySchedule>(weeklyResponse),
                    readJson<AvailabilityPolicy>(policyResponse),
                ]);
                if (!active) return;
                setSchedule(weekly);
                setAvailabilityPolicy(policy);
                setTimeZone(weekly.timeZone);
            })
            .catch(() => {
                if (active)
                    setCreateError(
                        'Não foi possível carregar as políticas atuais da agenda.',
                    );
            });
        return () => {
            active = false;
        };
    }, [enabled]);

    useEffect(() => {
        if (!enabled || !createOpen || bookingDate.length !== 10) return;
        let active = true;
        void fetch(
            apiBaseUrl +
                '/v1/admin/schedule/exceptions?' +
                new URLSearchParams({ from: bookingDate, to: bookingDate }),
            { credentials: 'same-origin', cache: 'no-store' },
        )
            .then(async (response) => {
                if (response.status === 401 || response.status === 403) {
                    sessionInvalidRef.current(response.status);
                    return;
                }
                const result = await readJson<{
                    exceptions: SpecialException[];
                }>(response);
                if (!active) return;
                setDateException(result.exceptions[0] ?? null);
                setExceptionDateLoaded(bookingDate);
            })
            .catch(() => {
                if (active)
                    setCreateError(
                        'Não foi possível consultar exceções da data.',
                    );
            });
        return () => {
            active = false;
        };
    }, [bookingDate, createOpen, enabled]);

    useEffect(() => {
        if (
            !enabled ||
            !createOpen ||
            clientMode !== 'existing' ||
            clientQuery.trim().length < 2
        ) {
            return;
        }
        let active = true;
        const timer = window.setTimeout(() => {
            void fetch(
                apiBaseUrl +
                    '/v1/admin/clients?' +
                    new URLSearchParams({ query: clientQuery.trim() }),
                { credentials: 'same-origin', cache: 'no-store' },
            )
                .then(async (response) => {
                    if (response.status === 401 || response.status === 403) {
                        sessionInvalidRef.current(response.status);
                        return;
                    }
                    const values = await readJson<ClientOption[]>(response);
                    if (active) setClientOptions(values);
                })
                .catch(() => {
                    if (active)
                        setCreateError('Não foi possível buscar clientes.');
                });
        }, 250);
        return () => {
            active = false;
            window.clearTimeout(timer);
        };
    }, [clientMode, clientQuery, createOpen, enabled]);

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
        if (rescheduleLocked()) return;
        setter(value);
        setPageIndex(0);
        setCursorStack([null]);
        setDetails(null);
    }

    function movePeriod(amount: number) {
        if (rescheduleLocked()) return;
        setSelectedDate(
            addDateDays(anchorDate, amount * (view === 'day' ? 1 : 7)),
        );
        resetPagination();
        setDetails(null);
    }

    async function loadDetails(appointmentId: string) {
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

    async function openDetails(appointmentId: string) {
        if (rescheduleLocked()) {
            setDetailsError(
                `Resolva primeiro a remarcação de ${reschedulePending?.appointmentId ?? details?.appointmentId ?? 'reserva original'} antes de navegar.`,
            );
            return;
        }
        await loadDetails(appointmentId);
    }

    function beginReschedule(appointment: Appointment) {
        if (rescheduleLocked()) return;
        setRescheduleOpen(true);
        setRescheduleDate(
            dateInZone(appointment.startsAt, appointment.timeZone),
        );
        setRescheduleTime(
            new Intl.DateTimeFormat('en-GB', {
                timeZone: appointment.timeZone,
                hour: '2-digit',
                minute: '2-digit',
                hourCycle: 'h23',
            }).format(new Date(appointment.startsAt)),
        );
        setRescheduleOffset('');
        setRescheduleError('');
        setRescheduleNotice('');
        setReschedulePending(null);
    }

    async function submitReschedule(retry = false) {
        if (
            !csrf ||
            !details ||
            !schedule ||
            rescheduleSubmissionInFlight.current ||
            (rescheduleRequestGuard.current && !retry)
        )
            return;
        const offsets = validLocalTimeOffsets(
            rescheduleDate,
            rescheduleTime,
            details.timeZone,
        );
        const chosenOffset =
            offsets.length === 1 ? offsets[0] : rescheduleOffset;
        if (
            !retry &&
            (offsets.length === 0 || !offsets.includes(chosenOffset))
        ) {
            setRescheduleError(
                offsets.length === 0
                    ? 'Esse horário local não existe no fuso do tenant.'
                    : 'Selecione um offset válido para esse horário local.',
            );
            return;
        }
        const body = retry
            ? reschedulePending?.body
            : {
                  expectedVersion: details.version,
                  expectedScheduleRevision: schedule.scheduleRevision,
                  startsAt: `${rescheduleDate}T${rescheduleTime}:00${chosenOffset}`,
              };
        if (!body) return;
        const targetAppointmentId = retry
            ? reschedulePending?.appointmentId
            : details.appointmentId;
        if (!targetAppointmentId) return;
        const scope = 'admin-appointment-reschedule-v1';
        const key = retry
            ? reschedulePending?.key
            : commandKey(rescheduleKeys.current, scope, [
                  targetAppointmentId,
                  body,
              ]);
        if (!key) return;
        if (!retry) {
            setReschedulePending({
                appointmentId: targetAppointmentId,
                body,
                key,
            });
        }
        rescheduleRequestGuard.current = true;
        rescheduleSubmissionInFlight.current = true;
        setRescheduleSaving(true);
        setRescheduleError('');
        setRescheduleNotice('');
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/admin/appointments/' +
                    targetAppointmentId +
                    '/reschedule',
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
                if (!retry || !rescheduleUncertain) {
                    setReschedulePending(null);
                    setRescheduleUncertain(false);
                    rescheduleRequestGuard.current = false;
                }
                sessionInvalidRef.current(response.status);
                return;
            }
            if (!response.ok) {
                const errorBody = (await response.json().catch(() => null)) as {
                    error?: { code?: string; message?: string };
                } | null;
                const code = errorBody?.error?.code ?? 'HTTP_ERROR';
                setRescheduleError(
                    errorBody?.error?.message ??
                        `Falha HTTP ${response.status}.`,
                );
                const uncertain =
                    code === 'OPERATION_IN_PROGRESS' || response.status >= 500;
                setRescheduleUncertain(uncertain);
                if (code === 'VERSION_CONFLICT') {
                    setRescheduleNotice(
                        'A reserva ou agenda mudou. Atualize as versões e revise o destino antes de enviar uma nova intenção.',
                    );
                }
                if (!uncertain) {
                    setReschedulePending(null);
                    rescheduleRequestGuard.current = false;
                }
                return;
            }
            const fingerprint = JSON.stringify([
                scope,
                [targetAppointmentId, body],
            ]);
            rescheduleKeys.current.delete(fingerprint);
            setReschedulePending(null);
            setRescheduleUncertain(false);
            setRescheduleOpen(false);
            setRescheduleNotice('Remarcação confirmada e persistida.');
            rescheduleRequestGuard.current = false;
            setRescheduleSaving(false);
            await load();
            await loadDetails(targetAppointmentId);
        } catch {
            setRescheduleUncertain(true);
            setRescheduleError(
                'A resposta não chegou; o resultado pode estar confirmado. Repita exatamente a mesma intenção antes de alterar o destino.',
            );
        } finally {
            rescheduleSubmissionInFlight.current = false;
            setRescheduleSaving(false);
        }
    }

    async function submitOutcome(retry = false) {
        if (!csrf || outcomeRequestGuard.current || outcomeSaving) return;
        let intention = pendingOutcome;
        if (retry) {
            if (!intention || !outcomeUncertain) return;
        } else {
            if (!details || details.status !== 'CONFIRMED' || !outcomeAction)
                return;
            const reason = outcomeReason.trim();
            const body: PendingOutcome['body'] = {
                expectedVersion: details.version,
                ...(reason ? { reason } : {}),
            };
            const key = commandKey(
                outcomeKeys.current,
                `admin-appointment-${outcomeAction}-v1`,
                [details.appointmentId, body],
            );
            intention = {
                appointmentId: details.appointmentId,
                action: outcomeAction,
                body,
                key,
            };
            setPendingOutcome(intention);
        }
        outcomeRequestGuard.current = true;
        setOutcomeSaving(true);
        setOutcomeError('');
        setOutcomeConflict(false);
        setOutcomeNotice('');
        try {
            const response = await fetch(
                `${apiBaseUrl}/v1/admin/appointments/${intention.appointmentId}/${intention.action}`,
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
                setPendingOutcome(null);
                setOutcomeUncertain(false);
                outcomeRequestGuard.current = false;
                sessionInvalidRef.current(response.status);
                return;
            }
            if (!response.ok) {
                const errorBody = (await response.json().catch(() => null)) as {
                    error?: { code?: string; message?: string };
                } | null;
                const code = errorBody?.error?.code ?? 'HTTP_ERROR';
                const uncertain =
                    code === 'OPERATION_IN_PROGRESS' || response.status >= 500;
                setOutcomeError(
                    errorBody?.error?.message ??
                        `Falha HTTP ${response.status}.`,
                );
                setOutcomeConflict(
                    code === 'VERSION_CONFLICT' ||
                        code === 'INVALID_STATE_TRANSITION',
                );
                setOutcomeUncertain(uncertain);
                outcomeRequestGuard.current = false;
                if (!uncertain) {
                    setPendingOutcome(null);
                }
                return;
            }
            const scope = `admin-appointment-${intention.action}-v1`;
            outcomeKeys.current.delete(
                JSON.stringify([
                    scope,
                    [intention.appointmentId, intention.body],
                ]),
            );
            const targetAppointmentId = intention.appointmentId;
            setPendingOutcome(null);
            setOutcomeUncertain(false);
            setOutcomeConflict(false);
            setOutcomeAction(null);
            setOutcomeReason('');
            setOutcomeNotice('Estado atualizado e persistido.');
            outcomeRequestGuard.current = false;
            await load();
            await loadDetails(targetAppointmentId);
        } catch {
            setOutcomeUncertain(true);
            outcomeRequestGuard.current = false;
            setOutcomeError(
                'A resposta não chegou; o comando pode ter sido confirmado. Repita a mesma ação sem alterar reserva, motivo ou versão.',
            );
        } finally {
            setOutcomeSaving(false);
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

    const activeServices = services.filter(
        (service) => service.status === 'ACTIVE',
    );
    const bookingService = activeServices.find(
        (service) => service.serviceId === createServiceId,
    );
    const bookingOffsets = validLocalTimeOffsets(
        bookingDate,
        bookingTime,
        timeZone,
    );
    const effectiveOffset = bookingOffsets.includes(bookingOffset)
        ? bookingOffset
        : bookingOffsets.length === 1
          ? bookingOffsets[0]
          : '';
    const exceptionLoaded = exceptionDateLoaded === bookingDate;
    const visibleClientOptions =
        clientQuery.trim().length >= 2 ? clientOptions : [];
    const weekdayNames = [
        'SUNDAY',
        'MONDAY',
        'TUESDAY',
        'WEDNESDAY',
        'THURSDAY',
        'FRIDAY',
        'SATURDAY',
    ];
    const dateWeekday = bookingDate
        ? weekdayNames[new Date(`${bookingDate}T12:00:00Z`).getUTCDay()]
        : '';
    const selectedPeriods =
        dateException?.kind === 'SPECIAL'
            ? dateException.periods
            : (schedule?.periods.filter(
                  (period) => period.weekday === dateWeekday,
              ) ?? []);
    const localMinute = bookingTime
        ? Number(bookingTime.slice(0, 2)) * 60 + Number(bookingTime.slice(3, 5))
        : -1;
    const policyViolationBySchedule = Boolean(
        bookingService &&
        availabilityPolicy?.configured &&
        schedule?.configured &&
        !selectedPeriods.some((period) => {
            const begin =
                Number(period.localStart.slice(0, 2)) * 60 +
                Number(period.localStart.slice(3, 5));
            const end =
                Number(period.localEnd.slice(0, 2)) * 60 +
                Number(period.localEnd.slice(3, 5));
            const occupiedMinutes =
                bookingService.durationMinutes +
                bookingService.bufferAfterMinutes;
            return (
                localMinute >= begin &&
                localMinute < end &&
                (localMinute - begin) %
                    (availabilityPolicy.slotStepMinutes ?? 1) ===
                    0 &&
                localMinute + occupiedMinutes <= end
            );
        }),
    );
    const selectedStart =
        effectiveOffset && bookingDate && bookingTime
            ? new Date(`${bookingDate}T${bookingTime}:00${effectiveOffset}`)
            : null;
    const policyViolationByNotice = Boolean(
        selectedStart &&
        availabilityPolicy?.minimumNoticeHours != null &&
        selectedStart.getTime() <
            new Date(nowInstant).getTime() +
                availabilityPolicy.minimumNoticeHours * 60 * 60 * 1000,
    );
    const outsideBookingHorizon = Boolean(
        bookingDate &&
        availabilityPolicy?.bookingHorizonDays != null &&
        (bookingDate < today ||
            bookingDate >
                addDateDays(today, availabilityPolicy.bookingHorizonDays - 1)),
    );
    const specialOrClosedBlocksScheduleOverride = Boolean(
        dateException && policyViolationBySchedule,
    );

    async function submitAdministrativeAppointment() {
        if (scheduleRefreshing) {
            setCreateError(
                'Aguarde a atualização da agenda antes de confirmar.',
            );
            return;
        }
        if (
            !csrf ||
            !schedule ||
            !bookingService ||
            !selectedStart ||
            createRequestGuard.current
        )
            return;
        if (!exceptionLoaded || outsideBookingHorizon) {
            setCreateError(
                'A data ainda não foi validada ou está fora do horizonte permitido.',
            );
            return;
        }
        if (clientMode === 'existing' && !selectedClientId) {
            setCreateError('Selecione um cliente ativo do tenant.');
            return;
        }
        if (
            clientMode === 'new' &&
            (!newClientName.trim() || !newClientPhone.trim())
        ) {
            setCreateError('Informe nome e telefone do novo cliente.');
            return;
        }
        if (
            policyViolationBySchedule &&
            (!overrideSchedule || specialOrClosedBlocksScheduleOverride)
        ) {
            setCreateError(
                'OUTSIDE_WEEKLY_SCHEDULE ignora somente a grade semanal e não ultrapassa uma exceção CLOSED ou os períodos SPECIAL.',
            );
            return;
        }
        if (policyViolationByNotice && !overrideNotice) {
            setCreateError(
                'A antecedência mínima exige confirmação explícita do override.',
            );
            return;
        }
        const overridePolicies = [
            ...(overrideSchedule ? ['OUTSIDE_WEEKLY_SCHEDULE'] : []),
            ...(overrideNotice ? ['MIN_NOTICE'] : []),
        ].sort();
        if (overridePolicies.length > 0 && !overrideReason.trim()) {
            setCreateError('Informe o motivo obrigatório para o encaixe.');
            return;
        }
        if (
            overrideSchedule !== policyViolationBySchedule ||
            overrideNotice !== policyViolationByNotice
        ) {
            setCreateError(
                'As exceções selecionadas precisam corresponder às regras estimadas para este horário.',
            );
            return;
        }
        const body = {
            serviceId: createServiceId,
            startsAt: `${bookingDate}T${bookingTime}:00${effectiveOffset}`,
            ...(clientMode === 'existing'
                ? { clientId: selectedClientId }
                : { client: { name: newClientName, phone: newClientPhone } }),
            overridePolicies,
            ...(overridePolicies.length > 0
                ? { reason: overrideReason.trim() }
                : {}),
            expectedScheduleVersion: schedule.scheduleRevision,
        };
        const scope = 'admin-appointment-create-v1';
        const fingerprint = JSON.stringify([scope, body]);
        const key = commandKey(createKeys.current, scope, body);
        createRequestGuard.current = true;
        setCreateSaving(true);
        setCreateError('');
        setCreateNotice('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/appointments',
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
                sessionInvalidRef.current(response.status);
                return;
            }
            if (!response.ok) {
                const errorBody = (await response.json().catch(() => null)) as {
                    error?: { code?: string; message?: string };
                } | null;
                const code = errorBody?.error?.code ?? 'HTTP_ERROR';
                setCreateError(
                    errorBody?.error?.message ??
                        `Falha HTTP ${response.status}.`,
                );
                if (code === 'VERSION_CONFLICT') {
                    setScheduleConflict(true);
                    setCreateNotice(
                        'Atualize a agenda/revisão antes de decidir novamente; os campos permanecem preservados.',
                    );
                }
                if (code === 'OPERATION_IN_PROGRESS') setCreateUncertain(true);
                else setCreateUncertain(false);
                return;
            }
            const created = (await response.json()) as {
                appointmentId: string;
            };
            createKeys.current.delete(fingerprint);
            setCreateUncertain(false);
            setCreateNotice('Reserva confirmada e persistida.');
            setOverrideReason('');
            await load();
            await openDetails(created.appointmentId);
        } catch {
            setCreateUncertain(true);
            setCreateError(
                'A resposta não chegou; o resultado pode estar confirmado. Repita o mesmo comando sem alterar os campos.',
            );
        } finally {
            createRequestGuard.current = false;
            setCreateSaving(false);
        }
    }

    async function refreshScheduleRevision(notify = true) {
        setScheduleRefreshing(true);
        try {
            const [scheduleResponse, policyResponse] = await Promise.all([
                fetch(apiBaseUrl + '/v1/admin/schedule/weekly-periods', {
                    credentials: 'same-origin',
                    cache: 'no-store',
                }),
                fetch(apiBaseUrl + '/v1/admin/availability-policy', {
                    credentials: 'same-origin',
                    cache: 'no-store',
                }),
            ]);
            if (
                [scheduleResponse.status, policyResponse.status].some(
                    (status) => status === 401 || status === 403,
                )
            ) {
                sessionInvalidRef.current(
                    scheduleResponse.status === 401 ||
                        scheduleResponse.status === 403
                        ? scheduleResponse.status
                        : policyResponse.status,
                );
                return;
            }
            const [updated, policy] = await Promise.all([
                readJson<WeeklySchedule>(scheduleResponse),
                readJson<AvailabilityPolicy>(policyResponse),
            ]);
            setSchedule(updated);
            setAvailabilityPolicy(policy);
            setTimeZone(updated.timeZone);
            setScheduleConflict(false);
            if (notify) {
                setCreateNotice(
                    'Revisão atualizada. Revise as regras e confirme novamente.',
                );
            }
            setOverrideSchedule(false);
            setOverrideNotice(false);
        } catch {
            setCreateError(
                'Não foi possível atualizar a configuração e a revisão da agenda.',
            );
        } finally {
            setScheduleRefreshing(false);
        }
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
                    <div className="admin-agenda__creation">
                        <button
                            type="button"
                            aria-expanded={createOpen}
                            onClick={() => {
                                const opening = !createOpen;
                                setCreateOpen(opening);
                                if (opening)
                                    void refreshScheduleRevision(false);
                                if (!bookingDate) setBookingDate(anchorDate);
                                if (!createServiceId && activeServices[0]) {
                                    setCreateServiceId(
                                        activeServices[0].serviceId,
                                    );
                                }
                                setCreateError('');
                            }}
                        >
                            {createOpen
                                ? 'Fechar nova reserva'
                                : 'Nova reserva'}
                        </button>
                        {createOpen && (
                            <form
                                aria-label="Criar reserva administrativa"
                                onSubmit={(event) => {
                                    event.preventDefault();
                                    void submitAdministrativeAppointment();
                                }}
                            >
                                <fieldset
                                    disabled={
                                        createSaving ||
                                        createUncertain ||
                                        scheduleRefreshing
                                    }
                                >
                                    <legend>Reserva administrativa</legend>
                                    <p>
                                        Escolha um horário diretamente; a lista
                                        carregada da agenda não representa
                                        disponibilidade. O servidor revalida
                                        grade, exceções, bloqueios e ocupações
                                        no commit.
                                    </p>
                                    {!schedule ||
                                    !availabilityPolicy?.configured ? (
                                        <p role="alert">
                                            Grade/revisão ou políticas
                                            obrigatórias ausentes. Não há
                                            defaults; configure a agenda antes
                                            de reservar.
                                        </p>
                                    ) : (
                                        <p>
                                            Revisão da agenda:{' '}
                                            {schedule.scheduleRevision} · fuso{' '}
                                            {schedule.timeZone}
                                        </p>
                                    )}
                                    <label>
                                        Serviço ativo
                                        <select
                                            value={createServiceId}
                                            onChange={(event) =>
                                                setCreateServiceId(
                                                    event.target.value,
                                                )
                                            }
                                            required
                                        >
                                            <option value="">Selecione</option>
                                            {activeServices.map((service) => (
                                                <option
                                                    key={service.serviceId}
                                                    value={service.serviceId}
                                                >
                                                    {service.name} ·{' '}
                                                    {service.durationMinutes}{' '}
                                                    min +{' '}
                                                    {service.bufferAfterMinutes}{' '}
                                                    min de buffer
                                                </option>
                                            ))}
                                        </select>
                                    </label>
                                    <fieldset>
                                        <legend>Cliente</legend>
                                        <label>
                                            <input
                                                type="radio"
                                                checked={
                                                    clientMode === 'existing'
                                                }
                                                onChange={() =>
                                                    setClientMode('existing')
                                                }
                                            />
                                            Cliente existente
                                        </label>
                                        <label>
                                            <input
                                                type="radio"
                                                checked={clientMode === 'new'}
                                                onChange={() =>
                                                    setClientMode('new')
                                                }
                                            />
                                            Novo cliente
                                        </label>
                                        {clientMode === 'existing' ? (
                                            <>
                                                <label>
                                                    Buscar por nome (mínimo 2
                                                    caracteres; sem telefone)
                                                    <input
                                                        value={clientQuery}
                                                        onChange={(event) =>
                                                            setClientQuery(
                                                                event.target
                                                                    .value,
                                                            )
                                                        }
                                                        autoComplete="off"
                                                    />
                                                </label>
                                                <label>
                                                    Cliente
                                                    <select
                                                        value={selectedClientId}
                                                        onChange={(event) =>
                                                            setSelectedClientId(
                                                                event.target
                                                                    .value,
                                                            )
                                                        }
                                                        required
                                                    >
                                                        <option value="">
                                                            Selecione um cliente
                                                            encontrado
                                                        </option>
                                                        {visibleClientOptions.map(
                                                            (client) => (
                                                                <option
                                                                    key={
                                                                        client.clientId
                                                                    }
                                                                    value={
                                                                        client.clientId
                                                                    }
                                                                >
                                                                    {
                                                                        client.displayName
                                                                    }
                                                                </option>
                                                            ),
                                                        )}
                                                    </select>
                                                </label>
                                            </>
                                        ) : (
                                            <>
                                                <label>
                                                    Nome
                                                    <input
                                                        value={newClientName}
                                                        onChange={(event) =>
                                                            setNewClientName(
                                                                event.target
                                                                    .value,
                                                            )
                                                        }
                                                        maxLength={120}
                                                        required
                                                    />
                                                </label>
                                                <label>
                                                    Telefone para cadastro (será
                                                    normalizado em E.164; não
                                                    verifica identidade)
                                                    <input
                                                        value={newClientPhone}
                                                        onChange={(event) =>
                                                            setNewClientPhone(
                                                                event.target
                                                                    .value,
                                                            )
                                                        }
                                                        maxLength={80}
                                                        autoComplete="tel"
                                                        required
                                                    />
                                                </label>
                                            </>
                                        )}
                                    </fieldset>
                                    <div className="admin-agenda__controls">
                                        <label>
                                            Data local ({timeZone})
                                            <input
                                                type="date"
                                                value={bookingDate}
                                                onChange={(event) =>
                                                    setBookingDate(
                                                        event.target.value,
                                                    )
                                                }
                                                required
                                            />
                                        </label>
                                        <label>
                                            Horário local
                                            <input
                                                type="time"
                                                value={bookingTime}
                                                onChange={(event) =>
                                                    setBookingTime(
                                                        event.target.value,
                                                    )
                                                }
                                                required
                                            />
                                        </label>
                                    </div>
                                    {bookingOffsets.length === 0 &&
                                        bookingDate && (
                                            <p role="alert">
                                                Horário local inexistente no
                                                fuso do tenant.
                                            </p>
                                        )}
                                    {bookingOffsets.length > 1 && (
                                        <label>
                                            Horário ambíguo: escolha
                                            explicitamente o offset
                                            <select
                                                value={bookingOffset}
                                                onChange={(event) =>
                                                    setBookingOffset(
                                                        event.target.value,
                                                    )
                                                }
                                                required
                                            >
                                                <option value="">
                                                    Escolha o offset
                                                </option>
                                                {bookingOffsets.map(
                                                    (offset) => (
                                                        <option
                                                            key={offset}
                                                            value={offset}
                                                        >
                                                            {offset}
                                                        </option>
                                                    ),
                                                )}
                                            </select>
                                        </label>
                                    )}
                                    {dateException?.kind === 'CLOSED' && (
                                        <p role="alert">
                                            Esta data foi explicitamente fechada
                                            e não pode ser ignorada.
                                        </p>
                                    )}
                                    {dateException?.kind === 'SPECIAL' && (
                                        <p>
                                            Esta data usa os períodos especiais
                                            como configuração final. Serviço e
                                            buffer precisam caber em um período;
                                            somente a antecedência mínima pode
                                            ser ignorada independentemente.
                                        </p>
                                    )}
                                    <p>
                                        Estimativa local:{' '}
                                        {policyViolationBySchedule
                                            ? dateException?.kind === 'SPECIAL'
                                                ? 'fora dos períodos ou do passo SPECIAL'
                                                : 'fora da grade semanal/slot configurado'
                                            : 'dentro da configuração estimada'}
                                        ;{' '}
                                        {policyViolationByNotice
                                            ? 'antecedência abaixo do mínimo'
                                            : 'antecedência estimada válida'}
                                        . A hora do navegador é apenas
                                        indicativa; servidor e PostgreSQL
                                        decidem.
                                    </p>
                                    <fieldset>
                                        <legend>Exceções explícitas</legend>
                                        <label>
                                            <input
                                                type="checkbox"
                                                checked={overrideSchedule}
                                                disabled={
                                                    !policyViolationBySchedule ||
                                                    specialOrClosedBlocksScheduleOverride ||
                                                    outsideBookingHorizon
                                                }
                                                onChange={(event) =>
                                                    setOverrideSchedule(
                                                        event.target.checked,
                                                    )
                                                }
                                            />
                                            Ignorar somente a grade semanal (sem
                                            exceção para a data)
                                        </label>
                                        <label>
                                            <input
                                                type="checkbox"
                                                checked={overrideNotice}
                                                disabled={
                                                    !policyViolationByNotice ||
                                                    outsideBookingHorizon
                                                }
                                                onChange={(event) =>
                                                    setOverrideNotice(
                                                        event.target.checked,
                                                    )
                                                }
                                            />
                                            Ignorar somente a antecedência
                                            mínima
                                        </label>
                                    </fieldset>
                                    {(overrideSchedule || overrideNotice) && (
                                        <label>
                                            Motivo obrigatório
                                            <textarea
                                                value={overrideReason}
                                                onChange={(event) =>
                                                    setOverrideReason(
                                                        event.target.value,
                                                    )
                                                }
                                                maxLength={500}
                                                required
                                            />
                                        </label>
                                    )}
                                    {outsideBookingHorizon && (
                                        <p role="alert">
                                            Data fora do horizonte configurado;
                                            essa regra não pode ser ignorada.
                                        </p>
                                    )}
                                </fieldset>
                                {createError && (
                                    <p role="alert">{createError}</p>
                                )}
                                {createNotice && (
                                    <output aria-live="polite">
                                        {createNotice}
                                    </output>
                                )}
                                {createUncertain && (
                                    <p role="alert">
                                        Resultado incerto: não altere os campos.
                                        Reenvie o mesmo comando para recuperar o
                                        resultado original.
                                    </p>
                                )}
                                {scheduleConflict && (
                                    <button
                                        type="button"
                                        onClick={() =>
                                            void refreshScheduleRevision()
                                        }
                                    >
                                        Atualizar revisão da agenda
                                    </button>
                                )}
                                <button
                                    type="submit"
                                    disabled={
                                        !csrf ||
                                        createSaving ||
                                        !schedule ||
                                        !availabilityPolicy?.configured ||
                                        !bookingService ||
                                        !effectiveOffset ||
                                        !exceptionLoaded ||
                                        outsideBookingHorizon ||
                                        dateException?.kind === 'CLOSED' ||
                                        policyViolationBySchedule !==
                                            overrideSchedule ||
                                        policyViolationByNotice !==
                                            overrideNotice ||
                                        (specialOrClosedBlocksScheduleOverride &&
                                            policyViolationBySchedule) ||
                                        ((overrideSchedule || overrideNotice) &&
                                            !overrideReason.trim())
                                    }
                                >
                                    {createSaving
                                        ? 'Confirmando…'
                                        : createUncertain
                                          ? 'Repetir mesmo comando'
                                          : 'Confirmar reserva'}
                                </button>
                            </form>
                        )}
                    </div>
                    <div
                        className="admin-agenda__controls"
                        aria-label="Controles da agenda"
                    >
                        <fieldset className="admin-agenda__view">
                            <legend>Visão</legend>
                            <button
                                type="button"
                                aria-pressed={view === 'day'}
                                disabled={commandInFlightOrUncertain}
                                onClick={() => {
                                    if (rescheduleLocked()) return;
                                    setView('day');
                                    resetPagination();
                                }}
                            >
                                Dia
                            </button>
                            <button
                                type="button"
                                aria-pressed={view === 'week'}
                                disabled={commandInFlightOrUncertain}
                                onClick={() => {
                                    if (rescheduleLocked()) return;
                                    setView('week');
                                    resetPagination();
                                }}
                            >
                                Semana
                            </button>
                        </fieldset>
                        <button
                            type="button"
                            disabled={commandInFlightOrUncertain}
                            onClick={() => movePeriod(-1)}
                        >
                            Período anterior
                        </button>
                        <button
                            type="button"
                            disabled={commandInFlightOrUncertain}
                            onClick={() => {
                                if (rescheduleLocked()) return;
                                setSelectedDate(today);
                                resetPagination();
                            }}
                        >
                            Hoje
                        </button>
                        <button
                            type="button"
                            disabled={commandInFlightOrUncertain}
                            onClick={() => movePeriod(1)}
                        >
                            Próximo período
                        </button>
                        <label>
                            Data de referência
                            <input
                                type="date"
                                value={anchorDate}
                                onChange={(event) => {
                                    if (rescheduleLocked()) return;
                                    setSelectedDate(event.target.value);
                                    resetPagination();
                                }}
                            />
                        </label>
                        <label>
                            Serviço
                            <select
                                value={serviceFilter}
                                disabled={commandInFlightOrUncertain}
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
                                disabled={commandInFlightOrUncertain}
                                onChange={(event) =>
                                    changeFilter(
                                        setStatusFilter,
                                        event.target.value,
                                    )
                                }
                            >
                                <option value="">Todos os status</option>
                                <option value="CONFIRMED">Confirmada</option>
                                <option value="CANCELLED_BY_PROVIDER">
                                    Cancelada pelo estabelecimento
                                </option>
                                <option value="COMPLETED">Concluída</option>
                                <option value="NO_SHOW">
                                    Falta registrada
                                </option>
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
                                                                disabled={
                                                                    commandInFlightOrUncertain
                                                                }
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
                            disabled={
                                pageIndex === 0 ||
                                loading ||
                                commandInFlightOrUncertain
                            }
                            onClick={() =>
                                !rescheduleLocked() &&
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
                            disabled={
                                !hasMore ||
                                !nextCursor ||
                                loading ||
                                commandInFlightOrUncertain
                            }
                            onClick={() => {
                                if (rescheduleLocked()) return;
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
                                disabled={commandInFlightOrUncertain}
                                onClick={() => {
                                    if (rescheduleLocked()) return;
                                    setDetails(null);
                                }}
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
                                            {item.previousStartsAt &&
                                                item.newStartsAt && (
                                                    <span>
                                                        {' '}
                                                        · horário:{' '}
                                                        {formatInstant(
                                                            item.previousStartsAt,
                                                            details.timeZone,
                                                            true,
                                                        )}{' '}
                                                        →{' '}
                                                        {formatInstant(
                                                            item.newStartsAt,
                                                            details.timeZone,
                                                            true,
                                                        )}{' '}
                                                        · versão{' '}
                                                        {item.versionBefore} →{' '}
                                                        {item.versionAfter}
                                                    </span>
                                                )}
                                            {(!item.previousStartsAt ||
                                                !item.newStartsAt) &&
                                                item.versionBefore !== null &&
                                                item.versionAfter !== null && (
                                                    <span>
                                                        {' '}
                                                        · versão{' '}
                                                        {
                                                            item.versionBefore
                                                        } → {item.versionAfter}
                                                    </span>
                                                )}
                                            {item.reason && (
                                                <span>
                                                    {' '}
                                                    · Motivo: {item.reason}
                                                </span>
                                            )}
                                        </li>
                                    ))}
                                </ol>
                            )}
                            {details.status === 'CONFIRMED' && (
                                <section aria-labelledby="reschedule-title">
                                    <h4 id="reschedule-title">
                                        Remarcar atendimento
                                    </h4>
                                    {!rescheduleOpen && (
                                        <button
                                            type="button"
                                            disabled={
                                                commandInFlightOrUncertain
                                            }
                                            onClick={() =>
                                                beginReschedule(details)
                                            }
                                        >
                                            Remarcar
                                        </button>
                                    )}
                                    {rescheduleOpen && (
                                        <>
                                            <p>
                                                Horário atual:{' '}
                                                {formatInstant(
                                                    details.startsAt,
                                                    details.timeZone,
                                                    true,
                                                )}{' '}
                                                · ocupação com buffer até{' '}
                                                {formatInstant(
                                                    details.occupiedUntil,
                                                    details.timeZone,
                                                    true,
                                                )}
                                                . Destino no fuso{' '}
                                                {details.timeZone}.
                                            </p>
                                            <label>
                                                Nova data local
                                                <input
                                                    type="date"
                                                    value={rescheduleDate}
                                                    disabled={
                                                        commandInFlightOrUncertain
                                                    }
                                                    onChange={(event) => {
                                                        if (rescheduleLocked())
                                                            return;
                                                        setRescheduleDate(
                                                            event.target.value,
                                                        );
                                                    }}
                                                />
                                            </label>
                                            <label>
                                                Novo horário local
                                                <input
                                                    type="time"
                                                    step="900"
                                                    value={rescheduleTime}
                                                    disabled={
                                                        commandInFlightOrUncertain
                                                    }
                                                    onChange={(event) => {
                                                        if (rescheduleLocked())
                                                            return;
                                                        setRescheduleTime(
                                                            event.target.value,
                                                        );
                                                    }}
                                                />
                                            </label>
                                            {rescheduleOffsets.length > 1 && (
                                                <label>
                                                    Offset para horário ambíguo
                                                    <select
                                                        value={rescheduleOffset}
                                                        disabled={
                                                            commandInFlightOrUncertain
                                                        }
                                                        onChange={(event) => {
                                                            if (
                                                                rescheduleLocked()
                                                            )
                                                                return;
                                                            setRescheduleOffset(
                                                                event.target
                                                                    .value,
                                                            );
                                                        }}
                                                    >
                                                        <option value="">
                                                            Selecione
                                                        </option>
                                                        {rescheduleOffsets.map(
                                                            (offset) => (
                                                                <option
                                                                    key={offset}
                                                                    value={
                                                                        offset
                                                                    }
                                                                >
                                                                    {offset}
                                                                </option>
                                                            ),
                                                        )}
                                                    </select>
                                                </label>
                                            )}
                                            <p>
                                                Duração{' '}
                                                {details.durationMinutes} min;
                                                buffer{' '}
                                                {details.bufferAfterMinutes}{' '}
                                                min. A configuração será
                                                revalidada no servidor.
                                            </p>
                                            {rescheduleUncertain ? (
                                                <button
                                                    type="button"
                                                    disabled={
                                                        rescheduleSaving ||
                                                        !rescheduleUncertain
                                                    }
                                                    onClick={() =>
                                                        void submitReschedule(
                                                            true,
                                                        )
                                                    }
                                                >
                                                    {rescheduleSaving
                                                        ? 'Recuperando…'
                                                        : 'Recuperar / repetir a mesma intenção'}
                                                </button>
                                            ) : (
                                                <button
                                                    type="button"
                                                    disabled={
                                                        commandInFlightOrUncertain ||
                                                        !csrf ||
                                                        !schedule
                                                    }
                                                    onClick={() =>
                                                        void submitReschedule()
                                                    }
                                                >
                                                    {rescheduleSaving
                                                        ? 'Salvando…'
                                                        : 'Confirmar remarcação'}
                                                </button>
                                            )}
                                            {rescheduleError && (
                                                <p role="alert">
                                                    {rescheduleError}
                                                </p>
                                            )}
                                            {rescheduleNotice.includes(
                                                'mudou',
                                            ) && (
                                                <button
                                                    type="button"
                                                    disabled={
                                                        scheduleRefreshing ||
                                                        rescheduleSaving ||
                                                        rescheduleUncertain
                                                    }
                                                    onClick={async () => {
                                                        if (rescheduleLocked())
                                                            return;
                                                        await refreshScheduleRevision(
                                                            false,
                                                        );
                                                        await openDetails(
                                                            details.appointmentId,
                                                        );
                                                        setRescheduleNotice(
                                                            'Versões atualizadas. Revise a data e confirme uma nova intenção.',
                                                        );
                                                    }}
                                                >
                                                    Atualizar versões sem
                                                    alterar o destino
                                                </button>
                                            )}
                                        </>
                                    )}
                                    {rescheduleNotice && (
                                        <output>{rescheduleNotice}</output>
                                    )}
                                </section>
                            )}
                            {details.status === 'CONFIRMED' && (
                                <section aria-labelledby="appointment-outcome-title">
                                    <h4 id="appointment-outcome-title">
                                        Encerrar reserva
                                    </h4>
                                    {!outcomeAction && !outcomeUncertain && (
                                        <div>
                                            <button
                                                type="button"
                                                disabled={
                                                    commandInFlightOrUncertain
                                                }
                                                onClick={() => {
                                                    setOutcomeAction('cancel');
                                                    setOutcomeReason('');
                                                    setOutcomeError('');
                                                }}
                                            >
                                                Cancelar reserva
                                            </button>
                                            <button
                                                type="button"
                                                disabled={
                                                    commandInFlightOrUncertain
                                                }
                                                onClick={() => {
                                                    setOutcomeAction(
                                                        'complete',
                                                    );
                                                    setOutcomeReason('');
                                                    setOutcomeError('');
                                                }}
                                            >
                                                Concluir atendimento
                                            </button>
                                            <button
                                                type="button"
                                                disabled={
                                                    commandInFlightOrUncertain
                                                }
                                                onClick={() => {
                                                    setOutcomeAction(
                                                        'mark-no-show',
                                                    );
                                                    setOutcomeReason('');
                                                    setOutcomeError('');
                                                }}
                                            >
                                                Marcar falta
                                            </button>
                                        </div>
                                    )}
                                    {outcomeAction && !outcomeUncertain && (
                                        <div>
                                            <p>
                                                Confirme{' '}
                                                {outcomeAction === 'cancel'
                                                    ? 'o cancelamento'
                                                    : outcomeAction ===
                                                        'complete'
                                                      ? 'a conclusão'
                                                      : 'o registro de falta'}{' '}
                                                para {details.appointmentId}{' '}
                                                (versão {details.version}). Esta
                                                ação é administrativa e não
                                                representa uma sessão verificada
                                                do cliente.
                                            </p>
                                            <label>
                                                Motivo (opcional)
                                                <input
                                                    value={outcomeReason}
                                                    maxLength={500}
                                                    disabled={outcomeSaving}
                                                    onChange={(event) =>
                                                        setOutcomeReason(
                                                            event.target.value,
                                                        )
                                                    }
                                                />
                                            </label>
                                            <button
                                                type="button"
                                                disabled={
                                                    !csrf ||
                                                    commandInFlightOrUncertain
                                                }
                                                onClick={() =>
                                                    void submitOutcome()
                                                }
                                            >
                                                {outcomeSaving
                                                    ? 'Salvando…'
                                                    : 'Confirmar ação'}
                                            </button>
                                            <button
                                                type="button"
                                                disabled={outcomeSaving}
                                                onClick={() =>
                                                    setOutcomeAction(null)
                                                }
                                            >
                                                Voltar
                                            </button>
                                        </div>
                                    )}
                                    {outcomeUncertain && pendingOutcome && (
                                        <output>
                                            <p>
                                                Resultado incerto para{' '}
                                                {pendingOutcome.action} da
                                                reserva{' '}
                                                {pendingOutcome.appointmentId}.
                                                Não altere a intenção até
                                                reconciliar.
                                            </p>
                                            <button
                                                type="button"
                                                disabled={
                                                    outcomeSaving || !csrf
                                                }
                                                onClick={() =>
                                                    void submitOutcome(true)
                                                }
                                            >
                                                {outcomeSaving
                                                    ? 'Recuperando…'
                                                    : 'Repetir exatamente a mesma ação'}
                                            </button>
                                        </output>
                                    )}
                                    {outcomeError && (
                                        <p role="alert">{outcomeError}</p>
                                    )}
                                    {outcomeConflict && !outcomeUncertain && (
                                        <button
                                            type="button"
                                            disabled={outcomeSaving}
                                            onClick={() =>
                                                void loadDetails(
                                                    details.appointmentId,
                                                )
                                            }
                                        >
                                            Atualizar detalhes da reserva
                                        </button>
                                    )}
                                </section>
                            )}
                            {outcomeNotice && <output>{outcomeNotice}</output>}
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
                                disabled={commandInFlightOrUncertain}
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
