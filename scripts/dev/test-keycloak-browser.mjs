import { readFile } from 'node:fs/promises';
import { execFileSync, spawn } from 'node:child_process';

const chromePath = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const credentialsText = await readFile('.demo-admin.credentials', 'utf8');
const credentials = Object.fromEntries(
    credentialsText
        .split('\n')
        .filter(Boolean)
        .map((line) => {
            const separator = line.indexOf('=');
            return [line.slice(0, separator), line.slice(separator + 1)];
        }),
);
const appEnvironment = Object.fromEntries(
    (await readFile('.env', 'utf8'))
        .split('\n')
        .filter((line) => line && !line.startsWith('#'))
        .map((line) => {
            const separator = line.indexOf('=');
            return [line.slice(0, separator), line.slice(separator + 1)];
        }),
);
const keycloakEnvironment = Object.fromEntries(
    (await readFile('.env.keycloak', 'utf8'))
        .split('\n')
        .filter((line) => line && !line.startsWith('#'))
        .map((line) => {
            const separator = line.indexOf('=');
            return [line.slice(0, separator), line.slice(separator + 1)];
        }),
);
const recentHttpResponses = [];
const browser = spawn(
    chromePath,
    [
        '--headless=new',
        '--no-sandbox',
        '--disable-gpu',
        '--disable-dev-shm-usage',
        '--remote-allow-origins=*',
        '--remote-debugging-port=9222',
        `--user-data-dir=/tmp/agendamento-keycloak-browser-${process.pid}`,
        'about:blank',
    ],
    { stdio: 'ignore' },
);

let socket;
try {
    await waitUntil(async () => {
        const response = await fetch('http://127.0.0.1:9222/json/version');
        return response.ok;
    });
    const targetResponse = await fetch(
        'http://127.0.0.1:9222/json/new?about:blank',
        { method: 'PUT' },
    );
    const target = await targetResponse.json();
    socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
        socket.addEventListener('open', resolve, { once: true });
        socket.addEventListener('error', reject, { once: true });
    });

    let commandId = 0;
    const pending = new Map();
    socket.addEventListener('message', ({ data }) => {
        const message = JSON.parse(data);
        if (message.method === 'Network.responseReceived') {
            try {
                const responseUrl = new URL(message.params.response.url);
                if (responseUrl.hostname === 'localhost') {
                    recentHttpResponses.push({
                        path: responseUrl.pathname,
                        status: message.params.response.status,
                        type: message.params.type,
                    });
                    if (recentHttpResponses.length > 20) recentHttpResponses.shift();
                }
            } catch {}
        }
        if (!message.id || !pending.has(message.id)) return;
        const { resolve, reject } = pending.get(message.id);
        pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
    });
    const command = (method, params = {}) =>
        new Promise((resolve, reject) => {
            const id = ++commandId;
            pending.set(id, { resolve, reject });
            socket.send(JSON.stringify({ id, method, params }));
        });
    const evaluate = async (expression) => {
        const result = await command('Runtime.evaluate', {
            expression,
            returnByValue: true,
            awaitPromise: true,
        });
        if (result.exceptionDetails) throw new Error('Execução do navegador falhou.');
        return result.result.value;
    };

    await command('Page.enable');
    await command('Runtime.enable');
    await command('Network.enable');
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    const sessionBeforeLogin = (await cookies(command)).find(
        (cookie) => cookie.name === 'AGDSESSION',
    );
    if (
        sessionBeforeLogin &&
        (!sessionBeforeLogin.httpOnly || sessionBeforeLogin.sameSite !== 'Lax')
    ) {
        throw new Error('Cookie de sessão não tem HttpOnly e SameSite=Lax.');
    }
    console.log('Browser: frontend carregado.');
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('username', evaluate, true);
    console.log('Browser: formulário Keycloak aberto.');
    await submitLogin(
        credentials.KEYCLOAK_DEMO_USERNAME,
        credentials.KEYCLOAK_DEMO_PASSWORD,
        evaluate,
    );
    await waitForText('Sessão administrativa ativa.', evaluate);
    console.log('Browser: sessão autorizada estabelecida.');
    const sessionAfterLogin = (await cookies(command)).find(
        (cookie) => cookie.name === 'AGDSESSION',
    );
    if (
        !sessionAfterLogin ||
        (sessionBeforeLogin && sessionAfterLogin.value === sessionBeforeLogin.value)
    ) {
        throw new Error('Identificador da sessão não foi renovado após autenticação.');
    }
    if (!sessionAfterLogin.httpOnly || sessionAfterLogin.sameSite !== 'Lax') {
        throw new Error('Cookie autenticado não tem HttpOnly e SameSite=Lax.');
    }
    const tenantDisplayed = await evaluate(
        "document.body.innerText.includes(" +
            JSON.stringify(credentials.DEMO_TENANT_ID) +
            ')',
    );
    if (!tenantDisplayed) throw new Error('Tenant autorizado não apareceu na UI.');
    const foreignTenantQuery = await evaluate(
        `fetch('/api/v1/admin/tenant?tenantId=00000000-0000-0000-0000-000000000001', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (foreignTenantQuery.tenantId !== credentials.DEMO_TENANT_ID) {
        throw new Error('Parâmetro tenantId do cliente alterou o contexto autorizado.');
    }
    const missingCsrfStatus = await evaluate(
        "fetch('/api/v1/admin/logout', {method:'POST', credentials:'same-origin'}).then(response => response.status)",
    );
    const invalidCsrfStatus = await evaluate(
        "fetch('/api/v1/admin/logout', {method:'POST', credentials:'same-origin', headers:{'X-CSRF-TOKEN':'invalid'}}).then(response => response.status)",
    );
    const csrfProtectedSessionStatus = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
    );
    if (
        missingCsrfStatus !== 403 ||
        invalidCsrfStatus !== 403 ||
        csrfProtectedSessionStatus !== 200
    ) {
        throw new Error('Mutação sem CSRF válido não foi rejeitada com sessão preservada.');
    }
    setAccessStatus('admin_tenant_membership', 'DISABLED', credentials, appEnvironment);
    try {
        const revokedMembershipStatus = await evaluate(
            "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
        );
        if (revokedMembershipStatus !== 403) {
            throw new Error('Vínculo desativado não revogou a autorização da sessão ativa.');
        }
    } finally {
        setAccessStatus('admin_tenant_membership', 'ACTIVE', credentials, appEnvironment);
    }
    const revokedMembershipReuseStatus = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
    );
    if (revokedMembershipReuseStatus !== 401) {
        throw new Error('Cookie revogado foi aceito após reativar a associação.');
    }
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('Sessão administrativa ativa.', evaluate);
    setAccessStatus('admin_user', 'DISABLED', credentials, appEnvironment);
    try {
        const disabledAdminStatus = await evaluate(
            "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
        );
        if (disabledAdminStatus !== 403) {
            throw new Error('Administrador desativado não teve a sessão revogada.');
        }
    } finally {
        setAccessStatus('admin_user', 'ACTIVE', credentials, appEnvironment);
    }
    const disabledAdminReuseStatus = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
    );
    if (disabledAdminReuseStatus !== 401) {
        throw new Error('Cookie revogado foi aceito após reativar o administrador.');
    }
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('Sessão administrativa ativa.', evaluate);

    const sessionBeforeExpiry = (await cookies(command)).find(
        (cookie) => cookie.name === 'AGDSESSION',
    );
    if (!sessionBeforeExpiry) throw new Error('Cookie de sessão não encontrado antes do teste de expiração.');
    const nearExpiryRecord = setSessionIdleMinutes(
        sessionBeforeExpiry.value,
        25,
        appEnvironment,
    );
    if (nearExpiryRecord.maxInactiveInterval !== 1800) {
        throw new Error(`Intervalo de inatividade inesperado: ${nearExpiryRecord.maxInactiveInterval}s.`);
    }
    if (nearExpiryRecord.expiryTime <= Date.now()) {
        throw new Error('expiry_time não permaneceu futuro com 25 minutos de inatividade.');
    }
    const validBeforeExpiry = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(async response => ({status:response.status, body:await response.json()}))",
    );
    if (validBeforeExpiry.status !== 200) {
        throw new Error('Sessão com 25 minutos de inatividade foi rejeitada antes do limite.');
    }
    const refreshedRecord = readSessionRecord(sessionBeforeExpiry.value, appEnvironment);
    if (
        refreshedRecord.maxInactiveInterval !== 1800 ||
        refreshedRecord.lastAccessTime <= nearExpiryRecord.lastAccessTime + 24 * 60 * 1000 ||
        Math.abs(Date.now() - refreshedRecord.lastAccessTime) > 120_000 ||
        refreshedRecord.expiryTime <= Date.now()
    ) {
        throw new Error('Requisição válida não renovou last_access_time na sessão JDBC de 30 minutos.');
    }

    setSessionIdleMinutes(sessionBeforeExpiry.value, 31, appEnvironment);
    const expiredApiResponse = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(async response => ({status:response.status, body:await response.json()}))",
    );
    if (
        expiredApiResponse.status !== 401 ||
        expiredApiResponse.body.error?.code !== 'AUTHENTICATION_REQUIRED'
    ) {
        throw new Error('Cookie da sessão inativa por mais de 30 minutos acessou a API protegida.');
    }
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Sua sessão expirou ou ainda não foi iniciada.', evaluate);
    if (!(await evaluate("Boolean(document.querySelector('a[href*=oauth2]'))"))) {
        throw new Error('UI não ofereceu novo login após receber 401 da sessão expirada.');
    }
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('Sessão administrativa ativa.', evaluate);
    if (await evaluate("Boolean(document.querySelector('input[name=\"username\"]'))")) {
        throw new Error('SSO Keycloak ativo deveria permitir novo login sem formulário de credenciais.');
    }
    const renewedSessionStatus = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
    );
    if (renewedSessionStatus !== 200) {
        throw new Error('Nova autenticação OIDC não criou sessão local depois da expiração.');
    }
    const renewedCsrfStatus = await evaluate(
        "fetch('/api/v1/csrf', {credentials:'same-origin'}).then(response => response.status)",
    );
    if (renewedCsrfStatus !== 200) {
        throw new Error(`Endpoint CSRF indisponível após login SSO (HTTP ${renewedCsrfStatus}).`);
    }
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Sessão administrativa ativa.', evaluate);
    console.log('Browser: expiração local de 30 min, renovação por atividade e novo login via SSO validados.');

    await waitUntil(() =>
        evaluate(
            "Boolean([...document.querySelectorAll('button')].find(button => !button.disabled))",
        ),
    );
    await evaluate(
        "[...document.querySelectorAll('button')].find(button => button.innerText.includes('Sair')).click()",
    );
    await waitForText('Sessão local encerrada.', evaluate);
    const reusedSessionStatus = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.status)",
    );
    if (reusedSessionStatus !== 401) {
        throw new Error('Cookie invalidado ainda foi aceito após logout.');
    }
    console.log('Browser: logout local/CSRF validado.');
    await command('Network.clearBrowserCookies');
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('username', evaluate, true);
    await submitLogin(
        credentials.KEYCLOAK_UNLINKED_USERNAME,
        credentials.KEYCLOAK_UNLINKED_PASSWORD,
        evaluate,
    );
    await waitForText('ADMIN_ACCESS_DENIED', evaluate);
    assertNoCredentialsInBackendLogs(appEnvironment, keycloakEnvironment, credentials);
    console.log('Browser E2E passed: OIDC login, tenant, 30-minute idle expiry, SSO reauthentication, revocation, CSRF logout, unlinked denial, no test credentials in backend logs.');
} finally {
    socket?.close();
    browser.kill('SIGTERM');
}

async function navigate(url, command, evaluate) {
    await command('Page.navigate', { url });
    await waitUntil(async () => (await evaluate('location.href')).startsWith(url));
}

async function submitLogin(username, password, evaluate) {
    await evaluate(`(() => {
      const usernameField = document.querySelector('input[name="username"]');
      const passwordField = document.querySelector('input[name="password"]');
      const loginForm = document.querySelector('form');
      if (!usernameField || !passwordField || !loginForm) throw new Error('Login form not found');
      usernameField.value = ${JSON.stringify(username)};
      passwordField.value = ${JSON.stringify(password)};
      loginForm.requestSubmit();
      return true;
    })()`);
}

async function cookies(command) {
    const result = await command('Network.getAllCookies');
    return result.cookies.filter((cookie) => cookie.domain.includes('localhost'));
}

function setAccessStatus(table, status, credentials, environment) {
    const update =
        table === 'admin_user'
            ? `UPDATE agendamento.admin_user SET status = '${status}' WHERE user_id = (SELECT user_id FROM agendamento.admin_tenant_membership WHERE tenant_id = '${credentials.DEMO_TENANT_ID}')`
            : `UPDATE agendamento.admin_tenant_membership SET status = '${status}' WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`;
    execFileSync(
        'docker',
        [
            'compose',
            '-p',
            'agendamento',
            'exec',
            '-T',
            '-e',
            `PGPASSWORD=${environment.POSTGRES_PASSWORD}`,
            'postgres',
            'psql',
            '-X',
            '-q',
            '-v',
            'ON_ERROR_STOP=1',
            '-U',
            environment.POSTGRES_USER,
            '-d',
            environment.POSTGRES_DB,
            '-c',
            update,
        ],
        { stdio: 'ignore' },
    );
}

function setSessionIdleMinutes(sessionId, idleMinutes, environment) {
    const storedSessionId = normalizeSessionId(sessionId);
    const result = queryPostgres(
        `WITH idle AS (SELECT clock_timestamp() - interval '${idleMinutes} minutes' AS last_access)
         UPDATE agendamento.spring_session AS stored_session
         SET last_access_time = (extract(epoch FROM idle.last_access) * 1000)::bigint,
             expiry_time = (extract(epoch FROM idle.last_access + stored_session.max_inactive_interval * interval '1 second') * 1000)::bigint
         FROM idle
         WHERE replace(trim(stored_session.session_id), '-', '') = '${storedSessionId}'
         RETURNING stored_session.max_inactive_interval || '|' || stored_session.last_access_time || '|' || stored_session.expiry_time`,
        environment,
    );
    if (!result) throw new Error('Sessão JDBC ativa não encontrada para o teste temporal.');
    return parseSessionRecord(result);
}

function readSessionRecord(sessionId, environment) {
    const storedSessionId = normalizeSessionId(sessionId);
    const result = queryPostgres(
        `SELECT max_inactive_interval || '|' || last_access_time || '|' || expiry_time
         FROM agendamento.spring_session WHERE replace(trim(session_id), '-', '') = '${storedSessionId}'`,
        environment,
    );
    return result ? parseSessionRecord(result) : null;
}

function normalizeSessionId(sessionId) {
    const decodedSessionId =
        /^[0-9a-f]{32}$/i.test(sessionId) ||
        /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(sessionId)
            ? sessionId
            : Buffer.from(sessionId, 'base64url').toString('utf8');
    if (/^[0-9a-f]{32}$/i.test(decodedSessionId)) {
        return decodedSessionId;
    }
    if (/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(decodedSessionId)) {
        return decodedSessionId.replaceAll('-', '');
    }
    throw new Error('Identificador de sessão tem formato inesperado.');
}

function parseSessionRecord(value) {
    const [maxInactiveInterval, lastAccessTime, expiryTime] = value
        .split('|')
        .map(Number);
    return { maxInactiveInterval, lastAccessTime, expiryTime };
}

function queryPostgres(query, environment) {
    return execFileSync(
        'docker',
        [
            'compose',
            '-p',
            'agendamento',
            'exec',
            '-T',
            '-e',
            `PGPASSWORD=${environment.POSTGRES_PASSWORD}`,
            'postgres',
            'psql',
            '-X',
            '-q',
            '-t',
            '-A',
            '-v',
            'ON_ERROR_STOP=1',
            '-U',
            environment.POSTGRES_USER,
            '-d',
            environment.POSTGRES_DB,
            '-c',
            query,
        ],
        { encoding: 'utf8' },
    ).trim();
}

function assertNoCredentialsInBackendLogs(appEnvironment, keycloakEnvironment, credentials) {
    const logs = execFileSync(
        'docker',
        [
            'compose',
            '-p',
            'agendamento',
            '--env-file',
            '.env',
            '--env-file',
            '.env.keycloak',
            '-f',
            'compose.yaml',
            '-f',
            'compose.keycloak.yaml',
            '-f',
            'compose.identity.yaml',
            'logs',
            '--no-color',
            'backend',
        ],
        { encoding: 'utf8' },
    );
    const secretValues = [
        appEnvironment.POSTGRES_PASSWORD,
        appEnvironment.DB_PASSWORD,
        keycloakEnvironment.KEYCLOAK_DB_PASSWORD,
        keycloakEnvironment.KEYCLOAK_ADMIN_PASSWORD,
        keycloakEnvironment.OIDC_CLIENT_SECRET,
        credentials.KEYCLOAK_DEMO_PASSWORD,
        credentials.KEYCLOAK_UNLINKED_PASSWORD,
    ].filter(Boolean);
    if (secretValues.some((secret) => logs.includes(secret))) {
        throw new Error('Log do backend contém uma credencial de teste.');
    }
}

async function waitForText(text, evaluate, selector = false) {
    try {
        await waitUntil(async () => {
            const expression = selector
                ? `Boolean(document.querySelector('input[name="${text}"]'))`
                : `document.body.innerText.includes(${JSON.stringify(text)})`;
            return evaluate(expression);
        });
    } catch (error) {
        const diagnostic = await evaluate(
            `JSON.stringify({page: location.origin + location.pathname, title: document.title, heading: document.querySelector("h1")?.innerText, output: document.querySelector("output")?.innerText, recentHttpResponses: ${JSON.stringify(recentHttpResponses)}})`,
        );
        throw new Error(`Estado ${text} não alcançado: ${diagnostic}`, { cause: error });
    }
}

async function waitUntil(check, timeoutMs = 60000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
        try {
            if (await check()) return;
        } catch {}
        await new Promise((resolve) => setTimeout(resolve, 250));
    }
    throw new Error('Timeout aguardando estado do navegador.');
}
