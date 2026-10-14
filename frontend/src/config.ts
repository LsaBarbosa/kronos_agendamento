export function resolveApiBaseUrl(configuredUrl?: string): string {
    const normalizedUrl = configuredUrl?.trim().replace(/\/+$/, '');
    return normalizedUrl || '/api';
}

export const apiBaseUrl = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);
