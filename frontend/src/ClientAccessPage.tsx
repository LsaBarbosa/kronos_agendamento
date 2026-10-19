import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { apiBaseUrl } from './config';
import { ClientAppointmentsArea } from './ClientAppointmentsArea';
import './ClientAccessPage.css';

type Csrf = { headerName: string; token: string };

export function ClientAccessPage({ slug }: { slug: string }) {
    const [phone, setPhone] = useState('');
    const [code, setCode] = useState('');
    const [challengeId, setChallengeId] = useState('');
    const [csrf, setCsrf] = useState<Csrf | null>(null);
    const [message, setMessage] = useState('');
    const [phase, setPhase] = useState<'phone' | 'code' | 'authenticated'>(
        'phone',
    );
    const [busy, setBusy] = useState(false);
    const [sessionChecking, setSessionChecking] = useState(true);
    const [uncertain, setUncertain] = useState(false);
    const [verifyUncertain, setVerifyUncertain] = useState(false);
    const [verifyRejected, setVerifyRejected] = useState(false);
    const requestIntent = useRef<{ phone: string; key: string } | null>(null);

    async function loadCsrfForMutation(): Promise<Csrf | null> {
        try {
            const response = await fetch(apiBaseUrl + '/v1/csrf', {
                credentials: 'same-origin',
                cache: 'no-store',
            });
            if (!response.ok) return null;
            const value = (await response.json()) as Partial<Csrf>;
            if (
                typeof value.headerName !== 'string' ||
                typeof value.token !== 'string' ||
                !value.token
            ) {
                return null;
            }
            const current = {
                headerName: value.headerName,
                token: value.token,
            };
            setCsrf(current);
            return current;
        } catch {
            return null;
        }
    }

    useEffect(() => {
        let active = true;
        void (async () => {
            try {
                const [csrfResponse, sessionResponse] = await Promise.all([
                    fetch(apiBaseUrl + '/v1/csrf', {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    }),
                    fetch(apiBaseUrl + '/v1/client/session', {
                        credentials: 'same-origin',
                        cache: 'no-store',
                    }),
                ]);
                if (!csrfResponse.ok || !sessionResponse.ok)
                    throw new Error('session');
                const [csrfToken, session] = await Promise.all([
                    csrfResponse.json() as Promise<Csrf>,
                    sessionResponse.json() as Promise<{
                        authenticated?: unknown;
                    }>,
                ]);
                if (!active) return;
                setCsrf(csrfToken);
                if (session.authenticated === true) {
                    setPhase('authenticated');
                    setMessage('Sessão cliente ativa.');
                }
            } catch {
                if (active)
                    setMessage(
                        'Não foi possível verificar a sessão agora. Tente novamente.',
                    );
            } finally {
                if (active) setSessionChecking(false);
            }
        })();
        return () => {
            active = false;
        };
    }, []);

    async function requestCode(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!csrf) {
            setMessage(
                'Proteção temporariamente indisponível. Tente novamente.',
            );
            return;
        }
        if (!requestIntent.current || requestIntent.current.phone !== phone) {
            requestIntent.current = { phone, key: crypto.randomUUID() };
        }
        setBusy(true);
        setMessage('Se os dados forem elegíveis, enviaremos um código.');
        const requestCsrf = await loadCsrfForMutation();
        if (!requestCsrf) {
            setMessage(
                'Não foi possível preparar a proteção da sessão. Tente novamente.',
            );
            setBusy(false);
            return;
        }
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/public/' +
                    encodeURIComponent(slug) +
                    '/access-challenges',
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: {
                        'Content-Type': 'application/json',
                        [requestCsrf.headerName]: requestCsrf.token,
                        'Idempotency-Key': requestIntent.current.key,
                    },
                    body: JSON.stringify({ phone }),
                },
            );
            if (!response.ok) {
                if (response.status === 400) {
                    const failure = (await response
                        .json()
                        .catch(() => null)) as {
                        error?: { code?: string };
                    } | null;
                    if (failure?.error?.code === 'INVALID_PHONE') {
                        requestIntent.current = null;
                        setUncertain(false);
                        setMessage(
                            'Telefone inválido. Informe o número completo com código do país e tente novamente.',
                        );
                        return;
                    }
                }
                throw new Error('request');
            }
            const result = (await response.json()) as {
                challengeAccepted: boolean;
                expiresInSeconds: number;
                challengeId: string | null;
            };
            if (!result.challengeId) {
                setMessage(
                    'A solicitação foi recebida. Se elegível, você receberá instruções; tente novamente mais tarde.',
                );
                setUncertain(true);
                return;
            }
            setChallengeId(result.challengeId);
            setPhase('code');
            setUncertain(false);
            setVerifyUncertain(false);
            setVerifyRejected(false);
            setMessage(
                'Se os dados forem elegíveis, enviaremos um código válido por até cinco minutos.',
            );
        } catch {
            setUncertain(true);
            setMessage(
                'Não foi possível confirmar a resposta. Mantenha os dados e repita a mesma solicitação; não criaremos uma nova identidade automaticamente.',
            );
        } finally {
            setBusy(false);
        }
    }

    async function verifyCode(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!csrf || !challengeId) return;
        const wasUncertain = verifyUncertain;
        setBusy(true);
        setMessage('Verificando código…');
        const requestCsrf = await loadCsrfForMutation();
        if (!requestCsrf) {
            setMessage(
                'Não foi possível preparar a proteção da sessão. Tente novamente.',
            );
            setBusy(false);
            return;
        }
        try {
            const response = await fetch(
                apiBaseUrl +
                    '/v1/public/' +
                    encodeURIComponent(slug) +
                    '/access-challenges/' +
                    encodeURIComponent(challengeId) +
                    '/verify',
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: {
                        'Content-Type': 'application/json',
                        [requestCsrf.headerName]: requestCsrf.token,
                    },
                    body: JSON.stringify({ code }),
                },
            );
            if (!response.ok) {
                if (wasUncertain) {
                    const sessionResponse = await fetch(
                        apiBaseUrl +
                            '/v1/client/session?slug=' +
                            encodeURIComponent(slug) +
                            '&challengeId=' +
                            encodeURIComponent(challengeId),
                        { credentials: 'same-origin', cache: 'no-store' },
                    ).catch(() => null);
                    if (!sessionResponse?.ok) {
                        setVerifyUncertain(true);
                        setMessage(
                            'A verificação ainda não pôde ser reconciliada. Tente recuperar novamente; não inicie outra solicitação.',
                        );
                        return;
                    }
                    const session = (await sessionResponse
                        .json()
                        .catch(() => null)) as {
                        authenticated?: unknown;
                    } | null;
                    if (
                        !session ||
                        typeof session.authenticated !== 'boolean'
                    ) {
                        setVerifyUncertain(true);
                        setMessage(
                            'A resposta de recuperação é inválida. Tente recuperar novamente; não inicie outra solicitação.',
                        );
                        return;
                    }
                    if (session.authenticated) {
                        setPhase('authenticated');
                        setMessage('Acesso verificado neste tenant.');
                        setPhone('');
                        setCode('');
                        setVerifyUncertain(false);
                        requestIntent.current = null;
                        return;
                    }
                }
                setVerifyUncertain(false);
                setVerifyRejected(true);
                setMessage(
                    'Código inválido, expirado ou temporariamente bloqueado. Solicite novo código mais tarde.',
                );
                return;
            }
            setPhase('authenticated');
            setMessage('Acesso verificado neste tenant.');
            setPhone('');
            setCode('');
            setVerifyUncertain(false);
            requestIntent.current = null;
        } catch {
            setVerifyUncertain(true);
            setMessage(
                'Não foi possível confirmar o resultado. Mantenha o mesmo código e tente novamente.',
            );
        } finally {
            setBusy(false);
        }
    }

    async function logout() {
        if (!csrf) return;
        setBusy(true);
        const requestCsrf = await loadCsrfForMutation();
        if (!requestCsrf) {
            setMessage(
                'Não foi possível preparar a proteção da sessão. Tente novamente.',
            );
            setBusy(false);
            return;
        }
        try {
            const response = await fetch(
                apiBaseUrl + '/v1/client/session/logout',
                {
                    method: 'POST',
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: { [requestCsrf.headerName]: requestCsrf.token },
                },
            );
            if (!response.ok) throw new Error('logout');
            setPhase('phone');
            setPhone('');
            setChallengeId('');
            setCode('');
            setVerifyUncertain(false);
            setUncertain(false);
            requestIntent.current = null;
            setMessage('Sessão encerrada.');
        } catch {
            setMessage('Não foi possível confirmar o encerramento.');
        } finally {
            setBusy(false);
        }
    }

    const handleSessionEnded = useCallback(() => {
        setPhase('phone');
        setPhone('');
        setCode('');
        setChallengeId('');
        setVerifyUncertain(false);
        setVerifyRejected(false);
        setUncertain(false);
        requestIntent.current = null;
        setMessage(
            'Sua sessão expirou. Autentique-se novamente para acessar suas reservas.',
        );
    }, []);

    return (
        <main className="client-access">
            <a href="/" className="client-access__back">
                Voltar
            </a>
            <section
                className="client-access__card"
                aria-labelledby="client-access-title"
            >
                <p className="client-access__eyebrow">Acesso do cliente</p>
                <h1 id="client-access-title">Entre com seu telefone</h1>
                {sessionChecking ? (
                    <output>Verificando sessão…</output>
                ) : phase === 'authenticated' ? (
                    <ClientAppointmentsArea
                        onLogout={logout}
                        onSessionEnded={handleSessionEnded}
                    />
                ) : phase === 'phone' ? (
                    <form onSubmit={(event) => void requestCode(event)}>
                        <label htmlFor="client-phone">
                            Telefone com código do país
                        </label>
                        <input
                            id="client-phone"
                            type="tel"
                            autoComplete="tel"
                            required
                            value={phone}
                            onChange={(event) => setPhone(event.target.value)}
                            disabled={busy || uncertain}
                            placeholder="+5511999999999"
                        />
                        <p>
                            Informe o código do país. Não presumimos uma região.
                        </p>
                        <button type="submit" disabled={busy || !csrf}>
                            {busy
                                ? 'Enviando…'
                                : uncertain
                                  ? 'Repetir mesma solicitação'
                                  : 'Solicitar código'}
                        </button>
                    </form>
                ) : (
                    <form onSubmit={(event) => void verifyCode(event)}>
                        <label htmlFor="client-code">Código de acesso</label>
                        <input
                            id="client-code"
                            inputMode="numeric"
                            autoComplete="one-time-code"
                            pattern="[0-9]{6}"
                            maxLength={6}
                            required
                            value={code}
                            onChange={(event) => {
                                setCode(
                                    event.target.value
                                        .replace(/\D/g, '')
                                        .slice(0, 6),
                                );
                                setVerifyRejected(false);
                            }}
                            disabled={busy || verifyUncertain}
                        />
                        <p>
                            O código expira em cinco minutos. Após expirar,
                            solicite outro.
                        </p>
                        <button
                            type="submit"
                            disabled={busy || code.length !== 6}
                        >
                            {busy
                                ? 'Verificando…'
                                : verifyUncertain
                                  ? 'Tentar novamente'
                                  : 'Verificar código'}
                        </button>
                        {verifyRejected && (
                            <button
                                type="button"
                                disabled={busy || !csrf}
                                onClick={() => {
                                    setCode('');
                                    setChallengeId('');
                                    setVerifyRejected(false);
                                    setPhase('phone');
                                    requestIntent.current = null;
                                    setMessage('Solicite um novo código.');
                                }}
                            >
                                Solicitar novo código
                            </button>
                        )}
                    </form>
                )}
                {message && phase !== 'authenticated' && (
                    <output aria-live="polite">{message}</output>
                )}
            </section>
        </main>
    );
}
