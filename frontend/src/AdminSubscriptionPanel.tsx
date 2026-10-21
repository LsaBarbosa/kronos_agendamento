import { useEffect, useState } from 'react';
import { apiBaseUrl } from './config';

type Plan = {
    code: 'MONTHLY' | 'ANNUAL';
    billingFrequency: string;
    recurring: boolean;
    annualEquivalentMonthlyPayments: number | null;
};

type Overview = {
    subscription: null;
    plans: Plan[];
    trialEligibilityPending: boolean;
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

    useEffect(() => {
        if (!enabled) return;
        const controller = new AbortController();
        void fetch(apiBaseUrl + '/v1/admin/subscription', {
            credentials: 'same-origin',
            cache: 'no-store',
            signal: controller.signal,
        })
            .then(async (response) => {
                if (response.status === 401 || response.status === 403) {
                    onSessionInvalid(response.status);
                    return;
                }
                if (!response.ok)
                    throw new Error(`Falha HTTP ${response.status}.`);
                setOverview((await response.json()) as Overview);
            })
            .catch((cause: unknown) => {
                if (!controller.signal.aborted) {
                    setError(
                        cause instanceof Error
                            ? cause.message
                            : 'Não foi possível carregar os planos.',
                    );
                }
            });
        return () => controller.abort();
    }, [enabled, onSessionInvalid]);

    if (!enabled) return null;

    return (
        <section aria-labelledby="subscription-heading">
            <h2 id="subscription-heading">Plano e período de avaliação</h2>
            {!overview && !error && (
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
                Nenhum checkout ou pagamento é realizado nesta etapa. O trial
                aprovado pela especificação tem duração de 14 dias e não exige
                cartão; sua concessão aguarda verificação de elegibilidade.
            </p>
        </section>
    );
}
