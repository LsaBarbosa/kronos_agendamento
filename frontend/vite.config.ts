import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
    plugins: [react()],
    server: {
        proxy: {
            '/api': 'http://localhost:8080',
            '/oauth2': 'http://localhost:8080',
            '/login': 'http://localhost:8080',
            '/logout': 'http://localhost:8080',
        },
    },
    test: {
        include: ['src/**/*.test.ts'],
    },
});
