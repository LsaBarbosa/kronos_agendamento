import { describe, expect, it } from 'vitest';
import { commandKey } from './commandKey';

describe('command retry identity', () => {
    it('reuses the key after a lost response and separates edited commands', () => {
        const keys = new Map<string, string>();
        const original = { name: 'Corte', bufferAfterMinutes: 0 };
        const first = commandKey(keys, 'create', original);
        expect(commandKey(keys, 'create', { ...original })).toBe(first);
        expect(
            commandKey(keys, 'create', { ...original, name: 'Barba' }),
        ).not.toBe(first);
        expect(commandKey(keys, 'create', original)).toBe(first);
    });

    it('separates targets, actions and expected versions', () => {
        const keys = new Map<string, string>();
        const first = commandKey(keys, 'status:service-1:activate', {
            expectedVersion: 0,
        });
        expect(
            commandKey(keys, 'status:service-2:activate', {
                expectedVersion: 0,
            }),
        ).not.toBe(first);
        expect(
            commandKey(keys, 'status:service-1:deactivate', {
                expectedVersion: 0,
            }),
        ).not.toBe(first);
        expect(
            commandKey(keys, 'status:service-1:activate', {
                expectedVersion: 1,
            }),
        ).not.toBe(first);
    });
});
