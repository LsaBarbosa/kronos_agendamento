import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { apiBaseUrl } from './config';
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
    const [uncertain, setUncertain] = useState(false);
    const [verifyUncertain, setVerifyUncertain] = useState(false);
    const [verifyRejected, setVerifyRejected] = useState(false);
    const requestIntent = useRef<{ phone: string; key: string } | null>(null);

    useEffect(() => {
        let active = true;
        void fetch(apiBaseUrl + '/v1/csrf', {
            credentials: 'same-origin',
            cache: 'no-store',
        })
            .then(async (response) => {
                if (!response.ok) throw new Error('csrf');
                const token = (await response.json()) as Csrf;
                if (active) setCsrf(token);
            })
            .catch(() => {
                if (active)
                    setMessage(
                        'Não foi possível preparar a proteção da sessão. Tente novamente.',
                    );
            });
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
                        [csrf.headerName]: csrf.token,
                        'Idempotency-Key': requestIntent.current.key,
                    },
                    body: JSON.stringify({ phone }),
                },
            );
            if (!response.ok) throw new Error('request');
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
                        [csrf.headerName]: csrf.token,
                    },
                    body: JSON.stringify({ code }),
                },
            );
            if (!response.ok) {
                if (wasUncertain) {
                    const sessionResponse = await fetch(
                        apiBaseUrl + '/v1/client/session',
                        { credentials: 'same-origin', cache: 'no-store' },
                    ).catch(() => null);
                    if (sessionResponse?.ok) {
                        const session = (await sessionResponse.json()) as {
                            authenticated: boolean;
                        };
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
        try {
            await fetch(apiBaseUrl + '/v1/client/session/logout', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { [csrf.headerName]: csrf.token },
            });
            setPhase('phone');
            setChallengeId('');
            setCode('');
            setMessage('Sessão encerrada.');
        } catch {
            setMessage('Não foi possível confirmar o encerramento.');
        } finally {
            setBusy(false);
        }
    }

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
                {phase === 'authenticated' ? (
                    <>
                        <output aria-live="polite">{message}</output>
                        <p>
                            Esta verificação não abre a área de reservas nesta
                            etapa.
                        </p>
                        <button
                            type="button"
                            disabled={busy}
                            onClick={() => void logout()}
                        >
                            Sair
                        </button>
                    </>
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
