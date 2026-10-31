import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';

type Plan = {
    code: 'MONTHLY' | 'ANNUAL';
    billingFrequency: string;
    recurring: boolean;
    annualEquivalentMonthlyPayments: number | null;
};

type Overview = {
    tenantContextId: string;
    subscription: {
        subscriptionId: string;
        status: 'TRIALING' | 'SUSPENDED' | 'ACTIVE' | 'PAST_DUE';
        trialStartedAt: string | null;
        trialEndsAt: string | null;
        planCode: 'MONTHLY' | 'ANNUAL' | null;
        paidThroughAt: string | null;
        graceEndsAt: string | null;
        version: number;
    } | null;
    plans: Plan[];
    checkoutOffers: {
        planCode: Plan['code'];
        amountMinorUnits: number;
        currency: string;
        termsVersion: string;
        offerVersion: string;
    }[];
    checkoutAvailable: boolean;
    trialEligibilityPending: boolean;
    commercialEligibility: 'VERIFICATION_UNAVAILABLE' | 'VERIFIED';
    dunningAttempts?: {
        logicalSequence: 0 | 3 | 6;
        scheduledAt: string;
        state: string;
        nextAttemptAt: string | null;
        technicalAttempts: number;
        safeErrorCode: string | null;
        skipReason: string | null;
    }[];
};

type CheckoutIntent = {
    tenantContextId: string;
    key: string;
    phase: 'PREPARED' | 'SENDING' | 'UNCERTAIN';
    body: { planCode: Plan['code']; expectedVersion: number };
};

type CheckoutView = {
    operationId: string;
    planCode: Plan['code'];
    state: string;
    amountMinorUnits: number;
    currency: string;
    termsVersion: string;
    createdAt: string;
    redirectUrl: string | null;
    replayed: boolean;
};

type CheckoutAttempt = {
    operationId: string;
    planCode: Plan['code'];
    state: string;
    createdAt: string;
    expectedSubscriptionVersion: number;
};

const intentStorageKey = (tenantContextId: string) =>
    `agd041.checkout-intent.v2:${tenantContextId}`;

function dunningStateLabel(state: string) {
    switch (state) {
        case 'SCHEDULED':
            return 'Agendada';
        case 'BLOCKED_CONFIGURATION':
            return 'Aguardando configuração de transporte ou autorização';
        case 'CLAIMED':
            return 'Reservada pelo worker, ainda sem chamada externa';
        case 'SUBMITTING':
            return 'Chamada iniciada; resultado ainda não persistido';
        case 'RETRYABLE_NO_EFFECT':
            return 'Falha sem efeito confirmado; retry técnico da mesma tentativa';
        case 'OUTCOME_UNKNOWN':
        case 'RECONCILING':
            return 'Resultado incerto; aguardando reconciliação, sem nova cobrança';
        case 'ACCEPTED':
            return 'Aceita pelo transporte; pagamento ainda não confirmado';
        case 'DECLINED':
            return 'Recusada pelo transporte';
        case 'FAILED_FINAL':
            return 'Falha definitiva';
        case 'SKIPPED_LATE_RECOVERY':
            return 'Ignorada por recuperação tardia';
        case 'CANCELLED':
            return 'Cancelada por pagamento ou encerramento do ciclo';
        default:
            return 'Estado operacional não reconhecido';
    }
}

function readIntent(tenantContextId: string): CheckoutIntent | null {
    try {
        const raw = sessionStorage.getItem(intentStorageKey(tenantContextId));
        if (!raw) return null;
        const parsed = JSON.parse(raw) as CheckoutIntent;
        if (
            parsed.tenantContextId === tenantContextId &&
            typeof parsed.key === 'string' &&
            (parsed.body?.planCode === 'MONTHLY' ||
                parsed.body?.planCode === 'ANNUAL') &&
            Number.isSafeInteger(parsed.body?.expectedVersion) &&
            ['PREPARED', 'SENDING', 'UNCERTAIN'].includes(parsed.phase)
        ) {
            return {
                ...parsed,
                phase: parsed.phase === 'SENDING' ? 'UNCERTAIN' : parsed.phase,
            };
        }
    } catch {
        sessionStorage.removeItem(intentStorageKey(tenantContextId));
    }
    return null;
}

export function AdminSubscriptionPanel({
    enabled,
    tenantContextId,
    onSessionInvalid,
}: {
    enabled: boolean;
    tenantContextId: string;
    onSessionInvalid: (status: number) => void;
}) {
    const [overview, setOverview] = useState<Overview | null>(null);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(true);
    const [checkoutBusy, setCheckoutBusy] = useState(false);
    const [checkoutMessage, setCheckoutMessage] = useState('');
    const [checkoutView, setCheckoutView] = useState<CheckoutView | null>(null);
    const [checkoutAttempts, setCheckoutAttempts] = useState<CheckoutAttempt[]>(
        [],
    );
    const [intent, setIntent] = useState<CheckoutIntent | null>(() =>
        readIntent(tenantContextId),
    );
    const [checkoutPhase, setCheckoutPhase] = useState<
        'IDLE' | 'PREPARED' | 'SENDING' | 'UNCERTAIN' | 'KNOWN'
    >(() => intent?.phase ?? 'IDLE');
    const generation = useRef(0);
    const contextEpoch = useRef(0);
    const contextRef = useRef(tenantContextId);
    const enabledRef = useRef(enabled);
    const intentRef = useRef(intent);
    const checkoutBusyRef = useRef(false);

    function invalidateContextEpoch() {
        contextEpoch.current++;
    }

    useLayoutEffect(() => {
        contextRef.current = tenantContextId;
        enabledRef.current = enabled;
        contextEpoch.current++;
        return () => {
            invalidateContextEpoch();
        };
    }, [enabled, tenantContextId]);

    function updateIntent(
        next: CheckoutIntent | null,
        contextId = tenantContextId,
    ) {
        intentRef.current = next;
        setIntent(next);
        setCheckoutPhase(next?.phase ?? 'IDLE');
        const key = intentStorageKey(contextId);
        if (next) sessionStorage.setItem(key, JSON.stringify(next));
        else sessionStorage.removeItem(key);
    }

    function isCurrent(contextId: string, epoch: number) {
        return (
            enabledRef.current &&
            contextRef.current === contextId &&
            contextEpoch.current === epoch
        );
    }

    async function serverContextMatches(contextId: string, epoch: number) {
        let response: Response;
        try {
            response = await fetch(apiBaseUrl + '/v1/admin/session', {
                credentials: 'same-origin',
                cache: 'no-store',
            });
        } catch {
            return false;
        }
        if (!isCurrent(contextId, epoch)) return false;
        if (response.status === 401 || response.status === 403) {
            onSessionInvalid(response.status);
            return false;
        }
        if (!response.ok) return false;
        let currentSession: { authenticated?: boolean; tenantId?: string };
        try {
            currentSession = await response.json();
        } catch {
            return false;
        }
        if (!isCurrent(contextId, epoch)) return false;
        if (
            currentSession.authenticated !== true ||
            currentSession.tenantId !== contextId
        ) {
            onSessionInvalid(403);
            return false;
        }
        return true;
    }

    useEffect(() => {
        const requestGeneration = ++generation.current;
        if (!enabled) {
            return;
        }
        const controller = new AbortController();
        void (async () => {
            try {
                const response = await fetch(
                    apiBaseUrl + '/v1/admin/subscription',
                    {
                        credentials: 'same-origin',
                        cache: 'no-store',
                        signal: controller.signal,
                    },
                );
                if (
                    controller.signal.aborted ||
                    generation.current !== requestGeneration
                )
                    return;
                if (response.status === 401 || response.status === 403) {
                    onSessionInvalid(response.status);
                    return;
                }
                if (!response.ok)
                    throw new Error(`Falha HTTP ${response.status}.`);
                const body = (await response.json()) as Overview;
                if (
                    controller.signal.aborted ||
                    generation.current !== requestGeneration
                )
                    return;
                if (body.tenantContextId !== tenantContextId)
                    throw new Error('O contexto autenticado foi alterado.');
                setOverview(body);
                const attemptsResponse = await fetch(
                    apiBaseUrl + '/v1/admin/subscription/payment-attempts',
                    {
                        credentials: 'same-origin',
                        cache: 'no-store',
                        signal: controller.signal,
                        headers: {
                            'X-Expected-Tenant-Context': tenantContextId,
                        },
                    },
                );
                if (
                    controller.signal.aborted ||
                    generation.current !== requestGeneration
                )
                    return;
                if (
                    attemptsResponse.status === 401 ||
                    attemptsResponse.status === 403
                ) {
                    onSessionInvalid(attemptsResponse.status);
                    return;
                }
                if (!attemptsResponse.ok)
                    throw new Error(
                        'Não foi possível consultar operações financeiras.',
                    );
                const attempts =
                    (await attemptsResponse.json()) as CheckoutAttempt[];
                if (
                    controller.signal.aborted ||
                    generation.current !== requestGeneration
                )
                    return;
                setCheckoutAttempts(attempts);
                setError('');
            } catch (cause: unknown) {
                if (
                    !controller.signal.aborted &&
                    generation.current === requestGeneration
                ) {
                    setError(
                        cause instanceof Error
                            ? cause.message
                            : 'Não foi possível carregar os planos.',
                    );
                }
            } finally {
                if (
                    !controller.signal.aborted &&
                    generation.current === requestGeneration
                ) {
                    setLoading(false);
                }
            }
        })();
        return () => {
            controller.abort();
        };
    }, [enabled, onSessionInvalid, tenantContextId]);

    async function submitCheckout() {
        if (
            !enabledRef.current ||
            !overview?.checkoutAvailable ||
            checkoutBusyRef.current
        )
            return;
        const activeIntent = intentRef.current;
        if (!activeIntent) {
            setCheckoutMessage('Selecione uma oferta aprovada para continuar.');
            return;
        }
        if (activeIntent.tenantContextId !== tenantContextId) return;
        checkoutBusyRef.current = true;
        const contextId = activeIntent.tenantContextId;
        const operationEpoch = contextEpoch.current;
        const beforeSendPhase = activeIntent.phase;
        setCheckoutBusy(true);
        setCheckoutMessage('Verificando a operação financeira…');
        try {
            const csrfResponse = await fetch(apiBaseUrl + '/v1/csrf', {
                credentials: 'same-origin',
                cache: 'no-store',
            });
            if (!isCurrent(contextId, operationEpoch)) return;
            if (!(await serverContextMatches(contextId, operationEpoch)))
                return;
            if (!isCurrent(contextId, operationEpoch)) return;
            if (!csrfResponse.ok) throw new Error('UNAVAILABLE');
            const csrf = (await csrfResponse.json()) as {
                headerName: string;
                token: string;
            };
            if (!isCurrent(contextId, operationEpoch)) return;
            if (!(await serverContextMatches(contextId, operationEpoch)))
                return;
            if (!isCurrent(contextId, operationEpoch)) return;
            const sendingIntent: CheckoutIntent = {
                ...activeIntent,
                phase: 'SENDING',
            };
            updateIntent(sendingIntent, contextId);
            setCheckoutPhase('SENDING');
            const response = await fetch(
                apiBaseUrl + '/v1/admin/subscription/checkout',
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': activeIntent.key,
                        'X-Expected-Tenant-Context': contextId,
                    },
                    body: JSON.stringify(activeIntent.body),
                },
            );
            if (!isCurrent(contextId, operationEpoch)) {
                sessionStorage.setItem(
                    intentStorageKey(contextId),
                    JSON.stringify({ ...sendingIntent, phase: 'UNCERTAIN' }),
                );
                return;
            }
            if (response.status === 401 || response.status === 403) {
                updateIntent(
                    { ...sendingIntent, phase: 'UNCERTAIN' },
                    contextId,
                );
                onSessionInvalid(response.status);
                throw new Error('SESSION');
            }
            if (!response.ok) {
                const errorCode = await response
                    .json()
                    .then((body: { error?: { code?: string } }) =>
                        typeof body.error?.code === 'string'
                            ? body.error.code
                            : undefined,
                    )
                    .catch(() => undefined);
                if (!isCurrent(contextId, operationEpoch)) {
                    sessionStorage.setItem(
                        intentStorageKey(contextId),
                        JSON.stringify({
                            ...sendingIntent,
                            phase: 'UNCERTAIN',
                        }),
                    );
                    return;
                }
                const definitiveConflictCodes = new Set([
                    'VERSION_CONFLICT',
                    'SUBSCRIPTION_ALREADY_ACTIVE',
                    'IDEMPOTENCY_KEY_REUSED',
                ]);
                if (
                    response.status === 409 &&
                    errorCode === 'TENANT_CONTEXT_CHANGED'
                ) {
                    const safePhase =
                        beforeSendPhase === 'UNCERTAIN'
                            ? 'UNCERTAIN'
                            : 'PREPARED';
                    updateIntent(
                        { ...activeIntent, phase: safePhase },
                        contextId,
                    );
                    setCheckoutMessage(
                        'O contexto autenticado mudou. Nenhum checkout foi iniciado por esta tentativa; retorne ao tenant original para continuar a mesma intenção.',
                    );
                    return;
                }
                if (
                    response.status === 408 ||
                    response.status >= 500 ||
                    !errorCode ||
                    (response.status === 409 &&
                        !definitiveConflictCodes.has(errorCode))
                ) {
                    updateIntent(
                        { ...sendingIntent, phase: 'UNCERTAIN' },
                        contextId,
                    );
                    setCheckoutPhase('UNCERTAIN');
                    setCheckoutMessage(
                        'Resultado ainda incerto. Recupere esta mesma operação; não inicie outra cobrança.',
                    );
                    return;
                }
                updateIntent(null, contextId);
                setCheckoutPhase('KNOWN');
                setCheckoutMessage(
                    'A solicitação foi rejeitada sem confirmação de pagamento. Revise os dados e tente novamente.',
                );
                return;
            }
            const value = (await response.json()) as CheckoutView;
            if (!isCurrent(contextId, operationEpoch)) {
                sessionStorage.setItem(
                    intentStorageKey(contextId),
                    JSON.stringify({ ...sendingIntent, phase: 'UNCERTAIN' }),
                );
                return;
            }
            if (
                !value ||
                typeof value.operationId !== 'string' ||
                typeof value.state !== 'string' ||
                value.planCode !== activeIntent.body.planCode
            ) {
                updateIntent(
                    { ...sendingIntent, phase: 'UNCERTAIN' },
                    contextId,
                );
                setCheckoutPhase('UNCERTAIN');
                setCheckoutMessage(
                    'A resposta não permitiu confirmar o resultado. Recupere a mesma operação antes de iniciar outra.',
                );
                return;
            }
            setCheckoutView(value);
            setCheckoutAttempts((current) => [
                {
                    operationId: value.operationId,
                    planCode: value.planCode,
                    state: value.state,
                    createdAt: value.createdAt,
                    expectedSubscriptionVersion:
                        activeIntent.body.expectedVersion,
                },
                ...current.filter(
                    (attempt) => attempt.operationId !== value.operationId,
                ),
            ]);
            if (
                ['CHECKOUT_READY', 'CONFIRMED', 'DECLINED'].includes(
                    value.state,
                )
            ) {
                updateIntent(null, contextId);
                setCheckoutPhase('KNOWN');
                setCheckoutMessage(
                    value.state === 'CHECKOUT_READY'
                        ? 'Sessão de checkout hospedado criada. Isso ainda não confirma pagamento.'
                        : value.state === 'CONFIRMED'
                          ? 'Pagamento confirmado por evidência do provedor.'
                          : 'O provedor recusou a operação; nenhuma assinatura foi ativada.',
                );
            } else {
                updateIntent(
                    { ...sendingIntent, phase: 'UNCERTAIN' },
                    contextId,
                );
                setCheckoutPhase('UNCERTAIN');
                setCheckoutMessage(
                    'Operação ainda em resultado incerto. Preserve a intenção e tente recuperar novamente.',
                );
            }
        } catch (cause: unknown) {
            if (cause instanceof Error && cause.message === 'SESSION') return;
            if (isCurrent(contextId, operationEpoch)) {
                const currentIntent = intentRef.current;
                if (currentIntent?.phase === 'SENDING') {
                    updateIntent(
                        { ...currentIntent, phase: 'UNCERTAIN' },
                        contextId,
                    );
                    setCheckoutPhase('UNCERTAIN');
                } else if (beforeSendPhase === 'PREPARED') {
                    updateIntent(
                        { ...activeIntent, phase: 'PREPARED' },
                        contextId,
                    );
                }
                setCheckoutMessage(
                    currentIntent?.phase === 'SENDING' ||
                        beforeSendPhase === 'UNCERTAIN'
                        ? 'Não foi possível confirmar o resultado. Recupere a mesma operação antes de iniciar outra cobrança.'
                        : 'Não foi possível preparar o envio. A seleção continua pronta e nenhuma solicitação financeira foi enviada.',
                );
            }
        } finally {
            checkoutBusyRef.current = false;
            if (isCurrent(contextId, operationEpoch)) setCheckoutBusy(false);
        }
    }

    function choosePlan(planCode: Plan['code']) {
        if (checkoutBusyRef.current || intentRef.current) return;
        const next: CheckoutIntent = {
            tenantContextId,
            key: crypto.randomUUID(),
            phase: 'PREPARED',
            body: {
                planCode,
                expectedVersion: overview?.subscription?.version ?? 0,
            },
        };
        updateIntent(next);
        setCheckoutMessage(
            'Oferta selecionada; nenhum pagamento foi iniciado.',
        );
        setCheckoutPhase('PREPARED');
    }

    function clearKnownIntent() {
        const current = intentRef.current;
        if (
            checkoutBusyRef.current ||
            (current && current.phase !== 'PREPARED')
        )
            return;
        updateIntent(null);
        setCheckoutView(null);
        setCheckoutMessage('');
    }

    async function recoverCheckout(operationId: string) {
        if (!enabledRef.current || checkoutBusyRef.current) return;
        const contextGeneration = contextEpoch.current;
        const contextId = tenantContextId;
        checkoutBusyRef.current = true;
        setCheckoutBusy(true);
        setCheckoutMessage('Consultando a operação financeira existente…');
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/admin/subscription/payment-attempts/' +
                    encodeURIComponent(operationId),
                {
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: { 'X-Expected-Tenant-Context': contextId },
                },
            );
            if (!isCurrent(contextId, contextGeneration)) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (!response.ok) throw new Error('RECOVERY_UNAVAILABLE');
            const value = (await response.json()) as CheckoutView;
            if (!isCurrent(contextId, contextGeneration)) return;
            setCheckoutView(value);
            setCheckoutAttempts((current) =>
                current.map((attempt) =>
                    attempt.operationId === value.operationId
                        ? { ...attempt, state: value.state }
                        : attempt,
                ),
            );
            setCheckoutMessage(
                value.state === 'CHECKOUT_READY'
                    ? 'Operação recuperada. A sessão hospedada não confirma pagamento.'
                    : 'Estado atual consultado; nenhuma cobrança foi iniciada pela consulta.',
            );
        } catch {
            if (isCurrent(contextId, contextGeneration))
                setCheckoutMessage(
                    'Não foi possível consultar a operação. Tente recuperar a mesma operação novamente.',
                );
        } finally {
            checkoutBusyRef.current = false;
            if (isCurrent(contextId, contextGeneration)) setCheckoutBusy(false);
        }
    }

    if (!enabled) return null;

    const checkoutPhaseLabel = {
        IDLE: 'inativa',
        PREPARED: 'preparada',
        SENDING: 'em envio',
        UNCERTAIN: 'incerta',
        KNOWN: 'conhecida',
    }[checkoutPhase];

    return (
        <section aria-labelledby="subscription-heading">
            <h2 id="subscription-heading">Plano e período de avaliação</h2>
            {loading && !overview && !error && (
                <output aria-live="polite">
                    Carregando informações comerciais…
                </output>
            )}
            {error && <p role="alert">{error}</p>}
            {overview?.trialEligibilityPending && (
                <output aria-live="polite">
                    O trial não foi provisionado: a identidade comercial
                    elegível ainda não pode ser verificada. A seleção de um
                    plano não confirma pagamento nem libera publicação.
                </output>
            )}
            {overview?.subscription && (
                <section aria-label="Estado do trial">
                    <p>
                        Estado:{' '}
                        {overview.subscription.status === 'TRIALING'
                            ? 'Trial ativo'
                            : overview.subscription.status === 'ACTIVE'
                              ? 'Assinatura ativa'
                              : overview.subscription.status === 'PAST_DUE'
                                ? 'Pagamento pendente — em tolerância'
                                : overview.subscription.planCode
                                  ? 'Assinatura suspensa'
                                  : 'Trial encerrado'}
                        .
                    </p>
                    {overview.subscription.trialStartedAt && (
                        <p>
                            Início:{' '}
                            {new Date(
                                overview.subscription.trialStartedAt,
                            ).toLocaleString('pt-BR', { timeZone: 'UTC' })}{' '}
                            UTC
                        </p>
                    )}
                    {overview.subscription.trialEndsAt && (
                        <p>
                            Fim:{' '}
                            {new Date(
                                overview.subscription.trialEndsAt,
                            ).toLocaleString('pt-BR', { timeZone: 'UTC' })}{' '}
                            UTC
                        </p>
                    )}
                    {overview.subscription.planCode && (
                        <p>Plano: {overview.subscription.planCode}</p>
                    )}
                    {overview.subscription.paidThroughAt && (
                        <p>
                            Pago até:{' '}
                            {new Date(
                                overview.subscription.paidThroughAt,
                            ).toLocaleString('pt-BR', { timeZone: 'UTC' })}{' '}
                            UTC
                        </p>
                    )}
                    {overview.subscription.graceEndsAt && (
                        <p>
                            Fim da tolerância:{' '}
                            {new Date(
                                overview.subscription.graceEndsAt,
                            ).toLocaleString('pt-BR', { timeZone: 'UTC' })}{' '}
                            UTC
                        </p>
                    )}
                </section>
            )}
            {!!overview?.dunningAttempts?.length && (
                <section aria-label="Tentativas lógicas de cobrança">
                    <h3>Tentativas de cobrança</h3>
                    <p>
                        A agenda e o aceite do transporte não confirmam
                        pagamento. Resultados incertos bloqueiam novas cobranças
                        até reconciliação.
                    </p>
                    <ol>
                        {overview.dunningAttempts.map((attempt) => (
                            <li
                                key={`${attempt.logicalSequence}:${attempt.scheduledAt}`}
                            >
                                D+{attempt.logicalSequence}:{' '}
                                {new Date(attempt.scheduledAt).toLocaleString(
                                    'pt-BR',
                                    { timeZone: 'UTC' },
                                )}{' '}
                                UTC — {dunningStateLabel(attempt.state)}
                                {attempt.state === 'RETRYABLE_NO_EFFECT' &&
                                    attempt.nextAttemptAt && (
                                        <span>
                                            {' '}
                                            Próxima tentativa técnica prevista:{' '}
                                            {new Date(
                                                attempt.nextAttemptAt,
                                            ).toLocaleString('pt-BR', {
                                                timeZone: 'UTC',
                                            })}{' '}
                                            UTC. Identidade da tentativa
                                            preservada.
                                        </span>
                                    )}
                                {attempt.state === 'SKIPPED_LATE_RECOVERY' && (
                                    <span>
                                        {' '}
                                        Motivo: tentativa anterior vencida e
                                        nunca iniciada durante recuperação do
                                        worker.
                                    </span>
                                )}
                                {attempt.technicalAttempts > 0 && (
                                    <span>
                                        {' '}
                                        Chamadas autorizadas:{' '}
                                        {attempt.technicalAttempts}.
                                    </span>
                                )}
                            </li>
                        ))}
                    </ol>
                </section>
            )}
            {overview && (
                <ul aria-label="Planos disponíveis">
                    {overview.plans.map((plan) => (
                        <li key={plan.code}>
                            <h3>
                                {plan.code === 'MONTHLY' ? 'Mensal' : 'Anual'}
                            </h3>
                            <p>
                                {plan.code === 'MONTHLY'
                                    ? 'Cobrança recorrente mensal.'
                                    : `Cobrança anual equivalente a ${plan.annualEquivalentMonthlyPayments} mensalidades.`}
                            </p>
                            {overview.checkoutOffers.find(
                                (offer) => offer.planCode === plan.code,
                            ) ? (
                                <p>
                                    Oferta aprovada:{' '}
                                    {
                                        overview.checkoutOffers.find(
                                            (offer) =>
                                                offer.planCode === plan.code,
                                        )?.amountMinorUnits
                                    }{' '}
                                    {
                                        overview.checkoutOffers.find(
                                            (offer) =>
                                                offer.planCode === plan.code,
                                        )?.currency
                                    }
                                    .
                                </p>
                            ) : (
                                <p>
                                    Preço e moeda serão informados quando
                                    aprovados.
                                </p>
                            )}
                            {overview.checkoutAvailable && (
                                <button
                                    type="button"
                                    onClick={() => choosePlan(plan.code)}
                                    disabled={checkoutBusy || Boolean(intent)}
                                >
                                    Selecionar plano
                                </button>
                            )}
                        </li>
                    ))}
                </ul>
            )}
            <p>
                Selecionar plano não confirma pagamento. Somente evidência
                autenticada do provedor altera o estado da assinatura. Trial
                elegível tem 14 dias e não exige cartão.
            </p>
            {intent && (
                <section aria-label="Operação de checkout pendente">
                    <p>
                        Operação {checkoutPhaseLabel}: {intent.body.planCode}. O
                        tenant, corpo e chave da intenção serão preservados até
                        resultado conhecido.
                    </p>
                    <button
                        type="button"
                        onClick={submitCheckout}
                        disabled={checkoutBusy}
                    >
                        {checkoutBusy
                            ? 'Processando…'
                            : 'Iniciar ou recuperar a mesma operação'}
                    </button>
                    <button
                        type="button"
                        onClick={clearKnownIntent}
                        disabled={checkoutBusy || intent.phase !== 'PREPARED'}
                    >
                        Descartar seleção ainda não enviada
                    </button>
                </section>
            )}
            {checkoutMessage && (
                <output aria-live="polite">{checkoutMessage}</output>
            )}
            {checkoutAttempts.length > 0 && (
                <section aria-label="Operações financeiras existentes">
                    <h3>Operações existentes</h3>
                    <ul>
                        {checkoutAttempts.map((attempt) => (
                            <li key={attempt.operationId}>
                                {attempt.planCode} — {attempt.state} —{' '}
                                <button
                                    type="button"
                                    onClick={() =>
                                        void recoverCheckout(
                                            attempt.operationId,
                                        )
                                    }
                                    disabled={checkoutBusy}
                                >
                                    Recuperar operação {attempt.operationId}
                                </button>
                            </li>
                        ))}
                    </ul>
                </section>
            )}
            {checkoutView && (
                <section aria-label="Resultado do checkout">
                    <p>
                        Estado da operação: {checkoutView.state}. Operação{' '}
                        {checkoutView.operationId}.
                    </p>
                    {checkoutView.redirectUrl &&
                        checkoutView.state === 'CHECKOUT_READY' && (
                            <a
                                href={checkoutView.redirectUrl}
                                target="_blank"
                                rel="noreferrer"
                            >
                                Abrir checkout hospedado
                            </a>
                        )}
                    {checkoutView.state !== 'CONFIRMED' && (
                        <p>
                            O retorno do checkout não confirma pagamento;
                            aguarde a evidência autenticada do provedor.
                        </p>
                    )}
                </section>
            )}
        </section>
    );
}
