import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';
import { SchedulePanel } from './SchedulePanel';
import { ScheduleExceptionsPanel } from './ScheduleExceptionsPanel';
import { PublicPage } from './PublicPage';
import { PublicPublicationPanel } from './PublicPublicationPanel';
import { AdminAgendaPanel } from './AdminAgendaPanel';
import { ClientAccessPage } from './ClientAccessPage';
import { ClientNoShowPolicyPanel } from './ClientNoShowPolicyPanel';

type Session = { authenticated: boolean; tenantId: string; role: string };
type Csrf = { headerName: string; token: string };
type Service = {
    serviceId: string;
    name: string;
    description: string | null;
    durationMinutes: number;
    bufferAfterMinutes: number;
    status: 'ACTIVE' | 'INACTIVE' | '';
    version: number;
};
type Draft = {
    name: string;
    description: string;
    durationMinutes: string;
    bufferAfterMinutes: string;
    status: 'ACTIVE' | 'INACTIVE' | '';
};

const emptyDraft: Draft = {
    name: '',
    description: '',
    durationMinutes: '30',
    bufferAfterMinutes: '0',
    status: '',
};

async function responseError(response: Response): Promise<string> {
    try {
        const body = (await response.json()) as {
            error?: { message?: string };
        };
        return body.error?.message ?? 'Falha HTTP ' + response.status + '.';
    } catch {
        return 'Falha HTTP ' + response.status + '.';
    }
}

function AdminApp() {
    const [session, setSession] = useState<Session | null>(null);
    const [csrf, setCsrf] = useState<Csrf | null>(null);
    const [status, setStatus] = useState('Verificando sessão…');
    const [services, setServices] = useState<Service[]>([]);
    const [catalogLoaded, setCatalogLoaded] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const [draft, setDraft] = useState<Draft>(emptyDraft);
    const [editing, setEditing] = useState<Service | null>(null);
    const [saving, setSaving] = useState(false);
    const pendingKeys = useRef(new Map<string, string>());

    useEffect(() => {
        let active = true;
        void (async () => {
            try {
                const response = await fetch(apiBaseUrl + '/v1/admin/session', {
                    credentials: 'same-origin',
                    cache: 'no-store',
                });
                if (!active) return;
                if (response.status === 401) {
                    setStatus('Sua sessão expirou ou ainda não foi iniciada.');
                    return;
                }
                if (response.status === 403) {
                    setStatus('Acesso administrativo negado.');
                    return;
                }
                if (!response.ok) {
                    setStatus(
                        'Não foi possível verificar a sessão. Tente novamente.',
                    );
                    return;
                }
                const authenticatedSession = (await response.json()) as Session;
                setSession(authenticatedSession);
                setStatus('Sessão administrativa ativa.');
                const csrfResponse = await fetch(apiBaseUrl + '/v1/csrf', {
                    credentials: 'same-origin',
                    cache: 'no-store',
                });
                if (!active) return;
                if (!csrfResponse.ok) {
                    setStatus('Não foi possível obter a proteção CSRF.');
                    return;
                }
                setCsrf((await csrfResponse.json()) as Csrf);
            } catch {
                if (active) setStatus('Backend indisponível. Tente novamente.');
            }
        })();
        return () => {
            active = false;
        };
    }, []);

    useEffect(() => {
        if (!session) return;
        let active = true;
        void fetch(apiBaseUrl + '/v1/admin/services', {
            credentials: 'same-origin',
            cache: 'no-store',
        })
            .then(async (response) => {
                if (!active) return;
                if (response.status === 401) {
                    setSession(null);
                    setStatus(
                        'Sua sessão expirou. Entre novamente para continuar.',
                    );
                    return;
                }
                if (response.status === 403) {
                    setSession(null);
                    setStatus('Acesso administrativo negado.');
                    return;
                }
                if (!response.ok) {
                    setError(await responseError(response));
                    return;
                }
                setServices((await response.json()) as Service[]);
            })
            .catch(() => {
                if (active) setError('Não foi possível carregar o catálogo.');
            })
            .finally(() => {
                if (active) setCatalogLoaded(true);
            });
        return () => {
            active = false;
        };
    }, [session]);

    function resetForm() {
        setDraft(emptyDraft);
        setEditing(null);
    }

    function edit(service: Service) {
        setEditing(service);
        setDraft({
            name: service.name,
            description: service.description ?? '',
            durationMinutes: String(service.durationMinutes),
            bufferAfterMinutes: String(service.bufferAfterMinutes),
            status: service.status,
        });
        setNotice('');
    }

    async function save(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!csrf) {
            setError('Proteção CSRF ainda indisponível.');
            return;
        }
        setSaving(true);
        setError('');
        setNotice('');
        const endpoint = editing
            ? apiBaseUrl + '/v1/admin/services/' + editing.serviceId
            : apiBaseUrl + '/v1/admin/services';
        const operationKey = editing ? 'update:' + editing.serviceId : 'create';
        const body = editing
            ? {
                  name: draft.name,
                  description: draft.description,
                  durationMinutes: Number(draft.durationMinutes),
                  bufferAfterMinutes: Number(draft.bufferAfterMinutes),
                  expectedVersion: editing.version,
              }
            : {
                  name: draft.name,
                  description: draft.description,
                  durationMinutes: Number(draft.durationMinutes),
                  bufferAfterMinutes: Number(draft.bufferAfterMinutes),
                  status: draft.status,
              };
        const key = commandKey(pendingKeys.current, operationKey, body);
        try {
            const response = await fetch(endpoint, {
                method: editing ? 'PATCH' : 'POST',
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    [csrf.headerName]: csrf.token,
                    'Idempotency-Key': key,
                },
                body: JSON.stringify(body),
            });
            if (response.status === 401) {
                setSession(null);
                setStatus(
                    'Sua sessão expirou. Entre novamente para continuar.',
                );
                return;
            }
            if (response.status === 403) {
                setError(
                    'Ação não permitida. Atualize a sessão e tente novamente.',
                );
                return;
            }
            if (!response.ok) {
                setError(await responseError(response));
                return;
            }
            const saved = (await response.json()) as Service;
            pendingKeys.current.delete(JSON.stringify([operationKey, body]));
            setServices((current) => {
                const unchanged = current.filter(
                    (item) => item.serviceId !== saved.serviceId,
                );
                return [...unchanged, saved].sort((left, right) =>
                    left.name.localeCompare(right.name),
                );
            });
            setNotice(editing ? 'Serviço atualizado.' : 'Serviço criado.');
            resetForm();
        } catch {
            setError(
                'Não foi possível salvar. Verifique a conexão e tente novamente.',
            );
        } finally {
            setSaving(false);
        }
    }

    async function changeStatus(service: Service) {
        if (!csrf) {
            setError('Proteção CSRF ainda indisponível.');
            return;
        }
        const action = service.status === 'ACTIVE' ? 'deactivate' : 'activate';
        const operationKey = 'status:' + service.serviceId + ':' + action;
        const body = { expectedVersion: service.version };
        const key = commandKey(pendingKeys.current, operationKey, body);
        setError('');
        setNotice('');
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/admin/services/' +
                    service.serviceId +
                    '/' +
                    action,
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
            if (response.status === 401) {
                setSession(null);
                setStatus(
                    'Sua sessão expirou. Entre novamente para continuar.',
                );
                return;
            }
            if (!response.ok) {
                setError(await responseError(response));
                return;
            }
            const updated = (await response.json()) as Service;
            pendingKeys.current.delete(JSON.stringify([operationKey, body]));
            setServices((current) =>
                current.map((item) =>
                    item.serviceId === updated.serviceId ? updated : item,
                ),
            );
            setNotice(
                updated.status === 'ACTIVE'
                    ? 'Serviço ativado.'
                    : 'Serviço inativado.',
            );
        } catch {
            setError('Não foi possível atualizar o status do serviço.');
        }
    }

    async function logout() {
        if (!csrf) {
            setStatus('Proteção CSRF ainda indisponível; tente novamente.');
            return;
        }
        const response = await fetch(apiBaseUrl + '/v1/admin/logout', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { [csrf.headerName]: csrf.token },
        });
        setSession(null);
        setCsrf(null);
        setServices([]);
        pendingKeys.current.clear();
        setStatus(
            response.ok ? 'Sessão local encerrada.' : 'Logout rejeitado.',
        );
    }

    return (
        <main className="page-shell">
            <section className="foundation-card" aria-labelledby="page-title">
                <p className="eyebrow">Área administrativa</p>
                <h1 id="page-title">Agendamento</h1>
                <p className="description">
                    Acesso protegido por sessão do backend e identidade
                    Keycloak.
                </p>
                <output aria-live="polite">{status}</output>
                {session ? (
                    <div>
                        <p>
                            Tenant autorizado: <code>{session.tenantId}</code>
                        </p>
                        <p>
                            Papel: <code>{session.role}</code>
                        </p>
                        <button
                            type="button"
                            disabled={!csrf}
                            onClick={() => void logout()}
                        >
                            {csrf ? 'Sair' : 'Aguardando proteção CSRF…'}
                        </button>
                        <section aria-labelledby="catalog-title">
                            <h2 id="catalog-title">Catálogo de serviços</h2>
                            <form onSubmit={(event) => void save(event)}>
                                <label>
                                    Nome
                                    <input
                                        required
                                        value={draft.name}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                name: event.target.value,
                                            })
                                        }
                                    />
                                </label>
                                <label>
                                    Descrição
                                    <textarea
                                        value={draft.description}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                description: event.target.value,
                                            })
                                        }
                                    />
                                </label>
                                <label>
                                    Duração (minutos)
                                    <input
                                        required
                                        type="number"
                                        min="15"
                                        step="15"
                                        value={draft.durationMinutes}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                durationMinutes:
                                                    event.target.value,
                                            })
                                        }
                                    />
                                </label>
                                <label>
                                    Intervalo posterior (minutos)
                                    <input
                                        required
                                        type="number"
                                        min="0"
                                        step="15"
                                        value={draft.bufferAfterMinutes}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                bufferAfterMinutes:
                                                    event.target.value,
                                            })
                                        }
                                    />
                                </label>
                                {!editing && (
                                    <label>
                                        Status inicial
                                        <select
                                            required
                                            value={draft.status}
                                            onChange={(event) =>
                                                setDraft({
                                                    ...draft,
                                                    status: event.target
                                                        .value as Draft['status'],
                                                })
                                            }
                                        >
                                            <option value="" disabled>
                                                Selecione
                                            </option>
                                            <option value="INACTIVE">
                                                Inativo
                                            </option>
                                            <option value="ACTIVE">
                                                Ativo
                                            </option>
                                        </select>
                                    </label>
                                )}
                                <button
                                    type="submit"
                                    disabled={!csrf || saving}
                                >
                                    {saving
                                        ? 'Salvando…'
                                        : editing
                                          ? 'Salvar alterações'
                                          : 'Criar serviço'}
                                </button>
                                {editing && (
                                    <button type="button" onClick={resetForm}>
                                        Cancelar edição
                                    </button>
                                )}
                            </form>
                            {notice && <output>{notice}</output>}
                            {error && <p role="alert">{error}</p>}
                            {!catalogLoaded ? (
                                <p>Carregando serviços…</p>
                            ) : services.length === 0 ? (
                                <p>Nenhum serviço cadastrado.</p>
                            ) : (
                                <ul aria-label="Serviços cadastrados">
                                    {services.map((service) => (
                                        <li key={service.serviceId}>
                                            <h3>{service.name}</h3>
                                            <p>
                                                {service.durationMinutes} min ·
                                                intervalo{' '}
                                                {service.bufferAfterMinutes} min
                                                ·{' '}
                                                {service.status === 'ACTIVE'
                                                    ? 'Ativo'
                                                    : 'Inativo'}
                                            </p>
                                            {service.description && (
                                                <p>{service.description}</p>
                                            )}
                                            <button
                                                type="button"
                                                onClick={() => edit(service)}
                                            >
                                                Editar
                                            </button>
                                            <button
                                                type="button"
                                                onClick={() =>
                                                    void changeStatus(service)
                                                }
                                            >
                                                {service.status === 'ACTIVE'
                                                    ? 'Inativar'
                                                    : 'Ativar'}
                                            </button>
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </section>
                    </div>
                ) : (
                    <a
                        className="login-link"
                        href="/oauth2/authorization/keycloak"
                    >
                        Entrar como administrador
                    </a>
                )}
                <SchedulePanel
                    enabled={Boolean(session)}
                    csrf={csrf}
                    onSessionInvalid={(responseStatus) => {
                        setSession(null);
                        setCsrf(null);
                        setStatus(
                            responseStatus === 401
                                ? 'Sua sessão expirou. Entre novamente para continuar.'
                                : 'Sua associação administrativa foi revogada ou o acesso foi negado.',
                        );
                    }}
                />
                <ScheduleExceptionsPanel
                    enabled={Boolean(session)}
                    csrf={csrf}
                    onSessionInvalid={(responseStatus) => {
                        setSession(null);
                        setCsrf(null);
                        setStatus(
                            responseStatus === 401
                                ? 'Sua sessão expirou. Entre novamente para continuar.'
                                : 'Sua associação administrativa foi revogada ou o acesso foi negado.',
                        );
                    }}
                />
                {session && (
                    <ClientNoShowPolicyPanel
                        enabled
                        csrf={csrf}
                        onSessionInvalid={(responseStatus) => {
                            setSession(null);
                            setCsrf(null);
                            setStatus(
                                responseStatus === 401
                                    ? 'Sua sessão expirou. Entre novamente para continuar.'
                                    : 'Sua associação administrativa foi revogada ou o acesso foi negado.',
                            );
                        }}
                    />
                )}
                <AdminAgendaPanel
                    enabled={Boolean(session)}
                    csrf={csrf}
                    onSessionInvalid={(responseStatus) => {
                        setSession(null);
                        setCsrf(null);
                        setStatus(
                            responseStatus === 401
                                ? 'Sua sessão expirou. Entre novamente para continuar.'
                                : 'Sua associação administrativa foi revogada ou o acesso foi negado.',
                        );
                    }}
                />
                <PublicPublicationPanel
                    enabled={Boolean(session)}
                    csrf={csrf}
                    onSessionInvalid={(responseStatus) => {
                        setSession(null);
                        setCsrf(null);
                        setStatus(
                            responseStatus === 401
                                ? 'Sua sessão expirou. Entre novamente para continuar.'
                                : 'Sua associação administrativa foi revogada ou o acesso foi negado.',
                        );
                    }}
                />
                <p className="api-note">
                    API: <code>{apiBaseUrl}</code>
                </p>
            </section>
        </main>
    );
}

export function App() {
    const clientAccessMatch = /^\/client-access\/([^/]+)\/?$/.exec(
        window.location.pathname,
    );
    if (clientAccessMatch) {
        let slug = clientAccessMatch[1];
        try {
            slug = decodeURIComponent(slug);
        } catch {
            slug = '';
        }
        return <ClientAccessPage slug={slug} />;
    }
    const match = /^\/public\/([^/]+)\/?$/.exec(window.location.pathname);
    if (match) {
        let slug = match[1];
        try {
            slug = decodeURIComponent(slug);
        } catch {
            slug = '';
        }
        return <PublicPage slug={slug} />;
    }
    return <AdminApp />;
}
