import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { apiBaseUrl } from '../config';
import { isRetentionAssessment, type RetentionAssessment } from './types';
import './retention.css';

type Props = {
    enabled: boolean;
    /** Local context guard only. This value is never sent to the API. */
    tenantId: string | null;
    onSessionInvalid: (status: number) => void;
};

const categoryLabels: Record<string, string> = {
    TENANT_LIFECYCLE: 'Ciclo de vida do tenant',
    CLIENT_RECORDS: 'Registros de clientes',
    APPOINTMENTS: 'Reservas',
    NOTIFICATION_INTENTS: 'Intenções de notificação',
    OUTBOX_EVENTS: 'Eventos da outbox',
    EXPORT_JOBS: 'Exportações',
    BILLING_OPERATIONS: 'Operações de assinatura e cobrança',
    TENANT_AND_BUSINESS_CONFIGURATION: 'Tenant e configuração do negócio',
    ADMIN_IDENTITIES_AND_MEMBERSHIPS:
        'Identidades e associações administrativas',
    BUSINESS_SNAPSHOTS: 'Snapshots de negócio',
    CHALLENGES_AND_SESSIONS: 'Desafios e sessões',
    IDEMPOTENCY_AND_RECEIPTS: 'Idempotência e receipts',
    NOTIFICATION_EVIDENCE_AND_INBOXES: 'Evidências e inboxes de notificação',
    PAYMENT_EVIDENCE: 'Evidências de pagamento',
    EXPORT_OBJECT_STORAGE: 'Objetos no armazenamento de exportações',
    AUDIT_EVENTS: 'Eventos de auditoria',
    TRIAL_LEDGER: 'Ledger de trials',
    BACKUPS_AND_RESTORES: 'Backups e restaurações',
    LEGAL_HOLDS: 'Legal holds',
};

const countLabels: Record<string, string> = {
    tenants: 'Tenants',
    records: 'Registros',
    futureConfirmed: 'Reservas futuras confirmadas',
    pendingOrClaimed: 'Pendentes ou em processamento',
    outcomeUnknown: 'Resultado incerto',
    acceptedTransport: 'Aceitas pelo transporte',
    reconciliationRequired: 'Reconciliação ou dead-letter',
    activeJobs: 'Exportações ativas',
    availableArtifacts: 'Artefatos no estado disponível',
    cleanupPending: 'Limpeza pendente',
    subscriptions: 'Assinaturas',
    openCheckouts: 'Checkouts em aberto',
    checkoutOutcomeUnknown: 'Checkout com resultado incerto',
    dunningPending: 'Tentativas de inadimplência pendentes',
    dunningOutcomeUnknown: 'Tentativas com resultado incerto',
};

const reasonLabels: Record<string, string> = {
    SOURCE_UNAVAILABLE:
        'A fonte não pôde ser consultada; nenhuma contagem foi presumida.',
    CATEGORY_OUTSIDE_INITIAL_SCOPE:
        'Esta categoria não é avaliada neste incremento.',
    COUNT_LIMIT_REACHED:
        'A contagem foi limitada; o total real pode ser maior.',
};

const blockerLabels: Record<string, string> = {
    TENANT_LIFECYCLE_SOURCE_UNAVAILABLE:
        'A fonte do ciclo de vida do tenant está indisponível.',
    CLIENT_RECORDS_SOURCE_UNAVAILABLE: 'A fonte de clientes está indisponível.',
    APPOINTMENTS_SOURCE_UNAVAILABLE: 'A fonte de reservas está indisponível.',
    FUTURE_APPOINTMENTS_PRESENT: 'Há reservas futuras confirmadas.',
    NOTIFICATION_SOURCE_UNAVAILABLE:
        'A fonte de notificações está indisponível.',
    NOTIFICATION_EFFECTS_PENDING: 'Há efeitos de notificação pendentes.',
    NOTIFICATION_OUTCOME_UNKNOWN: 'Há notificações com resultado incerto.',
    OUTBOX_SOURCE_UNAVAILABLE: 'A fonte da outbox está indisponível.',
    OUTBOX_EFFECTS_PENDING: 'Há eventos da outbox pendentes.',
    OUTBOX_RECONCILIATION_REQUIRED: 'Há eventos que exigem reconciliação.',
    EXPORT_SOURCE_UNAVAILABLE: 'A fonte de exportações está indisponível.',
    EXPORT_JOBS_ACTIVE: 'Há exportações ativas.',
    EXPORT_OBJECTS_NOT_VERIFIED:
        'O estado dos objetos no armazenamento externo não foi verificado.',
    BILLING_SOURCE_UNAVAILABLE:
        'A fonte de operações comerciais está indisponível.',
    BILLING_OPERATIONS_PENDING: 'Há operações comerciais pendentes.',
    BILLING_OUTCOME_UNKNOWN: 'Há operações comerciais com resultado incerto.',
    RETENTION_START_MARKER_UNRESOLVED:
        'O evento que inicia os prazos ainda não foi definido.',
    CATEGORY_POLICY_NOT_CONFIGURED:
        'As políticas de retenção por categoria não estão configuradas.',
    LEGAL_BASE_NOT_CONFIGURED: 'As bases aplicáveis não estão configuradas.',
    HOLD_AUTHORITY_UNDEFINED:
        'A autoridade para definir legal hold não está definida.',
    HOLD_INVENTORY_NOT_EVALUATED:
        'A existência de legal holds não foi avaliada.',
    ASSESSMENT_ONLY_NO_DISCARD_AUTHORIZATION:
        'Esta avaliação não autoriza descarte de dados.',
};

const factLabels: Record<string, string> = {
    status: 'Estado',
    subscriptionStatus: 'Estado da assinatura',
    objectStorageVerified: 'Armazenamento externo',
};

function categoryStatus(category: RetentionAssessment['categories'][number]) {
    if (category.evaluationStatus === 'SOURCE_UNAVAILABLE') {
        return 'Fonte indisponível';
    }
    if (category.evaluationStatus === 'NOT_EVALUATED') {
        return 'Não avaliada';
    }
    return 'Avaliada';
}

function formatReference(instant: string) {
    return `${new Intl.DateTimeFormat('pt-BR', {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: 'UTC',
    }).format(new Date(instant))} UTC`;
}

function countText(
    count: RetentionAssessment['categories'][number]['counts'][string],
) {
    if (count.value === null) return 'Contagem não informada';
    const value = new Intl.NumberFormat('pt-BR').format(count.value);
    if (count.status === 'AT_LEAST_LIMIT') return `Pelo menos ${value}`;
    return `${value} (exata)`;
}

export function RetentionAssessmentPanel({
    enabled,
    tenantId,
    onSessionInvalid,
}: Props) {
    const contextKey = JSON.stringify([enabled, tenantId]);
    return (
        <RetentionAssessmentPanelView
            key={contextKey}
            enabled={enabled}
            tenantId={tenantId}
            onSessionInvalid={onSessionInvalid}
        />
    );
}

function RetentionAssessmentPanelView({
    enabled,
    tenantId,
    onSessionInvalid,
}: Props) {
    const [assessment, setAssessment] = useState<RetentionAssessment | null>(
        null,
    );
    const [loading, setLoading] = useState(
        Boolean(enabled && tenantId?.trim()),
    );
    const [error, setError] = useState('');
    const sessionInvalidRef = useRef(onSessionInvalid);
    const refreshAction = useRef<() => void>(() => undefined);

    useLayoutEffect(() => {
        sessionInvalidRef.current = onSessionInvalid;
    }, [onSessionInvalid]);

    useEffect(() => {
        const contextId = tenantId?.trim() ?? '';
        if (!enabled || !contextId) return;

        let active = true;
        let inFlight = false;
        let sequence = 0;
        const controller = new AbortController();

        const perform = async (manual: boolean) => {
            if (!active || inFlight) return;
            inFlight = true;
            const currentSequence = ++sequence;
            if (manual) {
                setLoading(true);
                setError('');
            }

            try {
                const response = await fetch(
                    apiBaseUrl + '/v1/admin/retention/assessment',
                    {
                        credentials: 'same-origin',
                        cache: 'no-store',
                        signal: controller.signal,
                    },
                );
                if (!active || currentSequence !== sequence) return;

                if (response.status === 401 || response.status === 403) {
                    setAssessment(null);
                    setError(
                        'A sessão administrativa não está mais autorizada.',
                    );
                    sessionInvalidRef.current(response.status);
                    return;
                }
                if (!response.ok) {
                    throw new Error(
                        `A consulta falhou (HTTP ${response.status}).`,
                    );
                }

                const payload: unknown = await response.json();
                if (!active || currentSequence !== sequence) return;
                if (!isRetentionAssessment(payload)) {
                    throw new Error(
                        'O servidor retornou uma avaliação inválida.',
                    );
                }
                setAssessment(payload);
            } catch (loadError) {
                if (!active || currentSequence !== sequence) return;
                if (
                    loadError instanceof DOMException &&
                    loadError.name === 'AbortError'
                ) {
                    return;
                }
                setError(
                    loadError instanceof Error
                        ? loadError.message
                        : 'Não foi possível consultar a avaliação.',
                );
            } finally {
                inFlight = false;
                if (active && currentSequence === sequence) setLoading(false);
            }
        };

        refreshAction.current = () => void perform(true);
        void perform(false);
        return () => {
            active = false;
            controller.abort();
        };
    }, [enabled, tenantId]);

    const notReady = !enabled || !tenantId?.trim();

    return (
        <section
            className="retention-assessment"
            aria-labelledby="retention-assessment-title"
            aria-busy={loading}
        >
            <header className="retention-assessment__header">
                <div>
                    <h2 id="retention-assessment-title">
                        Avaliação informativa de retenção
                    </h2>
                    <p>
                        Consulta somente leitura das fontes cobertas para o
                        contexto administrativo atual.
                    </p>
                </div>
                <button
                    type="button"
                    className="retention-assessment__refresh"
                    onClick={() => refreshAction.current()}
                    disabled={notReady || loading}
                >
                    {loading ? 'Consultando…' : 'Atualizar avaliação'}
                </button>
            </header>

            <div className="retention-assessment__notice" role="note">
                <strong>
                    Esta avaliação não autoriza descarte, anonimização ou
                    alteração de dados.
                </strong>
                <span>
                    Ela não comprova conformidade legal, não valida legal holds
                    e não representa uma decisão de retenção.
                </span>
            </div>

            {notReady && (
                <output className="retention-assessment__muted">
                    A avaliação ficará disponível após a validação da sessão e
                    do tenant.
                </output>
            )}
            {loading && (
                <output className="retention-assessment__muted">
                    Consultando fontes de forma independente…
                </output>
            )}
            {error && (
                <p className="retention-assessment__error" role="alert">
                    {error}
                    {assessment &&
                        ' A última avaliação continua visível; atualize para tentar novamente.'}
                </p>
            )}

            {assessment && (
                <div className="retention-assessment__result">
                    <div className="retention-assessment__summary">
                        <p>
                            <strong>Instante de referência:</strong>{' '}
                            <time dateTime={assessment.referenceAt}>
                                {formatReference(assessment.referenceAt)}
                            </time>
                        </p>
                        <p>
                            <strong>Consistência:</strong> consultas
                            independentes em READ COMMITTED; não formam um
                            snapshot atômico.
                        </p>
                        <p>
                            <strong>Tenant:</strong>{' '}
                            {assessment.tenantLifecycleStatus}
                        </p>
                    </div>

                    <section aria-labelledby="retention-policy-title">
                        <h3 id="retention-policy-title">
                            Política e limites conhecidos
                        </h3>
                        <dl className="retention-assessment__policy">
                            <div>
                                <dt>
                                    Requisito inicial após
                                    cancelamento/suspensão
                                </dt>
                                <dd>
                                    {
                                        assessment.policy
                                            .initialPostCancellationOrSuspensionMonths
                                    }{' '}
                                    meses — marco inicial{' '}
                                    {assessment.policy.startMarker}
                                </dd>
                            </div>
                            <div>
                                <dt>Políticas por categoria</dt>
                                <dd>{assessment.policy.categoryPolicies}</dd>
                            </div>
                            <div>
                                <dt>Bases aplicáveis</dt>
                                <dd>{assessment.policy.legalBases}</dd>
                            </div>
                            <div>
                                <dt>Autoridade de legal hold</dt>
                                <dd>{assessment.policy.holdAuthority}</dd>
                            </div>
                            <div>
                                <dt>Inventário de legal hold</dt>
                                <dd>{assessment.policy.holdInventory}</dd>
                            </div>
                        </dl>
                    </section>

                    <section aria-labelledby="retention-blockers-title">
                        <h3 id="retention-blockers-title">
                            Limitações e pontos de atenção
                        </h3>
                        <ul className="retention-assessment__blockers">
                            {assessment.blockers.map((blocker) => (
                                <li key={blocker}>
                                    {blockerLabels[blocker] ?? blocker}
                                </li>
                            ))}
                        </ul>
                    </section>

                    <section aria-labelledby="retention-categories-title">
                        <h3 id="retention-categories-title">
                            Categorias de dados
                        </h3>
                        <div className="retention-assessment__categories">
                            {assessment.categories.map((category) => (
                                <article
                                    className="retention-assessment__category"
                                    key={category.code}
                                >
                                    <div className="retention-assessment__category-heading">
                                        <h4>
                                            {categoryLabels[category.code] ??
                                                category.code}
                                        </h4>
                                        <span
                                            className={`retention-assessment__status retention-assessment__status--${category.evaluationStatus.toLowerCase()}`}
                                        >
                                            {categoryStatus(category)}
                                        </span>
                                    </div>

                                    {category.evaluationStatus ===
                                        'SOURCE_UNAVAILABLE' && (
                                        <p className="retention-assessment__muted">
                                            A fonte não respondeu. Isso não
                                            representa zero registros.
                                        </p>
                                    )}
                                    {category.evaluationStatus ===
                                        'NOT_EVALUATED' && (
                                        <p className="retention-assessment__muted">
                                            Nenhuma consulta foi feita para esta
                                            categoria.
                                        </p>
                                    )}

                                    {Object.keys(category.counts).length >
                                        0 && (
                                        <dl className="retention-assessment__counts">
                                            {Object.entries(
                                                category.counts,
                                            ).map(([key, count]) => (
                                                <div key={key}>
                                                    <dt>
                                                        {countLabels[key] ??
                                                            key}
                                                    </dt>
                                                    <dd>
                                                        <span
                                                            className={
                                                                count.status ===
                                                                'AT_LEAST_LIMIT'
                                                                    ? 'retention-assessment__lower-bound'
                                                                    : undefined
                                                            }
                                                        >
                                                            {countText(count)}
                                                        </span>
                                                        {count.status ===
                                                            'AT_LEAST_LIMIT' && (
                                                            <small>
                                                                Limite de
                                                                contagem:{' '}
                                                                {new Intl.NumberFormat(
                                                                    'pt-BR',
                                                                ).format(
                                                                    count.limit,
                                                                )}
                                                            </small>
                                                        )}
                                                    </dd>
                                                </div>
                                            ))}
                                        </dl>
                                    )}

                                    {Object.entries(category.facts)
                                        .filter(([key]) => key in factLabels)
                                        .map(([key, value]) => (
                                            <p
                                                key={key}
                                                className="retention-assessment__fact"
                                            >
                                                <strong>
                                                    {factLabels[key]}:
                                                </strong>{' '}
                                                {value}
                                            </p>
                                        ))}
                                    {category.observedAt && (
                                        <p className="retention-assessment__observed">
                                            Consulta concluída em{' '}
                                            {formatReference(
                                                category.observedAt,
                                            )}
                                            .
                                        </p>
                                    )}
                                    {category.reasons.length > 0 && (
                                        <ul className="retention-assessment__reasons">
                                            {category.reasons.map((reason) => (
                                                <li key={reason}>
                                                    {reasonLabels[reason] ??
                                                        reason}
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                </article>
                            ))}
                        </div>
                    </section>
                </div>
            )}
        </section>
    );
}
