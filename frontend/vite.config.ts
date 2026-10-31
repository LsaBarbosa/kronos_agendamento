import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const backendProxy = process.env.VITE_BACKEND_PROXY ?? 'http://localhost:8080';

export default defineConfig({
    plugins: [react()],
    server: {
        proxy: {
            '/api': backendProxy,
            '/oauth2': backendProxy,
            '/login': backendProxy,
            '/logout': backendProxy,
        },
    },
    test: {
        environment: 'jsdom',
        include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    },
});
