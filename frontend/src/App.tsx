import { useEffect, useState } from 'react';
import { apiBaseUrl } from './config';

type Session = {
    authenticated: boolean;
    tenantId: string;
    role: string;
};

type Csrf = {
    headerName: string;
    token: string;
};

export function App() {
    const [session, setSession] = useState<Session | null>(null);
    const [csrf, setCsrf] = useState<Csrf | null>(null);
    const [status, setStatus] = useState('Verificando sessão…');

    useEffect(() => {
        let active = true;
        void fetch(`${apiBaseUrl}/v1/admin/session`, {
            credentials: 'same-origin',
            cache: 'no-store',
        })
            .then(async (response) => {
                if (!active) return;
                if (response.status === 401) {
                    setSession(null);
                    setStatus('Sua sessão expirou ou ainda não foi iniciada.');
                    return;
                }
                if (response.status === 403) {
                    setSession(null);
                    setStatus(
                        'Acesso administrativo negado. Verifique sua associação ao tenant.',
                    );
                    return;
                }
                if (!response.ok) {
                    setStatus(
                        'Não foi possível verificar a sessão. Tente novamente.',
                    );
                    return;
                }
                setSession((await response.json()) as Session);
                setStatus('Sessão administrativa ativa.');
            })
            .catch(() => {
                if (active) setStatus('Backend indisponível. Tente novamente.');
            });
        void fetch(`${apiBaseUrl}/v1/csrf`, {
            credentials: 'same-origin',
            cache: 'no-store',
        })
            .then(async (response) => {
                if (active && response.ok)
                    setCsrf((await response.json()) as Csrf);
            })
            .catch(() => {
                if (active)
                    setStatus('Não foi possível obter a proteção CSRF.');
            });
        return () => {
            active = false;
        };
    }, []);

    async function logout() {
        if (!csrf) {
            setStatus('Proteção CSRF ainda indisponível; tente novamente.');
            return;
        }
        const response = await fetch(`${apiBaseUrl}/v1/admin/logout`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { [csrf.headerName]: csrf.token },
        });
        setSession(null);
        setCsrf(null);
        setStatus(
            response.ok
                ? 'Sessão local encerrada.'
                : `Logout rejeitado (HTTP ${response.status}). Atualize a proteção CSRF e tente novamente.`,
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
                    </div>
                ) : (
                    <a
                        className="login-link"
                        href="/oauth2/authorization/keycloak"
                    >
                        Entrar como administrador
                    </a>
                )}
                <p className="api-note">
                    API: <code>{apiBaseUrl}</code>
                </p>
            </section>
        </main>
    );
}
