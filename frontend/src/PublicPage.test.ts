import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    LatestRequest,
    PublicApiError,
    loadPublicAvailability,
    loadPublicBusiness,
    loadPublicServices,
    publishTelemetry,
} from './PublicPage';

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
