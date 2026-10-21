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
        status: 'TRIALING' | 'SUSPENDED';
        trialStartedAt: string;
        trialEndsAt: string;
        version: number;
    } | null;
    plans: Plan[];
    trialEligibilityPending: boolean;
    commercialEligibility: 'VERIFICATION_UNAVAILABLE' | 'VERIFIED';
};

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
    const generation = useRef(0);

    useEffect(() => {
        const generationRef = generation;
        const requestGeneration = ++generation.current;
        if (!enabled) return;
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
                            : 'Trial encerrado'}
                        .
                    </p>
                    <p>
                        Início:{' '}
                        {new Date(
                            overview.subscription.trialStartedAt,
                        ).toLocaleString('pt-BR', { timeZone: 'UTC' })}{' '}
                        UTC
                    </p>
                    <p>
                        Fim:{' '}
                        {new Date(
                            overview.subscription.trialEndsAt,
                        ).toLocaleString('pt-BR', { timeZone: 'UTC' })}{' '}
                        UTC
                    </p>
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
                            <p>
                                Preço e moeda serão informados quando aprovados.
                            </p>
                        </li>
                    ))}
                </ul>
            )}
            <p>
                Nenhum checkout ou pagamento é realizado nesta etapa. Trial
                elegível tem 14 dias e não exige cartão.
            </p>
        </section>
    );
}
