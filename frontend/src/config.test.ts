import { describe, expect, it } from 'vitest';
import { resolveApiBaseUrl } from './config';

describe('resolveApiBaseUrl', () => {
    it('uses the local API path when no URL is configured', () => {
        expect(resolveApiBaseUrl()).toBe('/api');
    });

    it('trims whitespace and trailing slashes from a configured URL', () => {
        expect(resolveApiBaseUrl(' https://api.example.test/ ')).toBe(
            'https://api.example.test',
        );
    });
});
