import { describe, expect, it } from 'vitest';
import { validLocalTimeOffsets } from './localTimeOffsets';

describe('validLocalTimeOffsets', () => {
    it('returns both offsets for an ambiguous tenant-local time', () => {
        expect(
            validLocalTimeOffsets('2026-11-01', '01:15', 'America/New_York'),
        ).toEqual(['-04:00', '-05:00']);
    });

    it('returns no offset for a nonexistent tenant-local time', () => {
        expect(
            validLocalTimeOffsets('2026-03-08', '02:15', 'America/New_York'),
        ).toEqual([]);
    });

    it('returns one offset for an unambiguous tenant-local time', () => {
        expect(
            validLocalTimeOffsets('2026-11-01', '03:15', 'America/New_York'),
        ).toEqual(['-05:00']);
    });
});
