import { useEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';

type Plan = {
    code: 'MONTHLY' | 'ANNUAL';
    billingFrequency: string;
    recurring: boolean;
    annualEquivalentMonthlyPayments: number | null;
};

type Overview = {
    subscription: {
        subscriptionId: string;
        status: 'TRIALING' | 'SUSPENDED' | 'ACTIVE';
        trialStartedAt: string | null;
        trialEndsAt: string | null;
        planCode: 'MONTHLY' | 'ANNUAL' | null;
        paidThroughAt: string | null;
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
};

type CheckoutIntent = {
    key: string;
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

const intentStorageKey = 'agd041.checkout-intent.v1';

export function AdminSubscriptionPanel({
    enabled,
    onSessionInvalid,
}: {
    enabled: boolean;
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
    const [intent, setIntent] = useState<CheckoutIntent | null>(() => {
        try {
            const raw = sessionStorage.getItem(intentStorageKey);
            if (!raw) return null;
            const parsed = JSON.parse(raw) as CheckoutIntent;
            if (
                typeof parsed.key === 'string' &&
                (parsed.body?.planCode === 'MONTHLY' ||
                    parsed.body?.planCode === 'ANNUAL') &&
                Number.isSafeInteger(parsed.body?.expectedVersion)
            )
                return parsed;
        } catch {
            sessionStorage.removeItem(intentStorageKey);
        }
        return null;
    });
    const generation = useRef(0);

    useEffect(() => {
        const generationRef = generation;
        const requestGeneration = ++generation.current;
        if (!enabled) {
            sessionStorage.removeItem(intentStorageKey);
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
                setOverview(body);
                const attemptsResponse = await fetch(
                    apiBaseUrl + '/v1/admin/subscription/payment-attempts',
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
            if (generationRef.current === requestGeneration)
                generationRef.current++;
        };
    }, [enabled, onSessionInvalid]);

    async function submitCheckout() {
        if (!enabled || !overview?.checkoutAvailable || checkoutBusy) return;
        const activeIntent = intent;
        if (!activeIntent) {
            setCheckoutMessage('Selecione uma oferta aprovada para continuar.');
            return;
        }
        setCheckoutBusy(true);
        setCheckoutMessage('Verificando a operação financeira…');
        try {
            const csrfResponse = await fetch(apiBaseUrl + '/v1/csrf', {
                credentials: 'same-origin',
                cache: 'no-store',
            });
            if (!csrfResponse.ok) throw new Error('UNAVAILABLE');
            const csrf = (await csrfResponse.json()) as {
                headerName: string;
                token: string;
            };
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
                    },
                    body: JSON.stringify(activeIntent.body),
                },
            );
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                throw new Error('SESSION');
            }
            if (!response.ok) {
                if (response.status === 409 || response.status >= 500) {
                    setCheckoutMessage(
                        'Resultado ainda incerto. Recupere esta mesma operação; não inicie outra cobrança.',
                    );
                    return;
                }
                setIntent(null);
                sessionStorage.removeItem(intentStorageKey);
                setCheckoutMessage(
                    'A solicitação foi rejeitada sem confirmação de pagamento. Revise os dados e tente novamente.',
                );
                return;
            }
            const value = (await response.json()) as CheckoutView;
            if (!enabled) return;
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
                setIntent(null);
                sessionStorage.removeItem(intentStorageKey);
                setCheckoutMessage(
                    value.state === 'CHECKOUT_READY'
                        ? 'Sessão de checkout hospedado criada. Isso ainda não confirma pagamento.'
                        : value.state === 'CONFIRMED'
                          ? 'Pagamento confirmado por evidência do provedor.'
                          : 'O provedor recusou a operação; nenhuma assinatura foi ativada.',
                );
            } else {
                setCheckoutMessage(
                    'Operação ainda em resultado incerto. Preserve a intenção e tente recuperar novamente.',
                );
            }
        } catch (cause: unknown) {
            if (cause instanceof Error && cause.message === 'SESSION') return;
            setCheckoutMessage(
                'Não foi possível confirmar o resultado. Recupere a mesma operação antes de iniciar outra.',
            );
        } finally {
            setCheckoutBusy(false);
        }
    }

    function choosePlan(planCode: Plan['code']) {
        if (checkoutBusy || intent) return;
        const next: CheckoutIntent = {
            key: crypto.randomUUID(),
            body: {
                planCode,
                expectedVersion: overview?.subscription?.version ?? 0,
            },
        };
        setIntent(next);
        sessionStorage.setItem(intentStorageKey, JSON.stringify(next));
        setCheckoutMessage(
            'Oferta selecionada; nenhum pagamento foi iniciado.',
        );
    }

    function clearKnownIntent() {
        if (checkoutBusy) return;
        setIntent(null);
        sessionStorage.removeItem(intentStorageKey);
        setCheckoutView(null);
        setCheckoutMessage('');
    }

    async function recoverCheckout(operationId: string) {
        if (!enabled || checkoutBusy) return;
        const contextGeneration = generation.current;
        setCheckoutBusy(true);
        setCheckoutMessage('Consultando a operação financeira existente…');
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/admin/subscription/payment-attempts/' +
                    encodeURIComponent(operationId),
                { credentials: 'same-origin', cache: 'no-store' },
            );
            if (!enabled || generation.current !== contextGeneration) return;
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (!response.ok) throw new Error('RECOVERY_UNAVAILABLE');
            const value = (await response.json()) as CheckoutView;
            if (!enabled || generation.current !== contextGeneration) return;
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
            if (enabled && generation.current === contextGeneration)
                setCheckoutMessage(
                    'Não foi possível consultar a operação. Tente recuperar a mesma operação novamente.',
                );
        } finally {
            if (enabled && generation.current === contextGeneration)
                setCheckoutBusy(false);
        }
    }

    if (!enabled) return null;

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
                        Operação preparada: {intent.body.planCode}. A chave da
                        intenção será preservada até resultado conhecido.
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
                        disabled={checkoutBusy}
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
