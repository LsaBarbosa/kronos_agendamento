import { useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import { apiBaseUrl as defaultApiBaseUrl } from './config';
import {
    createPublicBooking,
    getPublicBookingIntent,
    newPublicBookingKey,
    newPublicBookingRecoveryCapability,
    PublicBookingError,
} from './publicBookingApi';
import type {
    PublicBookingConfirmation,
    PublicBookingRecovery,
    PublicBookingRequest,
} from './publicBookingApi';
import './PublicPage.css';

export type PublicBusiness = {
    name: string;
    slug: string;
    timeZone: string;
    contactPhone: string | null;
};

export type PublicService = {
    serviceId: string;
    name: string;
    description: string | null;
    durationMinutes: number;
};

export type PublicSlot = {
    startsAt: string;
    serviceEndsAt: string;
};

export type PublicAvailability = {
    serviceId: string;
    from: string;
    to: string;
    timeZone: string;
    generatedAt: string;
    configured: boolean;
    days: {
        date: string;
        explicitlyClosed: boolean;
        slots: PublicSlot[];
    }[];
};

export type PublicTelemetryEvent =
    'SERVICE_SELECTED' | 'SLOT_SELECTED' | 'FLOW_ABANDONED';

type StoredBookingIntent = {
    idempotencyKey: string;
    recoveryCapability: string;
    serviceId: string;
    date: string;
    startsAt: string;
    serviceEndsAt: string;
};

type BookingIntentClaim =
    | { blocked: true; sharedIntent: StoredBookingIntent | null }
    | {
          blocked: false;
          claimedIntent: {
              fingerprint: string;
              key: string;
              recoveryCapability: string;
              body: PublicBookingRequest;
          };
      };

function bookingIntentStorageKey(slug: string): string {
    return 'kronos.public-booking.intent.' + slug;
}

function parseStoredBookingIntent(
    value: string | null,
): StoredBookingIntent | null {
    try {
        if (!value) return null;
        const parsed: unknown = JSON.parse(value);
        if (typeof parsed !== 'object' || parsed === null) return null;
        const candidate = parsed as Partial<StoredBookingIntent>;
        if (
            typeof candidate.idempotencyKey !== 'string' ||
            typeof candidate.recoveryCapability !== 'string' ||
            !/^[A-Za-z0-9_-]{43}$/.test(candidate.recoveryCapability) ||
            typeof candidate.serviceId !== 'string' ||
            typeof candidate.date !== 'string' ||
            typeof candidate.startsAt !== 'string' ||
            typeof candidate.serviceEndsAt !== 'string'
        ) {
            return null;
        }
        return candidate as StoredBookingIntent;
    } catch {
        return null;
    }
}

function readStoredBookingIntent(slug: string): StoredBookingIntent | null {
    try {
        return parseStoredBookingIntent(
            window.localStorage.getItem(bookingIntentStorageKey(slug)),
        );
    } catch {
        return null;
    }
}

async function withBookingIntentLock<T>(
    slug: string,
    action: () => T | Promise<T>,
): Promise<T> {
    if (!navigator.locks)
        throw new Error(
            'Web Locks API indisponível para reservar com segurança.',
        );
    return navigator.locks.request(
        'kronos.public-booking.intent.' + slug,
        { mode: 'exclusive' },
        action,
    );
}

function persistBookingIntent(
    slug: string,
    intent: StoredBookingIntent,
): boolean {
    try {
        window.localStorage.setItem(
            bookingIntentStorageKey(slug),
            JSON.stringify(intent),
        );
        return true;
    } catch {
        return false;
    }
}

export function unavailableBookingOutcome(wasUncertain: boolean) {
    return wasUncertain
        ? {
              state: 'uncertain' as const,
              message:
                  'Esta página pública deixou de estar disponível. Não foi possível verificar o resultado da operação original; não inicie outra reserva. Tente recuperar quando o serviço estiver disponível.',
          }
        : {
              state: 'unavailable' as const,
              message: 'Esta página pública não está mais disponível.',
          };
}

export type PublicPageState =
    | 'loading'
    | 'ready'
    | 'empty-services'
    | 'not-published'
    | 'temporarily-unavailable'
    | 'error';

export class PublicApiError extends Error {
    constructor(
        readonly status: number,
        message: string,
    ) {
        super(message);
    }
}

export class LatestRequest {
    private sequence = 0;
    private controller: AbortController | null = null;

    begin() {
        this.controller?.abort();
        this.controller = new AbortController();
        const requestSequence = ++this.sequence;
        const signal = this.controller.signal;
        return {
            signal,
            isCurrent: () =>
                requestSequence === this.sequence && !signal.aborted,
        };
    }

    cancel() {
        this.sequence += 1;
        this.controller?.abort();
        this.controller = null;
    }
}

function publicPath(slug: string, apiBaseUrl: string) {
    return apiBaseUrl + '/v1/public/' + encodeURIComponent(slug);
}

async function readResponse<T>(response: Response): Promise<T> {
    if (response.status === 503) {
        throw new PublicApiError(
            503,
            'Disponibilidade temporariamente indisponível.',
        );
    }
    if (response.status === 404) {
        throw new PublicApiError(404, 'Negócio não publicado ou indisponível.');
    }
    if (!response.ok) {
        throw new PublicApiError(
            response.status,
            'Não foi possível concluir a consulta.',
        );
    }
    return (await response.json()) as T;
}

export async function loadPublicBusiness(
    slug: string,
    apiBaseUrl = defaultApiBaseUrl,
    signal?: AbortSignal,
): Promise<PublicBusiness> {
    const response = await fetch(publicPath(slug, apiBaseUrl), {
        signal,
        headers: { Accept: 'application/json' },
    });
    return readResponse<PublicBusiness>(response);
}

export async function loadPublicServices(
    slug: string,
    apiBaseUrl = defaultApiBaseUrl,
    signal?: AbortSignal,
): Promise<PublicService[]> {
    const response = await fetch(publicPath(slug, apiBaseUrl) + '/services', {
        signal,
        headers: { Accept: 'application/json' },
    });
    const services = await readResponse<PublicService[]>(response);
    return services.filter(
        (service) =>
            typeof service.serviceId === 'string' &&
            typeof service.name === 'string' &&
            Number.isFinite(service.durationMinutes),
    );
}

export async function loadPublicAvailability(
    slug: string,
    serviceId: string,
    date: string,
    apiBaseUrl = defaultApiBaseUrl,
    signal?: AbortSignal,
): Promise<PublicAvailability> {
    const query = new URLSearchParams({ serviceId, from: date, to: date });
    const response = await fetch(
        publicPath(slug, apiBaseUrl) + '/availability?' + query.toString(),
        { signal, headers: { Accept: 'application/json' } },
    );
    return readResponse<PublicAvailability>(response);
}

export async function publishTelemetry(
    slug: string,
    event: PublicTelemetryEvent,
    apiBaseUrl = defaultApiBaseUrl,
    keepalive = false,
): Promise<void> {
    try {
        await fetch(publicPath(slug, apiBaseUrl) + '/telemetry', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ event }),
            keepalive,
        });
    } catch {
        return;
    }
}

export function shiftLocalDate(date: string, days: number): string {
    const [year, month, day] = date.split('-').map(Number);
    const shifted = new Date(Date.UTC(year, month - 1, day + days));
    return [
        shifted.getUTCFullYear(),
        String(shifted.getUTCMonth() + 1).padStart(2, '0'),
        String(shifted.getUTCDate()).padStart(2, '0'),
    ].join('-');
}

function formatTime(instant: string, timeZone: string): string {
    return new Intl.DateTimeFormat('pt-BR', {
        timeZone,
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
    }).format(new Date(instant));
}

function formatDate(date: string, timeZone: string): string {
    return (
        new Intl.DateTimeFormat('pt-BR', {
            timeZone: 'UTC',
            weekday: 'long',
            day: 'numeric',
            month: 'long',
            year: 'numeric',
        }).format(new Date(date + 'T12:00:00Z')) +
        ' · ' +
        timeZone
    );
}

function localDate(instant: string, timeZone: string): string {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).formatToParts(new Date(instant));
    const values = Object.fromEntries(
        parts.map((part) => [part.type, part.value]),
    );
    return `${values.year}-${values.month}-${values.day}`;
}

function formatDuration(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    if (hours === 0) return minutes + ' min';
    if (remainingMinutes === 0)
        return hours + (hours === 1 ? ' hora' : ' horas');
    return hours + ' h ' + remainingMinutes + ' min';
}

function PublicPageContent({
    slug,
    apiBaseUrl = defaultApiBaseUrl,
}: {
    slug: string;
    apiBaseUrl?: string;
}) {
    const [storedIntent, setStoredIntent] = useState(() =>
        readStoredBookingIntent(slug),
    );
    const [recoveryLookupIntent, setRecoveryLookupIntent] = useState(() =>
        readStoredBookingIntent(slug),
    );
    const [initialStoredDate] = useState(() => storedIntent?.date ?? '');
    const [business, setBusiness] = useState<PublicBusiness | null>(null);
    const [services, setServices] = useState<PublicService[]>([]);
    const [servicesState, setServicesState] =
        useState<PublicPageState>('loading');
    const [serviceId, setServiceId] = useState(
        () => storedIntent?.serviceId ?? '',
    );
    const [date, setDate] = useState(() => storedIntent?.date ?? '');
    const [availability, setAvailability] = useState<PublicAvailability | null>(
        null,
    );
    const [availabilityState, setAvailabilityState] =
        useState<PublicPageState>('loading');
    const [selectedSlot, setSelectedSlot] = useState<PublicSlot | null>(() =>
        storedIntent
            ? {
                  startsAt: storedIntent.startsAt,
                  serviceEndsAt: storedIntent.serviceEndsAt,
              }
            : null,
    );
    const [clientName, setClientName] = useState('');
    const [clientPhone, setClientPhone] = useState('');
    const [bookingState, setBookingState] = useState<
        | 'idle'
        | 'submitting'
        | 'uncertain'
        | 'validation-error'
        | 'slot-conflict'
        | 'unavailable'
        | 'recovery-unavailable'
        | 'error'
        | 'confirmed'
    >(storedIntent ? 'uncertain' : 'idle');
    const [bookingMessage, setBookingMessage] = useState(
        storedIntent ? 'Verificando a intenção anterior…' : '',
    );
    const [confirmation, setConfirmation] =
        useState<PublicBookingConfirmation | null>(null);
    const [availabilityRefresh, setAvailabilityRefresh] = useState(0);
    const [recoveryRefresh, setRecoveryRefresh] = useState(0);
    const [recoveryNeedsDetails, setRecoveryNeedsDetails] = useState(false);
    const [recoveryLookupFailed, setRecoveryLookupFailed] = useState(false);
    const [recoveryFromReload, setRecoveryFromReload] = useState(() =>
        Boolean(storedIntent),
    );
    const [announcement, setAnnouncement] = useState('');
    const [errorMessage, setErrorMessage] = useState('');
    const availabilityRequests = useRef(new LatestRequest());
    const businessRequests = useRef(new LatestRequest());
    const selectionMade = useRef(false);
    const bookingInFlight = useRef(false);
    const bookingIntent = useRef<{
        fingerprint: string | null;
        key: string;
        recoveryCapability: string;
        body: PublicBookingRequest | null;
    } | null>(null);
    const recoveryPollTimer = useRef<number | null>(null);
    const recoveryPollAttempts = useRef(0);

    function scheduleRecoveryPoll(): boolean {
        if (recoveryPollAttempts.current >= 8) return false;
        if (recoveryPollTimer.current !== null)
            window.clearTimeout(recoveryPollTimer.current);
        const delay = Math.min(250 * 2 ** recoveryPollAttempts.current, 2000);
        recoveryPollTimer.current = window.setTimeout(() => {
            recoveryPollTimer.current = null;
            recoveryPollAttempts.current += 1;
            setRecoveryRefresh((current) => current + 1);
        }, delay);
        return true;
    }

    useEffect(() => {
        const stored = recoveryLookupIntent;
        if (!stored) {
            bookingIntent.current = null;
            return;
        }
        const previousIntent = bookingIntent.current;
        bookingIntent.current = {
            fingerprint: null,
            key: stored.idempotencyKey,
            recoveryCapability: stored.recoveryCapability,
            body:
                previousIntent?.key === stored.idempotencyKey
                    ? previousIntent.body
                    : null,
        };
        void getPublicBookingIntent(slug, stored.recoveryCapability, apiBaseUrl)
            .then((result: PublicBookingRecovery) => {
                if (result.state === 'COMPLETED') {
                    recoveryPollAttempts.current = 0;
                    setConfirmation(result.receipt);
                    setBookingState('confirmed');
                    setBookingMessage('Reserva original recuperada.');
                    setAnnouncement('Reserva confirmada recuperada.');
                } else {
                    setRecoveryNeedsDetails(!bookingIntent.current?.body);
                    setBookingState('uncertain');
                    setBookingMessage(
                        'A operação ainda pode estar em andamento. Consultando novamente sem criar outra reserva; se continuar incerta, reintroduza os mesmos dados para repetir a mesma intenção.',
                    );
                    if (!scheduleRecoveryPoll()) {
                        setBookingMessage(
                            'O resultado continua incerto. Reintroduza os mesmos dados e repita somente esta intenção; a mesma chave será preservada.',
                        );
                    }
                }
            })
            .catch((error: unknown) => {
                if (
                    error instanceof PublicBookingError &&
                    error.status === 404
                ) {
                    setBookingState('uncertain');
                    setBookingMessage(
                        'A intenção ainda não está visível no servidor. O resultado não foi considerado ausente; consultando novamente com a mesma identidade.',
                    );
                    if (!scheduleRecoveryPoll()) {
                        setRecoveryNeedsDetails(true);
                        setBookingMessage(
                            'Não foi possível localizar a intenção após novas consultas. Não inicie outra reserva. Reintroduza os mesmos dados para repetir apenas a identidade original.',
                        );
                    }
                } else {
                    setBookingState('uncertain');
                    setRecoveryLookupFailed(true);
                    setBookingMessage(
                        'A recuperação está temporariamente indisponível. O resultado continua incerto; consulte novamente antes de qualquer nova intenção.',
                    );
                    if (!scheduleRecoveryPoll()) {
                        setRecoveryNeedsDetails(true);
                        setRecoveryLookupFailed(false);
                    }
                }
            });
        return () => {
            if (recoveryPollTimer.current !== null) {
                window.clearTimeout(recoveryPollTimer.current);
                recoveryPollTimer.current = null;
            }
        };
    }, [apiBaseUrl, recoveryLookupIntent, recoveryRefresh, slug]);

    useEffect(() => {
        const onStorage = (event: StorageEvent) => {
            if (event.key === bookingIntentStorageKey(slug)) {
                if (bookingInFlight.current || bookingState === 'confirmed')
                    return;
                const nextIntent = parseStoredBookingIntent(event.newValue);
                if (nextIntent) {
                    adoptSharedIntent(nextIntent);
                    setRecoveryRefresh((current) => current + 1);
                }
            }
        };
        window.addEventListener('storage', onStorage);
        return () => window.removeEventListener('storage', onStorage);
    }, [bookingState, slug]);

    useEffect(() => {
        const requests = businessRequests.current;
        const request = requests.begin();
        selectionMade.current = false;

        void Promise.all([
            loadPublicBusiness(slug, apiBaseUrl, request.signal),
            loadPublicServices(slug, apiBaseUrl, request.signal),
        ])
            .then(([publicBusiness, publicServices]) => {
                if (!request.isCurrent()) return;
                setBusiness(publicBusiness);
                setServices(publicServices);
                setDate((current) => current || initialStoredDate);
                setServicesState(
                    publicServices.length === 0 ? 'empty-services' : 'ready',
                );
                setAnnouncement(
                    publicServices.length === 0
                        ? 'Nenhum serviço disponível.'
                        : 'Página do negócio carregada.',
                );
            })
            .catch((error: unknown) => {
                if (!request.isCurrent()) return;
                if (error instanceof PublicApiError && error.status === 404) {
                    setServicesState('not-published');
                    setErrorMessage(error.message);
                } else if (
                    error instanceof PublicApiError &&
                    error.status === 503
                ) {
                    setServicesState('temporarily-unavailable');
                    setErrorMessage(error.message);
                } else if (!(
                    error instanceof DOMException && error.name === 'AbortError'
                )) {
                    setServicesState('error');
                    setErrorMessage(
                        'Não foi possível carregar esta página. Tente novamente.',
                    );
                }
            });

        return () => requests.cancel();
    }, [apiBaseUrl, initialStoredDate, slug]);

    useEffect(() => {
        const requests = availabilityRequests.current;
        if (!serviceId || !date || !business) {
            requests.cancel();
            return;
        }

        const request = requests.begin();
        void loadPublicAvailability(
            slug,
            serviceId,
            date,
            apiBaseUrl,
            request.signal,
        )
            .then((result) => {
                if (!request.isCurrent()) return;
                setAvailability(result);
                setAvailabilityState('ready');
                setAnnouncement('Horários atualizados para ' + date + '.');
            })
            .catch((error: unknown) => {
                if (!request.isCurrent()) return;
                if (error instanceof PublicApiError && error.status === 404) {
                    setAvailabilityState('not-published');
                    setErrorMessage(error.message);
                } else if (
                    error instanceof PublicApiError &&
                    error.status === 503
                ) {
                    setAvailabilityState('temporarily-unavailable');
                    setErrorMessage(error.message);
                } else if (!(
                    error instanceof DOMException && error.name === 'AbortError'
                )) {
                    setAvailabilityState('error');
                    setErrorMessage(
                        'Não foi possível consultar os horários. Tente novamente.',
                    );
                }
            });
        return () => requests.cancel();
    }, [apiBaseUrl, availabilityRefresh, business, date, serviceId, slug]);

    useEffect(() => {
        const onPageHide = () => {
            if (selectionMade.current) {
                void publishTelemetry(slug, 'FLOW_ABANDONED', apiBaseUrl, true);
            }
        };
        window.addEventListener('pagehide', onPageHide);
        return () => window.removeEventListener('pagehide', onPageHide);
    }, [apiBaseUrl, slug]);

    const slots = useMemo(
        () => availability?.days.find((day) => day.date === date)?.slots ?? [],
        [availability, date],
    );
    const currentService = services.find(
        (service) => service.serviceId === serviceId,
    );

    function selectService(event: ChangeEvent<HTMLSelectElement>) {
        const nextServiceId = event.currentTarget.value;
        availabilityRequests.current.cancel();
        setServiceId(nextServiceId);
        setSelectedSlot(null);
        setAvailability(null);
        setAvailabilityState('loading');
        setErrorMessage('');
        resetBookingFeedback();
        selectionMade.current = selectionMade.current || Boolean(nextServiceId);
        if (nextServiceId) {
            void publishTelemetry(slug, 'SERVICE_SELECTED', apiBaseUrl);
        }
    }

    function selectDate(event: ChangeEvent<HTMLInputElement>) {
        availabilityRequests.current.cancel();
        setDate(event.currentTarget.value);
        setSelectedSlot(null);
        setAvailability(null);
        setAvailabilityState('loading');
        setErrorMessage('');
        resetBookingFeedback();
    }

    function chooseSlot(slot: PublicSlot) {
        setSelectedSlot(slot);
        resetBookingFeedback();
        selectionMade.current = true;
        setAnnouncement(
            'Horário selecionado: ' +
                formatTime(slot.startsAt, business?.timeZone ?? 'UTC') +
                '. Esta seleção não é uma reserva.',
        );
        void publishTelemetry(slug, 'SLOT_SELECTED', apiBaseUrl);
    }

    function shiftDate(days: number) {
        if (!date) return;
        availabilityRequests.current.cancel();
        setDate(shiftLocalDate(date, days));
        setSelectedSlot(null);
        setAvailability(null);
        setAvailabilityState('loading');
        setErrorMessage('');
        resetBookingFeedback();
    }

    function resetBookingFeedback() {
        if (bookingInFlight.current || confirmation) return;
        setBookingState('idle');
        setBookingMessage('');
    }

    function discardResolvedIntent() {
        const sharedIntent = readStoredBookingIntent(slug);
        if (
            sharedIntent &&
            bookingIntent.current &&
            sharedIntent.idempotencyKey !== bookingIntent.current.key
        ) {
            return sharedIntent;
        }
        window.localStorage.removeItem(bookingIntentStorageKey(slug));
        bookingIntent.current = null;
        setStoredIntent(null);
        setRecoveryLookupIntent(null);
        setRecoveryFromReload(false);
        setRecoveryNeedsDetails(false);
        setRecoveryLookupFailed(false);
        return null;
    }

    function adoptSharedIntent(intent: StoredBookingIntent) {
        bookingIntent.current = {
            fingerprint: null,
            key: intent.idempotencyKey,
            recoveryCapability: intent.recoveryCapability,
            body: null,
        };
        setStoredIntent(intent);
        setRecoveryLookupIntent(intent);
        setServiceId(intent.serviceId);
        setDate(intent.date);
        setSelectedSlot({
            startsAt: intent.startsAt,
            serviceEndsAt: intent.serviceEndsAt,
        });
        setRecoveryFromReload(true);
        setBookingState('uncertain');
        setBookingMessage(
            'Outra aba iniciou esta intenção. Verificando o resultado…',
        );
    }

    async function startAnotherBooking() {
        let intentFromOtherTab: StoredBookingIntent | null;
        try {
            intentFromOtherTab = await withBookingIntentLock(slug, () => {
                const sharedIntent = readStoredBookingIntent(slug);
                if (
                    sharedIntent &&
                    (!storedIntent ||
                        sharedIntent.idempotencyKey !==
                            storedIntent.idempotencyKey)
                ) {
                    return sharedIntent;
                }
                window.localStorage.removeItem(bookingIntentStorageKey(slug));
                return null;
            });
        } catch {
            setBookingState('error');
            setBookingMessage(
                'Este navegador não oferece coordenação segura entre abas. Atualize o navegador antes de iniciar outra reserva.',
            );
            return;
        }
        if (intentFromOtherTab) {
            adoptSharedIntent(intentFromOtherTab);
            setRecoveryRefresh((current) => current + 1);
            return;
        }
        discardResolvedIntent();
        setConfirmation(null);
        setSelectedSlot(null);
        setBookingState('idle');
        setBookingMessage('');
        setClientName('');
        setClientPhone('');
        setAvailability(null);
        setAvailabilityState('loading');
        setAvailabilityRefresh((current) => current + 1);
        setAnnouncement(
            'Escolha outro horário para iniciar uma nova intenção.',
        );
    }

    async function submitBooking(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (
            bookingInFlight.current ||
            !business ||
            !serviceId ||
            !selectedSlot
        ) {
            return;
        }
        const name = clientName.trim();
        const phone = clientPhone.trim();
        if (!name || !/^\+[1-9]\d{7,14}$/.test(phone)) {
            setBookingState('validation-error');
            setBookingMessage(
                !name
                    ? 'Informe seu nome.'
                    : 'Informe um telefone no formato internacional, por exemplo +5511999999999.',
            );
            return;
        }

        const body: PublicBookingRequest = {
            serviceId,
            startsAt: selectedSlot.startsAt,
            client: { name, phone },
        };
        const fingerprint = JSON.stringify([slug, body]);
        let claim: BookingIntentClaim;
        try {
            claim = await withBookingIntentLock(slug, () => {
                const sharedIntent = readStoredBookingIntent(slug);
                const activeIntent = bookingIntent.current;
                if (sharedIntent) {
                    const sameSlot =
                        sharedIntent.serviceId === serviceId &&
                        sharedIntent.date === date &&
                        sharedIntent.startsAt === selectedSlot.startsAt;
                    if (!sameSlot)
                        return { blocked: true as const, sharedIntent };
                    const claimedIntent = {
                        fingerprint,
                        key: sharedIntent.idempotencyKey,
                        recoveryCapability: sharedIntent.recoveryCapability,
                        body,
                    };
                    bookingIntent.current = claimedIntent;
                    return { blocked: false as const, claimedIntent };
                }
                const claimedIntent =
                    activeIntent &&
                    (activeIntent.fingerprint === fingerprint ||
                        bookingState === 'uncertain')
                        ? { ...activeIntent, fingerprint, body }
                        : {
                              fingerprint,
                              key: newPublicBookingKey(),
                              recoveryCapability:
                                  newPublicBookingRecoveryCapability(),
                              body,
                          };
                const persisted = {
                    idempotencyKey: claimedIntent.key,
                    recoveryCapability: claimedIntent.recoveryCapability,
                    serviceId,
                    date,
                    startsAt: selectedSlot.startsAt,
                    serviceEndsAt: selectedSlot.serviceEndsAt,
                };
                if (!persistBookingIntent(slug, persisted))
                    return { blocked: true as const, sharedIntent: null };
                bookingIntent.current = claimedIntent;
                return { blocked: false as const, claimedIntent };
            });
        } catch {
            setBookingState('error');
            setBookingMessage(
                'Este navegador não oferece coordenação segura entre abas. Atualize o navegador para evitar reservas duplicadas.',
            );
            return;
        }
        if (claim.blocked) {
            if (claim.sharedIntent) {
                adoptSharedIntent(claim.sharedIntent);
                setBookingMessage(
                    'Outra aba mantém uma intenção diferente sem resultado confirmado. Recupere-a antes de escolher outro horário.',
                );
                setRecoveryRefresh((current) => current + 1);
            } else {
                setBookingState('error');
                setBookingMessage(
                    'Não foi possível preservar a identidade desta intenção neste navegador. A reserva não foi enviada.',
                );
            }
            return;
        }
        const intent = claim.claimedIntent;
        const persistedIntent = {
            idempotencyKey: intent.key,
            recoveryCapability: intent.recoveryCapability,
            serviceId,
            date,
            startsAt: selectedSlot.startsAt,
            serviceEndsAt: selectedSlot.serviceEndsAt,
        };
        setStoredIntent(persistedIntent);
        setRecoveryLookupFailed(false);
        const retryingUncertainIntent = bookingState === 'uncertain';
        bookingInFlight.current = true;
        setBookingState('submitting');
        setBookingMessage('Enviando sua solicitação…');
        try {
            const result = await createPublicBooking(
                slug,
                body,
                intent.key,
                intent.recoveryCapability,
                apiBaseUrl,
            );
            setConfirmation(result);
            setBookingState('confirmed');
            setBookingMessage('Reserva confirmada.');
            setAnnouncement(
                'Reserva confirmada para ' + result.serviceName + '.',
            );
        } catch (error: unknown) {
            if (error instanceof PublicBookingError) {
                if (error.phase === 'csrf') {
                    setBookingState(
                        retryingUncertainIntent ? 'uncertain' : 'error',
                    );
                    setBookingMessage(
                        retryingUncertainIntent
                            ? 'Não foi possível verificar a proteção. A intenção original permanece incerta; tente recuperar novamente.'
                            : 'Não foi possível iniciar a solicitação protegida. Tente novamente.',
                    );
                } else if (error.code === 'IDEMPOTENCY_KEY_REUSED') {
                    setBookingState('uncertain');
                    setRecoveryNeedsDetails(false);
                    setBookingMessage(
                        'Esta chave já está vinculada a outro conteúdo. A intenção original foi preservada; não altere nem inicie outra reserva até recuperar o resultado.',
                    );
                } else if (
                    error.status === 400 &&
                    error.code === 'INVALID_APPOINTMENT_TIME'
                ) {
                    const otherIntent = discardResolvedIntent();
                    if (otherIntent) {
                        adoptSharedIntent(otherIntent);
                        return;
                    }
                    setSelectedSlot(null);
                    setAvailability(null);
                    setAvailabilityState('loading');
                    setBookingState('slot-conflict');
                    setBookingMessage(
                        'Não foi possível validar esse horário. Atualizamos as opções; escolha outro horário. Seus dados foram mantidos.',
                    );
                    setAvailabilityRefresh((current) => current + 1);
                } else if (error.status === 400) {
                    const otherIntent = discardResolvedIntent();
                    if (otherIntent) {
                        adoptSharedIntent(otherIntent);
                        return;
                    }
                    setBookingState('validation-error');
                    setBookingMessage(
                        'Confira o nome e o telefone e tente novamente.',
                    );
                } else if (
                    error.status === 409 &&
                    error.code === 'SLOT_NO_LONGER_AVAILABLE'
                ) {
                    const otherIntent = discardResolvedIntent();
                    if (otherIntent) {
                        adoptSharedIntent(otherIntent);
                        return;
                    }
                    setSelectedSlot(null);
                    setAvailability(null);
                    setAvailabilityState('loading');
                    setBookingState('slot-conflict');
                    setBookingMessage(
                        'Esse horário acabou de ficar indisponível. Atualizamos as opções; escolha outro horário. Seus dados foram mantidos.',
                    );
                    setAvailabilityRefresh((current) => current + 1);
                } else if (error.status === 404) {
                    if (retryingUncertainIntent || error.phase === 'prepare') {
                        setBookingState('uncertain');
                        setBookingMessage(
                            'A página deixou de estar disponível e o resultado da intenção original não pôde ser verificado. Não inicie outra reserva.',
                        );
                    } else {
                        const outcome = unavailableBookingOutcome(false);
                        setBookingState(outcome.state);
                        setBookingMessage(outcome.message);
                    }
                } else if (error.status === 503) {
                    setBookingState('unavailable');
                    setBookingMessage(
                        'A fonte de horários está temporariamente indisponível. Tente novamente mais tarde.',
                    );
                } else if (error.outcomeUnknown) {
                    setBookingState('uncertain');
                    setRecoveryNeedsDetails(false);
                    setRecoveryFromReload(false);
                    setBookingMessage(
                        'Não foi possível confirmar o resultado do envio. Seus dados e a chave da operação foram preservados. Tente novamente para consultar a mesma operação.',
                    );
                    recoveryPollAttempts.current = 0;
                    setRecoveryLookupIntent(persistedIntent);
                } else {
                    setBookingState('error');
                    setBookingMessage(error.message);
                }
            } else {
                setBookingState('uncertain');
                setRecoveryNeedsDetails(false);
                setBookingMessage(
                    'Não foi possível confirmar o resultado do envio. Seus dados e a chave da operação foram preservados. Tente novamente para consultar a mesma operação.',
                );
                recoveryPollAttempts.current = 0;
                setRecoveryLookupIntent(persistedIntent);
            }
        } finally {
            bookingInFlight.current = false;
        }
    }

    const displayedError = errorMessage && (
        <p className="public-page__message" role="alert" tabIndex={-1}>
            {errorMessage}
        </p>
    );
    const bookingControlsLocked =
        bookingState === 'submitting' ||
        bookingState === 'confirmed' ||
        bookingState === 'uncertain' ||
        bookingState === 'recovery-unavailable';

    return (
        <main className="public-page">
            <header className="public-page__header">
                <p className="public-page__eyebrow">Agendamento</p>
                {business && <h1>{business.name}</h1>}
                {business && (
                    <p className="public-page__timezone">
                        Horários no fuso {business.timeZone}
                    </p>
                )}
                {business?.contactPhone && (
                    <a href={'tel:' + business.contactPhone}>
                        {business.contactPhone}
                    </a>
                )}
            </header>

            <output
                className="public-page__live"
                aria-live="polite"
                aria-atomic="true"
            >
                {announcement ||
                    (servicesState === 'loading' ? 'Carregando página…' : '')}
            </output>

            {recoveryFromReload && (
                <section
                    className="public-page__panel"
                    aria-labelledby="recovery-heading"
                    aria-live="polite"
                >
                    <h2 id="recovery-heading">Recuperação da reserva</h2>
                    <output>{bookingMessage}</output>
                    {recoveryLookupFailed && (
                        <button
                            type="button"
                            onClick={() => {
                                setBookingMessage('Consultando a intenção…');
                                setRecoveryLookupFailed(false);
                                setRecoveryRefresh((current) => current + 1);
                            }}
                        >
                            Consultar novamente
                        </button>
                    )}
                    {recoveryNeedsDetails && (
                        <p>
                            Por privacidade, nome e telefone não foram salvos.
                            Reintroduza os mesmos dados para reenviar a mesma
                            intenção.
                        </p>
                    )}
                </section>
            )}

            {bookingState === 'confirmed' && confirmation && (
                <section
                    className="public-page__confirmation"
                    aria-labelledby="booking-confirmed-heading"
                    aria-live="polite"
                >
                    <h2 id="booking-confirmed-heading">Reserva confirmada</h2>
                    <p>
                        {confirmation.serviceName} em{' '}
                        {formatDate(
                            localDate(
                                confirmation.startsAt,
                                confirmation.timeZone,
                            ),
                            confirmation.timeZone,
                        )}{' '}
                        às{' '}
                        {formatTime(
                            confirmation.startsAt,
                            confirmation.timeZone,
                        )}{' '}
                        · {confirmation.timeZone}.
                    </p>
                    <p>
                        Código da reserva:{' '}
                        <strong>{confirmation.appointmentId}</strong>
                    </p>
                    <button type="button" onClick={startAnotherBooking}>
                        Fazer outra reserva
                    </button>
                </section>
            )}

            {servicesState === 'loading' && (
                <section className="public-page__panel" aria-busy="true">
                    <p>Carregando negócio e serviços…</p>
                </section>
            )}
            {servicesState === 'not-published' && (
                <section
                    className="public-page__panel"
                    aria-labelledby="public-state-title"
                >
                    <h2 id="public-state-title">Página indisponível</h2>
                    {displayedError}
                </section>
            )}
            {servicesState === 'temporarily-unavailable' && (
                <section
                    className="public-page__panel"
                    aria-labelledby="public-state-title"
                >
                    <h2 id="public-state-title">
                        Indisponibilidade temporária
                    </h2>
                    <p>
                        Não foi possível carregar as informações agora. Tente
                        novamente em instantes.
                    </p>
                    {displayedError}
                </section>
            )}
            {servicesState === 'error' && (
                <section
                    className="public-page__panel"
                    aria-labelledby="public-state-title"
                >
                    <h2 id="public-state-title">
                        Não foi possível carregar a página
                    </h2>
                    {displayedError}
                    <button
                        type="button"
                        onClick={() => window.location.reload()}
                    >
                        Tentar novamente
                    </button>
                </section>
            )}
            {servicesState === 'empty-services' && (
                <section
                    className="public-page__panel"
                    aria-labelledby="public-state-title"
                >
                    <h2 id="public-state-title">Nenhum serviço disponível</h2>
                    <p>
                        Este negócio ainda não oferece serviços para
                        agendamento.
                    </p>
                </section>
            )}

            {servicesState === 'ready' && business && (
                <div className="public-page__content">
                    <section
                        className="public-page__panel"
                        aria-labelledby="service-heading"
                    >
                        <h2 id="service-heading">1. Escolha um serviço</h2>
                        <label htmlFor="public-service">Serviço</label>
                        <select
                            id="public-service"
                            value={serviceId}
                            onChange={selectService}
                            disabled={bookingControlsLocked}
                        >
                            <option value="">Selecione um serviço</option>
                            {services.map((service) => (
                                <option
                                    key={service.serviceId}
                                    value={service.serviceId}
                                >
                                    {service.name} ·{' '}
                                    {formatDuration(service.durationMinutes)}
                                </option>
                            ))}
                        </select>
                        {currentService?.description && (
                            <p className="public-page__description">
                                {currentService.description}
                            </p>
                        )}
                        {currentService && (
                            <p className="public-page__duration">
                                Duração do atendimento:{' '}
                                {formatDuration(currentService.durationMinutes)}
                            </p>
                        )}
                    </section>

                    {serviceId && (
                        <section
                            className="public-page__panel"
                            aria-labelledby="date-heading"
                        >
                            <h2 id="date-heading">
                                2. Escolha uma data e um horário
                            </h2>
                            <div className="public-page__date-controls">
                                <button
                                    type="button"
                                    onClick={() => shiftDate(-1)}
                                    aria-label="Dia anterior"
                                    disabled={bookingControlsLocked}
                                >
                                    Anterior
                                </button>
                                <label htmlFor="public-date">Data</label>
                                <input
                                    id="public-date"
                                    type="date"
                                    value={date}
                                    onChange={selectDate}
                                    disabled={bookingControlsLocked}
                                />
                                <button
                                    type="button"
                                    onClick={() => shiftDate(1)}
                                    aria-label="Próximo dia"
                                    disabled={bookingControlsLocked}
                                >
                                    Próximo
                                </button>
                            </div>
                            {!date && (
                                <p>
                                    Escolha uma data local para consultar os
                                    horários.
                                </p>
                            )}
                            {date && (
                                <p className="public-page__date-label">
                                    {formatDate(date, business.timeZone)}
                                </p>
                            )}
                            {date && availabilityState === 'loading' && (
                                <p aria-busy="true">Consultando horários…</p>
                            )}
                            {availabilityState ===
                                'temporarily-unavailable' && (
                                <div className="public-page__notice">
                                    <strong>
                                        Horários temporariamente indisponíveis
                                    </strong>
                                    <p>
                                        Não mostramos horários sem confirmar as
                                        fontes necessárias. Tente novamente.
                                    </p>
                                    {displayedError}
                                </div>
                            )}
                            {availabilityState === 'not-published' &&
                                displayedError}
                            {availabilityState === 'error' && displayedError}
                            {date &&
                                availabilityState === 'ready' &&
                                slots.length === 0 && (
                                    <p className="public-page__empty">
                                        {availability?.days.find(
                                            (day) => day.date === date,
                                        )?.explicitlyClosed
                                            ? 'Este negócio não atende nesta data.'
                                            : 'Não há horários disponíveis nesta data.'}
                                    </p>
                                )}
                            {availabilityState === 'ready' &&
                                slots.length > 0 && (
                                    <fieldset className="public-page__slots">
                                        <legend className="public-page__visually-hidden">
                                            Horários disponíveis
                                        </legend>
                                        {slots.map((slot) => {
                                            const selected =
                                                selectedSlot?.startsAt ===
                                                    slot.startsAt &&
                                                selectedSlot.serviceEndsAt ===
                                                    slot.serviceEndsAt;
                                            return (
                                                <button
                                                    key={slot.startsAt}
                                                    type="button"
                                                    className={
                                                        selected
                                                            ? 'public-page__slot is-selected'
                                                            : 'public-page__slot'
                                                    }
                                                    aria-pressed={selected}
                                                    disabled={
                                                        bookingControlsLocked
                                                    }
                                                    onClick={() =>
                                                        chooseSlot(slot)
                                                    }
                                                >
                                                    {formatTime(
                                                        slot.startsAt,
                                                        business.timeZone,
                                                    )}
                                                </button>
                                            );
                                        })}
                                    </fieldset>
                                )}
                            {selectedSlot && (
                                <output className="public-page__selection">
                                    Seleção:{' '}
                                    {formatTime(
                                        selectedSlot.startsAt,
                                        business.timeZone,
                                    )}
                                    –
                                    {formatTime(
                                        selectedSlot.serviceEndsAt,
                                        business.timeZone,
                                    )}{' '}
                                    · {business.timeZone}.
                                    <br />A seleção não confirma uma reserva.
                                </output>
                            )}
                            {(selectedSlot ||
                                bookingState === 'slot-conflict') && (
                                <form
                                    className="public-page__booking-form"
                                    onSubmit={submitBooking}
                                    aria-labelledby="booking-heading"
                                >
                                    <h3 id="booking-heading">3. Seus dados</h3>
                                    <label htmlFor="public-client-name">
                                        Nome
                                    </label>
                                    <input
                                        id="public-client-name"
                                        name="name"
                                        autoComplete="name"
                                        required
                                        maxLength={120}
                                        value={clientName}
                                        onChange={(event) => {
                                            setClientName(
                                                event.currentTarget.value,
                                            );
                                            resetBookingFeedback();
                                        }}
                                        disabled={
                                            bookingControlsLocked &&
                                            !recoveryNeedsDetails
                                        }
                                        aria-invalid={
                                            bookingState ===
                                                'validation-error' &&
                                            !clientName.trim()
                                        }
                                    />
                                    <label htmlFor="public-client-phone">
                                        Telefone com código do país
                                    </label>
                                    <input
                                        id="public-client-phone"
                                        name="phone"
                                        type="tel"
                                        autoComplete="tel"
                                        inputMode="tel"
                                        placeholder="+5511999999999"
                                        pattern="\+[1-9][0-9]{7,14}"
                                        required
                                        value={clientPhone}
                                        onChange={(event) => {
                                            setClientPhone(
                                                event.currentTarget.value,
                                            );
                                            resetBookingFeedback();
                                        }}
                                        disabled={
                                            bookingControlsLocked &&
                                            !recoveryNeedsDetails
                                        }
                                        aria-invalid={
                                            bookingState ===
                                                'validation-error' &&
                                            !/^\+[1-9]\d{7,14}$/.test(
                                                clientPhone.trim(),
                                            )
                                        }
                                    />
                                    <p className="public-page__privacy-note">
                                        Usaremos esses dados para registrar e
                                        administrar este agendamento. Informar o
                                        telefone não verifica sua identidade.
                                    </p>
                                    {bookingMessage &&
                                        !recoveryFromReload &&
                                        bookingState !== 'confirmed' && (
                                            <p
                                                className="public-page__booking-message"
                                                role={
                                                    bookingState ===
                                                    'submitting'
                                                        ? 'status'
                                                        : 'alert'
                                                }
                                                aria-live={
                                                    bookingState ===
                                                    'submitting'
                                                        ? 'polite'
                                                        : 'assertive'
                                                }
                                            >
                                                {bookingMessage}
                                            </p>
                                        )}
                                    <button
                                        type="submit"
                                        disabled={
                                            bookingState === 'submitting' ||
                                            bookingState === 'confirmed' ||
                                            bookingState ===
                                                'recovery-unavailable' ||
                                            recoveryLookupFailed
                                        }
                                    >
                                        {bookingState === 'submitting'
                                            ? 'Enviando…'
                                            : bookingState === 'uncertain'
                                              ? recoveryNeedsDetails
                                                  ? 'Reenviar a mesma intenção'
                                                  : 'Tentar novamente com segurança'
                                              : 'Confirmar reserva'}
                                    </button>
                                </form>
                            )}
                        </section>
                    )}
                </div>
            )}

            <footer className="public-page__footer">
                <p>
                    <a href={'/client-access/' + encodeURIComponent(slug)}>
                        Acessar com código
                    </a>
                </p>
                <p>
                    Somente uma resposta válida do servidor confirma uma
                    reserva.
                </p>
            </footer>
        </main>
    );
}

export function PublicPage({
    slug,
    apiBaseUrl = defaultApiBaseUrl,
}: {
    slug: string;
    apiBaseUrl?: string;
}) {
    return <PublicPageContent key={slug} slug={slug} apiBaseUrl={apiBaseUrl} />;
}
