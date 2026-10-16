import { useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import { apiBaseUrl as defaultApiBaseUrl } from './config';
import {
    createPublicBooking,
    newPublicBookingKey,
    PublicBookingError,
} from './publicBookingApi';
import type {
    PublicBookingConfirmation,
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
    const [business, setBusiness] = useState<PublicBusiness | null>(null);
    const [services, setServices] = useState<PublicService[]>([]);
    const [servicesState, setServicesState] =
        useState<PublicPageState>('loading');
    const [serviceId, setServiceId] = useState('');
    const [date, setDate] = useState('');
    const [availability, setAvailability] = useState<PublicAvailability | null>(
        null,
    );
    const [availabilityState, setAvailabilityState] =
        useState<PublicPageState>('loading');
    const [selectedSlot, setSelectedSlot] = useState<PublicSlot | null>(null);
    const [clientName, setClientName] = useState('');
    const [clientPhone, setClientPhone] = useState('');
    const [bookingState, setBookingState] = useState<
        | 'idle'
        | 'submitting'
        | 'uncertain'
        | 'validation-error'
        | 'slot-conflict'
        | 'unavailable'
        | 'error'
        | 'confirmed'
    >('idle');
    const [bookingMessage, setBookingMessage] = useState('');
    const [confirmation, setConfirmation] =
        useState<PublicBookingConfirmation | null>(null);
    const [availabilityRefresh, setAvailabilityRefresh] = useState(0);
    const [announcement, setAnnouncement] = useState('');
    const [errorMessage, setErrorMessage] = useState('');
    const availabilityRequests = useRef(new LatestRequest());
    const businessRequests = useRef(new LatestRequest());
    const selectionMade = useRef(false);
    const bookingInFlight = useRef(false);
    const bookingIntent = useRef<{
        fingerprint: string;
        key: string;
        body: PublicBookingRequest;
    } | null>(null);

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
                setDate('');
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
    }, [apiBaseUrl, slug]);

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
        if (bookingIntent.current?.fingerprint !== fingerprint) {
            bookingIntent.current = {
                fingerprint,
                key: newPublicBookingKey(),
                body,
            };
        }
        const intent = bookingIntent.current;
        bookingInFlight.current = true;
        setBookingState('submitting');
        setBookingMessage('Enviando sua solicitação…');
        try {
            const result = await createPublicBooking(
                slug,
                intent.body,
                intent.key,
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
                    setBookingState('error');
                    setBookingMessage(
                        'Não foi possível iniciar a solicitação protegida. Tente novamente.',
                    );
                } else if (
                    error.status === 400 &&
                    error.code === 'INVALID_APPOINTMENT_TIME'
                ) {
                    setSelectedSlot(null);
                    setAvailability(null);
                    setAvailabilityState('loading');
                    setBookingState('slot-conflict');
                    setBookingMessage(
                        'Não foi possível validar esse horário. Atualizamos as opções; escolha outro horário. Seus dados foram mantidos.',
                    );
                    setAvailabilityRefresh((current) => current + 1);
                } else if (error.status === 400) {
                    setBookingState('validation-error');
                    setBookingMessage(
                        'Confira o nome e o telefone e tente novamente.',
                    );
                } else if (
                    error.status === 409 &&
                    error.code === 'SLOT_NO_LONGER_AVAILABLE'
                ) {
                    setSelectedSlot(null);
                    setAvailability(null);
                    setAvailabilityState('loading');
                    setBookingState('slot-conflict');
                    setBookingMessage(
                        'Esse horário acabou de ficar indisponível. Atualizamos as opções; escolha outro horário. Seus dados foram mantidos.',
                    );
                    setAvailabilityRefresh((current) => current + 1);
                } else if (error.status === 404) {
                    setBookingState('unavailable');
                    setBookingMessage(
                        'Esta página pública não está mais disponível.',
                    );
                } else if (error.status === 503) {
                    setBookingState('unavailable');
                    setBookingMessage(
                        'A fonte de horários está temporariamente indisponível. Tente novamente mais tarde.',
                    );
                } else if (error.outcomeUnknown) {
                    setBookingState('uncertain');
                    setBookingMessage(
                        'Não foi possível confirmar o resultado do envio. Seus dados e a chave da operação foram preservados. Tente novamente para consultar a mesma operação.',
                    );
                } else {
                    setBookingState('error');
                    setBookingMessage(error.message);
                }
            } else {
                setBookingState('uncertain');
                setBookingMessage(
                    'Não foi possível confirmar o resultado do envio. Seus dados e a chave da operação foram preservados. Tente novamente para consultar a mesma operação.',
                );
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
                            disabled={
                                bookingState === 'submitting' ||
                                bookingState === 'confirmed'
                            }
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
                                    disabled={
                                        bookingState === 'submitting' ||
                                        bookingState === 'confirmed'
                                    }
                                >
                                    Anterior
                                </button>
                                <label htmlFor="public-date">Data</label>
                                <input
                                    id="public-date"
                                    type="date"
                                    value={date}
                                    onChange={selectDate}
                                    disabled={
                                        bookingState === 'submitting' ||
                                        bookingState === 'confirmed'
                                    }
                                />
                                <button
                                    type="button"
                                    onClick={() => shiftDate(1)}
                                    aria-label="Próximo dia"
                                    disabled={
                                        bookingState === 'submitting' ||
                                        bookingState === 'confirmed'
                                    }
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
                                                        bookingState ===
                                                            'submitting' ||
                                                        bookingState ===
                                                            'confirmed'
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
                                            bookingState === 'submitting' ||
                                            bookingState === 'confirmed'
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
                                            bookingState === 'submitting' ||
                                            bookingState === 'confirmed'
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
                                            bookingState === 'confirmed'
                                        }
                                    >
                                        {bookingState === 'submitting'
                                            ? 'Enviando…'
                                            : bookingState === 'uncertain'
                                              ? 'Tentar novamente com segurança'
                                              : 'Confirmar reserva'}
                                    </button>
                                </form>
                            )}
                            {bookingState === 'confirmed' && confirmation && (
                                <section
                                    className="public-page__confirmation"
                                    aria-labelledby="booking-confirmed-heading"
                                    aria-live="polite"
                                >
                                    <h3 id="booking-confirmed-heading">
                                        Reserva confirmada
                                    </h3>
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
                                        )}
                                        .
                                    </p>
                                    <p>
                                        Código da reserva:{' '}
                                        <strong>
                                            {confirmation.appointmentId}
                                        </strong>
                                    </p>
                                </section>
                            )}
                        </section>
                    )}
                </div>
            )}

            <footer className="public-page__footer">
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
