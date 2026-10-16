import { useCallback, useEffect, useRef, useState } from 'react';
import { apiBaseUrl } from './config';
import { commandKey } from './commandKey';

type Csrf = { headerName: string; token: string };
type Blocker = { code: string; message: string };
type Publication = {
    businessName: string;
    slug: string;
    timeZone: string;
    contactPhone: string | null;
    published: boolean;
    version: number;
    ready: boolean;
    blockers: Blocker[];
};

export function PublicPublicationPanel({
    enabled,
    csrf,
    onSessionInvalid,
}: {
    enabled: boolean;
    csrf: Csrf | null;
    onSessionInvalid: (status: number) => void;
}) {
    const [publication, setPublication] = useState<Publication | null>(null);
    const [contactPhone, setContactPhone] = useState('');
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');
    const pendingKeys = useRef(new Map<string, string>());

    const load = useCallback(async () => {
        if (!enabled) return;
        setError('');
        try {
            const response = await fetch(apiBaseUrl + '/v1/admin/publication', {
                credentials: 'same-origin',
                cache: 'no-store',
            });
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (!response.ok) throw new Error(`Falha HTTP ${response.status}.`);
            const result = (await response.json()) as Publication;
            setPublication(result);
            setContactPhone(result.contactPhone ?? '');
        } catch (loadError) {
            setError(
                loadError instanceof Error
                    ? loadError.message
                    : 'Não foi possível consultar o estado de publicação.',
            );
        } finally {
            setLoading(false);
        }
    }, [enabled, onSessionInvalid]);

    useEffect(() => {
        const timer = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(timer);
    }, [load]);

    async function submit(path: string, body: Record<string, unknown>) {
        if (!csrf || !publication) {
            setError('Sessão administrativa ou proteção CSRF indisponível.');
            return;
        }
        setSaving(true);
        setError('');
        setNotice('');
        const key = commandKey(pendingKeys.current, path, body);
        try {
            const response = await fetch(apiBaseUrl + path, {
                method: 'PUT',
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    [csrf.headerName]: csrf.token,
                    'Idempotency-Key': key,
                },
                body: JSON.stringify(body),
            });
            if (response.status === 401 || response.status === 403) {
                onSessionInvalid(response.status);
                return;
            }
            if (response.status === 409) {
                setError(
                    'A publicação mudou ou ainda tem requisitos pendentes. Preservei seus dados; recarregue para revisar o estado atual.',
                );
                return;
            }
            if (!response.ok) {
                const failure = (await response.json()) as {
                    error?: {
                        message?: string;
                        details?: { blockers?: Blocker[] };
                    };
                };
                const blockers = failure.error?.details?.blockers ?? [];
                setError(
                    blockers.length > 0
                        ? blockers.map((blocker) => blocker.message).join(' ')
                        : (failure.error?.message ??
                              `Falha HTTP ${response.status}.`),
                );
                return;
            }
            pendingKeys.current.delete(JSON.stringify([path, body]));
            const result = (await response.json()) as Publication;
            setPublication(result);
            setContactPhone(result.contactPhone ?? '');
            setNotice(
                path.endsWith('/publication')
                    ? result.published
                        ? 'Página pública publicada.'
                        : 'Página pública despublicada.'
                    : 'Telefone de contato salvo.',
            );
        } catch {
            setError(
                'A resposta não chegou. Você pode repetir o mesmo comando com segurança.',
            );
        } finally {
            setSaving(false);
        }
    }

    if (!enabled) return null;

    return (
        <section aria-labelledby="publication-title">
            <h2 id="publication-title">Página pública</h2>
            <p>
                O endereço <code>/public/{publication?.slug ?? '…'}</code> só
                fica acessível após publicação e validação contínua dos
                requisitos. Fuso horário:{' '}
                <code>{publication?.timeZone ?? '…'}</code>.
            </p>
            {(loading || (enabled && !publication)) && (
                <output>Verificando prontidão…</output>
            )}
            {publication && (
                <>
                    <output>
                        Estado:{' '}
                        {publication.published ? 'Publicado' : 'Despublicado'}
                        {publication.ready
                            ? ' · Pronto para publicação.'
                            : ' · Ainda não está pronto.'}
                    </output>
                    <form
                        onSubmit={(event) => {
                            event.preventDefault();
                            void submit('/v1/admin/public-profile', {
                                contactPhone,
                                expectedVersion: publication.version,
                            });
                        }}
                    >
                        <label>
                            Telefone de contato público
                            <input
                                required
                                minLength={6}
                                maxLength={32}
                                value={contactPhone}
                                onChange={(event) =>
                                    setContactPhone(event.target.value)
                                }
                            />
                        </label>
                        <button type="submit" disabled={!csrf || saving}>
                            {saving ? 'Salvando…' : 'Salvar telefone'}
                        </button>
                    </form>
                    {!publication.ready && (
                        <div aria-label="Itens pendentes para publicação">
                            <h3>Itens pendentes</h3>
                            <ul>
                                {publication.blockers.map((blocker) => (
                                    <li key={blocker.code}>
                                        {blocker.message}
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}
                    <button
                        type="button"
                        disabled={
                            !csrf ||
                            saving ||
                            (!publication.published && !publication.ready)
                        }
                        onClick={() =>
                            void submit('/v1/admin/publication', {
                                published: !publication.published,
                                expectedVersion: publication.version,
                            })
                        }
                    >
                        {publication.published
                            ? 'Despublicar página'
                            : 'Publicar página'}
                    </button>
                    <button
                        type="button"
                        disabled={loading || saving}
                        onClick={() => {
                            setLoading(true);
                            void load();
                        }}
                    >
                        Recarregar prontidão
                    </button>
                </>
            )}
            {error && <p role="alert">{error}</p>}
            {notice && <output>{notice}</output>}
        </section>
    );
}
