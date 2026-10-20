import { useCallback, useEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';

type Policy = {
    configured: boolean;
    policyStatus: 'CONFIGURED' | 'NOT_CONFIGURED';
    version: number;
    threshold: number | null;
    restrictionStatus: 'NOT_TRACKED';
};
type Csrf = { headerName: string; token: string };

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

export function ClientNoShowPolicyPanel({
    enabled,
    csrf,
    onSessionInvalid,
}: {
    enabled: boolean;
    csrf: Csrf | null;
    onSessionInvalid: (status: number) => void;
}) {
    const [policy, setPolicy] = useState<Policy | null>(null);
    const [threshold, setThreshold] = useState('');
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [uncertain, setUncertain] = useState(false);
    const pending = useRef<{ body: object; key: string } | null>(null);
    const keys = useRef(new Map<string, string>());
    const readGeneration = useRef(0);
    const readController = useRef<AbortController | null>(null);

    function invalidateReads() {
        readGeneration.current += 1;
        readController.current?.abort();
        readController.current = null;
        setLoading(false);
    }

    const load = useCallback(async () => {
        if (!enabled || pending.current) return;
        const generation = ++readGeneration.current;
        readController.current?.abort();
        const controller = new AbortController();
        readController.current = controller;
        setLoading(true);
        setError('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/no-show-policy',
                {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    signal: controller.signal,
                },
            );
            if (generation !== readGeneration.current) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (!response.ok) throw new Error(await errorMessage(response));
            const result = (await response.json()) as Policy;
            if (generation !== readGeneration.current) return;
            setPolicy(result);
            setThreshold(result.configured ? String(result.threshold) : '');
        } catch (loadError) {
            if (generation === readGeneration.current) {
                setError(
                    loadError instanceof Error
                        ? loadError.message
                        : 'Não foi possível carregar a política.',
                );
            }
        } finally {
            if (generation === readGeneration.current) {
                readController.current = null;
                setLoading(false);
            }
        }
    }, [enabled, onSessionInvalid]);

    useEffect(() => {
        if (!enabled) return;
        const timer = window.setTimeout(() => void load(), 0);
        return () => {
            window.clearTimeout(timer);
            readGeneration.current += 1;
            readController.current?.abort();
            readController.current = null;
        };
    }, [enabled, load]);

    async function save() {
        if (!csrf || !policy) {
            setError('Sessão administrativa ou proteção CSRF indisponível.');
            return;
        }
        const body = pending.current?.body ?? {
            expectedVersion: policy.version,
            threshold: Number(threshold),
        };
        if (!pending.current && !/^[1-5]$/.test(threshold)) {
            setError('Informe um limite inteiro entre 1 e 5.');
            return;
        }
        const key =
            pending.current?.key ??
            commandKey(keys.current, 'client-no-show-policy', body);
        invalidateReads();
        pending.current = { body, key };
        setSaving(true);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/admin/no-show-policy',
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
                const message = await errorMessage(response);
                if (!response.headers.get('Idempotency-Replayed')) {
                    pending.current = null;
                    setUncertain(false);
                }
                setError(
                    message +
                        ' Recarregue a política antes de decidir novamente.',
                );
                return;
            }
            if (!response.ok) {
                const message = await errorMessage(response);
                if (response.status === 408 || response.status >= 500) {
                    setUncertain(true);
                    setError(
                        'O servidor/gateway não confirmou o resultado. ' +
                            message +
                            ' Tente recuperar a mesma operação; não altere o comando.',
                    );
                    return;
                }
                pending.current = null;
                setUncertain(false);
                setError(message);
                return;
            }
            const saved = (await response.json()) as Policy;
            setPolicy(saved);
            setThreshold(String(saved.threshold));
            pending.current = null;
            setUncertain(false);
            setNotice(
                'Limite salvo. A aplicação efetiva de restrições depende de AGD-033.',
            );
        } catch {
            setUncertain(true);
            setError(
                'Resultado incerto. Tente novamente para recuperar a mesma operação.',
            );
        } finally {
            setSaving(false);
        }
    }

    if (!enabled) return null;
    return (
        <section
            className="admin-agenda"
            aria-labelledby="no-show-policy-title"
        >
            <h2 id="no-show-policy-title">Política de faltas</h2>
            {loading && (
                <output aria-live="polite">Carregando política…</output>
            )}
            {policy && (
                <>
                    <p>
                        {policy.configured
                            ? `Limite configurado: ${policy.threshold} falta(s), versão ${policy.version}.`
                            : 'Limite ainda não configurado. Nenhum valor padrão é aplicado.'}
                    </p>
                    <p>
                        Estado da política: {policy.policyStatus}. Estado de
                        restrição: {policy.restrictionStatus}.
                    </p>
                    <p>
                        Estado da restrição automática: não acompanhado nesta
                        história. Esta configuração não bloqueia reservas; isso
                        depende de AGD-033.
                    </p>
                    <label htmlFor="no-show-threshold">
                        Faltas na janela móvel de 12 meses (limite 1 a 5)
                    </label>
                    <select
                        id="no-show-threshold"
                        value={threshold}
                        disabled={saving || uncertain}
                        onChange={(event) => setThreshold(event.target.value)}
                    >
                        <option value="">Selecione um limite</option>
                        {[1, 2, 3, 4, 5].map((value) => (
                            <option key={value} value={value}>
                                {value}
                            </option>
                        ))}
                    </select>
                    <div className="admin-agenda__actions">
                        <button
                            type="button"
                            disabled={saving || !threshold}
                            onClick={() => void save()}
                        >
                            {saving
                                ? 'Salvando…'
                                : uncertain
                                  ? 'Recuperar operação'
                                  : 'Salvar limite'}
                        </button>
                        <button
                            type="button"
                            disabled={saving || uncertain}
                            onClick={() => void load()}
                        >
                            Atualizar política
                        </button>
                    </div>
                </>
            )}
            {error && <p role="alert">{error}</p>}
            {notice && <output aria-live="polite">{notice}</output>}
        </section>
    );
}
