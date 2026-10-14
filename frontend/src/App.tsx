import { apiBaseUrl } from './config';

export function App() {
    return (
        <main className="page-shell">
            <section className="foundation-card" aria-labelledby="page-title">
                <p className="eyebrow">Aplicação web</p>
                <h1 id="page-title">Agendamento</h1>
                <p className="description">
                    Fundação React e TypeScript pronta para receber os fluxos
                    priorizados no backlog.
                </p>
                <div className="status-row">
                    <span className="status-dot" aria-hidden="true" />
                    <span>Interface inicial carregada</span>
                </div>
                <p className="api-note">
                    API configurada em <code>{apiBaseUrl}</code>
                </p>
            </section>
        </main>
    );
}
