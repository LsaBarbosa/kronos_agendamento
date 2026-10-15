import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';

const weekdays = [
    ['MONDAY', 'Segunda-feira'],
    ['TUESDAY', 'Terça-feira'],
    ['WEDNESDAY', 'Quarta-feira'],
    ['THURSDAY', 'Quinta-feira'],
    ['FRIDAY', 'Sexta-feira'],
    ['SATURDAY', 'Sábado'],
    ['SUNDAY', 'Domingo'],
] as const;

type Weekday = (typeof weekdays)[number][0];
type Csrf = { headerName: string; token: string };
type Period = {
    id: string;
    weekday: Weekday;
    localStart: string;
    localEnd: string;
};
type WeeklySchedule = {
    configured: boolean;
    timeZone: string;
    version: number;
    periods: Omit<Period, 'id'>[];
};
type AvailabilityPolicy = {
    configured: boolean;
    version: number;
    slotStepMinutes: number | null;
    minimumNoticeHours: number | null;
    bookingHorizonDays: number | null;
    cancellationCutoffHours: number | null;
    reminderOffsetHours: number | null;
};
type PolicyDraft = {
    slotStepMinutes: string;
    minimumNoticeHours: string;
    bookingHorizonDays: string;
    cancellationCutoffHours: string;
    reminderOffsetHours: string;
};
type ApiFailure = { code: string; message: string };

const emptyPolicy: PolicyDraft = {
    slotStepMinutes: '',
    minimumNoticeHours: '',
    bookingHorizonDays: '',
    cancellationCutoffHours: '',
    reminderOffsetHours: '',
};

async function apiFailure(response: Response): Promise<ApiFailure> {
    try {
        const body = (await response.json()) as {
            error?: { code?: string; message?: string };
        };
        return {
            code: body.error?.code ?? 'HTTP_ERROR',
            message: body.error?.message ?? `Falha HTTP ${response.status}.`,
        };
    } catch {
        return {
            code: 'HTTP_ERROR',
            message: `Falha HTTP ${response.status}.`,
        };
    }
}

function canonicalPeriods(periods: Period[]) {
    return [...periods]
        .sort((left, right) => {
            const day =
                weekdays.findIndex(([value]) => value === left.weekday) -
                weekdays.findIndex(([value]) => value === right.weekday);
            if (day !== 0) return day;
            return (
                left.localStart.localeCompare(right.localStart) ||
                left.localEnd.localeCompare(right.localEnd)
            );
        })
        .map(({ weekday, localStart, localEnd }) => ({
            weekday,
            localStart,
            localEnd,
        }));
}

export function SchedulePanel({
    enabled,
    csrf,
    onSessionInvalid,
}: {
    enabled: boolean;
    csrf: Csrf | null;
    onSessionInvalid: (status: number) => void;
}) {
    const [loaded, setLoaded] = useState(false);
    const [loading, setLoading] = useState(true);
    const [weekly, setWeekly] = useState<WeeklySchedule | null>(null);
    const [policy, setPolicy] = useState<AvailabilityPolicy | null>(null);
    const [periods, setPeriods] = useState<Period[]>([]);
    const [policyDraft, setPolicyDraft] = useState<PolicyDraft>(emptyPolicy);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [periodError, setPeriodError] = useState('');
    const [conflict, setConflict] = useState(false);
    const [savingWeekly, setSavingWeekly] = useState(false);
    const [savingPolicy, setSavingPolicy] = useState(false);
    const pendingKeys = useRef(new Map<string, string>());

    const loadConfiguration = useCallback(
        async (discardDraft: boolean) => {
            if (!enabled) return;
            try {
                const [weeklyResponse, policyResponse] = await Promise.all([
                    fetch(apiBaseUrl + '/v1/admin/schedule/weekly-periods', {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    }),
                    fetch(apiBaseUrl + '/v1/admin/availability-policy', {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    }),
                ]);
                for (const response of [weeklyResponse, policyResponse]) {
                    if (response.status === 401 || response.status === 403) {
                        onSessionInvalid(response.status);
                        return;
                    }
                    if (!response.ok) {
                        const failure = await apiFailure(response);
                        setError(failure.message);
                        return;
                    }
                }
                const nextWeekly =
                    (await weeklyResponse.json()) as WeeklySchedule;
                const nextPolicy =
                    (await policyResponse.json()) as AvailabilityPolicy;
                setWeekly(nextWeekly);
                setPolicy(nextPolicy);
                if (discardDraft || !loaded) {
                    setPeriods(
                        nextWeekly.periods.map((period) => ({
                            ...period,
                            id: crypto.randomUUID(),
                        })),
                    );
                    setPolicyDraft(
                        nextPolicy.configured
                            ? {
                                  slotStepMinutes: String(
                                      nextPolicy.slotStepMinutes,
                                  ),
                                  minimumNoticeHours: String(
                                      nextPolicy.minimumNoticeHours,
                                  ),
                                  bookingHorizonDays: String(
                                      nextPolicy.bookingHorizonDays,
                                  ),
                                  cancellationCutoffHours: String(
                                      nextPolicy.cancellationCutoffHours,
                                  ),
                                  reminderOffsetHours: String(
                                      nextPolicy.reminderOffsetHours,
                                  ),
                              }
                            : emptyPolicy,
                    );
                }
                setConflict(false);
                setNotice('Configuração recarregada.');
                setLoaded(true);
            } catch {
                setError('Não foi possível carregar a grade e as políticas.');
            } finally {
                setLoading(false);
            }
        },
        [enabled, loaded, onSessionInvalid],
    );

    useEffect(() => {
        if (enabled && !loaded) {
            void Promise.resolve().then(() => loadConfiguration(false));
        }
    }, [enabled, loaded, loadConfiguration]);

    function addPeriod(weekday: Weekday) {
        setPeriods((current) => [
            ...current,
            { id: crypto.randomUUID(), weekday, localStart: '', localEnd: '' },
        ]);
        setPeriodError('');
    }

    function updatePeriod(
        id: string,
        field: 'localStart' | 'localEnd',
        value: string,
    ) {
        setPeriods((current) =>
            current.map((period) =>
                period.id === id ? { ...period, [field]: value } : period,
            ),
        );
        setPeriodError('');
    }

    function removePeriod(id: string) {
        setPeriods((current) => current.filter((period) => period.id !== id));
        setPeriodError('');
    }

    function validatePeriods(): string {
        for (const [weekday, label] of weekdays) {
            const dayPeriods = canonicalPeriods(
                periods.filter((period) => period.weekday === weekday),
            );
            for (const period of dayPeriods) {
                if (
                    !period.localStart ||
                    !period.localEnd ||
                    period.localStart >= period.localEnd
                ) {
                    return `${label}: informe início e fim no mesmo dia, com início anterior ao fim.`;
                }
            }
            for (let index = 1; index < dayPeriods.length; index += 1) {
                if (
                    dayPeriods[index].localStart <
                    dayPeriods[index - 1].localEnd
                ) {
                    return `${label}: os períodos não podem se sobrepor.`;
                }
            }
        }
        return '';
    }

    async function saveWeekly(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!csrf || !weekly) return;
        const validation = validatePeriods();
        if (validation) {
            setPeriodError(validation);
            return;
        }
        const body = {
            expectedVersion: weekly.version,
            periods: canonicalPeriods(periods),
        };
        const scope = 'weekly-schedule';
        const fingerprint = JSON.stringify([scope, body]);
        const key = commandKey(pendingKeys.current, scope, body);
        setSavingWeekly(true);
        setError('');
        setNotice('');
        setPeriodError('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/schedule/weekly-periods',
                {
                    method: 'PUT',
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
            if (response.status === 409) {
                const failure = await apiFailure(response);
                if (failure.code === 'VERSION_CONFLICT') {
                    setConflict(true);
                    setError(
                        'Outra sessão salvou esta grade. Seus campos foram preservados. Recarregue explicitamente ou reveja antes de salvar.',
                    );
                    return;
                }
            }
            if (!response.ok) {
                const failure = await apiFailure(response);
                setError(failure.message);
                return;
            }
            const saved = (await response.json()) as WeeklySchedule;
            setWeekly(saved);
            pendingKeys.current.delete(fingerprint);
            setNotice('Grade semanal salva.');
        } catch {
            setError(
                'Não foi possível confirmar a resposta. Tente novamente; o mesmo comando reutilizará a mesma chave.',
            );
        } finally {
            setSavingWeekly(false);
        }
    }

    async function savePolicy(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!csrf || !policy) return;
        const body = {
            expectedVersion: policy.version,
            slotStepMinutes: Number(policyDraft.slotStepMinutes),
            minimumNoticeHours: Number(policyDraft.minimumNoticeHours),
            bookingHorizonDays: Number(policyDraft.bookingHorizonDays),
            cancellationCutoffHours: Number(
                policyDraft.cancellationCutoffHours,
            ),
            reminderOffsetHours: Number(policyDraft.reminderOffsetHours),
        };
        const scope = 'availability-policy';
        const fingerprint = JSON.stringify([scope, body]);
        const key = commandKey(pendingKeys.current, scope, body);
        setSavingPolicy(true);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/availability-policy',
                {
                    method: 'PUT',
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
            if (response.status === 409) {
                const failure = await apiFailure(response);
                if (failure.code === 'VERSION_CONFLICT') {
                    setConflict(true);
                    setError(
                        'Outra sessão salvou estas políticas. Seus campos foram preservados. Recarregue explicitamente ou reveja antes de salvar.',
                    );
                    return;
                }
            }
            if (!response.ok) {
                const failure = await apiFailure(response);
                setError(failure.message);
                return;
            }
            const saved = (await response.json()) as AvailabilityPolicy;
            setPolicy(saved);
            pendingKeys.current.delete(fingerprint);
            setNotice('Políticas salvas.');
        } catch {
            setError(
                'Não foi possível confirmar a resposta. Tente novamente; o mesmo comando reutilizará a mesma chave.',
            );
        } finally {
            setSavingPolicy(false);
        }
    }

    function recoverConflict() {
        if (
            conflict &&
            window.confirm(
                'Recarregar os dados persistidos descartará os campos locais desta tela. Deseja continuar?',
            )
        ) {
            setLoading(true);
            setError('');
            void loadConfiguration(true);
        }
    }

    if (!enabled) {
        return <section hidden aria-label="Grade semanal e políticas" />;
    }

    return (
        <section aria-labelledby="schedule-title">
            <h2 id="schedule-title">Grade semanal e políticas</h2>
            <p>
                A grade usa horários locais de{' '}
                <strong>{weekly?.timeZone ?? '…'}</strong>. Configurar esta tela
                não publica o negócio.
            </p>
            {loading && <p>Carregando grade e políticas…</p>}
            {error && <p role="alert">{error}</p>}
            {!loading && !loaded && error && (
                <button
                    type="button"
                    onClick={() => {
                        setLoading(true);
                        setError('');
                        void loadConfiguration(false);
                    }}
                >
                    Tentar carregar novamente
                </button>
            )}
            {notice && <output aria-live="polite">{notice}</output>}
            {!loading && loaded && weekly && policy && (
                <>
                    {!weekly.configured && (
                        <p>
                            A grade ainda não foi configurada. Dias sem períodos
                            ficarão fechados.
                        </p>
                    )}
                    <form onSubmit={(event) => void saveWeekly(event)}>
                        <h3>Horários por dia</h3>
                        {weekdays.map(([weekday, label]) => {
                            const dayPeriods = periods.filter(
                                (period) => period.weekday === weekday,
                            );
                            return (
                                <fieldset key={weekday}>
                                    <legend>{label}</legend>
                                    {dayPeriods.length === 0 && (
                                        <p>Sem atendimento neste dia.</p>
                                    )}
                                    {dayPeriods.map((period, index) => (
                                        <div key={period.id}>
                                            <label>
                                                Início do período {index + 1}
                                                <input
                                                    aria-label={`${label} início ${index + 1}`}
                                                    type="time"
                                                    required
                                                    value={period.localStart}
                                                    onChange={(event) =>
                                                        updatePeriod(
                                                            period.id,
                                                            'localStart',
                                                            event.target.value,
                                                        )
                                                    }
                                                />
                                            </label>
                                            <label>
                                                Fim do período {index + 1}
                                                <input
                                                    aria-label={`${label} fim ${index + 1}`}
                                                    type="time"
                                                    required
                                                    value={period.localEnd}
                                                    onChange={(event) =>
                                                        updatePeriod(
                                                            period.id,
                                                            'localEnd',
                                                            event.target.value,
                                                        )
                                                    }
                                                />
                                            </label>
                                            <button
                                                type="button"
                                                onClick={() =>
                                                    removePeriod(period.id)
                                                }
                                            >
                                                Remover período
                                            </button>
                                        </div>
                                    ))}
                                    <button
                                        type="button"
                                        onClick={() => addPeriod(weekday)}
                                    >
                                        Adicionar período
                                    </button>
                                </fieldset>
                            );
                        })}
                        {periodError && <p role="alert">{periodError}</p>}
                        <button type="submit" disabled={!csrf || savingWeekly}>
                            {savingWeekly
                                ? 'Salvando grade…'
                                : 'Salvar grade semanal'}
                        </button>
                    </form>
                    {!policy.configured && (
                        <p>
                            As políticas ainda não foram configuradas; não há
                            valores padrão.
                        </p>
                    )}
                    <form onSubmit={(event) => void savePolicy(event)}>
                        <h3>Políticas de agendamento</h3>
                        <label>
                            Intervalo inicial dos horários (minutos)
                            <select
                                required
                                value={policyDraft.slotStepMinutes}
                                onChange={(event) =>
                                    setPolicyDraft({
                                        ...policyDraft,
                                        slotStepMinutes: event.target.value,
                                    })
                                }
                            >
                                <option value="" disabled>
                                    Selecione
                                </option>
                                {[15, 30, 60].map((value) => (
                                    <option key={value} value={value}>
                                        {value} minutos
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label>
                            Antecedência mínima (horas)
                            <input
                                required
                                type="number"
                                min="1"
                                max="24"
                                step="1"
                                value={policyDraft.minimumNoticeHours}
                                onChange={(event) =>
                                    setPolicyDraft({
                                        ...policyDraft,
                                        minimumNoticeHours: event.target.value,
                                    })
                                }
                            />
                        </label>
                        <label>
                            Horizonte máximo para agendamento (dias)
                            <select
                                required
                                value={policyDraft.bookingHorizonDays}
                                onChange={(event) =>
                                    setPolicyDraft({
                                        ...policyDraft,
                                        bookingHorizonDays: event.target.value,
                                    })
                                }
                            >
                                <option value="" disabled>
                                    Selecione
                                </option>
                                {[7, 30, 60, 90].map((value) => (
                                    <option key={value} value={value}>
                                        {value} dias
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label>
                            Limite de cancelamento pelo cliente (horas antes)
                            <input
                                required
                                type="number"
                                min="2"
                                max="24"
                                step="1"
                                value={policyDraft.cancellationCutoffHours}
                                onChange={(event) =>
                                    setPolicyDraft({
                                        ...policyDraft,
                                        cancellationCutoffHours:
                                            event.target.value,
                                    })
                                }
                            />
                        </label>
                        <label>
                            Lembrete único antes do atendimento (horas)
                            <select
                                required
                                value={policyDraft.reminderOffsetHours}
                                onChange={(event) =>
                                    setPolicyDraft({
                                        ...policyDraft,
                                        reminderOffsetHours: event.target.value,
                                    })
                                }
                            >
                                <option value="" disabled>
                                    Selecione
                                </option>
                                {[2, 6, 12, 24].map((value) => (
                                    <option key={value} value={value}>
                                        {value} horas
                                    </option>
                                ))}
                            </select>
                        </label>
                        <button type="submit" disabled={!csrf || savingPolicy}>
                            {savingPolicy
                                ? 'Salvando políticas…'
                                : 'Salvar políticas'}
                        </button>
                    </form>
                    {conflict && (
                        <button type="button" onClick={recoverConflict}>
                            Recarregar versão atual (substitui campos locais)
                        </button>
                    )}
                    <button
                        type="button"
                        disabled={loading}
                        onClick={() => {
                            setLoading(true);
                            setError('');
                            void loadConfiguration(true);
                        }}
                    >
                        Recarregar configuração persistida
                    </button>
                </>
            )}
        </section>
    );
}
