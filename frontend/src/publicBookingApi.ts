import { apiBaseUrl as defaultApiBaseUrl } from './config';

export type PublicBookingRequest = {
    serviceId: string;
    startsAt: string;
    client: { name: string; phone: string };
};

export type PublicBookingConfirmation = {
    appointmentId: string;
    operationId: string;
    status: 'CONFIRMED';
    serviceName: string;
    startsAt: string;
    serviceEndsAt: string;
    timeZone: string;
};

export class PublicBookingError extends Error {
    constructor(
        readonly kind: 'network' | 'http' | 'invalid-response',
        message: string,
        readonly status?: number,
        readonly code?: string,
        readonly phase: 'csrf' | 'booking' = 'booking',
        readonly outcomeUnknown = true,
    ) {
        super(message);
    }
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function isValidConfirmation(
    value: unknown,
): value is PublicBookingConfirmation {
    if (!isRecord(value)) return false;
    const stringFields = [
        'appointmentId',
        'operationId',
        'serviceName',
        'startsAt',
        'serviceEndsAt',
        'timeZone',
    ] as const;
    if (
        value.status !== 'CONFIRMED' ||
        stringFields.some(
            (field) =>
                typeof value[field] !== 'string' ||
                value[field].trim().length === 0,
        )
    ) {
        return false;
    }
    const startsAt = Date.parse(value.startsAt as string);
    const endsAt = Date.parse(value.serviceEndsAt as string);
    if (
        !Number.isFinite(startsAt) ||
        !Number.isFinite(endsAt) ||
        endsAt <= startsAt
    ) {
        return false;
    }
    try {
        new Intl.DateTimeFormat('en', { timeZone: value.timeZone as string });
    } catch {
        return false;
    }
    return true;
}

async function readError(
    response: Response,
    phase: 'csrf' | 'booking' = 'booking',
): Promise<PublicBookingError> {
    let code: string | undefined;
    try {
        const body: unknown = await response.json();
        if (
            isRecord(body) &&
            isRecord(body.error) &&
            typeof body.error.code === 'string'
        ) {
            code = body.error.code;
        }
    } catch {
        code = undefined;
    }
    return new PublicBookingError(
        'http',
        'A reserva não foi concluída. Revise os dados e tente novamente.',
        response.status,
        code,
        phase,
        phase === 'booking' && response.status >= 500,
    );
}

export async function createPublicBooking(
    slug: string,
    request: PublicBookingRequest,
    idempotencyKey: string,
    apiBaseUrl = defaultApiBaseUrl,
): Promise<PublicBookingConfirmation> {
    const basePath = apiBaseUrl + '/v1/public/' + encodeURIComponent(slug);
    let csrfResponse: Response;
    try {
        csrfResponse = await fetch(apiBaseUrl + '/v1/csrf', {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
        });
    } catch {
        throw new PublicBookingError(
            'network',
            'Não foi possível iniciar o envio. Tente novamente.',
            undefined,
            undefined,
            'csrf',
            false,
        );
    }
    if (!csrfResponse.ok) throw await readError(csrfResponse, 'csrf');

    let csrf: unknown;
    try {
        csrf = await csrfResponse.json();
    } catch {
        throw new PublicBookingError(
            'invalid-response',
            'Não foi possível validar a proteção da solicitação.',
            undefined,
            undefined,
            'csrf',
            false,
        );
    }
    if (
        !isRecord(csrf) ||
        typeof csrf.headerName !== 'string' ||
        !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(csrf.headerName) ||
        typeof csrf.token !== 'string' ||
        !csrf.token
    ) {
        throw new PublicBookingError(
            'invalid-response',
            'Não foi possível validar a proteção da solicitação.',
            undefined,
            undefined,
            'csrf',
            false,
        );
    }

    let response: Response;
    try {
        response = await fetch(basePath + '/appointments', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
                [csrf.headerName]: csrf.token,
            },
            body: JSON.stringify(request),
        });
    } catch {
        throw new PublicBookingError(
            'network',
            'O resultado do envio é desconhecido. Tente novamente com segurança para consultar a mesma operação.',
        );
    }
    if (!response.ok) throw await readError(response);

    let body: unknown;
    try {
        body = await response.json();
    } catch {
        throw new PublicBookingError(
            'invalid-response',
            'O servidor não retornou uma confirmação válida. Tente novamente com segurança.',
            response.status,
        );
    }
    if (!isValidConfirmation(body)) {
        throw new PublicBookingError(
            'invalid-response',
            'O servidor não retornou uma confirmação válida. Tente novamente com segurança.',
            response.status,
        );
    }
    return body;
}

export function newPublicBookingKey(): string {
    return crypto.randomUUID();
}
