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
    await waitUntil(async () => (await cookies(command)).some((cookie) => cookie.name === 'AGDSESSION'));
    const sessionBeforeLogin = (await cookies(command)).find(
        (cookie) => cookie.name === 'AGDSESSION',
    );
    if (!sessionBeforeLogin?.httpOnly || sessionBeforeLogin.sameSite !== 'Lax') {
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
    if (!sessionAfterLogin || sessionAfterLogin.value === sessionBeforeLogin.value) {
        throw new Error('Identificador da sessão não foi renovado após autenticação.');
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
    console.log('Browser E2E passed: OIDC login, tenant, revocation, CSRF logout, unlinked denial, no test credentials in backend logs.');
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
            'JSON.stringify({page: location.origin + location.pathname, title: document.title, heading: document.querySelector("h1")?.innerText, output: document.querySelector("output")?.innerText})',
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
