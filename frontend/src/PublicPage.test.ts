import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    LatestRequest,
    PublicApiError,
    loadPublicAvailability,
    loadPublicBusiness,
    loadPublicServices,
    publishTelemetry,
    unavailableBookingOutcome,
} from './PublicPage';
import { createPublicBooking, PublicBookingError } from './publicBookingApi';

const business = {
    name: 'Ateliê Horizonte',
    slug: 'atelie-horizonte',
    timeZone: 'America/Sao_Paulo',
    contactPhone: '+5511999999999',
};

const availability = {
    serviceId: 'service-1',
    from: '2026-10-16',
    to: '2026-10-16',
    timeZone: 'America/Sao_Paulo',
    generatedAt: '2026-10-15T15:00:00Z',
    configured: true,
    days: [
        {
            date: '2026-10-16',
            explicitlyClosed: false,
            slots: [
                {
                    startsAt: '2026-10-16T13:00:00-03:00',
                    serviceEndsAt: '2026-10-16T13:45:00-03:00',
                },
            ],
        },
    ],
};

describe('uncertain public booking state', () => {
    it('does not release an unresolved intent when the page is unpublished', () => {
        expect(unavailableBookingOutcome(true)).toEqual({
            state: 'uncertain',
            message: expect.stringContaining('não inicie outra reserva'),
        });
        expect(unavailableBookingOutcome(false)).toEqual({
            state: 'unavailable',
            message: 'Esta página pública não está mais disponível.',
        });
    });
});

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((complete) => {
        resolve = complete;
    });
    return { promise, resolve };
}

type FetchMock = (
    input: RequestInfo | URL,
    init?: RequestInit,
) => Promise<Response>;

afterEach(() => vi.unstubAllGlobals());

describe('PublicPage REST client', () => {
    it('loads the public profile using the slug route', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValue(jsonResponse(business));
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            loadPublicBusiness('atelie horizonte', '/api'),
        ).resolves.toEqual(business);
        expect(fetchMock).toHaveBeenCalledWith(
            '/api/v1/public/atelie%20horizonte',
            expect.objectContaining({
                headers: { Accept: 'application/json' },
            }),
        );
    });

    it('returns an empty service list without fabricating services', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn<FetchMock>().mockResolvedValue(jsonResponse([])),
        );
        await expect(
            loadPublicServices('atelie-horizonte', '/api'),
        ).resolves.toEqual([]);
    });

    it('requests a single local date and preserves returned empty-day semantics', async () => {
        const emptyAvailability = {
            ...availability,
            days: [{ date: '2026-10-16', explicitlyClosed: true, slots: [] }],
        };
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValue(jsonResponse(emptyAvailability));
        vi.stubGlobal('fetch', fetchMock);

        const result = await loadPublicAvailability(
            'atelie-horizonte',
            'service-1',
            '2026-10-16',
            '/api',
        );

        expect(
            new URL(fetchMock.mock.calls[0][0] as string, 'http://local')
                .searchParams,
        ).toEqual(
            new URLSearchParams({
                serviceId: 'service-1',
                from: '2026-10-16',
                to: '2026-10-16',
            }),
        );
        expect(result.days[0]).toEqual({
            date: '2026-10-16',
            explicitlyClosed: true,
            slots: [],
        });
    });

    it('distinguishes temporarily unavailable public availability (503)', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn<FetchMock>()
                .mockResolvedValue(new Response(null, { status: 503 })),
        );

        await expect(
            loadPublicAvailability(
                'atelie-horizonte',
                'service-1',
                '2026-10-16',
                '/api',
            ),
        ).rejects.toMatchObject<Partial<PublicApiError>>({ status: 503 });
    });

    it('does not allow an obsolete response to become the latest result', async () => {
        const older = deferred<Response>();
        const newer = deferred<Response>();
        const fetchMock = vi
            .fn<FetchMock>()
            .mockImplementationOnce(() => older.promise)
            .mockImplementationOnce(() => newer.promise);
        vi.stubGlobal('fetch', fetchMock);

        const requests = new LatestRequest();
        const firstRequest = requests.begin();
        const firstResult = loadPublicAvailability(
            'atelie-horizonte',
            'service-1',
            '2026-10-16',
            '/api',
            firstRequest.signal,
        ).then((value) => (firstRequest.isCurrent() ? value : undefined));

        const secondRequest = requests.begin();
        const secondResult = loadPublicAvailability(
            'atelie-horizonte',
            'service-1',
            '2026-10-17',
            '/api',
            secondRequest.signal,
        ).then((value) => (secondRequest.isCurrent() ? value : undefined));

        newer.resolve(
            jsonResponse({
                ...availability,
                from: '2026-10-17',
                to: '2026-10-17',
            }),
        );
        await expect(secondResult).resolves.toMatchObject({
            from: '2026-10-17',
        });
        older.resolve(jsonResponse(availability));
        await expect(firstResult).resolves.toBeUndefined();
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('sends telemetry events without visitor, service, or slot identifiers', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValue(new Response(null, { status: 204 }));
        vi.stubGlobal('fetch', fetchMock);

        await publishTelemetry('atelie-horizonte', 'SLOT_SELECTED', '/api');

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/v1/public/atelie-horizonte/telemetry',
            expect.objectContaining({
                method: 'POST',
                body: JSON.stringify({ event: 'SLOT_SELECTED' }),
            }),
        );
        expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('credentials');
    });
});

describe('public booking REST client', () => {
    const request = {
        serviceId: 'service-1',
        startsAt: '2026-10-16T13:00:00-03:00',
        client: { name: 'Ana Silva', phone: '+5511999999999' },
    };
    const confirmation = {
        appointmentId: 'appointment-1',
        operationId: 'operation-1',
        status: 'CONFIRMED',
        serviceName: 'Corte',
        startsAt: '2026-10-16T13:00:00-03:00',
        serviceEndsAt: '2026-10-16T13:45:00-03:00',
        timeZone: 'America/Sao_Paulo',
    };
    const csrf = { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' };

    it('does not report an uncertain booking when CSRF setup fails before POST', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockRejectedValueOnce(new TypeError('connection unavailable'));
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            createPublicBooking('atelie-horizonte', request, 'key', '/api'),
        ).rejects.toMatchObject<Partial<PublicBookingError>>({
            kind: 'network',
            phase: 'csrf',
            outcomeUnknown: false,
        });
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('treats an operation already in progress as an unresolved booking intent', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValueOnce(jsonResponse(csrf))
            .mockResolvedValueOnce(
                jsonResponse({ error: { code: 'OPERATION_IN_PROGRESS' } }, 409),
            );
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            createPublicBooking(
                'atelie-horizonte',
                request,
                'same-key',
                '/api',
            ),
        ).rejects.toMatchObject<Partial<PublicBookingError>>({
            status: 409,
            code: 'OPERATION_IN_PROGRESS',
            outcomeUnknown: true,
        });
    });

    it('fetches CSRF first and sends the returned header and idempotency key', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValueOnce(jsonResponse(csrf))
            .mockResolvedValueOnce(jsonResponse(confirmation, 201));
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            createPublicBooking(
                'atelie horizonte',
                request,
                'intent-key',
                '/api',
            ),
        ).resolves.toEqual(confirmation);

        expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/csrf');
        expect(fetchMock.mock.calls[1][0]).toBe(
            '/api/v1/public/atelie%20horizonte/appointments',
        );
        expect(fetchMock.mock.calls[1][1]).toMatchObject({
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'Idempotency-Key': 'intent-key',
                'X-CSRF-TOKEN': 'csrf-token',
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(request),
        });
    });

    it('preserves the caller intent key and body for an explicit retry after network uncertainty', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValueOnce(jsonResponse(csrf))
            .mockRejectedValueOnce(new TypeError('connection lost'))
            .mockResolvedValueOnce(jsonResponse(csrf))
            .mockResolvedValueOnce(jsonResponse(confirmation));
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            createPublicBooking(
                'atelie-horizonte',
                request,
                'same-key',
                '/api',
            ),
        ).rejects.toMatchObject<Partial<PublicBookingError>>({
            kind: 'network',
        });
        await expect(
            createPublicBooking(
                'atelie-horizonte',
                request,
                'same-key',
                '/api',
            ),
        ).resolves.toEqual(confirmation);

        expect(fetchMock.mock.calls[1][1]).toMatchObject({
            headers: expect.objectContaining({ 'Idempotency-Key': 'same-key' }),
            body: JSON.stringify(request),
        });
        expect(fetchMock.mock.calls[3][1]).toMatchObject({
            headers: expect.objectContaining({ 'Idempotency-Key': 'same-key' }),
            body: JSON.stringify(request),
        });
    });

    it('rejects a successful HTTP response that is not a valid confirmation', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValueOnce(jsonResponse(csrf))
            .mockResolvedValueOnce(
                jsonResponse({ ...confirmation, status: 'PENDING' }),
            );
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            createPublicBooking('atelie-horizonte', request, 'key', '/api'),
        ).rejects.toMatchObject<Partial<PublicBookingError>>({
            kind: 'invalid-response',
            status: 200,
        });
    });

    it('exposes validation and lost-slot responses without treating them as confirmation', async () => {
        const fetchMock = vi
            .fn<FetchMock>()
            .mockResolvedValueOnce(jsonResponse(csrf))
            .mockResolvedValueOnce(
                jsonResponse(
                    { error: { code: 'SLOT_NO_LONGER_AVAILABLE' } },
                    409,
                ),
            );
        vi.stubGlobal('fetch', fetchMock);

        await expect(
            createPublicBooking('atelie-horizonte', request, 'key', '/api'),
        ).rejects.toMatchObject<Partial<PublicBookingError>>({
            kind: 'http',
            status: 409,
            code: 'SLOT_NO_LONGER_AVAILABLE',
        });
    });

    it.each([400, 404, 503])(
        'preserves HTTP status %s for the booking form to explain',
        async (status) => {
            const fetchMock = vi
                .fn<FetchMock>()
                .mockResolvedValueOnce(jsonResponse(csrf))
                .mockResolvedValueOnce(
                    jsonResponse({ error: { code: 'REQUEST_FAILED' } }, status),
                );
            vi.stubGlobal('fetch', fetchMock);

            await expect(
                createPublicBooking('atelie-horizonte', request, 'key', '/api'),
            ).rejects.toMatchObject<Partial<PublicBookingError>>({ status });
        },
    );
});
