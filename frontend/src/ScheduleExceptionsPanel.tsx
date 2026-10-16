import { useCallback, useEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';
import { validLocalTimeOffsets } from './localTimeOffsets';

type Csrf = { headerName: string; token: string };
type Period = {
    localStart: string;
    localEnd: string;
    localStartOffset?: string | null;
    localEndOffset?: string | null;
};
type Exception = {
    exceptionId: string;
    localDate: string;
    kind: 'CLOSED' | 'SPECIAL';
    periods: Period[];
    reason: string | null;
    version: number;
    timeZone: string;
};
type Block = {
    blockId: string;
    startsAt: string;
    endsAt: string;
    timeZone: string;
    reason: string;
    status: 'ACTIVE' | 'CANCELLED';
    version: number;
    cancellationReason: string | null;
};

function localDateToday() {
    const date = new Date();
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000)
        .toISOString()
        .slice(0, 10);
}

function localDateOffset(days: number) {
    const date = new Date();
    date.setDate(date.getDate() + days);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000)
        .toISOString()
        .slice(0, 10);
}

function tenantDate(timeZone: string, offsetDays = 0) {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).formatToParts(new Date());
    const values = Object.fromEntries(
        parts.map((part) => [part.type, part.value]),
    );
    const date = new Date(
        Date.UTC(
            Number(values.year),
            Number(values.month) - 1,
            Number(values.day) + offsetDays,
        ),
    );
    return date.toISOString().slice(0, 10);
}

async function failure(response: Response) {
    try {
        const body = await response.json();
        return body.error?.message ?? `Falha HTTP ${response.status}.`;
    } catch {
        return `Falha HTTP ${response.status}.`;
    }
}

function formatTenantInstant(instant: string, timeZone: string) {
    return new Intl.DateTimeFormat('pt-BR', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
        timeZone,
        timeZoneName: 'short',
    }).format(new Date(instant));
}

export function ScheduleExceptionsPanel({
    enabled,
    csrf,
    onSessionInvalid,
}: {
    enabled: boolean;
    csrf: Csrf | null;
    onSessionInvalid: (status: number) => void;
}) {
    const [date, setDate] = useState(localDateToday);
    const [rangeFrom, setRangeFrom] = useState(localDateToday);
    const [rangeTo, setRangeTo] = useState(() => localDateOffset(30));
    const [exceptions, setExceptions] = useState<Exception[]>([]);
    const [blocks, setBlocks] = useState<Block[]>([]);
    const [zone, setZone] = useState('');
    const [kind, setKind] = useState<'CLOSED' | 'SPECIAL'>('CLOSED');
    const [periods, setPeriods] = useState<Period[]>([]);
    const [reason, setReason] = useState('');
    const [blockStart, setBlockStart] = useState('');
    const [blockEnd, setBlockEnd] = useState('');
    const [blockReason, setBlockReason] = useState('');
    const [cancelReasons, setCancelReasons] = useState<Record<string, string>>(
        {},
    );
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [conflict, setConflict] = useState(false);
    const pendingKeys = useRef(new Map<string, string>());
    const sessionInvalidRef = useRef(onSessionInvalid);
    const initializedZone = useRef('');
    const selectedException = exceptions.find(
        (item) => item.localDate === date,
    );

    useEffect(() => {
        sessionInvalidRef.current = onSessionInvalid;
    }, [onSessionInvalid]);

    const load = useCallback(async () => {
        if (!enabled) return;
        try {
            const query = `?from=${encodeURIComponent(rangeFrom)}&to=${encodeURIComponent(rangeTo)}`;
            const [exceptionResponse, blockResponse, scheduleResponse] =
                await Promise.all([
                    fetch(
                        apiBaseUrl + '/v1/admin/schedule/exceptions' + query,
                        {
                            credentials: 'same-origin',
                            cache: 'no-store',
                        },
                    ),
                    fetch(apiBaseUrl + '/v1/admin/blocked-periods' + query, {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    }),
                    fetch(apiBaseUrl + '/v1/admin/schedule/weekly-periods', {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    }),
                ]);
            for (const response of [
                exceptionResponse,
                blockResponse,
                scheduleResponse,
            ]) {
                if (response.status === 401 || response.status === 403) {
                    sessionInvalidRef.current(response.status);
                    return;
                }
                if (!response.ok) throw new Error(await failure(response));
            }
            const exceptionList = (await exceptionResponse.json()) as {
                from: string;
                to: string;
                timeZone: string;
                exceptions: Exception[];
            };
            const nextExceptions = exceptionList.exceptions;
            const nextBlocks = (await blockResponse.json()) as Block[];
            const schedule = (await scheduleResponse.json()) as {
                timeZone: string;
            };
            setExceptions(nextExceptions);
            setBlocks(nextBlocks);
            const nextZone =
                schedule.timeZone ??
                nextExceptions[0]?.timeZone ??
                nextBlocks[0]?.timeZone;
            if (nextZone) setZone(nextZone);
            if (nextZone && initializedZone.current !== nextZone) {
                initializedZone.current = nextZone;
                const tenantToday = tenantDate(nextZone);
                setDate(tenantToday);
                setRangeFrom(tenantToday);
                setRangeTo(tenantDate(nextZone, 30));
            }
            const refreshed = nextExceptions.find(
                (item) => item.localDate === date,
            );
            if (refreshed) {
                setKind(refreshed.kind);
                setPeriods(refreshed.periods);
                setReason(refreshed.reason ?? '');
            }
            setConflict(false);
        } catch (cause) {
            setError(
                cause instanceof Error
                    ? cause.message
                    : 'Falha de rede ao carregar exceções.',
            );
        } finally {
            setLoading(false);
        }
    }, [date, enabled, rangeFrom, rangeTo]);

    useEffect(() => {
        const scheduled = window.setTimeout(() => {
            void load();
        }, 0);
        return () => window.clearTimeout(scheduled);
    }, [load]);

    function chooseDate(value: string) {
        if (value !== date) setLoading(true);
        setError('');
        setDate(value);
        setRangeFrom(value);
        setRangeTo(value);
        const existing = exceptions.find((item) => item.localDate === value);
        setKind(existing?.kind ?? 'CLOSED');
        setPeriods(existing?.periods ?? []);
        setReason(existing?.reason ?? '');
        setNotice('');
    }

    function addPeriod() {
        setPeriods((items) => [
            ...items,
            { localStart: '09:00', localEnd: '12:00' },
        ]);
    }

    function updatePeriod(index: number, field: keyof Period, value: string) {
        setPeriods((items) =>
            items.map((item, itemIndex) =>
                itemIndex === index
                    ? field === 'localStart'
                        ? {
                              ...item,
                              localStart: value,
                              localStartOffset: null,
                          }
                        : field === 'localEnd'
                          ? {
                                ...item,
                                localEnd: value,
                                localEndOffset: null,
                            }
                          : { ...item, [field]: value || null }
                    : item,
            ),
        );
    }

    async function saveException() {
        if (!csrf) return setError('Proteção CSRF indisponível.');
        const body = {
            localDate: date,
            kind,
            periods:
                kind === 'CLOSED'
                    ? []
                    : [...periods].sort((a, b) =>
                          a.localStart.localeCompare(b.localStart),
                      ),
            reason: reason.trim() || null,
            ...(selectedException
                ? { expectedVersion: selectedException.version }
                : {}),
        };
        const scope = selectedException
            ? 'exception:' + selectedException.exceptionId
            : 'exception:' + date;
        const key = commandKey(pendingKeys.current, scope, body);
        setSaving(true);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/admin/schedule/exceptions' +
                    (selectedException
                        ? '/' + selectedException.exceptionId
                        : ''),
                {
                    method: selectedException ? 'PATCH' : 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': key,
                    },
                    body: JSON.stringify(body),
                },
            );
            if (response.status === 401 || response.status === 403)
                return sessionInvalidRef.current(response.status);
            if (response.status === 409) setConflict(true);
            if (!response.ok) throw new Error(await failure(response));
            pendingKeys.current.delete(JSON.stringify([scope, body]));
            setNotice('Exceção persistida.');
            await load();
        } catch (cause) {
            setError(
                cause instanceof Error
                    ? cause.message
                    : 'Falha de rede. O mesmo comando pode ser tentado novamente.',
            );
        } finally {
            setSaving(false);
        }
    }

    async function removeException() {
        if (!selectedException || !csrf) return;
        const body = { expectedVersion: selectedException.version };
        const scope = 'exception-remove:' + selectedException.exceptionId;
        const key = commandKey(pendingKeys.current, scope, body);
        setSaving(true);
        setError('');
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/admin/schedule/exceptions/' +
                    selectedException.exceptionId,
                {
                    method: 'DELETE',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': key,
                    },
                    body: JSON.stringify(body),
                },
            );
            if (response.status === 401 || response.status === 403)
                return sessionInvalidRef.current(response.status);
            if (response.status === 409) setConflict(true);
            if (!response.ok) throw new Error(await failure(response));
            pendingKeys.current.delete(JSON.stringify([scope, body]));
            setNotice(
                'Exceção removida; a grade semanal volta a ser aplicada.',
            );
            setKind('CLOSED');
            setPeriods([]);
            setReason('');
            await load();
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : 'Falha de rede.');
        } finally {
            setSaving(false);
        }
    }

    async function createBlock() {
        if (!csrf) return setError('Proteção CSRF indisponível.');
        const body = {
            startsAt: blockStart,
            endsAt: blockEnd,
            reason: blockReason.trim(),
        };
        const scope = 'block:' + blockStart;
        const key = commandKey(pendingKeys.current, scope, body);
        setSaving(true);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/blocked-periods',
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
            if (response.status === 401 || response.status === 403)
                return sessionInvalidRef.current(response.status);
            if (response.status === 409) setConflict(true);
            if (!response.ok) throw new Error(await failure(response));
            pendingKeys.current.delete(JSON.stringify([scope, body]));
            setNotice('Bloqueio persistido.');
            setBlockReason('');
            await load();
        } catch (cause) {
            setError(
                cause instanceof Error
                    ? cause.message
                    : 'Falha de rede. O mesmo comando pode ser tentado novamente.',
            );
        } finally {
            setSaving(false);
        }
    }

    async function cancelBlock(block: Block) {
        if (!csrf) return setError('Proteção CSRF indisponível.');
        const reasonText = cancelReasons[block.blockId] ?? '';
        const body = {
            expectedVersion: block.version,
            reason: reasonText.trim(),
        };
        const scope = 'block-cancel:' + block.blockId;
        const key = commandKey(pendingKeys.current, scope, body);
        setSaving(true);
        setError('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/blocked-periods/' + block.blockId,
                {
                    method: 'DELETE',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': key,
                    },
                    body: JSON.stringify(body),
                },
            );
            if (response.status === 401 || response.status === 403)
                return sessionInvalidRef.current(response.status);
            if (response.status === 409) setConflict(true);
            if (!response.ok) throw new Error(await failure(response));
            pendingKeys.current.delete(JSON.stringify([scope, body]));
            setNotice('Bloqueio cancelado; o histórico foi preservado.');
            await load();
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : 'Falha de rede.');
        } finally {
            setSaving(false);
        }
    }

    if (!enabled) return null;
    return (
        <section aria-labelledby="exceptions-title">
            <h2 id="exceptions-title">Exceções e bloqueios</h2>
            <p>
                {zone
                    ? `Fuso horário do tenant: ${zone}.`
                    : 'O fuso será carregado da configuração do tenant.'}{' '}
                Horários locais em intervalos [início, fim); limites de 15
                minutos.
            </p>
            <label>
                Início da consulta{' '}
                <input
                    type="date"
                    value={rangeFrom}
                    onChange={(event) => {
                        setLoading(true);
                        setRangeFrom(event.target.value);
                    }}
                />
            </label>
            <label>
                Fim da consulta{' '}
                <input
                    type="date"
                    value={rangeTo}
                    onChange={(event) => {
                        setLoading(true);
                        setRangeTo(event.target.value);
                    }}
                />
            </label>
            <label>
                Data local{' '}
                <input
                    type="date"
                    value={date}
                    onChange={(event) => chooseDate(event.target.value)}
                />
            </label>
            <button
                type="button"
                onClick={() => {
                    setLoading(true);
                    setError('');
                    void load();
                }}
                disabled={loading}
            >
                Recarregar
            </button>
            {loading && <p>Carregando exceções e bloqueios…</p>}
            {!loading &&
                !exceptions.some((item) => item.localDate === date) && (
                    <p>
                        Nenhuma exceção nesta data; aplica-se a grade semanal.
                    </p>
                )}
            {exceptions.length > 0 && (
                <ul aria-label="Exceções no período">
                    {exceptions.map((item) => (
                        <li key={item.exceptionId}>
                            <button
                                type="button"
                                onClick={() => chooseDate(item.localDate)}
                            >
                                Editar {item.localDate}: {item.kind} (v
                                {item.version})
                            </button>
                        </li>
                    ))}
                </ul>
            )}
            <label>
                Tratamento da data
                <select
                    value={kind}
                    onChange={(event) =>
                        setKind(event.target.value as typeof kind)
                    }
                >
                    <option value="CLOSED">Fechada</option>
                    <option value="SPECIAL">Horários especiais</option>
                </select>
            </label>
            {kind === 'SPECIAL' && (
                <div>
                    <p>
                        Esta lista substitui a grade semanal. Períodos
                        adjacentes são permitidos; sobreposição e cruzamento de
                        meia-noite não.
                    </p>
                    {periods.map((period, index) => (
                        <fieldset key={index}>
                            <legend>Período {index + 1}</legend>
                            <label>
                                Início local{' '}
                                <input
                                    type="time"
                                    step="900"
                                    value={period.localStart}
                                    onChange={(event) =>
                                        updatePeriod(
                                            index,
                                            'localStart',
                                            event.target.value,
                                        )
                                    }
                                />
                            </label>
                            {validLocalTimeOffsets(
                                date,
                                period.localStart,
                                zone,
                            ).length > 1 && (
                                <label>
                                    Ocorrência do início{' '}
                                    <select
                                        required
                                        value={period.localStartOffset ?? ''}
                                        onChange={(event) =>
                                            updatePeriod(
                                                index,
                                                'localStartOffset',
                                                event.target.value,
                                            )
                                        }
                                    >
                                        <option value="">
                                            Selecione o offset
                                        </option>
                                        {validLocalTimeOffsets(
                                            date,
                                            period.localStart,
                                            zone,
                                        ).map((offset) => (
                                            <option key={offset} value={offset}>
                                                {offset}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            )}
                            {period.localStart &&
                                validLocalTimeOffsets(
                                    date,
                                    period.localStart,
                                    zone,
                                ).length === 0 && (
                                    <p role="alert">
                                        Esse horário de início não existe nessa
                                        data e zona por causa da mudança de
                                        horário.
                                    </p>
                                )}
                            <label>
                                Fim local{' '}
                                <input
                                    type="time"
                                    step="900"
                                    value={period.localEnd}
                                    onChange={(event) =>
                                        updatePeriod(
                                            index,
                                            'localEnd',
                                            event.target.value,
                                        )
                                    }
                                />
                            </label>
                            {validLocalTimeOffsets(date, period.localEnd, zone)
                                .length > 1 && (
                                <label>
                                    Ocorrência do fim{' '}
                                    <select
                                        required
                                        value={period.localEndOffset ?? ''}
                                        onChange={(event) =>
                                            updatePeriod(
                                                index,
                                                'localEndOffset',
                                                event.target.value,
                                            )
                                        }
                                    >
                                        <option value="">
                                            Selecione o offset
                                        </option>
                                        {validLocalTimeOffsets(
                                            date,
                                            period.localEnd,
                                            zone,
                                        ).map((offset) => (
                                            <option key={offset} value={offset}>
                                                {offset}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            )}
                            {period.localEnd &&
                                validLocalTimeOffsets(
                                    date,
                                    period.localEnd,
                                    zone,
                                ).length === 0 && (
                                    <p role="alert">
                                        Esse horário de fim não existe nessa
                                        data e zona por causa da mudança de
                                        horário.
                                    </p>
                                )}
                            <button
                                type="button"
                                onClick={() =>
                                    setPeriods((items) =>
                                        items.filter(
                                            (_, itemIndex) =>
                                                itemIndex !== index,
                                        ),
                                    )
                                }
                            >
                                Remover período
                            </button>
                        </fieldset>
                    ))}
                    <button type="button" onClick={addPeriod}>
                        Adicionar período
                    </button>
                </div>
            )}
            <label>
                Motivo (opcional){' '}
                <input
                    maxLength={500}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                />
            </label>
            <button
                type="button"
                disabled={
                    !csrf ||
                    saving ||
                    loading ||
                    (kind === 'SPECIAL' && periods.length === 0)
                }
                onClick={() => void saveException()}
            >
                {saving
                    ? 'Salvando…'
                    : selectedException
                      ? 'Salvar exceção'
                      : 'Salvar exceção'}
            </button>
            {selectedException && (
                <button
                    type="button"
                    disabled={!csrf || saving || loading}
                    onClick={() => void removeException()}
                >
                    Remover exceção e restaurar grade
                </button>
            )}
            <h3>Bloqueios que atravessam esta data</h3>
            {blocks.length === 0 ? (
                <p>Nenhum bloqueio no intervalo.</p>
            ) : (
                <ul>
                    {blocks.map((block) => (
                        <li key={block.blockId}>
                            <p>
                                {formatTenantInstant(
                                    block.startsAt,
                                    block.timeZone,
                                )}{' '}
                                —{' '}
                                {formatTenantInstant(
                                    block.endsAt,
                                    block.timeZone,
                                )}{' '}
                                ({block.timeZone}) · {block.status} · motivo:{' '}
                                {block.reason}
                                {block.cancellationReason
                                    ? ` · cancelamento: ${block.cancellationReason}`
                                    : ''}
                            </p>
                            {block.status === 'ACTIVE' && (
                                <>
                                    <label>
                                        Motivo do cancelamento{' '}
                                        <input
                                            required
                                            maxLength={500}
                                            value={
                                                cancelReasons[block.blockId] ??
                                                ''
                                            }
                                            onChange={(event) =>
                                                setCancelReasons((current) => ({
                                                    ...current,
                                                    [block.blockId]:
                                                        event.target.value,
                                                }))
                                            }
                                        />
                                    </label>
                                    <button
                                        type="button"
                                        disabled={
                                            !csrf ||
                                            saving ||
                                            !(
                                                cancelReasons[block.blockId] ??
                                                ''
                                            ).trim()
                                        }
                                        onClick={() => void cancelBlock(block)}
                                    >
                                        Cancelar bloqueio
                                    </button>
                                </>
                            )}
                        </li>
                    ))}
                </ul>
            )}
            <h3>Criar bloqueio</h3>
            <p>
                Data/hora local do tenant. Horários inexistentes ou ambíguos por
                mudança de horário são rejeitados; não são ajustados
                automaticamente.
            </p>
            <label>
                Início local{' '}
                <input
                    type="datetime-local"
                    step="900"
                    value={blockStart}
                    onChange={(event) => setBlockStart(event.target.value)}
                />
            </label>
            <label>
                Fim local{' '}
                <input
                    type="datetime-local"
                    step="900"
                    value={blockEnd}
                    onChange={(event) => setBlockEnd(event.target.value)}
                />
            </label>
            <label>
                Motivo obrigatório{' '}
                <input
                    required
                    maxLength={500}
                    value={blockReason}
                    onChange={(event) => setBlockReason(event.target.value)}
                />
            </label>
            <button
                type="button"
                disabled={
                    !csrf ||
                    saving ||
                    !blockStart ||
                    !blockEnd ||
                    !blockReason.trim()
                }
                onClick={() => void createBlock()}
            >
                {saving ? 'Salvando…' : 'Criar bloqueio'}
            </button>
            {conflict && (
                <p role="alert">
                    A configuração mudou. Seu formulário foi preservado;
                    recarregue explicitamente antes de decidir como resolver.
                </p>
            )}
            {error && <p role="alert">{error}</p>}
            {notice && <output aria-live="polite">{notice}</output>}
        </section>
    );
}
