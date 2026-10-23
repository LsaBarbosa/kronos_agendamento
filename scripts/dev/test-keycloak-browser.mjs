import { readFile } from 'node:fs/promises';
import { createHash, createHmac, randomUUID } from 'node:crypto';
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
let browserCdp;
let secondTab;
let secondTargetId;
let unlinkedTab;
let unlinkedTargetId;
try {
    await waitUntil(async () => {
        const response = await fetch('http://127.0.0.1:9222/json/version');
        return response.ok;
    });
    const targetResponse = await fetch('http://127.0.0.1:9222/json/new?about:blank', { method: 'PUT' });
    const target = await targetResponse.json();
    socket = await connectToCdp(target.webSocketDebuggerUrl, recentHttpResponses);
    const { command, evaluate } = socket;

    await command('Page.enable');
    await command('Runtime.enable');
    await command('Network.enable');
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    const sessionBeforeLogin = (await cookies(command)).find((cookie) => cookie.name === 'AGDSESSION');
    if (sessionBeforeLogin && (!sessionBeforeLogin.httpOnly || sessionBeforeLogin.sameSite !== 'Lax')) {
        throw new Error('Cookie de sessão não tem HttpOnly e SameSite=Lax.');
    }
    console.log('Browser: frontend carregado.');
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('username', evaluate, true);
    console.log('Browser: formulário Keycloak aberto.');
    await submitLogin(credentials.KEYCLOAK_DEMO_USERNAME, credentials.KEYCLOAK_DEMO_PASSWORD, evaluate);
    await waitForText('Sessão administrativa ativa.', evaluate);
    console.log('Browser: sessão autorizada estabelecida.');
    const sessionAfterLogin = (await cookies(command)).find((cookie) => cookie.name === 'AGDSESSION');
    if (!sessionAfterLogin || (sessionBeforeLogin && sessionAfterLogin.value === sessionBeforeLogin.value)) {
        throw new Error('Identificador da sessão não foi renovado após autenticação.');
    }
    if (!sessionAfterLogin.httpOnly || sessionAfterLogin.sameSite !== 'Lax') {
        throw new Error('Cookie autenticado não tem HttpOnly e SameSite=Lax.');
    }
    const authenticatedSession = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.json())",
    );
    if (!authenticatedSession.authenticated || authenticatedSession.tenantId !== credentials.DEMO_TENANT_ID) {
        throw new Error(
            `Sessão autenticada diverge do tenant de demonstração: esperado ${credentials.DEMO_TENANT_ID}, recebido ${authenticatedSession.tenantId}.`,
        );
    }
    const tenantDisplayed = await evaluate(
        `Array.from(document.querySelectorAll('p')).some(paragraph => paragraph.textContent.includes('Tenant autorizado:') && paragraph.querySelector('code')?.textContent === ${JSON.stringify(authenticatedSession.tenantId)})`,
    );
    if (!tenantDisplayed) throw new Error('Tenant autorizado não apareceu na UI.');
    await waitForText('Plano e período de avaliação', evaluate);
    await waitForText('Cobrança anual equivalente a 10 mensalidades', evaluate);
    const subscriptionOverview = await evaluate(
        "fetch('/api/v1/admin/subscription', {credentials:'same-origin', cache:'no-store'}).then(async response => ({status:response.status, cache:response.headers.get('Cache-Control'), body:await response.json()}))",
    );
    if (
        subscriptionOverview.status !== 200 ||
        !subscriptionOverview.cache?.includes('no-store') ||
        subscriptionOverview.body.subscription?.status !== 'TRIALING' ||
        subscriptionOverview.body.subscription?.version !== 1 ||
        !subscriptionOverview.body.subscription?.trialStartedAt ||
        !subscriptionOverview.body.subscription?.trialEndsAt ||
        subscriptionOverview.body.trialEligibilityPending !== false ||
        subscriptionOverview.body.commercialEligibility !== 'VERIFIED' ||
        subscriptionOverview.body.plans.length !== 2 ||
        subscriptionOverview.body.plans.find((plan) => plan.code === 'ANNUAL')?.annualEquivalentMonthlyPayments !== 10
    ) {
        throw new Error('Visão administrativa não exibiu o trial sintético persistido e o catálogo esperado.');
    }
    const subscriptionHoldScript = await command('Page.addScriptToEvaluateOnNewDocument', {
        source: `(() => {
          const originalFetch = window.fetch.bind(window);
          window.__adminSessionFetches = 0;
          window.fetch = async (input, init) => {
            const response = await originalFetch(input, init);
            const requestUrl = typeof input === 'string' ? input : input.url;
            const path = new URL(requestUrl, location.href).pathname;
            if (path === '/api/v1/admin/session') window.__adminSessionFetches += 1;
            if (path === '/api/v1/admin/subscription' && !window.__subscriptionHoldUsed) {
              window.__subscriptionHoldUsed = true;
              const originalJson = response.json.bind(response);
              response.json = async (...args) => {
                const body = await originalJson(...args);
                window.__subscriptionBodyParsed = true;
                await new Promise(resolve => { window.__releaseSubscriptionBody = resolve; });
                return body;
              };
            }
            return response;
          };
        })();`,
    });
    await command('Page.reload', { ignoreCache: true });
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitUntil(() => evaluate('window.__subscriptionBodyParsed === true'));
    await command('Page.removeScriptToEvaluateOnNewDocument', {
        identifier: subscriptionHoldScript.identifier,
    });
    await clickButtonByText('Sair', evaluate);
    await waitForText('Sessão local encerrada.', evaluate);
    await evaluate('window.__releaseSubscriptionBody?.()');
    const subscriptionLogoutState = () => evaluate(`({
      loggedOut: document.body.innerText.includes('Sessão local encerrada.'),
      subscriptionPanel: Boolean(document.querySelector('#subscription-heading')),
      trialRestored: document.body.innerText.includes('Trial ativo'),
      loginLink: Boolean(document.querySelector('.login-link')),
      logoutButton: [...document.querySelectorAll('button')].some(button => button.innerText.trim() === 'Sair'),
      subscriptionPrivateData: ['Trial ativo', 'Assinatura ativa', 'Pagamento pendente', 'Assinatura suspensa', 'Trial encerrado', 'Plano:', 'Pago até:', 'Fim da tolerância:'].some(text => document.body.innerText.includes(text)),
      adminSessionFetches: window.__adminSessionFetches,
    })`);
    const stateBeforeRelease = await subscriptionLogoutState();
    if (!stateBeforeRelease.loginLink || stateBeforeRelease.logoutButton) {
        throw new Error(`Logout visual não foi aplicado antes de liberar GET atrasado: ${JSON.stringify(stateBeforeRelease)}`);
    }
    try {
        await waitUntil(async () => {
            const state = await subscriptionLogoutState();
            return state.loginLink && !state.logoutButton && !state.subscriptionPrivateData;
        });
    } catch (cause) {
        const finalState = await subscriptionLogoutState();
        throw new Error(
            `GET comercial atrasado não atingiu o estado protegido após logout: ${JSON.stringify(finalState)}`,
            { cause },
        );
    }
    const staleSubscriptionAfterLogout = await subscriptionLogoutState();
    if (!staleSubscriptionAfterLogout.loginLink || staleSubscriptionAfterLogout.logoutButton || staleSubscriptionAfterLogout.subscriptionPrivateData) {
        throw new Error(`GET comercial atrasado restaurou estado após logout: ${JSON.stringify(staleSubscriptionAfterLogout)}`);
    }
    console.log('Browser: GET administrativo de assinatura liberado após logout não restaura dados comerciais privados no DOM.');
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitForText('Plano e período de avaliação', evaluate);
    await waitForText('Trial ativo', evaluate);
    await waitForText('Catálogo de serviços', evaluate);
    await waitForText('Nenhum serviço cadastrado.', evaluate);
    await waitForText('Grade semanal e políticas', evaluate);
    await waitForText('A grade ainda não foi configurada.', evaluate);
    const catalogName = `Catálogo E2E ${Date.now()}`;
    await setAdminField('input', 0, catalogName, evaluate);
    await setAdminField('textarea', 0, 'Atendimento de demonstração', evaluate);
    await setAdminField('input', 1, '45', evaluate);
    await setAdminField('input', 2, '0', evaluate);
    await setAdminField('select', 0, 'ACTIVE', evaluate);
    // Commit succeeds, but the first response is lost before the UI receives it.
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).endsWith('/v1/admin/services') && args[1]?.method === 'POST' && response.ok) {
          window.fetch = originalFetch;
          throw new TypeError('Simulated lost response after commit');
        }
        return response;
      };
    })()`);
    await evaluate("document.querySelector('form').requestSubmit()");
    await waitForText('Não foi possível salvar.', evaluate);
    await evaluate("document.querySelector('form').requestSubmit()");
    await waitForText('Serviço criado.', evaluate);
    const matchingServices = await evaluate(
        `fetch('/api/v1/admin/services', { credentials: 'same-origin' }).then(response => response.json()).then(services => services.filter(service => service.name === ${JSON.stringify(catalogName)}).length)`,
    );
    if (matchingServices !== 1) throw new Error('Retry duplicated catalog service after lost response');
    await waitForText(catalogName, evaluate);
    await evaluate(`(() => {
      const serviceRow = [...document.querySelectorAll('li')].find(item => item.innerText.includes(${JSON.stringify(catalogName)}));
      const action = [...serviceRow.querySelectorAll('button')].find(button => button.innerText === 'Inativar');
      action.click();
    })()`);
    await waitForText('Serviço inativado.', evaluate);
    await evaluate(`(() => {
      const serviceRow = [...document.querySelectorAll('li')].find(item => item.innerText.includes(${JSON.stringify(catalogName)}));
      [...serviceRow.querySelectorAll('button')].find(button => button.innerText === 'Editar').click();
    })()`);
    await setAdminField('input', 0, catalogName + ' Editado', evaluate);
    await evaluate("document.querySelector('form').requestSubmit()");
    await waitForText('Serviço atualizado.', evaluate);
    await waitForText(catalogName + ' Editado', evaluate);
    await evaluate(`(() => {
      const serviceRow = [...document.querySelectorAll('li')].find(item => item.innerText.includes(${JSON.stringify(catalogName + ' Editado')}));
      [...serviceRow.querySelectorAll('button')].find(button => button.innerText === 'Ativar').click();
    })()`);
    await waitForText('Serviço ativado.', evaluate);
    console.log('Browser: catálogo autenticado criou, editou, inativou e ativou serviço.');
    await evaluate(`(() => {
      const day = [...document.querySelectorAll('fieldset')].find(fieldset => fieldset.querySelector('legend')?.innerText === 'Segunda-feira');
      const add = [...day.querySelectorAll('button')].find(button => button.innerText === 'Adicionar período');
      add.click(); add.click();
      return true;
    })()`);
    await setFieldByAriaLabel('Segunda-feira início 1', '09:00', evaluate);
    await setFieldByAriaLabel('Segunda-feira fim 1', '12:00', evaluate);
    await setFieldByAriaLabel('Segunda-feira início 2', '13:00', evaluate);
    await setFieldByAriaLabel('Segunda-feira fim 2', '17:00', evaluate);
    await setFieldByAriaLabel('Segunda-feira início 1', '09:10', evaluate);
    await clickButtonByText('Salvar grade semanal', evaluate);
    await waitForText('início e fim devem estar alinhados a cada 15 minutos.', evaluate);
    const unchangedSchedule = await evaluate(
        "fetch('/api/v1/admin/schedule/weekly-periods', {credentials:'same-origin'}).then(response => response.json())",
    );
    if (unchangedSchedule.configured || unchangedSchedule.version !== 0) {
        throw new Error('Rejected unaligned period changed the persisted schedule.');
    }
    await setFieldByAriaLabel('Segunda-feira início 1', '09:00', evaluate);
    await clickButtonByText('Salvar grade semanal', evaluate);
    await waitForText('Grade semanal salva.', evaluate);
    await setSelectByLabel('Intervalo inicial dos horários (minutos)', '30', evaluate);
    await setFieldByLabel('Antecedência mínima (horas)', '12', evaluate);
    await setSelectByLabel('Horizonte máximo para agendamento (dias)', '30', evaluate);
    await setFieldByLabel('Limite de cancelamento pelo cliente (horas antes)', '4', evaluate);
    await setSelectByLabel('Lembrete único antes do atendimento (horas)', '6', evaluate);
    await clickButtonByText('Salvar políticas', evaluate);
    await waitForText('Políticas salvas.', evaluate);

    await waitForText('Limite ainda não configurado.', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.__noShowRequests = [];
      window.__noShowCalls = [];
      window.__holdNoShowReads = true;
      window.__noShowGatewayFault = false;
      window.__noShowOriginalFetch = originalFetch;
      window.fetch = async (...args) => {
        const url = String(args[0]);
        const method = args[1]?.method ?? 'GET';
        if (url.endsWith('/v1/admin/no-show-policy') && method === 'GET' && window.__holdNoShowReads) {
          return new Promise(resolve => window.__noShowRequests.push({
            release: () => resolve(new Response(JSON.stringify({configured:false, policyStatus:'NOT_CONFIGURED', version:0, threshold:null, restrictionIntegrationStatus:'TRACKED'}), {status:200, headers:{'Content-Type':'application/json'}}))
          }));
        }
        if (url.endsWith('/v1/admin/no-show-policy') && method === 'PUT') {
          const response = await originalFetch(...args);
          const call = {body: args[1].body, key: args[1].headers['Idempotency-Key'], status: response.status, replayed: response.headers.get('Idempotency-Replayed')};
          if (window.__noShowGatewayFault && response.ok) {
            window.__noShowGatewayFault = false;
            call.status = 503;
            call.replayed = null;
            window.__noShowCalls.push(call);
            return new Response(JSON.stringify({error:{code:'GATEWAY_UNAVAILABLE',message:'Resposta substituída pelo gateway E2E'}}), {status:503, headers:{'Content-Type':'application/json'}});
          }
          window.__noShowCalls.push(call);
          return response;
        }
        return originalFetch(...args);
      };
    })()`);
    await clickButtonByText('Atualizar política', evaluate);
    await clickButtonByText('Atualizar política', evaluate);
    await waitUntil(() => evaluate('window.__noShowRequests.length === 2'));
    await evaluate('window.__noShowRequests[0].release()');
    await setSelectById('no-show-threshold', '3', evaluate);
    await clickButtonByText('Salvar limite', evaluate);
    await waitForText('Limite de faltas salvo para este tenant.', evaluate);
    await evaluate(`(() => {
      window.__holdNoShowReads = false;
      window.__noShowRequests[1].release();
    })()`);
    const policyAfterStaleRead = await evaluate(`(() => ({
      text: document.querySelector('#no-show-policy-title')?.parentElement?.innerText ?? '',
      threshold: document.getElementById('no-show-threshold')?.value,
      loading: [...document.querySelectorAll('output')].some(output => output.innerText.includes('Carregando política'))
    }))()`);
    if (
        !policyAfterStaleRead.text.includes('Limite configurado: 3 falta(s), versão 1.') ||
        policyAfterStaleRead.threshold !== '3' ||
        policyAfterStaleRead.loading
    ) {
        throw new Error(`GET obsoleto substituiu a política salva ou manteve loading: ${JSON.stringify(policyAfterStaleRead)}`);
    }

    await setSelectById('no-show-threshold', '4', evaluate);
    await evaluate('window.__noShowCalls = []; window.__noShowGatewayFault = true');
    await clickButtonByText('Salvar limite', evaluate);
    await waitForText('O servidor/gateway não confirmou o resultado.', evaluate);
    const uncertainNoShow = await evaluate(`(() => ({
      text: document.querySelector('#no-show-policy-title')?.parentElement?.innerText ?? '',
      thresholdDisabled: document.getElementById('no-show-threshold')?.disabled,
      retryText: [...document.querySelectorAll('button')].find(button => button.innerText.includes('Recuperar operação'))?.innerText,
      calls: window.__noShowCalls
    }))()`);
    if (
        !uncertainNoShow.thresholdDisabled ||
        !uncertainNoShow.retryText ||
        uncertainNoShow.calls.length !== 1 ||
        uncertainNoShow.calls[0].status !== 503 ||
        JSON.parse(uncertainNoShow.calls[0].body).expectedVersion !== 1 ||
        JSON.parse(uncertainNoShow.calls[0].body).threshold !== 4
    ) {
        throw new Error(`UI não preservou o comando após 503: ${JSON.stringify(uncertainNoShow)}`);
    }
    await clickButtonByText('Recuperar operação', evaluate);
    await waitForText('Limite de faltas salvo para este tenant.', evaluate);
    const recoveredNoShow = await evaluate(`(() => ({
      text: document.querySelector('#no-show-policy-title')?.parentElement?.innerText ?? '',
      threshold: document.getElementById('no-show-threshold')?.value,
      calls: window.__noShowCalls
    }))()`);
    if (
        !recoveredNoShow.text.includes('Limite configurado: 4 falta(s), versão 2.') ||
        recoveredNoShow.threshold !== '4' ||
        recoveredNoShow.calls.length !== 2 ||
        recoveredNoShow.calls[0].body !== recoveredNoShow.calls[1].body ||
        recoveredNoShow.calls[0].key !== recoveredNoShow.calls[1].key ||
        recoveredNoShow.calls[1].replayed !== 'true'
    ) {
        throw new Error(`Retry após 503 mudou intenção ou não recuperou o receipt: ${JSON.stringify(recoveredNoShow)}`);
    }
    const noShowEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT count(*) FROM agendamento.client_no_show_policy WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND threshold = 4 AND version = 2) = 1 AND (SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND action = 'CLIENT_NO_SHOW_POLICY_UPDATED') = 2 AND (SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND command_scope = 'clients.no-show-policy.replace.v1' AND state = 'COMPLETED') = 2 THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (noShowEffects !== 1) throw new Error('Retries da política criaram mais de uma mutação, auditoria ou receipt por comando.');
    console.log('Browser: GET obsoleto não substituiu v1; 503 após commit recuperou o mesmo body/chave, receipt e versão v2.');

    await clickButtonByText('Recarregar configuração persistida', evaluate);
    await waitForText('Configuração recarregada.', evaluate);
    await clickButtonByText('Atualizar política', evaluate);
    await waitForText('Limite configurado: 4 falta(s), versão 2.', evaluate);
    const persistedSchedule = await evaluate(
        "fetch('/api/v1/admin/schedule/weekly-periods', {credentials:'same-origin'}).then(response => response.json())",
    );
    const persistedPolicy = await evaluate(
        "fetch('/api/v1/admin/availability-policy', {credentials:'same-origin'}).then(response => response.json())",
    );
    const persistedNoShowPolicy = await evaluate(
        "fetch('/api/v1/admin/no-show-policy', {credentials:'same-origin'}).then(response => response.json())",
    );
    if (
        persistedSchedule.periods.length !== 2 ||
        persistedSchedule.periods[0].weekday !== 'MONDAY' ||
        persistedSchedule.periods[0].localStart !== '09:00' ||
        persistedSchedule.periods[1].localStart !== '13:00' ||
        persistedPolicy.slotStepMinutes !== 30 ||
        persistedPolicy.minimumNoticeHours !== 12 ||
        persistedPolicy.bookingHorizonDays !== 30 ||
        persistedPolicy.cancellationCutoffHours !== 4 ||
        persistedPolicy.reminderOffsetHours !== 6 ||
        !persistedNoShowPolicy.configured ||
        persistedNoShowPolicy.threshold !== 4 ||
        persistedNoShowPolicy.version !== 2 ||
        persistedNoShowPolicy.policyStatus !== 'CONFIGURED' ||
        persistedNoShowPolicy.restrictionIntegrationStatus !== 'TRACKED'
    ) {
        throw new Error('Grade/políticas não persistiram ou divergiram após recarregar.');
    }
    console.log('Browser: grade semanal e políticas salvas, recarregadas e confirmadas pela API.');
    const publication = await evaluate(
        "fetch('/api/v1/admin/publication', {credentials:'same-origin'}).then(response => response.json())",
    );
    if (publication.published) throw new Error('Tenant de demonstração iniciou publicado.');
    await setFieldByLabel('Telefone de contato público', '+5511999990000', evaluate);
    await clickButtonByText('Salvar telefone', evaluate);
    await waitForText('Telefone de contato salvo.', evaluate);
    await clickButtonByText('Publicar página', evaluate);
    await waitForText('Estado: Publicado', evaluate);
    const publicService = await evaluate(
        `fetch('/api/v1/admin/services', {credentials:'same-origin'}).then(response => response.json()).then(rows => rows.find(service => service.name === ${JSON.stringify(catalogName + ' Editado')} && service.status === 'ACTIVE'))`,
    );
    if (!publicService?.serviceId)
        throw new Error('Serviço ativo de demonstração não encontrado para a página pública.');
    await navigate(`http://localhost:5173/public/${encodeURIComponent(publication.slug)}`, command, evaluate);
    await waitForText(catalogName + ' Editado', evaluate);
    await setSelectById('public-service', publicService.serviceId, evaluate);
    const publicDate = await evaluate(`(() => {
      const timeZone = ${JSON.stringify(publication.timeZone)};
      const fields = Object.fromEntries(new Intl.DateTimeFormat('en-US', {timeZone, year:'numeric', month:'2-digit', day:'2-digit'}).formatToParts(new Date()).map(part => [part.type, part.value]));
      const today = new Date(Date.UTC(Number(fields.year), Number(fields.month)-1, Number(fields.day)));
      const daysUntilMonday = (8 - today.getUTCDay()) % 7;
      const daysAhead = daysUntilMonday < 4 ? daysUntilMonday + 7 : daysUntilMonday;
      today.setUTCDate(today.getUTCDate() + daysAhead);
      return today.toISOString().slice(0, 10);
    })()`);
    await setFieldById('public-date', publicDate, evaluate);
    await waitUntil(() => evaluate("document.querySelectorAll('.public-page__slot').length > 0"));
    const publicSlotsBeforeSelection = await evaluate("document.querySelectorAll('.public-page__slot').length");
    const selectedStart = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${publicDate}&to=${publicDate}').then(response => response.json()).then(result => result.days[0].slots[0].startsAt)`,
    );
    await evaluate("document.querySelector('.public-page__slot').click()");
    if (!(await evaluate("document.body.innerText.includes('A seleção não confirma uma reserva.')"))) {
        throw new Error('A seleção pública não deixou claro que não confirma uma reserva.');
    }
    await setFieldById('public-client-name', 'Visitante de demonstração', evaluate);
    await setFieldById('public-client-phone', '+5511999991234', evaluate);
    const bookingInputState = await evaluate(`(() => {
      const name = document.getElementById('public-client-name').value;
      const phone = document.getElementById('public-client-phone').value;
      return {nameLength: name.trim().length, phoneLength: phone.trim().length, phoneValid: /^\\+[1-9][0-9]{7,14}$/.test(phone.trim())};
    })()`);
    if (bookingInputState.nameLength === 0 || !bookingInputState.phoneValid) {
        throw new Error(`Browser booking inputs invalid: ${JSON.stringify(bookingInputState)}`);
    }
    const browserVersion = await fetch('http://127.0.0.1:9222/json/version').then((response) => response.json());
    browserCdp = await connectToCdp(browserVersion.webSocketDebuggerUrl);
    const secondTarget = await browserCdp.command('Target.createTarget', {
        url: 'about:blank',
    });
    secondTargetId = secondTarget.targetId;
    const secondTargetInfo = await waitForTarget(secondTargetId);
    secondTab = await connectToCdp(secondTargetInfo.webSocketDebuggerUrl);
    await secondTab.command('Page.enable');
    await secondTab.command('Runtime.enable');
    await navigate(
        `http://localhost:5173/public/${encodeURIComponent(publication.slug)}`,
        secondTab.command,
        secondTab.evaluate,
    );
    await waitForText(catalogName + ' Editado', secondTab.evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).endsWith('/appointments') && !response.ok) {
          const payload = await response.clone().json().catch(() => ({}));
          window.__bookingE2eError = {status: response.status, code: payload?.error?.code};
        }
        if (String(args[0]).endsWith('/appointments') && args[1]?.method === 'POST' && response.ok) {
          window.__bookingResponseLost = true;
          throw new TypeError('Simulated lost appointment response after commit');
        }
        if (String(args[0]).endsWith('/booking-intents/current') && !args[1]?.method && !window.__recoveryResponseHeld) {
          window.fetch = originalFetch;
          window.__recoveryResponseHeld = true;
          window.__recoveryResponseReady = true;
          await new Promise(resolve => { window.__releaseRecoveryResponse = resolve; });
        }
        return response;
      };
    })()`);
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitUntil(() => evaluate('window.__bookingResponseLost === true && window.__recoveryResponseReady === true'));
    await waitForText('Não foi possível confirmar o resultado do envio.', evaluate);
    await waitForText('Recuperação da reserva', secondTab.evaluate);
    await waitForText('Reserva original recuperada.', secondTab.evaluate);
    const unresolvedIntentLocked = await evaluate(`(() => {
      const slotButtons = [...document.querySelectorAll('.public-page__slot')];
      return document.getElementById('public-service').disabled
        && document.getElementById('public-date').disabled
        && document.getElementById('public-client-name').disabled
        && document.getElementById('public-client-phone').disabled
        && slotButtons.length > 0 && slotButtons.every(button => button.disabled)
        && !document.querySelector('.public-page__booking-form button[type="submit"]').disabled;
    })()`);
    if (!unresolvedIntentLocked)
        throw new Error('A interface permitiu alterar uma intenção de reserva ainda não resolvida.');
    await evaluate('window.__releaseRecoveryResponse?.()');
    await waitForText('Reserva confirmada recuperada.', evaluate);
    let appointmentIdInEachTab = [null, null];
    try {
        await waitUntil(async () => {
            appointmentIdInEachTab = await Promise.all([
                evaluate("document.querySelector('.public-page__confirmation strong')?.textContent"),
                secondTab.evaluate("document.querySelector('.public-page__confirmation strong')?.textContent"),
            ]);
            return Boolean(appointmentIdInEachTab[0] && appointmentIdInEachTab[0] === appointmentIdInEachTab[1]);
        }, 10000);
    } catch (error) {
        const tabStates = await Promise.all([
            evaluate(
                "JSON.stringify({heading:document.querySelector('h2')?.innerText, alerts:[...document.querySelectorAll('[role=alert]')].map(item=>item.innerText).slice(0,3)})",
            ),
            secondTab.evaluate(
                "JSON.stringify({heading:document.querySelector('h2')?.innerText, alerts:[...document.querySelectorAll('[role=alert]')].map(item=>item.innerText).slice(0,3)})",
            ),
        ]);
        throw new Error(
            `Duas abas não recuperaram a mesma reserva original: ${JSON.stringify({ appointmentIdInEachTab, tabStates })}`,
            { cause: error },
        );
    }
    const storedIntent = await evaluate(`(() => {
      const value = localStorage.getItem('kronos.public-booking.intent.' + ${JSON.stringify(publication.slug)});
      if (!value) return null;
      const parsed = JSON.parse(value);
      return {
        hasKey: typeof parsed.idempotencyKey === 'string',
        hasCapability: typeof parsed.recoveryCapability === 'string',
        containsName: value.includes('Visitante de demonstração'),
        containsPhone: value.includes('+5511999991234'),
      };
    })()`);
    if (
        !storedIntent?.hasKey ||
        !storedIntent?.hasCapability ||
        storedIntent.containsName ||
        storedIntent.containsPhone
    ) {
        throw new Error(`Recovery storage contém formato inválido ou PII: ${JSON.stringify(storedIntent)}`);
    }
    await command('Page.reload');
    await waitForText('Reserva original recuperada.', evaluate);
    await waitForText('Reserva confirmada', evaluate);
    const restoredDate = await evaluate("document.getElementById('public-date')?.value");
    if (restoredDate !== publicDate) {
        throw new Error('Reload não restaurou a data da intenção original.');
    }
    if (
        !(await evaluate(
            `document.body.innerText.includes(${JSON.stringify(publicService.name)}) && document.body.innerText.includes(${JSON.stringify(publication.timeZone)})`,
        ))
    ) {
        throw new Error('Confirmação não exibiu serviço e timezone esperados.');
    }
    const persistedBookingCount = Number(
        queryPostgres(
            `SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.status = 'CONFIRMED'`,
            appEnvironment,
        ),
    );
    if (persistedBookingCount !== 1)
        throw new Error('Retry após resposta perdida não manteve uma única reserva persistida.');
    const slotsAfterConfirmation = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${publicDate}&to=${publicDate}').then(response => response.json()).then(result => result.days[0].slots)`,
    );
    if (slotsAfterConfirmation.some((slot) => slot.startsAt === selectedStart)) {
        throw new Error('Disponibilidade continuou oferecendo o horário já confirmado.');
    }
    const postTrialBookingCandidate = slotsAfterConfirmation.find(
        (slot) => slot.startsAt !== selectedStart,
    );
    if (!postTrialBookingCandidate) {
        throw new Error('A fixture não preservou um segundo horário elegível para o teste do gate comercial.');
    }
    console.log(
        'Browser: visitante perdeu a resposta após commit, recarregou e recuperou a reserva persistida; storage não contém PII e permaneceu uma única reserva.',
    );
    await clickButtonByText('Fazer outra reserva', evaluate);
    await waitUntil(
        async () =>
            await evaluate(
                `localStorage.getItem('kronos.public-booking.intent.' + ${JSON.stringify(publication.slug)}) === null && !document.getElementById('public-service').disabled`,
            ),
    );
    await waitUntil(
        async () =>
            await evaluate("[...document.querySelectorAll('.public-page__slot')].some(button => !button.disabled)"),
    );
    const anotherSlotCanBeSelected = await evaluate(
        "[...document.querySelectorAll('.public-page__slot')].some(button => !button.disabled)",
    );
    if (!anotherSlotCanBeSelected) {
        throw new Error('Receipt recuperado bloqueou permanentemente uma nova intenção explícita.');
    }
    await evaluate(
        "document.querySelectorAll('.public-page__slot:not(:disabled)')[document.querySelectorAll('.public-page__slot:not(:disabled)').length - 1].click()",
    );
    await setFieldById('public-client-name', 'Segundo visitante de demonstração', evaluate);
    await setFieldById('public-client-phone', '+5511999991235', evaluate);
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitForText('Reserva confirmada', evaluate);
    const secondAppointmentId = queryPostgres(
        `SELECT a.appointment_id FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.client_name_snapshot = 'Segundo visitante de demonstração'`,
        appEnvironment,
    );
    await command('Emulation.setDeviceMetricsOverride', {
        width: 390,
        height: 844,
        deviceScaleFactor: 1,
        mobile: true,
    });
    if (!(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'))) {
        throw new Error('A página pública excedeu a largura do viewport móvel.');
    }
    await command('Emulation.setDeviceMetricsOverride', {
        width: 1280,
        height: 900,
        deviceScaleFactor: 1,
        mobile: false,
    });
    if (!(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'))) {
        throw new Error('A página pública excedeu a largura do viewport desktop.');
    }
    await clickButtonByText('Fazer outra reserva', evaluate);
    await waitUntil(() => evaluate(`localStorage.getItem('kronos.public-booking.intent.' + ${JSON.stringify(publication.slug)}) === null`));
    console.log(
        `Browser: visitante consultou ${publicDate}, confirmou duas intenções sem duplicação entre ${publicSlotsBeforeSelection} horários; layout móvel/desktop sem overflow.`,
    );
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Estado: Publicado', evaluate);
    await waitForText('Exportar agendamentos', evaluate);
    await setFieldByLabel('Data inicial', publicDate, evaluate);
    await setFieldByLabel('Data final', publicDate, evaluate);
    await setFieldByLabel('ID do serviço (opcional)', publicService.serviceId, evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__exportPostAttempts = [];
      window.fetch = async (input, init = {}) => {
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        const method = (init.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
        if (url.pathname === '/api/v1/admin/exports' && method === 'POST') {
          const headers = new Headers(input instanceof Request ? input.headers : undefined);
          new Headers(init.headers).forEach((value, name) => headers.set(name, value));
          const attempt = {body: init.body, key: headers.get('Idempotency-Key'), committedStatus: null, returnedStatus: null};
          window.__exportPostAttempts.push(attempt);
          const response = await originalFetch(input, init);
          if (window.__exportPostAttempts.length === 1 && response.status === 202) {
            window.__exportFirstResponseLost = true;
            attempt.committedStatus = response.status;
            const gatewayResponse = new Response(JSON.stringify({error:{code:'GATEWAY_UNAVAILABLE',message:'Resposta substituída pelo gateway E2E'}}), {status:503, headers:{'Content-Type':'application/json'}});
            attempt.returnedStatus = gatewayResponse.status;
            return gatewayResponse;
          }
          attempt.committedStatus = response.status;
          attempt.returnedStatus = response.status;
          return response;
        }
        const response = await originalFetch(input, init);
        if (url.pathname.endsWith('/download-token') && response.ok) {
          window.__exportDownloadHandle = (await response.clone().json()).downloadHandle;
        }
        if (url.pathname.endsWith('/content')) {
          window.__exportContentStatus = response.status;
          if (response.ok) window.__exportContentText = await response.clone().text();
        }
        if (method === 'DELETE' && /\\/api\\/v1\\/admin\\/exports\\//.test(url.pathname)) {
          window.__exportIfMatch = new Headers(init.headers).get('If-Match');
          window.__exportExpireStatus = response.status;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Solicitar exportação', evaluate);
    await waitUntil(() => evaluate('window.__exportFirstResponseLost === true'));
    await waitForText('O resultado da solicitação é incerto.', evaluate);
    const firstExportAttempt = await evaluate('window.__exportPostAttempts[0]');
    const uncertainExportUi = await evaluate(`({
      intent: JSON.parse(sessionStorage.getItem('agd044.export-context.v1:' + encodeURIComponent(${JSON.stringify(authenticatedSession.tenantId)}))).intent,
      fromDisabled: document.querySelector('input[type=date]')?.disabled,
      retryDisabled: [...document.querySelectorAll('button')].find(button => button.innerText.includes('Recuperar solicitação pendente'))?.disabled,
      submitDisabled: [...document.querySelectorAll('button')].find(button => button.innerText.includes('Solicitar exportação'))?.disabled
    })`);
    const committedExportsBeforeRetry = Number(queryPostgres(
        `SELECT count(*) FROM agendamento.export_job WHERE tenant_id = '${authenticatedSession.tenantId}' AND category = 'APPOINTMENTS'`,
        appEnvironment,
    ));
    if (
        !firstExportAttempt?.key ||
        !firstExportAttempt.body ||
        firstExportAttempt.committedStatus !== 202 ||
        firstExportAttempt.returnedStatus !== 503 ||
        uncertainExportUi.intent?.phase !== 'UNCERTAIN' ||
        !uncertainExportUi.fromDisabled ||
        uncertainExportUi.retryDisabled ||
        !uncertainExportUi.submitDisabled ||
        committedExportsBeforeRetry !== 1
    ) {
        throw new Error(`POST aceito com commit seguido de 503 não preservou intenção/controles no DOM: ${JSON.stringify({firstExportAttempt, uncertainExportUi, committedExportsBeforeRetry})}`);
    }
    await clickButtonByText('Recuperar solicitação pendente', evaluate);
    await waitForText('Solicitação aceita.', evaluate);
    await waitUntil(async () => evaluate('window.__exportPostAttempts.length === 2'));
    const exportAttempts = await evaluate('window.__exportPostAttempts');
    if (
        exportAttempts[1].key !== firstExportAttempt.key ||
        exportAttempts[1].body !== firstExportAttempt.body ||
        exportAttempts[1].committedStatus !== 202 ||
        exportAttempts[1].returnedStatus !== 202 ||
        JSON.parse(exportAttempts[1].body).filters.from !== publicDate ||
        JSON.parse(exportAttempts[1].body).filters.to !== publicDate
    ) {
        throw new Error(`Retry de exportação mudou a intenção original: ${JSON.stringify(exportAttempts)}`);
    }
    await waitForText('Arquivo disponível', evaluate);
    const activeExport = await evaluate(`(async () => {
      const response = await fetch('/api/v1/admin/exports/active', {credentials:'same-origin', cache:'no-store'});
      const body = response.status === 204 ? null : await response.json();
      if (body) {
        const individual = await fetch('/api/v1/admin/exports/' + body.exportId, {credentials:'same-origin', cache:'no-store'});
        return {status:response.status, cache:response.headers.get('Cache-Control'), etag:response.headers.get('ETag'), body, individualStatus:individual.status, individualEtag:individual.headers.get('ETag'), individualBody:await individual.json()};
      }
      return {status:response.status, cache:response.headers.get('Cache-Control'), body};
    })()`);
    if (
        activeExport.status !== 200 ||
        !activeExport.cache?.includes('no-store') ||
        !/^"[1-9][0-9]*"$/.test(activeExport.etag ?? '') ||
        activeExport.individualStatus !== 200 ||
        activeExport.etag !== activeExport.individualEtag ||
        activeExport.body.exportId !== activeExport.individualBody.exportId ||
        activeExport.body.state !== 'AVAILABLE' ||
        'tenantId' in activeExport.body ||
        'requestedByAdminId' in activeExport.body ||
        'snapshotCiphertext' in activeExport.body
    ) {
        throw new Error(`GET /active divergiu do contrato minimizado/ETag: ${JSON.stringify(activeExport)}`);
    }
    const exportRows = Number(queryPostgres(
        `SELECT count(*) FROM agendamento.export_job WHERE tenant_id = '${authenticatedSession.tenantId}' AND export_id = '${activeExport.body.exportId}' AND state = 'AVAILABLE' AND category = 'APPOINTMENTS'`,
        appEnvironment,
    ));
    const exportEffects = queryPostgres(
        `SELECT (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${activeExport.body.exportId}' AND action = 'EXPORT_REQUESTED') || ':' || (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${activeExport.body.exportId}' AND event_type = 'ExportRequested') || ':' || (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${activeExport.body.exportId}' AND action = 'EXPORT_AVAILABLE') || ':' || (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${activeExport.body.exportId}' AND event_type = 'ExportAvailable')`,
        appEnvironment,
    );
    if (exportRows !== 1 || exportEffects !== '1:1:1:1') {
        throw new Error(`Retry duplicou exportação ou efeitos transacionais: ${JSON.stringify({exportRows, exportEffects})}`);
    }
    await command('Page.reload', {ignoreCache: true});
    await waitForText('Arquivo disponível', evaluate);
    const recoveredExportId = await evaluate(`JSON.parse(sessionStorage.getItem('agd044.export-context.v1:' + encodeURIComponent(${JSON.stringify(authenticatedSession.tenantId)}))).exportId`);
    const recoveredExportContext = await evaluate(`sessionStorage.getItem('agd044.export-context.v1:' + encodeURIComponent(${JSON.stringify(authenticatedSession.tenantId)}))`);
    if (
        recoveredExportId !== activeExport.body.exportId ||
        recoveredExportContext.includes('Visitante de demonstração') ||
        recoveredExportContext.includes('+5511999991234')
    ) {
        throw new Error('Reload não recuperou o mesmo job persistido da exportação.');
    }
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.fetch = async (input, init = {}) => {
        const response = await originalFetch(input, init);
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        if (url.pathname.endsWith('/download-token') && response.ok) window.__exportDownloadHandle = (await response.clone().json()).downloadHandle;
        if (url.pathname.endsWith('/content')) {
          window.__exportContentStatus = response.status;
          if (response.ok) window.__exportContentText = await response.clone().text();
        }
        if (init.method === 'DELETE' && /\\/api\\/v1\\/admin\\/exports\\//.test(url.pathname)) {
          window.__exportIfMatch = new Headers(init.headers).get('If-Match');
          window.__exportExpireStatus = response.status;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Baixar arquivo', evaluate);
    await waitForText('Download iniciado.', evaluate);
    const exportContent = await evaluate(`({text:window.__exportContentText, status:window.__exportContentStatus, handle:window.__exportDownloadHandle})`);
    if (
        exportContent.status !== 200 ||
        typeof exportContent.handle !== 'string' ||
        !exportContent.text?.includes('appointment_id') ||
        !exportContent.text.includes(publicService.name) ||
        !exportContent.text.includes(appointmentIdInEachTab[0]) ||
        exportContent.text.includes('Visitante de demonstração') ||
        exportContent.text.includes('+5511999991234')
    ) {
        throw new Error('CSV baixado não corresponde ao snapshot autorizado ou contém PII.');
    }
    await clickButtonByText('Encerrar acesso agora', evaluate);
    await waitForText('O acesso a esta exportação foi encerrado.', evaluate);
    const afterEarlyExpiry = await evaluate(`fetch('/api/v1/admin/exports/${activeExport.body.exportId}/content', {credentials:'same-origin', headers:{'X-Export-Download-Handle':window.__exportDownloadHandle}, cache:'no-store'}).then(async response => ({status:response.status, cache:response.headers.get('Cache-Control')}))`);
    const noActiveAfterExpiry = await evaluate(`fetch('/api/v1/admin/exports/active', {credentials:'same-origin', cache:'no-store'}).then(async response => ({status:response.status, body:await response.text(), cache:response.headers.get('Cache-Control')}))`);
    const expiredExportAudit = queryPostgres(
        `SELECT (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${activeExport.body.exportId}' AND action = 'EXPORT_EXPIRED') || ':' || (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${activeExport.body.exportId}' AND event_type = 'ExportExpired')`,
        appEnvironment,
    );
    const expiryDom = await evaluate(`({ifMatch:window.__exportIfMatch, status:window.__exportExpireStatus, state:document.querySelector('.export-center__job h3')?.innerText})`);
    if (
        expiryDom.ifMatch !== activeExport.etag ||
        expiryDom.status !== 204 ||
        expiryDom.state !== 'Acesso expirado' ||
        afterEarlyExpiry.status !== 410 ||
        noActiveAfterExpiry.status !== 204 ||
        noActiveAfterExpiry.body !== '' ||
        !noActiveAfterExpiry.cache?.includes('no-store') ||
        expiredExportAudit !== '1:1'
    ) {
        throw new Error(`Expiração antecipada/negação posterior não respeitou ETag e efeitos únicos: ${JSON.stringify({expiryDom, afterEarlyExpiry, noActiveAfterExpiry, expiredExportAudit})}`);
    }
    console.log('Browser E2E sintético: exportação solicitada pelo DOM; resposta perdida após commit recuperada com corpo/chave idênticos; worker gerou CSV AVAILABLE; reload recuperou o mesmo ID; GET /active e GET individual compartilharam ETag forte e DTO minimizado; download correspondeu ao snapshot sem PII; expiração usou If-Match literal e bloqueou conteúdo posterior. PostgreSQL confirmou job e efeitos únicos.');
    const adminAppointment = await evaluate(
        `fetch('/api/v1/admin/appointments?from=${publicDate}&to=${publicDate}&serviceId=${encodeURIComponent(publicService.serviceId)}', {credentials:'same-origin'}).then(response => response.json()).then(page => page.items.find(item => item.appointmentId === ${JSON.stringify(appointmentIdInEachTab[0])}))`,
    );
    if (
        !adminAppointment ||
        adminAppointment.serviceName !== publicService.name ||
        adminAppointment.durationMinutes !== 45 ||
        adminAppointment.bufferAfterMinutes !== 0 ||
        adminAppointment.timeZone !== publication.timeZone
    ) {
        throw new Error(
            `Reserva persistida não apareceu na consulta administrativa com snapshot esperado: ${JSON.stringify(adminAppointment)}`,
        );
    }
    await setFieldByLabel('Data de referência', publicDate, evaluate);
    await clickButtonByText('Semana', evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    await waitForText('45 min', evaluate);
    await waitForText(publication.timeZone, evaluate);
    await clickButtonByText('Detalhes e história', evaluate);
    await waitForText('Detalhes da reserva', evaluate);
    await waitForText('APPOINTMENT_CREATED', evaluate);
    await clickButtonByText('Remarcar', evaluate);
    const rescheduleDate = await evaluate(`(() => {
      return ${JSON.stringify(publicDate)};
    })()`);
    await setFieldByLabel('Nova data local', rescheduleDate, evaluate);
    await setFieldByLabel('Novo horário local', '10:00', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).includes('/reschedule') && args[1]?.method === 'POST' && response.ok) {
          window.__firstRescheduleRequest = { url: String(args[0]), body: args[1].body, key: args[1].headers['Idempotency-Key'] };
          window.__rescheduleResponseReady = true;
          await new Promise(resolve => { window.__releaseRescheduleResponse = resolve; });
          window.fetch = originalFetch;
          throw new TypeError('Simulated lost reschedule response after commit');
        }
        return response;
      };
    })()`);
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitUntil(() => evaluate('window.__rescheduleResponseReady === true'));
    const pendingAssociation = await evaluate(`(() => ({
      detail: document.querySelector('.admin-agenda__details')?.innerText,
      closeDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes')?.disabled,
      periodDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Próximo período')?.disabled,
      firstRequest: window.__firstRescheduleRequest,
    }))()`);
    if (
        !pendingAssociation.closeDisabled ||
        !pendingAssociation.periodDisabled ||
        !pendingAssociation.firstRequest.url.includes(appointmentIdInEachTab[0]) ||
        !pendingAssociation.detail.includes('Visitante de demonstração') ||
        pendingAssociation.detail.includes('Segundo visitante de demonstração')
    ) {
        throw new Error(`Remarcação pendente perdeu a associação visual com A: ${JSON.stringify(pendingAssociation)}`);
    }
    await evaluate(`(() => {
      document.querySelector('[data-appointment-id="${secondAppointmentId}"] button')?.click();
      [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes')?.click();
    })()`);
    const guardedNavigation = await evaluate(`(() => ({
      detail: document.querySelector('.admin-agenda__details')?.innerText,
      closeDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes')?.disabled,
      periodDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Próximo período')?.disabled,
    }))()`);
    if (
        !guardedNavigation.closeDisabled ||
        !guardedNavigation.periodDisabled ||
        !guardedNavigation.detail.includes('Visitante de demonstração') ||
        guardedNavigation.detail.includes('Segundo visitante de demonstração')
    ) {
        throw new Error(
            `A navegação alterou a associação durante o POST pendente: ${JSON.stringify(guardedNavigation)}`,
        );
    }
    await evaluate('window.__releaseRescheduleResponse?.()');
    await waitForText('A resposta não chegou; o resultado pode estar confirmado.', evaluate);
    const uncertainAssociation = await evaluate(`(() => ({
      detail: document.querySelector('.admin-agenda__details')?.innerText,
      closeDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes')?.disabled,
      periodDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Próximo período')?.disabled,
    }))()`);
    if (
        !uncertainAssociation.closeDisabled ||
        !uncertainAssociation.periodDisabled ||
        !uncertainAssociation.detail.includes('Visitante de demonstração') ||
        uncertainAssociation.detail.includes('Segundo visitante de demonstração')
    ) {
        throw new Error(
            `Após perda da resposta, a intenção deixou de estar associada a A: ${JSON.stringify(uncertainAssociation)}`,
        );
    }
    const originalIntent = await evaluate('window.__firstRescheduleRequest');
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        if (String(args[0]).includes('/reschedule') && args[1]?.method === 'POST') {
          window.__retryRescheduleRequest = { url: String(args[0]), body: args[1].body, key: args[1].headers['Idempotency-Key'] };
          window.fetch = originalFetch;
        }
        return originalFetch(...args);
      };
    })()`);
    await clickButtonByText('Recuperar / repetir a mesma intenção', evaluate);
    await waitForText('Remarcação confirmada e persistida.', evaluate);
    const retriedIntent = await evaluate('window.__retryRescheduleRequest');
    if (JSON.stringify(originalIntent) !== JSON.stringify(retriedIntent)) {
        throw new Error(`Retry não preservou alvo/body/chave: ${JSON.stringify({ originalIntent, retriedIntent })}`);
    }
    const rescheduled = await evaluate(
        `fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    const rescheduleHistory = await evaluate(
        `fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}/history', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (
        rescheduled.version !== 2 ||
        rescheduled.startsAt.slice(0, 16) !== `${rescheduleDate}T10:00` ||
        !rescheduleHistory.items.some(
            (item) =>
                item.reasonCode === 'APPOINTMENT_RESCHEDULED' && item.versionBefore === 1 && item.versionAfter === 2,
        )
    ) {
        throw new Error(
            `Remarcação não persistiu com história/versionamento: ${JSON.stringify({ rescheduled, rescheduleHistory })}`,
        );
    }
    await waitUntil(() =>
        evaluate(`(() => {
          const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
          return text.includes('Visitante de demonstração') &&
            text.includes('10:00') &&
            text.includes('versão 2') &&
            text.includes('APPOINTMENT_RESCHEDULED') &&
            text.includes('versão 1 → 2');
        })()`),
    );
    const renderedRescheduleDetails = await evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      const closeButton = [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes');
      const retryButton = [...document.querySelectorAll('button')].find(button => button.innerText.includes('Recuperar / repetir'));
      const pendingAlerts = [...document.querySelectorAll('[role=alert]')].map(alert => alert.innerText).filter(text => text.includes('Resolva primeiro a remarcação') || text.includes('A resposta não chegou'));
      return { text: details?.innerText, closeDisabled: closeButton?.disabled, retryVisible: Boolean(retryButton), pendingAlerts };
    })()`);
    if (
        !renderedRescheduleDetails.text.includes('Visitante de demonstração') ||
        !renderedRescheduleDetails.text.includes('10:00') ||
        !renderedRescheduleDetails.text.includes('versão 2') ||
        !renderedRescheduleDetails.text.includes('APPOINTMENT_RESCHEDULED') ||
        !renderedRescheduleDetails.text.includes('versão 1 → 2') ||
        renderedRescheduleDetails.text.includes('Segundo visitante de demonstração') ||
        renderedRescheduleDetails.closeDisabled !== false ||
        renderedRescheduleDetails.retryVisible ||
        renderedRescheduleDetails.pendingAlerts.length !== 0
    ) {
        throw new Error(
            `Painel não atualizou os detalhes de A após replay bem-sucedido: ${JSON.stringify(renderedRescheduleDetails)}`,
        );
    }
    const rescheduleEffects = Number(
        queryPostgres(
            `SELECT CASE WHEN (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${appointmentIdInEachTab[0]}' AND reason_code = 'APPOINTMENT_RESCHEDULED') = 1 AND (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${appointmentIdInEachTab[0]}' AND action = 'APPOINTMENT_RESCHEDULED') = 1 AND (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${appointmentIdInEachTab[0]}' AND event_type = 'AppointmentRescheduled') = 1 AND (SELECT version FROM agendamento.appointment WHERE appointment_id = '${appointmentIdInEachTab[0]}') = 2 THEN 1 ELSE 0 END`,
            appEnvironment,
        ),
    );
    if (rescheduleEffects !== 1) throw new Error('Retry duplicou efeitos ou não preservou versão única.');
    await evaluate(`document.querySelector('[data-appointment-id="${secondAppointmentId}"] button')?.click()`);
    await waitUntil(() =>
        evaluate(
            `document.querySelector('.admin-agenda__details')?.innerText.includes('Segundo visitante de demonstração')`,
        ),
    );
    await clickButtonByText('Remarcar', evaluate);
    await setFieldByLabel('Novo horário local', '11:00', evaluate);
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitForText(
        'A reserva ou agenda mudou. Atualize as versões e revise o destino antes de enviar uma nova intenção.',
        evaluate,
    );
    await clickButtonByText('Atualizar versões sem alterar o destino', evaluate);
    await waitForText('Versões atualizadas. Revise a data e confirme uma nova intenção.', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).includes('/reschedule') && args[1]?.method === 'POST' && response.ok) {
          window.__delayedSuccessReady = true;
          await new Promise(resolve => { window.__releaseDelayedSuccess = resolve; });
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitUntil(() => evaluate('window.__delayedSuccessReady === true'));
    const delayedSuccessAssociation = await evaluate(`(() => ({
      detail: document.querySelector('.admin-agenda__details')?.innerText,
      closeDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes')?.disabled,
    }))()`);
    if (
        !delayedSuccessAssociation.closeDisabled ||
        !delayedSuccessAssociation.detail.includes('Segundo visitante de demonstração')
    ) {
        throw new Error(
            `Resposta bem-sucedida atrasada não manteve o alvo B: ${JSON.stringify(delayedSuccessAssociation)}`,
        );
    }
    await evaluate(`document.querySelector('[data-appointment-id="${appointmentIdInEachTab[0]}"] button')?.click()`);
    const delayedSuccessGuard = await evaluate(`(() => ({
      detail: document.querySelector('.admin-agenda__details')?.innerText,
      closeDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Fechar detalhes')?.disabled,
    }))()`);
    if (
        !delayedSuccessGuard.closeDisabled ||
        !delayedSuccessGuard.detail.includes('Segundo visitante de demonstração') ||
        delayedSuccessGuard.detail.includes('Visitante de demonstração ·')
    ) {
        throw new Error(
            `A remarcação pendente de B perdeu sua associação visual: ${JSON.stringify(delayedSuccessGuard)}`,
        );
    }
    await evaluate('window.__releaseDelayedSuccess?.()');
    await waitForText('Remarcação confirmada e persistida.', evaluate);
    const delayedSuccessVersion = Number(
        queryPostgres(
            `SELECT version FROM agendamento.appointment WHERE appointment_id = '${secondAppointmentId}'`,
            appEnvironment,
        ),
    );
    if (delayedSuccessVersion !== 2)
        throw new Error('Resposta de sucesso atrasada não confirmou a única alteração esperada em B.');
    await evaluate(`document.querySelector('[data-appointment-id="${appointmentIdInEachTab[0]}"] button')?.click()`);
    await waitUntil(() =>
        evaluate(
            `document.querySelector('.admin-agenda__details')?.innerText.includes('Visitante de demonstração') && !document.querySelector('.admin-agenda__details')?.innerText.includes('Segundo visitante de demonstração')`,
        ),
    );
    const oldDate = publicDate;
    const oldDateSlots = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${oldDate}&to=${oldDate}').then(response => response.json()).then(result => result.days[0].slots)`,
    );
    const newDateSlots = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${rescheduleDate}&to=${rescheduleDate}').then(response => response.json()).then(result => result.days[0].slots)`,
    );
    if (
        !oldDateSlots.some((slot) => slot.startsAt === selectedStart) ||
        newDateSlots.some((slot) => slot.startsAt === rescheduled.startsAt)
    ) {
        throw new Error('A disponibilidade não refletiu atomicamente a troca do horário.');
    }
    const conflictingDate = await evaluate(`(() => {
      const date = new Date(${JSON.stringify(rescheduleDate)} + 'T12:00:00Z');
      date.setUTCDate(date.getUTCDate() + 7);
      return date.toISOString().slice(0, 10);
    })()`);
    await setFieldByLabel('Início local', `${conflictingDate}T10:00`, evaluate);
    await setFieldByLabel('Fim local', `${conflictingDate}T11:00`, evaluate);
    await setFieldByLabel('Motivo obrigatório', 'Conflito de remarcação E2E', evaluate);
    await clickButtonByText('Criar bloqueio', evaluate);
    await waitForText('Bloqueio persistido.', evaluate);
    await clickButtonByText('Remarcar', evaluate);
    await setFieldByLabel('Nova data local', conflictingDate, evaluate);
    await setFieldByLabel('Novo horário local', '10:00', evaluate);
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitForText(
        'A reserva ou agenda mudou. Atualize as versões e revise o destino antes de enviar uma nova intenção.',
        evaluate,
    );
    await clickButtonByText('Atualizar versões sem alterar o destino', evaluate);
    await waitForText('Versões atualizadas. Revise a data e confirme uma nova intenção.', evaluate);
    const preservedRescheduleDraft = await evaluate(`(() => {
      const field = name => [...document.querySelectorAll('label')]
        .find(label => label.childNodes[0]?.textContent?.trim() === name)
        ?.querySelector('input')?.value;
      return { date: field('Nova data local'), time: field('Novo horário local') };
    })()`);
    if (preservedRescheduleDraft.date !== conflictingDate || preservedRescheduleDraft.time !== '10:00') {
        throw new Error(
            `A atualização de versões alterou o rascunho do destino: ${JSON.stringify(preservedRescheduleDraft)}`,
        );
    }
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitForText('O horário conflita com um bloqueio ou atendimento existente.', evaluate);
    const afterRejectedReschedule = await evaluate(
        `fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (afterRejectedReschedule.startsAt !== rescheduled.startsAt || afterRejectedReschedule.version !== 2) {
        throw new Error('Conflito no navegador alterou a reserva anterior.');
    }
    await setFieldByLabel('Data de referência', conflictingDate, evaluate);
    await waitForText('Conflito de remarcação E2E', evaluate);
    await setFieldByLabel('Motivo do cancelamento', 'Limpeza E2E', evaluate);
    await clickButtonByText('Cancelar bloqueio', evaluate);
    await waitUntil(
        async () =>
            await evaluate(
                `fetch('/api/v1/admin/blocked-periods?from=${conflictingDate}&to=${conflictingDate}', {credentials:'same-origin'}).then(response => response.json()).then(rows => rows.some(row => row.status === 'CANCELLED'))`,
            ),
    );
    console.log(
        'Browser: remarcação real preservou ID/snapshot, gravou história, trocou a disponibilidade; destino bloqueado foi rejeitado sem alterar a reserva.',
    );
    await setFieldByLabel('Data de referência', rescheduleDate, evaluate);
    await clickButtonByText('Dia', evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    await command('Page.reload');
    await waitForText('Agenda', evaluate);
    await waitUntil(
        async () =>
            await evaluate(
                "[...document.querySelectorAll('label')].some(label => label.childNodes[0]?.textContent?.trim() === 'Data de referência' && label.querySelector('input'))",
            ),
    );
    await setFieldByLabel('Data de referência', rescheduleDate, evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    if (
        !(await evaluate(
            `document.querySelector('[data-appointment-id="${appointmentIdInEachTab[0]}"]')?.innerText.includes('45 min')`,
        ))
    ) {
        throw new Error('Reload não recuperou a reserva persistida na agenda administrativa.');
    }
    await setSelectByLabel('Serviço', publicService.serviceId, evaluate);
    await setSelectByLabel('Status', 'CONFIRMED', evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    const staleAgendaDate = new Date(new Date(rescheduleDate + 'T12:00:00Z').getTime() + 86400000)
        .toISOString()
        .slice(0, 10);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.__originalAgendaFetch = originalFetch;
      window.__staleAgendaHeld = false;
      window.__latestAgendaResponseSeen = false;
      window.fetch = async (...args) => {
        const url = String(args[0]);
        if (!window.__staleAgendaHeld && url.includes('/v1/admin/appointments?from=${staleAgendaDate}')) {
          window.__staleAgendaHeld = true;
          await new Promise(resolve => { window.__releaseStaleAgenda = resolve; });
        }
        const response = await originalFetch(...args);
        if (url.includes('/v1/admin/appointments?from=${rescheduleDate}')) window.__latestAgendaResponseSeen = true;
        return response;
      };
    })()`);
    await setFieldByLabel('Data de referência', staleAgendaDate, evaluate);
    await waitUntil(() => evaluate('window.__staleAgendaHeld === true'));
    await setFieldByLabel('Data de referência', rescheduleDate, evaluate);
    await waitUntil(() => evaluate('window.__latestAgendaResponseSeen === true'));
    await waitForText('Visitante de demonstração', evaluate);
    await evaluate('window.__releaseStaleAgenda?.(); window.fetch = window.__originalAgendaFetch');
    if (
        !(await evaluate(
            `document.querySelector('[data-appointment-id="${appointmentIdInEachTab[0]}"]')?.innerText.includes('45 min')`,
        ))
    ) {
        throw new Error('Uma resposta antiga substituiu a consulta administrativa mais recente.');
    }
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.__originalAgendaFetch = originalFetch;
      window.__agendaPageCalls = 0;
      window.fetch = async (...args) => {
        const url = String(args[0]);
        if (url.includes('/v1/admin/appointments?')) {
          window.__agendaPageCalls++;
          const firstPage = window.__agendaPageCalls === 1;
          return new Response(JSON.stringify({timeZone:${JSON.stringify(publication.timeZone)},items:[],nextCursor:firstPage?'test-next-page':null,hasMore:firstPage}), {status:200,headers:{'Content-Type':'application/json'}});
        }
        if (url.includes('/v1/admin/blocked-periods?')) {
          return new Response('[]', {status:200,headers:{'Content-Type':'application/json'}});
        }
        return originalFetch(...args);
      };
    })()`);
    await clickButtonByText('Atualizar', evaluate);
    await waitForText('Exibindo uma página parcial.', evaluate);
    await clickButtonByText('Próxima página', evaluate);
    await waitForText('Página 2', evaluate);
    await waitForText('Nenhum atendimento ou bloqueio neste período.', evaluate);
    await clickButtonByText('Página anterior', evaluate);
    await waitForText('Página 1', evaluate);
    await evaluate('window.fetch = window.__originalAgendaFetch');
    await clickButtonByText('Atualizar', evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        if (String(args[0]).includes('/v1/admin/appointments?')) {
          window.fetch = originalFetch;
          throw new Error('Falha simulada de rede da agenda');
        }
        return originalFetch(...args);
      };
    })()`);
    await waitUntil(
        async () =>
            await evaluate(
                "[...document.querySelectorAll('.admin-agenda button')].some(button => button.innerText.trim() === 'Atualizar' && !button.disabled)",
            ),
    );
    await clickButtonByText('Atualizar', evaluate);
    await waitForText('Falha simulada de rede da agenda', evaluate);
    await clickButtonByText('Tentar novamente', evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.__originalSessionFetch = originalFetch;
      window.fetch = async (...args) => {
        if (String(args[0]).includes('/v1/admin/appointments?')) {
          return new Response('', {status:401});
        }
        return originalFetch(...args);
      };
    })()`);
    await waitUntil(
        async () =>
            await evaluate(
                "[...document.querySelectorAll('.admin-agenda button')].some(button => button.innerText.trim() === 'Atualizar' && !button.disabled)",
            ),
    );
    await clickButtonByText('Atualizar', evaluate);
    await waitForText('Sua sessão expirou. Entre novamente para continuar.', evaluate);
    await evaluate('window.fetch = window.__originalSessionFetch');
    await command('Page.reload');
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitForText('Agenda', evaluate);
    await setFieldByLabel('Data de referência', rescheduleDate, evaluate);
    await waitForText('Visitante de demonstração', evaluate);
    await clickButtonByText('Despublicar página', evaluate);
    await waitForText('Página pública despublicada.', evaluate);
    const unpublishedRead = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}').then(response => response.status)`,
    );
    if (unpublishedRead !== 404) throw new Error('Despublicar não bloqueou uma leitura pública direta.');
    console.log('Browser: despublicação impediu leituras públicas diretas.');
    const exceptionDate = new Date(Date.now() + 45 * 86400_000).toISOString().slice(0, 10);
    await setFieldByLabel('Data local', exceptionDate, evaluate);
    await waitForText('Nenhuma exceção nesta data; aplica-se a grade semanal.', evaluate);
    await setSelectByLabel('Tratamento da data', 'CLOSED', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).endsWith('/v1/admin/schedule/exceptions') && args[1]?.method === 'POST' && response.ok) {
          window.fetch = originalFetch;
          throw new TypeError('Falha de rede simulada após commit');
        }
        return response;
      };
    })()`);
    await clickButtonByText('Salvar exceção', evaluate);
    await waitForText('Falha de rede simulada após commit', evaluate);
    await clickButtonByText('Salvar exceção', evaluate);
    await waitForText('Exceção persistida.', evaluate);
    let exceptionRows = await evaluate(
        `fetch('/api/v1/admin/schedule/exceptions?from=${exceptionDate}&to=${exceptionDate}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (exceptionRows.exceptions.length !== 1 || exceptionRows.exceptions[0].kind !== 'CLOSED') {
        throw new Error('Exceção fechada não persistiu.');
    }
    await clickButtonByText('Remover exceção e restaurar grade', evaluate);
    await waitForText('Exceção removida; a grade semanal volta a ser aplicada.', evaluate);
    await waitUntil(
        async () =>
            (await evaluate(
                `fetch('/api/v1/admin/schedule/exceptions?from=${exceptionDate}&to=${exceptionDate}', {credentials:'same-origin'}).then(response => response.json()).then(result => result.exceptions.length)`,
            )) === 0,
    );
    await setSelectByLabel('Tratamento da data', 'SPECIAL', evaluate);
    await evaluate(`(() => {
      const section = document.querySelector('section[aria-labelledby="exceptions-title"]');
      [...section.querySelectorAll('button')].find(button => button.innerText.trim() === 'Adicionar período').click();
    })()`);
    await setFieldByLabel('Início local', '09:00', evaluate);
    await setFieldByLabel('Fim local', '11:00', evaluate);
    await clickButtonByText('Salvar exceção', evaluate);
    await waitForText('Exceção persistida.', evaluate);
    await clickButtonByText('Recarregar', evaluate);
    exceptionRows = await evaluate(
        `fetch('/api/v1/admin/schedule/exceptions?from=${exceptionDate}&to=${exceptionDate}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (
        exceptionRows.exceptions.length !== 1 ||
        exceptionRows.exceptions[0].kind !== 'SPECIAL' ||
        exceptionRows.exceptions[0].periods[0].localStart !== '09:00'
    ) {
        throw new Error(
            `Horário especial não persistiu após recarregar: ${JSON.stringify(exceptionRows.exceptions.map((item) => ({ kind: item.kind, periods: item.periods, version: item.version, timeZone: item.timeZone })))}`,
        );
    }
    const outsideRangeDate = new Date(Date.now() + 60 * 86400_000).toISOString().slice(0, 10);
    await setFieldByLabel('Data local', outsideRangeDate, evaluate);
    await waitForText('Nenhuma exceção nesta data; aplica-se a grade semanal.', evaluate);
    await setFieldByLabel('Data local', exceptionDate, evaluate);
    await waitForText(`Editar ${exceptionDate}: SPECIAL (v1)`, evaluate);
    await clickButtonByText(`Editar ${exceptionDate}: SPECIAL (v1)`, evaluate);
    await setFieldByLabel('Motivo (opcional)', 'Edição E2E', evaluate);
    await clickButtonByText('Salvar exceção', evaluate);
    await waitForText('Exceção persistida.', evaluate);
    exceptionRows = await evaluate(
        `fetch('/api/v1/admin/schedule/exceptions?from=${exceptionDate}&to=${exceptionDate}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (
        exceptionRows.exceptions.length !== 1 ||
        exceptionRows.exceptions[0].version !== 2 ||
        exceptionRows.exceptions[0].reason !== 'Edição E2E'
    ) {
        throw new Error('Edição da exceção fora do intervalo inicial não persistiu.');
    }
    await clickButtonByText('Remover exceção e restaurar grade', evaluate);
    await waitForText('Exceção removida; a grade semanal volta a ser aplicada.', evaluate);
    const blockDay = exceptionDate;
    await setFieldByLabel('Início local', blockDay + 'T09:00', evaluate);
    await setFieldByLabel('Fim local', blockDay + 'T10:00', evaluate);
    await setFieldByLabel('Motivo obrigatório', 'Manutenção E2E', evaluate);
    await waitUntil(() =>
        evaluate(
            `[...document.querySelectorAll('button')].some(button => button.innerText.trim() === 'Criar bloqueio' && !button.disabled)`,
        ),
    );
    await clickButtonByText('Criar bloqueio', evaluate);
    await waitForText('Bloqueio persistido.', evaluate);
    const createdBlocks = await evaluate(
        `fetch('/api/v1/admin/blocked-periods?from=${blockDay}&to=${blockDay}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (createdBlocks.length !== 1 || createdBlocks[0].status !== 'ACTIVE') {
        throw new Error('Bloqueio não apareceu na consulta após criação.');
    }
    await setFieldByLabel('Data de referência', blockDay, evaluate);
    await waitUntil(() =>
        evaluate("document.querySelector('.admin-agenda__block--active')?.innerText.includes('Manutenção E2E')"),
    );
    await setFieldByLabel('Motivo do cancelamento', 'Concluído E2E', evaluate);
    await clickButtonByText('Cancelar bloqueio', evaluate);
    await waitUntil(
        async () =>
            (await evaluate(
                `fetch('/api/v1/admin/blocked-periods?from=${blockDay}&to=${blockDay}', {credentials:'same-origin'}).then(response => response.json()).then(rows => rows[0]?.status)`,
            )) === 'CANCELLED',
    );
    await clickButtonByText('Atualizar', evaluate);
    await waitUntil(() =>
        evaluate("document.querySelector('.admin-agenda__block--cancelled')?.innerText.includes('Manutenção E2E')"),
    );
    console.log('Browser: exceção fechada, horário especial, restauração e bloqueio cancelado persistem após reload.');
    await clickButtonByText('Nova reserva', evaluate);
    await setSelectByLabel('Serviço ativo', publicService.serviceId, evaluate);
    await evaluate(`(() => {
      const form = document.querySelector('form[aria-label="Criar reserva administrativa"]');
      const label = [...form.querySelectorAll('label')].find(item => item.innerText.includes('Novo cliente'));
      label?.querySelector('input')?.click();
    })()`);
    await waitUntil(() =>
        evaluate(`Boolean((() => {
          const form = document.querySelector('form[aria-label="Criar reserva administrativa"]');
          return [...(form?.querySelectorAll('label') ?? [])].some(item =>
            item.innerText.trim().startsWith('Nome') && item.querySelector('input'),
          );
        })())`),
    );
    await setAdminAppointmentField('Nome', 'Reserva administrativa E2E', evaluate);
    await setAdminAppointmentField('Telefone para cadastro', '+5511999998310', evaluate);
    await setAdminAppointmentField('Data local', publicDate, evaluate);
    await setAdminAppointmentField('Horário local', '14:00', evaluate);
    await waitUntil(() =>
        evaluate(
            'document.querySelector(\'form[aria-label=\\"Criar reserva administrativa\\"] button[type=submit]\') && !document.querySelector(\'form[aria-label=\\"Criar reserva administrativa\\"] button[type=submit]\').disabled',
        ),
    );
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitForText('Reserva confirmada e persistida.', evaluate);
    await waitForText('Reserva administrativa E2E', evaluate);
    const administrativeBookingCount = Number(
        queryPostgres(
            `SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) JOIN agendamento.client c USING (tenant_id, client_id) WHERE t.slug = '${publication.slug}' AND c.display_name = 'Reserva administrativa E2E' AND a.status = 'CONFIRMED'`,
            appEnvironment,
        ),
    );
    if (administrativeBookingCount !== 1)
        throw new Error('Reserva administrativa comum não foi persistida uma única vez.');

    await setAdminAppointmentField('Horário local', '08:00', evaluate);
    await waitUntil(() =>
        evaluate(`(() => {
      const form = document.querySelector('form[aria-label="Criar reserva administrativa"]');
      const label = [...form.querySelectorAll('label')].find(item => item.innerText.includes('Ignorar somente a grade semanal'));
      return Boolean(label && !label.querySelector('input')?.disabled);
    })()`),
    );
    await evaluate(`(() => {
      const form = document.querySelector('form[aria-label="Criar reserva administrativa"]');
      [...form.querySelectorAll('label')].find(item => item.innerText.includes('Ignorar somente a grade semanal'))?.querySelector('input')?.click();
    })()`);
    await setAdminAppointmentField('Nome', 'Encaixe administrativo E2E', evaluate);
    await setAdminAppointmentField('Telefone para cadastro', '+5511999998311', evaluate);
    await setAdminAppointmentTextarea('Motivo obrigatório', 'Encaixe E2E autorizado', evaluate);
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitForText('Reserva confirmada e persistida.', evaluate);
    await waitForText('Encaixe administrativo E2E', evaluate);
    await waitForText('Motivo: Encaixe E2E autorizado', evaluate);
    const administrativeOverrideCount = Number(
        queryPostgres(
            `SELECT count(*) FROM agendamento.appointment_history h JOIN agendamento.appointment a USING (tenant_id, appointment_id) JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND h.reason_code = 'ADMIN_BOOKING_OVERRIDE' AND h.reason = 'Encaixe E2E autorizado'`,
            appEnvironment,
        ),
    );
    if (administrativeOverrideCount !== 1) throw new Error('Encaixe administrativo não persistiu história e motivo.');

    await setAdminAppointmentField('Nome', 'Encaixe conflitante E2E', evaluate);
    await setAdminAppointmentField('Telefone para cadastro', '+5511999998312', evaluate);
    await setAdminAppointmentTextarea('Motivo obrigatório', 'Tentativa conflitante E2E', evaluate);
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitForText('O horário conflita com um bloqueio ou atendimento existente.', evaluate);
    const conflictingBookingCount = Number(
        queryPostgres(
            `SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.starts_at = '${publicDate} 08:00:00-03' AND a.status = 'CONFIRMED'`,
            appEnvironment,
        ),
    );
    if (conflictingBookingCount !== 1) throw new Error('Tentativa sobreposta alterou a agenda persistida.');
    console.log(
        'Browser: reserva administrativa comum e encaixe real persistidos; motivo aparece na história e a sobreposição é recusada.',
    );
    await setFieldByAriaLabel('Segunda-feira início 1', '08:00', evaluate);
    const concurrentUpdateStatus = await evaluate(`(async () => {
      const [currentResponse, csrfResponse] = await Promise.all([
        fetch('/api/v1/admin/schedule/weekly-periods', {credentials:'same-origin'}),
        fetch('/api/v1/csrf', {credentials:'same-origin'})
      ]);
      const current = await currentResponse.json();
      const csrf = await csrfResponse.json();
      return fetch('/api/v1/admin/schedule/weekly-periods', {
        method:'PUT', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({expectedVersion:current.version, periods:[{weekday:'MONDAY',localStart:'10:00',localEnd:'11:00'}]})
      }).then(response => response.status);
    })()`);
    if (concurrentUpdateStatus !== 200)
        throw new Error('Comando concorrente não atualizou a grade para o teste de CAS.');
    await clickButtonByText('Salvar grade semanal', evaluate);
    await waitForText('Outra sessão salvou esta grade.', evaluate);
    if ((await evaluate('document.querySelector(\'[aria-label="Segunda-feira início 1"]\').value')) !== '08:00') {
        throw new Error('Conflito de versão descartou o rascunho local.');
    }
    await evaluate('window.confirm = () => true');
    await clickButtonByText('Recarregar versão atual (substitui campos locais)', evaluate);
    await waitForText('Configuração recarregada.', evaluate);
    if ((await evaluate('document.querySelector(\'[aria-label="Segunda-feira início 1"]\').value')) !== '10:00') {
        throw new Error('Recuperação explícita do conflito não carregou a versão atual.');
    }
    console.log('Browser: conflito CAS preservou rascunho e recuperação explícita carregou a versão vigente.');

    const administrativeAppointmentId = queryPostgres(
        `SELECT a.appointment_id FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.client_name_snapshot = 'Reserva administrativa E2E' AND a.status = 'CONFIRMED'`,
        appEnvironment,
    );
    queryPostgres(
        `WITH moment AS (SELECT clock_timestamp() AS now) UPDATE agendamento.appointment a SET starts_at = moment.now - interval '1 day', service_ends_at = moment.now - interval '1 day' + make_interval(mins => a.service_duration_minutes), occupied_until = moment.now - interval '1 day' + make_interval(mins => a.service_duration_minutes + a.service_buffer_after_minutes) FROM moment WHERE a.appointment_id = '${administrativeAppointmentId}'::uuid`,
        appEnvironment,
    );

    await setFieldByLabel('Data de referência', publicDate, evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-appointment-id="${appointmentIdInEachTab[0]}"]'))`));
    await evaluate(`document.querySelector('[data-appointment-id="${appointmentIdInEachTab[0]}"] button')?.click()`);
    await waitUntil(() => evaluate(`document.querySelector('.admin-agenda__details')?.innerText.includes('Visitante de demonstração')`));
    const initialOutcomeAppointment = await evaluate(
        `fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    const externalRescheduleDate = new Date(`${publicDate}T12:00:00Z`);
    externalRescheduleDate.setUTCDate(externalRescheduleDate.getUTCDate() + 7);
    const externalRescheduleLocalDate = externalRescheduleDate.toISOString().slice(0, 10);
    const externalReschedule = await secondTab.evaluate(`(async () => {
      const [appointmentResponse, scheduleResponse, csrfResponse] = await Promise.all([
        fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}', {credentials:'same-origin'}),
        fetch('/api/v1/admin/schedule/weekly-periods', {credentials:'same-origin'}),
        fetch('/api/v1/csrf', {credentials:'same-origin'})
      ]);
      const [appointment, schedule, csrf] = await Promise.all([appointmentResponse.json(), scheduleResponse.json(), csrfResponse.json()]);
      const response = await fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}/reschedule', {
        method:'POST', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({expectedVersion:appointment.version, expectedScheduleRevision:schedule.scheduleRevision, startsAt:'${externalRescheduleLocalDate}T10:00:00-03:00'})
      });
      return {status:response.status, body:await response.json()};
    })()`);
    if (externalReschedule.status !== 200) {
        throw new Error(`A alteração de outra aba não provocou conflito CAS: ${JSON.stringify(externalReschedule)}`);
    }
    await clickButtonByText('Cancelar reserva', evaluate);
    await clickButtonByText('Confirmar ação', evaluate);
    await waitForText('mudou em outra operação', evaluate);
    const rejectedOutcomeContext = await evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      const labels = [...(details?.querySelectorAll('button') ?? [])].map(button => button.innerText.trim());
      return {
        conflict: details?.innerText.includes('mudou em outra operação'),
        terminalActionsAvailable: ['Cancelar reserva', 'Concluir atendimento', 'Marcar falta'].every(label => labels.includes(label)),
      };
    })()`);
    if (!rejectedOutcomeContext.conflict || !rejectedOutcomeContext.terminalActionsAvailable) {
        throw new Error(`Conflito definitivo manteve uma ação antiga ou perdeu a recuperação: ${JSON.stringify(rejectedOutcomeContext)}`);
    }
    await clickButtonByText('Atualizar detalhes da reserva', evaluate);
    await waitUntil(() => evaluate(`document.querySelector('.admin-agenda__details')?.innerText.includes('versão ${initialOutcomeAppointment.version + 1}')`));
    await waitForText('mudou em outra operação', evaluate);
    await clickButtonByText('Remarcar', evaluate);

    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).includes('/reschedule') && args[1]?.method === 'POST' && response.ok) {
          window.__pendingRescheduleRequest = { url: String(args[0]), body: args[1].body, key: args[1].headers['Idempotency-Key'] };
          window.__pendingRescheduleReady = true;
          await new Promise(resolve => { window.__releaseRescheduleResponse = resolve; });
          window.fetch = originalFetch;
          throw new TypeError('Simulated lost reschedule response after commit');
        }
        return response;
      };
    })()`);
    const retryRescheduleDate = new Date(`${publicDate}T12:00:00Z`);
    retryRescheduleDate.setUTCDate(retryRescheduleDate.getUTCDate() + 14);
    await setFieldByLabel('Nova data local', retryRescheduleDate.toISOString().slice(0, 10), evaluate);
    await setFieldByLabel('Novo horário local', '10:00', evaluate);
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitForText(
        'A reserva ou agenda mudou. Atualize as versões e revise o destino antes de enviar uma nova intenção.',
        evaluate,
    );
    await clickButtonByText('Atualizar versões sem alterar o destino', evaluate);
    await waitForText('Versões atualizadas. Revise a data e confirme uma nova intenção.', evaluate);
    await clickButtonByText('Confirmar remarcação', evaluate);
    await waitUntil(() => evaluate(`window.__pendingRescheduleReady === true`));
    const originalRescheduleRequest = await evaluate('window.__pendingRescheduleRequest');
    if (!originalRescheduleRequest.url.includes(appointmentIdInEachTab[0])) {
        throw new Error(`Remarcação pendente não ficou associada ao alvo original: ${JSON.stringify(originalRescheduleRequest)}`);
    }
    const conflictAndNavigationGuarded = await evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      const refresh = [...document.querySelectorAll('.admin-agenda__details button')].find(button => button.innerText.trim() === 'Atualizar detalhes da reserva');
      const close = [...document.querySelectorAll('.admin-agenda__details button')].find(button => button.innerText.trim() === 'Fechar detalhes');
      const otherAppointment = document.querySelector('[data-appointment-id="${secondAppointmentId}"] button');
      return text.includes('mudou em outra operação') && refresh?.disabled === true && close?.disabled === true && otherAppointment?.disabled === true;
    })()`);
    if (!conflictAndNavigationGuarded) throw new Error('Refresh manual ou navegação escapou do guard enquanto a remarcação estava pendente.');

    const externalCancel = await secondTab.evaluate(`(async () => {
      const [appointmentResponse, csrfResponse] = await Promise.all([
        fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}', {credentials:'same-origin'}),
        fetch('/api/v1/csrf', {credentials:'same-origin'})
      ]);
      const [appointment, csrf] = await Promise.all([appointmentResponse.json(), csrfResponse.json()]);
      const response = await fetch('/api/v1/admin/appointments/${appointmentIdInEachTab[0]}/cancel', {
        method:'POST', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({expectedVersion:appointment.version, reason:'Cancelamento em outra aba'})
      });
      return {status:response.status, body:await response.json()};
    })()`);
    if (externalCancel.status !== 200) {
        throw new Error(`Cancelamento concorrente em outra aba falhou: ${JSON.stringify(externalCancel)}`);
    }
    const refreshStayedBlocked = await evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      const refresh = [...document.querySelectorAll('.admin-agenda__details button')].find(button => button.innerText.trim() === 'Atualizar detalhes da reserva');
      const retry = [...document.querySelectorAll('.admin-agenda__details button')].find(button => button.innerText.includes('Recuperar / repetir a mesma intenção'));
      return text.includes('Visitante de demonstração') && text.includes('mudou em outra operação') && refresh?.disabled === true && !retry;
    })()`);
    if (!refreshStayedBlocked) throw new Error('A atualização manual alterou ou ocultou o contexto da intenção pendente.');
    await evaluate('window.__releaseRescheduleResponse?.()');
    await waitForText('A resposta não chegou; o resultado pode estar confirmado.', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        if (String(args[0]).includes('/reschedule') && args[1]?.method === 'POST') {
          window.__retryRescheduleRequest = { url: String(args[0]), body: args[1].body, key: args[1].headers['Idempotency-Key'] };
          window.fetch = originalFetch;
        }
        return originalFetch(...args);
      };
    })()`);
    await clickButtonByText('Recuperar / repetir a mesma intenção', evaluate);
    await waitForText('Remarcação confirmada e persistida.', evaluate);
    const retryRescheduleRequest = await evaluate('window.__retryRescheduleRequest');
    if (JSON.stringify(originalRescheduleRequest) !== JSON.stringify(retryRescheduleRequest)) {
        throw new Error(`Retry da remarcação alterou alvo/body/chave: ${JSON.stringify({ originalRescheduleRequest, retryRescheduleRequest })}`);
    }
    await waitUntil(() => evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      return text.includes('CANCELLED_BY_PROVIDER') && text.includes('versão ${initialOutcomeAppointment.version + 3}') && text.includes('APPOINTMENT_RESCHEDULED') && text.includes('APPOINTMENT_CANCELLED_BY_PROVIDER');
    })()`));
    const outcomeDetailsReleased = await evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      const close = [...document.querySelectorAll('.admin-agenda__details button')].find(button => button.innerText.trim() === 'Fechar detalhes');
      const otherAppointment = document.querySelector('[data-appointment-id="${secondAppointmentId}"] button');
      const refresh = [...document.querySelectorAll('.admin-agenda__details button')].find(button => button.innerText.trim() === 'Atualizar detalhes da reserva');
      return text.includes('CANCELLED_BY_PROVIDER') && !text.includes('mudou em outra operação') && !text.includes('resultado pode estar confirmado') && refresh === undefined && close?.disabled === false && otherAppointment?.disabled === false;
    })()`);
    if (!outcomeDetailsReleased) throw new Error('A reconciliação não exibiu o estado atual nem liberou a navegação.');
    const outcomeEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${appointmentIdInEachTab[0]}' AND reason_code = 'APPOINTMENT_RESCHEDULED') = 3 AND (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${appointmentIdInEachTab[0]}' AND action = 'APPOINTMENT_RESCHEDULED') = 3 AND (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${appointmentIdInEachTab[0]}' AND event_type = 'AppointmentRescheduled') = 3 AND (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${appointmentIdInEachTab[0]}' AND reason_code = 'APPOINTMENT_CANCELLED_BY_PROVIDER') = 1 AND (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${appointmentIdInEachTab[0]}' AND action = 'APPOINTMENT_CANCELLED_BY_PROVIDER') = 1 AND (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${appointmentIdInEachTab[0]}' AND event_type = 'AppointmentCancelled') = 1 AND (SELECT version FROM agendamento.appointment WHERE appointment_id = '${appointmentIdInEachTab[0]}') = ${initialOutcomeAppointment.version + 3} THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (outcomeEffects !== 1) throw new Error('Replay repetiu ou omitiu efeitos entre abas.');
    await evaluate(`document.querySelector('[data-appointment-id="${secondAppointmentId}"] button')?.click()`);
    await waitUntil(() => evaluate(`document.querySelector('.admin-agenda__details')?.innerText.includes('Segundo visitante de demonstração')`));
    queryPostgres(
        `WITH moment AS (SELECT clock_timestamp() AS now) UPDATE agendamento.appointment a SET starts_at = moment.now - interval '2 days', service_ends_at = moment.now - interval '2 days' + make_interval(mins => a.service_duration_minutes), occupied_until = moment.now - interval '2 days' + make_interval(mins => a.service_duration_minutes + a.service_buffer_after_minutes) FROM moment WHERE a.appointment_id = '${secondAppointmentId}'::uuid`,
        appEnvironment,
    );
    const completionAppointment = await evaluate(
        `fetch('/api/v1/admin/appointments/${secondAppointmentId}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    const completionDate = await evaluate(`(() => {
      const parts = new Intl.DateTimeFormat('en-CA', {timeZone:${JSON.stringify(publication.timeZone)},year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(${JSON.stringify(completionAppointment.startsAt)}));
      const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
      return values.year + '-' + values.month + '-' + values.day;
    })()`);
    await setFieldByLabel('Data de referência', completionDate, evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-appointment-id="${secondAppointmentId}"]'))`));
    await evaluate(`document.querySelector('[data-appointment-id="${secondAppointmentId}"] button')?.click()`);
    await waitUntil(() => evaluate(`document.querySelector('.admin-agenda__details')?.innerText.includes('Segundo visitante de demonstração')`));
    await clickButtonByText('Concluir atendimento', evaluate);
    await clickButtonByText('Confirmar ação', evaluate);
    await waitForText('Estado atualizado e persistido.', evaluate);
    await waitUntil(() => evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      return text.includes('COMPLETED') && text.includes('versão 3') && text.includes('APPOINTMENT_COMPLETED') && text.includes('versão 2 → 3');
    })()`));

    const noShowAppointment = await evaluate(
        `fetch('/api/v1/admin/appointments/${administrativeAppointmentId}', {credentials:'same-origin'}).then(response => response.json())`,
    );
    const noShowDate = await evaluate(`(() => {
      const parts = new Intl.DateTimeFormat('en-CA', {timeZone:${JSON.stringify(publication.timeZone)},year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(${JSON.stringify(noShowAppointment.startsAt)}));
      const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
      return values.year + '-' + values.month + '-' + values.day;
    })()`);
    await setFieldByLabel('Data de referência', noShowDate, evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-appointment-id="${administrativeAppointmentId}"]'))`));
    await evaluate(`document.querySelector('[data-appointment-id="${administrativeAppointmentId}"] button')?.click()`);
    await waitUntil(() => evaluate(`document.querySelector('.admin-agenda__details')?.innerText.includes('Reserva administrativa E2E')`));
    const restrictionPolicyUpdate = await evaluate(`(async () => {
      const [policyResponse, csrfResponse] = await Promise.all([
        fetch('/api/v1/admin/no-show-policy', {credentials:'same-origin', cache:'no-store'}),
        fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'})
      ]);
      const [policy, csrf] = await Promise.all([policyResponse.json(), csrfResponse.json()]);
      const response = await fetch('/api/v1/admin/no-show-policy', {
        method:'PUT', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({expectedVersion:policy.version, threshold:1})
      });
      return {status:response.status, body:response.ok ? await response.json() : await response.text()};
    })()`);
    if (restrictionPolicyUpdate.status !== 200 || restrictionPolicyUpdate.body.threshold !== 1) {
        throw new Error(`Fixture da política de falta não foi configurada: ${JSON.stringify(restrictionPolicyUpdate)}`);
    }
    await clickButtonByText('Marcar falta', evaluate);
    await clickButtonByText('Confirmar ação', evaluate);
    await waitForText('Estado atualizado e persistido.', evaluate);
    await waitUntil(() => evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      return text.includes('NO_SHOW') && text.includes('versão 2') && text.includes('APPOINTMENT_NO_SHOW');
    })()`));

    const noShowClientId = queryPostgres(
        `SELECT client_id FROM agendamento.appointment WHERE appointment_id = '${administrativeAppointmentId}'`,
        appEnvironment,
    );
    const activeRestrictionId = queryPostgres(
        `SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${noShowClientId}' AND status = 'ACTIVE'`,
        appEnvironment,
    );
    const restrictedPhone = queryPostgres(
        `SELECT normalized_phone FROM agendamento.client WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${noShowClientId}'`,
        appEnvironment,
    );
    if (!activeRestrictionId) throw new Error('NO_SHOW no fluxo do navegador não materializou restrição ativa.');
    await clickButtonByText('Publicar página', evaluate);
    await waitForText('Estado: Publicado', evaluate);
    const restrictionPagePublication = await evaluate(
        "fetch('/api/v1/admin/publication', {credentials:'same-origin', cache:'no-store'}).then(response => response.json())",
    );
    if (restrictionPagePublication.published !== true) {
        throw new Error('Fixture pública não estava publicada para testar a rejeição neutra por restrição.');
    }
    const restrictionLiftSearchPrefix = 'Cliente P2 desbloqueio E2E';
    const restrictionLiftClientAName = `${restrictionLiftSearchPrefix} A`;
    const secondRestrictedClientName = `${restrictionLiftSearchPrefix} B`;
    const secondRestrictedClientPhone = '+5511999991237';
    const secondRestrictionAppointment = await evaluate(`(async () => {
      const [scheduleResponse, csrfResponse] = await Promise.all([
        fetch('/api/v1/admin/schedule/weekly-periods', {credentials:'same-origin', cache:'no-store'}),
        fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'})
      ]);
      const [schedule, csrf] = await Promise.all([scheduleResponse.json(), csrfResponse.json()]);
      let slot = null;
      for (let offset = 0; offset <= 25 && !slot; offset++) {
        const date = new Date(${JSON.stringify(publicDate)} + 'T12:00:00Z');
        date.setUTCDate(date.getUTCDate() + offset);
        const localDate = date.toISOString().slice(0, 10);
        const query = new URLSearchParams({serviceId:${JSON.stringify(publicService.serviceId)}, from:localDate, to:localDate});
        const response = await fetch('/api/v1/public/' + encodeURIComponent(${JSON.stringify(publication.slug)}) + '/availability?' + query);
        if (!response.ok) continue;
        const page = await response.json();
        slot = page.days?.flatMap(day => day.slots ?? [])[0] ?? null;
      }
      if (!slot) return {status:0, error:'No slot found for second restricted fixture'};
      const response = await fetch('/api/v1/admin/appointments', {
        method:'POST', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({serviceId:${JSON.stringify(publicService.serviceId)}, startsAt:slot.startsAt, client:{name:${JSON.stringify(secondRestrictedClientName)}, phone:${JSON.stringify(secondRestrictedClientPhone)}}, overridePolicies:[], expectedScheduleVersion:schedule.scheduleRevision})
      });
      return {status:response.status, body:await response.json()};
    })()`);
    if (secondRestrictionAppointment.status !== 201) {
        throw new Error(`Não foi possível criar a segunda fixture de cliente restrito: ${JSON.stringify(secondRestrictionAppointment)}`);
    }
    const secondRestrictionAppointmentId = secondRestrictionAppointment.body.appointmentId;
    queryPostgres(
        `WITH moment AS (SELECT clock_timestamp() AS now) UPDATE agendamento.appointment a SET starts_at = moment.now - interval '2 days', service_ends_at = moment.now - interval '2 days' + make_interval(mins => a.service_duration_minutes), occupied_until = moment.now - interval '2 days' + make_interval(mins => a.service_duration_minutes + a.service_buffer_after_minutes) FROM moment WHERE a.appointment_id = '${secondRestrictionAppointmentId}'::uuid`,
        appEnvironment,
    );
    const secondNoShow = await evaluate(`(async () => {
      const csrfResponse = await fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'});
      const csrf = await csrfResponse.json();
      const response = await fetch('/api/v1/admin/appointments/${secondRestrictionAppointmentId}/mark-no-show', {
        method:'POST', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({expectedVersion:1, reason:'Fixture restrita de segundo cliente'})
      });
      return {status:response.status, body:await response.json()};
    })()`);
    if (secondNoShow.status !== 200 || secondNoShow.body.status !== 'NO_SHOW') {
        throw new Error(`Não foi possível restringir o segundo cliente de teste: ${JSON.stringify(secondNoShow)}`);
    }
    const secondRestrictedClientId = queryPostgres(
        `SELECT client_id FROM agendamento.appointment WHERE appointment_id = '${secondRestrictionAppointmentId}'`,
        appEnvironment,
    );
    const secondActiveRestrictionId = queryPostgres(
        `SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${secondRestrictedClientId}' AND status = 'ACTIVE'`,
        appEnvironment,
    );
    if (!secondActiveRestrictionId) throw new Error('Segundo NO_SHOW não materializou a restrição da fixture.');
    queryPostgres(
        `UPDATE agendamento.client SET display_name = '${restrictionLiftClientAName}' WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${noShowClientId}'`,
        appEnvironment,
    );
    const restrictionLiftFixtureNames = queryPostgres(
        `SELECT string_agg(client_id::text || ':' || display_name, ' | ' ORDER BY display_name) FROM agendamento.client WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id IN ('${noShowClientId}', '${secondRestrictedClientId}')`,
        appEnvironment,
    );
    const restrictionLiftSearchProbe = await evaluate(
        `fetch('/api/v1/admin/clients?query=' + encodeURIComponent(${JSON.stringify(restrictionLiftSearchPrefix)}), {credentials:'same-origin', cache:'no-store'}).then(response => response.json())`,
    );
    if (
        !restrictionLiftSearchProbe.some(
            (client) =>
                client.clientId === noShowClientId &&
                client.displayName === restrictionLiftClientAName,
        ) ||
        !restrictionLiftSearchProbe.some(
            (client) =>
                client.clientId === secondRestrictedClientId &&
                client.displayName === secondRestrictedClientName,
        )
    ) {
        throw new Error(
            `Fixtures A/B não estão disponíveis pela busca tenant-scoped: banco=${restrictionLiftFixtureNames}; API=${JSON.stringify(restrictionLiftSearchProbe)}`,
        );
    }
    console.log('Browser: busca administrativa encontrou ambos os clientes restritos de fixture.');
    await navigate(`http://localhost:5173/public/${encodeURIComponent(publication.slug)}`, secondTab.command, secondTab.evaluate);
    await waitForText(catalogName + ' Editado', secondTab.evaluate);
    await waitUntil(() => secondTab.evaluate("Boolean(document.getElementById('public-service') && !document.getElementById('public-service').disabled)"));
    await setSelectById('public-service', publicService.serviceId, secondTab.evaluate);
    const blockedBookingDate = await secondTab.evaluate(`(async () => {
      const base = new Date(${JSON.stringify(publicDate)} + 'T12:00:00Z');
      for (let offset = 1; offset <= 30; offset++) {
        const candidate = new Date(base);
        candidate.setUTCDate(candidate.getUTCDate() + offset);
        const date = candidate.toISOString().slice(0, 10);
        const query = new URLSearchParams({serviceId:${JSON.stringify(publicService.serviceId)}, from:date, to:date});
        const response = await fetch('/api/v1/public/' + encodeURIComponent(${JSON.stringify(publication.slug)}) + '/availability?' + query);
        if (!response.ok) continue;
        const page = await response.json();
        if (page.days?.[0]?.slots?.length) return date;
      }
      return null;
    })()`);
    if (!blockedBookingDate) throw new Error('Não há slot de teste futuro para provar rejeição pública neutra.');
    await setFieldById('public-date', blockedBookingDate, secondTab.evaluate);
    await waitUntil(() => secondTab.evaluate("document.querySelectorAll('.public-page__slot').length > 0"));
    await secondTab.evaluate("document.querySelector('.public-page__slot')?.click()");
    await waitUntil(() => secondTab.evaluate("Boolean(document.getElementById('public-client-name'))"));
    await setFieldById('public-client-name', 'Reserva administrativa E2E', secondTab.evaluate);
    await setFieldById('public-client-phone', restrictedPhone, secondTab.evaluate);
    const appointmentsBeforeRestrictedAttempt = Number(queryPostgres(
        `SELECT count(*) FROM agendamento.appointment WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`,
        appEnvironment,
    ));
    await clickButtonByText('Confirmar reserva', secondTab.evaluate);
    await waitForText('Não foi possível concluir a reserva.', secondTab.evaluate);
    const restrictedPublicText = await secondTab.evaluate("document.body.innerText");
    if (restrictedPublicText.includes('NO_SHOW') || restrictedPublicText.includes('falta') || restrictedPublicText.includes(activeRestrictionId)) {
        throw new Error('A página pública revelou o motivo/identificador da restrição.');
    }
    const restrictedIntentWasCleared = await secondTab.evaluate(
        `localStorage.getItem('kronos.public-booking.intent.' + ${JSON.stringify(publication.slug)}) === null`,
    );
    if (!restrictedIntentWasCleared) {
        throw new Error('A rejeição definitiva da reserva restrita deixou uma intenção recuperável sem receipt.');
    }
    const appointmentsAfterRestrictedAttempt = Number(queryPostgres(
        `SELECT count(*) FROM agendamento.appointment WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`,
        appEnvironment,
    ));
    if (appointmentsAfterRestrictedAttempt !== appointmentsBeforeRestrictedAttempt) {
        throw new Error('Tentativa pública bloqueada deixou reserva persistida.');
    }
    await clickButtonByText('Despublicar página', evaluate);
    await waitForText('Página pública despublicada.', evaluate);
    const restrictionPageUnpublished = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}').then(response => response.status)`,
    );
    if (restrictionPageUnpublished !== 404) {
        throw new Error('Fixture da rejeição por restrição não restaurou o estado público despublicado.');
    }
    await navigate(`http://localhost:5173/admin`, secondTab.command, secondTab.evaluate);
    await waitForText('Faltas de clientes', evaluate);
    await setFieldById('no-show-client-search', restrictionLiftSearchPrefix, evaluate);
    await clickButtonByText('Buscar', evaluate);
    await waitUntil(() => evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).some(button => button.innerText === '${restrictionLiftClientAName}')`));
    await waitUntil(() => evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).some(button => button.innerText === '${secondRestrictedClientName}')`));
    await clickButtonByText(restrictionLiftClientAName, evaluate);
    await waitUntil(() => evaluate(`document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText.includes('conta na janela')`));
    if ((await evaluate(`document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId`)) !== noShowClientId) {
        throw new Error('A seleção inicial dos fatos não corresponde ao cliente A.');
    }
    await waitForText('Restrição ativa desde', evaluate);
    const activeRestrictionDom = await evaluate(`document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]')?.innerText ?? ''`);
    if (!activeRestrictionDom.includes('1 de 1 faltas') || !activeRestrictionDom.includes(`política v${restrictionPolicyUpdate.body.version}`)) {
        throw new Error(`Decisão ativa não explicou contagem/limite/versão: ${activeRestrictionDom}`);
    }
    await setTextareaById('restriction-lift-reason', 'Revisão administrativa E2E', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__restrictionLiftAttempts = [];
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = String(args[0]);
        if (url.endsWith('/restrictions/${activeRestrictionId}/lift') && args[1]?.method === 'POST') {
          const attempt = {url, body:args[1].body, key:args[1].headers['Idempotency-Key'], operationId:response.headers.get('X-Operation-Id'), status:response.status, replayed:response.headers.get('Idempotency-Replayed'), receipt:response.ok ? await response.clone().json() : null};
          window.__restrictionLiftAttempts.push(attempt);
          if (response.ok && window.__restrictionLiftAttempts.length === 1) {
            window.__restrictionLiftResponseReady = true;
            await new Promise(resolve => { window.__releaseRestrictionLiftResponse = resolve; });
            throw new TypeError('Simulated lost restriction lift response after commit');
          }
          if (window.__restrictionLiftAttempts.length === 2) window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Desbloquear autoagendamento', evaluate);
    await waitUntil(() => evaluate('window.__restrictionLiftResponseReady === true'));
    const selectionGuard = await evaluate(`(() => {
      const buttons = [...document.querySelectorAll('[aria-label="Resultados de clientes"] button')];
      const clientB = buttons.find(button => button.innerText.trim() === '${secondRestrictedClientName}');
      const wasDisabled = clientB?.disabled;
      if (clientB) { clientB.disabled = false; clientB.click(); clientB.disabled = wasDisabled; }
      return {
        selectedClientId: document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId,
        clientBDisabled: wasDisabled,
        factsClientId: document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId,
      };
    })()`);
    if (selectionGuard.selectedClientId !== noShowClientId || selectionGuard.factsClientId !== noShowClientId || selectionGuard.clientBDisabled !== true) {
        throw new Error(`A seleção mudou durante o desbloqueio pendente: ${JSON.stringify(selectionGuard)}`);
    }
    const pendingLiftRequest = await evaluate('window.__restrictionLiftAttempts[0]');
    if (!pendingLiftRequest?.url.includes(`/clients/${noShowClientId}/restrictions/${activeRestrictionId}/lift`)) {
        throw new Error(`A intenção pendente não permaneceu associada ao cliente A: ${JSON.stringify(pendingLiftRequest)}`);
    }
    await evaluate('window.__releaseRestrictionLiftResponse?.()');
    await waitForText('Resultado incerto. Repita o mesmo desbloqueio para recuperar a operação original.', evaluate);
    await clickButtonByText('Recuperar mesmo desbloqueio', evaluate);
    await waitForText('Autoagendamento desbloqueado.', evaluate);
    await waitUntil(() => evaluate(`(() => {
      const summary = document.querySelector('section[aria-labelledby="client-no-shows-title"]')?.innerText ?? '';
      const facts = document.querySelector('[data-testid="client-no-show-facts"]');
      return summary.includes('Situação do autoagendamento: CLEAR') && summary.includes('Bloqueio removido') && facts?.dataset.clientId === '${noShowClientId}';
    })()`));
    const recoveredLiftDom = await evaluate(`(() => {
      const summary = document.querySelector('section[aria-labelledby="client-no-shows-title"]')?.innerText ?? '';
      return {
        clientId: document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId,
        factsClientId: document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId,
        active: Boolean(document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]')),
        history: summary.includes('Bloqueio removido'),
        liftedVersion: summary.includes('versão v2'),
        alertText: document.querySelector('[role="alert"]')?.innerText ?? '',
        retryVisible: [...document.querySelectorAll('button')].some(button => button.innerText.includes('Recuperar mesmo desbloqueio')),
        reasonDisabled: document.getElementById('restriction-lift-reason')?.disabled,
        clientB: (() => { const button = [...document.querySelectorAll('[aria-label="Resultados de clientes"] button')].find(item => item.innerText.trim() === '${secondRestrictedClientName}'); return {visible:Boolean(button), disabled:button?.disabled}; })(),
      };
    })()`);
    if (recoveredLiftDom.clientId !== noShowClientId || recoveredLiftDom.factsClientId !== noShowClientId || recoveredLiftDom.active || !recoveredLiftDom.history || !recoveredLiftDom.liftedVersion || recoveredLiftDom.alertText.includes('incerto') || recoveredLiftDom.retryVisible || recoveredLiftDom.reasonDisabled || !recoveredLiftDom.clientB.visible || recoveredLiftDom.clientB.disabled) {
        throw new Error(`DOM após replay não reconciliou cliente A/liberou navegação: ${JSON.stringify(recoveredLiftDom)}`);
    }
    await evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).find(button => button.innerText.trim() === '${secondRestrictedClientName}')?.click()`);
    try {
        await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId === '${secondRestrictedClientId}' && document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${secondRestrictedClientId}' && document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]'))`));
    } catch (selectionError) {
        const selectionDom = await evaluate(`(() => ({selected:document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId, facts:document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId, active:Boolean(document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]')), button:[...document.querySelectorAll('[aria-label="Resultados de clientes"] button')].find(item => item.innerText.trim() === '${secondRestrictedClientName}')?.disabled, alert:document.querySelector('[role="alert"]')?.innerText ?? '', loading:document.querySelector('output[aria-live="polite"]')?.innerText ?? ''}))()`);
        throw new Error(`Seleção pós-recovery de B não atualizou os fatos: ${JSON.stringify(selectionDom)}; ${selectionError.message}`);
    }
    const liftAttempts = await evaluate('window.__restrictionLiftAttempts');
    if (liftAttempts.length !== 2 || liftAttempts[0].url !== liftAttempts[1].url || liftAttempts[0].body !== liftAttempts[1].body || liftAttempts[0].key !== liftAttempts[1].key || !liftAttempts[0].operationId || liftAttempts[0].operationId !== liftAttempts[1].operationId || liftAttempts[1].replayed !== 'true' || liftAttempts[1].receipt?.status !== 'LIFTED') {
        throw new Error(`Retry do desbloqueio não recuperou a intenção original: ${JSON.stringify(liftAttempts)}`);
    }
    const liftEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'LIFTED' AND version = 2 FROM agendamento.client_booking_restriction WHERE restriction_id = '${activeRestrictionId}') AND (SELECT count(*) = 2 FROM agendamento.client_booking_restriction_history WHERE restriction_id = '${activeRestrictionId}') AND (SELECT count(*) = 1 FROM agendamento.audit_event WHERE target_id = '${activeRestrictionId}' AND action = 'CLIENT_SELF_BOOKING_UNBLOCKED') AND (SELECT count(*) = 1 FROM agendamento.outbox_event WHERE aggregate_id = '${activeRestrictionId}' AND event_type = 'ClientSelfBookingUnblocked') AND (SELECT count(*) = 1 FROM agendamento.idempotency_record WHERE command_scope = 'clients.booking-restriction.lift.v1' AND state = 'COMPLETED' AND operation_id = '${liftAttempts[1].operationId}') THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (liftEffects !== 1) throw new Error('Retry do desbloqueio duplicou ou omitiu efeitos transacionais.');
    await command('Page.reload');
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitForText('Faltas de clientes', evaluate);
    await setFieldById('no-show-client-search', restrictionLiftSearchPrefix, evaluate);
    await clickButtonByText('Buscar', evaluate);
    await waitUntil(() => evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).some(button => button.innerText === '${restrictionLiftClientAName}')`));
    await clickButtonByText(restrictionLiftClientAName, evaluate);
    await waitUntil(() => evaluate(`document.querySelector('section[aria-labelledby="client-no-shows-title"]')?.innerText.includes('Bloqueio removido')`));
    const restrictionAfterReload = await evaluate(`document.querySelector('section[aria-labelledby="client-no-shows-title"]')?.innerText ?? ''`);
    if (restrictionAfterReload.includes('Restrição ativa desde') || !restrictionAfterReload.includes('Faltas que contam: 1') || !restrictionAfterReload.includes('Bloqueio removido')) {
        throw new Error(`Desbloqueio/fatos não persistiram após reload: ${restrictionAfterReload}`);
    }
    await setFieldById('no-show-client-search', restrictionLiftSearchPrefix, evaluate);
    await clickButtonByText('Buscar', evaluate);
    await waitUntil(() => evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).some(button => button.innerText === '${secondRestrictedClientName}')`));
    await clickButtonByText(secondRestrictedClientName, evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${secondRestrictedClientId}' && document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]'))`));
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__staleClientFactsReady = false;
      window.fetch = async (...args) => {
        const url = String(args[0]);
        const response = await originalFetch(...args);
        if (url.includes('/clients/${noShowClientId}/no-shows?') && !args[1]?.method && !window.__staleClientFactsReady) {
          window.__staleClientFactsReady = true;
          await new Promise(resolve => { window.__releaseStaleClientFacts = resolve; });
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).find(button => button.innerText.trim() === '${restrictionLiftClientAName}')?.click()`);
    await waitUntil(() => evaluate('typeof window.__releaseStaleClientFacts === "function"'));
    await evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).find(button => button.innerText.trim() === '${secondRestrictedClientName}')?.click()`);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${secondRestrictedClientId}' && document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]'))`));
    await evaluate('(async () => { window.__releaseStaleClientFacts?.(); await new Promise(resolve => setTimeout(resolve, 0)); })()');
    const staleFactsDom = await evaluate(`({selected:document.querySelector('[data-testid="selected-no-show-client"]')?.innerText, selectedId:document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId, factsId:document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId, restriction:document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]')?.innerText ?? ''})`);
    if (staleFactsDom.selectedId !== secondRestrictedClientId || staleFactsDom.factsId !== secondRestrictedClientId || !staleFactsDom.selected?.includes(secondRestrictedClientName) || !staleFactsDom.restriction) {
        throw new Error(`Resposta tardia dos fatos do cliente A contaminou o cliente B: ${JSON.stringify(staleFactsDom)}`);
    }
    console.log('Browser: desbloqueio incerto permaneceu ligado ao cliente/restrição originais; retry único atualizou A, bloqueou troca durante envio/incerteza e leitura tardia preservou os dados de B.');
    await evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).find(button => button.innerText.trim() === '${restrictionLiftClientAName}')?.click()`);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${noShowClientId}' && document.getElementById('no-show-reason-${administrativeAppointmentId}'))`));
    await setTextareaById(`no-show-reason-${administrativeAppointmentId}`, 'Correção compensatória comprovada E2E', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.__noShowCorrectionRequests = [];
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = String(args[0]);
        if (url.endsWith('/correct-no-show') && args[1]?.method === 'POST') {
          const request = {url, body: args[1].body, key: args[1].headers['Idempotency-Key']};
          if (response.ok && window.__noShowCorrectionRequests.length === 0) {
            window.__noShowCorrectionRequests.push(request);
            throw new TypeError('Simulated lost no-show correction response after commit');
          }
          window.__noShowCorrectionRequests.push({...request, status: response.status, replayed: response.headers.get('Idempotency-Replayed'), receipt: response.ok ? await response.clone().json() : null});
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Corrigir para concluída', evaluate);
    await waitForText('Resultado incerto. Repita a mesma correção para recuperar o receipt original.', evaluate);
    await clickButtonByText('Recuperar mesma correção', evaluate);
    try {
        await waitUntil(() => evaluate(`document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText.includes('corrigida; não conta')`));
    } catch (correctionError) {
        const correctionState = await evaluate(`(() => ({selected:document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId, facts:document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId, row:document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText ?? null, alert:document.querySelector('[role="alert"]')?.innerText ?? '', notice:[...document.querySelectorAll('output')].map(output => output.innerText), requests:window.__noShowCorrectionRequests}))()`);
        throw new Error(`Correção de falta após retry não reconciliou a linha: ${JSON.stringify(correctionState)}; ${correctionError.message}`);
    }
    const correctionDom = await evaluate(`document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText ?? ''`);
    if (!correctionDom.includes('COMPLETED, versão 3') || !correctionDom.includes('Correção compensatória comprovada E2E')) {
        throw new Error(`DOM não mostrou correção e motivo persistidos: ${correctionDom}`);
    }
    const correctionRequests = await evaluate('window.__noShowCorrectionRequests');
    if (correctionRequests.length !== 2 || correctionRequests[0].url !== correctionRequests[1].url || correctionRequests[0].body !== correctionRequests[1].body || correctionRequests[0].key !== correctionRequests[1].key || correctionRequests[1].replayed !== 'true') {
        throw new Error(`Retry da correção alterou a intenção ou não reproduziu receipt: ${JSON.stringify(correctionRequests)}`);
    }
    const correctionEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'COMPLETED' AND version = 3 FROM agendamento.appointment WHERE appointment_id = '${administrativeAppointmentId}') AND (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${administrativeAppointmentId}' AND reason_code = 'APPOINTMENT_NO_SHOW') = 1 AND (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${administrativeAppointmentId}' AND reason_code = 'APPOINTMENT_NO_SHOW_CORRECTED' AND corrects_history_id IS NOT NULL) = 1 AND (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${administrativeAppointmentId}' AND action = 'APPOINTMENT_NO_SHOW_CORRECTED') = 1 AND (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${administrativeAppointmentId}' AND event_type = 'ClientNoShowCorrected') = 1 AND (SELECT count(*) FROM agendamento.idempotency_record WHERE command_scope = 'appointments.admin.correct-no-show.v1' AND state = 'COMPLETED' AND operation_id = '${correctionRequests[1].receipt.operationId}') = 1 THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (correctionEffects !== 1) throw new Error('Correção após resposta perdida duplicou ou omitiu efeitos persistidos.');
    const correctionHistory = await evaluate(
        `fetch('/api/v1/admin/appointments/${administrativeAppointmentId}/history', {credentials:'same-origin'}).then(response => response.json())`,
    );
    if (!correctionHistory.items.some(item => item.reasonCode === 'APPOINTMENT_NO_SHOW_CORRECTED' && item.correctsHistoryId)) {
        throw new Error('História compensatória referenciada não apareceu na consulta administrativa.');
    }
    await command('Page.reload');
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitForText('Faltas de clientes', evaluate);
    await setFieldById('no-show-client-search', restrictionLiftSearchPrefix, evaluate);
    await clickButtonByText('Buscar', evaluate);
    await waitUntil(() => evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).some(button => button.innerText === '${restrictionLiftClientAName}')`));
    await clickButtonByText(restrictionLiftClientAName, evaluate);
    try {
        await waitUntil(() => evaluate(`document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText.includes('corrigida; não conta')`));
    } catch (correctionError) {
        const correctionState = await evaluate(`(() => ({selected:document.querySelector('[data-testid="selected-no-show-client"]')?.dataset.clientId, facts:document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId, row:document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText ?? null, alert:document.querySelector('[role="alert"]')?.innerText ?? '', notice:[...document.querySelectorAll('output')].map(output => output.innerText), requests:window.__noShowCorrectionRequests}))()`);
        const correctionDatabase = queryPostgres(`SELECT status || ':v' || version FROM agendamento.appointment WHERE appointment_id = '${administrativeAppointmentId}'`, appEnvironment);
        throw new Error(`Correção após retry não reconciliou fatos no DOM: ${JSON.stringify(correctionState)}; db=${correctionDatabase}; ${correctionError.message}`);
    }
    const correctedFactsAfterReload = await evaluate(`(() => ({
      text: document.querySelector('[data-testid="no-show-${administrativeAppointmentId}"]')?.innerText ?? '',
      summary: document.querySelector('section[aria-labelledby="client-no-shows-title"]')?.innerText ?? ''
    }))()`);
    if (!correctedFactsAfterReload.text.includes('COMPLETED, versão 3') || !correctedFactsAfterReload.summary.includes('Faltas que contam: 0')) {
        throw new Error(`Correção/count não persistiram após reload: ${JSON.stringify(correctedFactsAfterReload)}`);
    }
    console.log(`Browser: falta consultada e corrigida com motivo, retry idempotente, história e reload (cliente ${noShowClientId}).`);

    const reconciliationClientId = randomUUID();
    const reconciliationAppointmentId = randomUUID();
    const reconciliationInitialHistoryId = randomUUID();
    const reconciliationNoShowHistoryId = randomUUID();
    const reconciliationClientName = `Cliente reconciliação AGD033 ${Date.now()}`;
    const reconciliationPhone = `+5511999${String(Date.now()).slice(-7)}`;
    queryPostgres(
        `INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES ('${reconciliationClientId}', '${credentials.DEMO_TENANT_ID}', '${reconciliationClientName}', '${reconciliationPhone}');
         WITH moment AS (SELECT clock_timestamp() AS now), service AS (SELECT duration_minutes, buffer_after_minutes, name FROM agendamento.service WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND service_id = '${publicService.serviceId}'::uuid)
         INSERT INTO agendamento.appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version)
         SELECT '${reconciliationAppointmentId}', '${credentials.DEMO_TENANT_ID}', '${reconciliationClientId}', '${publicService.serviceId}', moment.now - interval '2 days', moment.now - interval '2 days' + make_interval(mins => service.duration_minutes), moment.now - interval '2 days' + make_interval(mins => service.duration_minutes + service.buffer_after_minutes), '${publication.timeZone}', '${reconciliationClientName}', service.name, service.duration_minutes, service.buffer_after_minutes, 'NO_SHOW', moment.now - interval '2 days', 2 FROM moment CROSS JOIN service;
         INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at) VALUES ('${reconciliationInitialHistoryId}', '${credentials.DEMO_TENANT_ID}', '${reconciliationAppointmentId}', NULL, 'CONFIRMED', 'PUBLIC', 'PUBLIC_BOOKING', 'APPOINTMENT_CREATED', clock_timestamp() - interval '2 days');
         INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, version_before, version_after) VALUES ('${reconciliationNoShowHistoryId}', '${credentials.DEMO_TENANT_ID}', '${reconciliationAppointmentId}', 'CONFIRMED', 'NO_SHOW', 'ADMIN', 'TENANT_ADMIN', 'APPOINTMENT_NO_SHOW', clock_timestamp() - interval '1 day', 1, 2);
         DELETE FROM agendamento.client_no_show_policy WHERE tenant_id = '${credentials.DEMO_TENANT_ID}';`,
        appEnvironment,
    );
    const restoreNoShowPolicy = await evaluate(`(async () => {
      const [policyResponse, csrfResponse] = await Promise.all([
        fetch('/api/v1/admin/no-show-policy', {credentials:'same-origin', cache:'no-store'}),
        fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'})
      ]);
      const [policy, csrf] = await Promise.all([policyResponse.json(), csrfResponse.json()]);
      const response = await fetch('/api/v1/admin/no-show-policy', {
        method:'PUT', credentials:'same-origin',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':crypto.randomUUID()},
        body:JSON.stringify({expectedVersion:policy.version, threshold:1})
      });
      return {status:response.status, body:response.ok ? await response.json() : await response.text()};
    })()`);
    if (restoreNoShowPolicy.status !== 200 || restoreNoShowPolicy.body.version !== 1) {
        throw new Error(`Não foi possível restaurar política para prova explícita de reconciliação: ${JSON.stringify(restoreNoShowPolicy)}`);
    }
    const restrictionBeforeExplicitReconciliation = queryPostgres(
        `SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${reconciliationClientId}'`,
        appEnvironment,
    );
    if (restrictionBeforeExplicitReconciliation !== '0') {
        throw new Error('Salvar a política executou uma reconciliação implícita.');
    }
    await setFieldById('no-show-client-search', reconciliationClientName, evaluate);
    await clickButtonByText('Buscar', evaluate);
    await waitUntil(() => evaluate(`Array.from(document.querySelectorAll('[aria-label="Resultados de clientes"] button')).some(button => button.innerText === ${JSON.stringify(reconciliationClientName)})`));
    await clickButtonByText(reconciliationClientName, evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${reconciliationClientId}' && [...document.querySelectorAll('button')].some(button => button.innerText === 'Reavaliar política para este cliente'))`));
    const unreconciledFacts = await evaluate(`(() => ({text:document.querySelector('[data-testid="client-no-show-facts"]')?.innerText ?? '', active:Boolean(document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]'))}))()`);
    if (!unreconciledFacts.text.includes('Faltas que contam: 1') || unreconciledFacts.active) {
        throw new Error(`A política atualizou o cliente sem reconciliação explícita: ${JSON.stringify(unreconciledFacts)}`);
    }
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__restrictionReconciliationAttempts = [];
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = String(args[0]);
        if (url.endsWith('/restrictions/reconcile') && args[1]?.method === 'POST') {
          const request = {url, body:args[1].body, key:args[1].headers['Idempotency-Key']};
          if (response.ok && window.__restrictionReconciliationAttempts.length === 0) {
            window.__restrictionReconciliationAttempts.push(request);
            throw new TypeError('Simulated lost reconciliation response after commit');
          }
          window.__restrictionReconciliationAttempts.push({...request, status:response.status, replayed:response.headers.get('Idempotency-Replayed'), operationId:response.headers.get('X-Operation-Id'), receipt:response.ok ? await response.clone().json() : null});
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Reavaliar política para este cliente', evaluate);
    await waitForText('Resultado incerto. Repita a mesma reconciliação para recuperar o receipt original.', evaluate);
    await clickButtonByText('Recuperar mesma reconciliação', evaluate);
    await waitForText('ACTIVATED', evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]') && document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${reconciliationClientId}')`));
    const reconciliationAttempts = await evaluate('window.__restrictionReconciliationAttempts');
    if (reconciliationAttempts.length !== 2 || reconciliationAttempts[0].url !== reconciliationAttempts[1].url || reconciliationAttempts[0].body !== reconciliationAttempts[1].body || reconciliationAttempts[0].key !== reconciliationAttempts[1].key || reconciliationAttempts[1].replayed !== 'true' || !/^[0-9a-f-]{36}$/i.test(reconciliationAttempts[1].operationId ?? '') || reconciliationAttempts[1].receipt?.decision !== 'ACTIVATED') {
        throw new Error(`Retry da reconciliação alterou a intenção ou não recuperou o receipt: ${JSON.stringify(reconciliationAttempts)}`);
    }
    const reconciliationEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'ACTIVE' AND version = 1 AND source_history_id = '${reconciliationNoShowHistoryId}' FROM agendamento.client_booking_restriction WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${reconciliationClientId}') AND (SELECT count(*) = 1 FROM agendamento.client_booking_restriction_history WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND event_type = 'RECONCILED' AND actor_type = 'ADMIN' AND restriction_id = (SELECT restriction_id FROM agendamento.client_booking_restriction WHERE client_id = '${reconciliationClientId}')) AND (SELECT count(*) = 1 FROM agendamento.audit_event WHERE action = 'CLIENT_SELF_BOOKING_RECONCILED' AND target_id = '${reconciliationClientId}') AND (SELECT count(*) = 1 FROM agendamento.outbox_event WHERE event_type = 'ClientSelfBookingBlocked' AND aggregate_id = (SELECT restriction_id FROM agendamento.client_booking_restriction WHERE client_id = '${reconciliationClientId}')) AND (SELECT count(*) = 1 FROM agendamento.idempotency_record WHERE command_scope = 'clients.booking-restriction.reconcile.v1' AND state = 'COMPLETED' AND operation_id = '${reconciliationAttempts[1].operationId}') THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (reconciliationEffects !== 1) throw new Error('Retry da reconciliação duplicou ou omitiu efeitos transacionais.');
    const restrictionId = queryPostgres(
        `SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${reconciliationClientId}'`,
        appEnvironment,
    );
    const activatedReceiptOperationId = reconciliationAttempts[1].operationId;
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${reconciliationClientId}' && document.querySelector('#restriction-lift-reason') && [...document.querySelectorAll('button')].some(button => button.innerText === 'Desbloquear autoagendamento'))`));
    await setTextareaById('restriction-lift-reason', 'Desbloqueio para provar nova intenção E2E', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__restrictionLiftAttempts = [];
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = String(args[0]);
        if (url.endsWith('/restrictions/${restrictionId}/lift') && args[1]?.method === 'POST') {
          window.__restrictionLiftAttempts.push({url, body:args[1].body, key:args[1].headers['Idempotency-Key'], status:response.status, operationId:response.headers.get('X-Operation-Id'), receipt:response.ok ? await response.clone().json() : null});
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Desbloquear autoagendamento', evaluate);
    await waitForText('Autoagendamento desbloqueado.', evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${reconciliationClientId}' && !document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]') && [...document.querySelectorAll('button')].some(button => button.innerText === 'Reavaliar política para este cliente'))`));
    const reconciliationLiftAttempts = await evaluate('window.__restrictionLiftAttempts');
    if (reconciliationLiftAttempts.length !== 1 || reconciliationLiftAttempts[0].status !== 200 || reconciliationLiftAttempts[0].receipt?.status !== 'LIFTED' || reconciliationLiftAttempts[0].receipt?.version !== 2 || !/^[0-9a-f-]{36}$/i.test(reconciliationLiftAttempts[0].operationId ?? '')) {
        throw new Error(`Desbloqueio administrativo não foi confirmado como uma única operação: ${JSON.stringify(reconciliationLiftAttempts)}`);
    }
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__restrictionReconciliationAfterLift = [];
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = String(args[0]);
        if (url.endsWith('/restrictions/reconcile') && args[1]?.method === 'POST') {
          window.__restrictionReconciliationAfterLift.push({url, body:args[1].body, key:args[1].headers['Idempotency-Key'], status:response.status, replayed:response.headers.get('Idempotency-Replayed'), operationId:response.headers.get('X-Operation-Id'), receipt:response.ok ? await response.clone().json() : null});
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Reavaliar política para este cliente', evaluate);
    await waitForText('NO_UNUSED_CAUSAL_FACT', evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-testid="client-no-show-facts"]')?.dataset.clientId === '${reconciliationClientId}' && !document.querySelector('section[aria-label="Restrição ativa de autoagendamento"]'))`));
    const reconciliationAfterLift = await evaluate('window.__restrictionReconciliationAfterLift');
    if (reconciliationAfterLift.length !== 1 || reconciliationAfterLift[0].status !== 200 || reconciliationAfterLift[0].url !== reconciliationAttempts[1].url || reconciliationAfterLift[0].body !== reconciliationAttempts[1].body || reconciliationAfterLift[0].key === reconciliationAttempts[1].key || reconciliationAfterLift[0].replayed !== 'false' || !/^[0-9a-f-]{36}$/i.test(reconciliationAfterLift[0].operationId ?? '') || reconciliationAfterLift[0].operationId === activatedReceiptOperationId || reconciliationAfterLift[0].operationId === reconciliationLiftAttempts[0].operationId || reconciliationAfterLift[0].receipt?.decision !== 'NO_UNUSED_CAUSAL_FACT' || reconciliationAfterLift[0].receipt?.clientId !== reconciliationClientId || reconciliationAfterLift[0].receipt?.policyVersion !== 1) {
        throw new Error(`Nova reconciliação explícita não recebeu identidade própria ou retornou resultado inesperado: ${JSON.stringify({first: reconciliationAttempts, afterLift: reconciliationAfterLift})}`);
    }
    const postLiftReconciliationEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'LIFTED' AND version = 2 AND source_history_id = '${reconciliationNoShowHistoryId}' FROM agendamento.client_booking_restriction WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND client_id = '${reconciliationClientId}' AND restriction_id = '${restrictionId}') AND (SELECT count(*) = 2 FROM agendamento.client_booking_restriction_history WHERE tenant_id = '${credentials.DEMO_TENANT_ID}' AND restriction_id = '${restrictionId}' AND event_type IN ('RECONCILED','LIFTED')) AND (SELECT count(*) = 1 FROM agendamento.audit_event WHERE action = 'CLIENT_SELF_BOOKING_UNBLOCKED' AND target_id = '${restrictionId}') AND (SELECT count(*) = 2 FROM agendamento.audit_event WHERE action = 'CLIENT_SELF_BOOKING_RECONCILED' AND target_id = '${reconciliationClientId}') AND (SELECT count(*) = 1 FROM agendamento.outbox_event WHERE event_type = 'ClientSelfBookingBlocked' AND aggregate_id = '${restrictionId}') AND (SELECT count(*) = 1 FROM agendamento.outbox_event WHERE event_type = 'ClientSelfBookingUnblocked' AND aggregate_id = '${restrictionId}') AND (SELECT count(*) = 3 FROM agendamento.idempotency_record WHERE state = 'COMPLETED' AND operation_id IN ('${activatedReceiptOperationId}','${reconciliationAfterLift[0].operationId}','${reconciliationLiftAttempts[0].operationId}')) THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (postLiftReconciliationEffects !== 1) throw new Error('Nova reconciliação duplicou ativação/outbox ou alterou indevidamente o desbloqueio.');
    console.log('Browser: reconciliação com resposta perdida foi recuperada com mesma intenção; desbloqueio seguido de nova ação no mesmo cliente/política usou chave/operação distintas, retornou NO_UNUSED_CAUSAL_FACT e preservou o estado LIFTED sem duplicar ativação/outbox.');

    const cancelRetryAppointmentId = queryPostgres(
        `SELECT a.appointment_id FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.client_name_snapshot = 'Encaixe administrativo E2E' AND a.status = 'CONFIRMED'`,
        appEnvironment,
    );
    const cancelRetryInitialVersion = Number(queryPostgres(
        `SELECT version FROM agendamento.appointment WHERE appointment_id = '${cancelRetryAppointmentId}'`,
        appEnvironment,
    ));
    await setFieldByLabel('Data de referência', publicDate, evaluate);
    await waitUntil(() => evaluate(`Boolean(document.querySelector('[data-appointment-id="${cancelRetryAppointmentId}"]'))`));
    await evaluate(`document.querySelector('[data-appointment-id="${cancelRetryAppointmentId}"] button')?.click()`);
    await waitUntil(() => evaluate(`document.querySelector('.admin-agenda__details')?.innerText.includes('Encaixe administrativo E2E')`));
    await clickButtonByText('Cancelar reserva', evaluate);
    await evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      const wrapper = [...(details?.querySelectorAll('label') ?? [])].find(item => item.childNodes[0]?.textContent?.trim() === 'Motivo (opcional)');
      const field = wrapper?.querySelector('input');
      if (!field) throw new Error('Cancellation reason field not found inside appointment details');
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor.set.call(field, 'Cancelamento com resposta perdida E2E');
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
      if (field.value !== 'Cancelamento com resposta perdida E2E') throw new Error('Cancellation reason was not rendered in the original appointment details');
    })()`);
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = String(args[0]);
        if (url.endsWith('/api/v1/admin/appointments/${cancelRetryAppointmentId}/cancel') && args[1]?.method === 'POST' && response.ok) {
          window.__pendingCancelRequest = { url, body: args[1].body, key: args[1].headers['Idempotency-Key'] };
          window.__committedCancelReceipt = await response.clone().json();
          window.__committedCancelReplayHeader = response.headers.get('Idempotency-Replayed');
          window.__pendingCancelReady = true;
          await new Promise(resolve => { window.__releaseCancelResponse = resolve; });
          window.fetch = originalFetch;
          throw new TypeError('Simulated lost cancellation response after commit');
        }
        return response;
      };
    })()`);
    await clickButtonByText('Confirmar ação', evaluate);
    await waitUntil(() => evaluate('window.__pendingCancelReady === true'));
    const pendingCancelDom = await evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      return {
        text: details?.innerText ?? '',
        closeDisabled: [...(details?.querySelectorAll('button') ?? [])].find(button => button.innerText.trim() === 'Fechar detalhes')?.disabled,
        periodDisabled: [...document.querySelectorAll('button')].find(button => button.innerText.trim() === 'Próximo período')?.disabled,
        request: window.__pendingCancelRequest,
        receipt: window.__committedCancelReceipt,
      };
    })()`);
    if (
        !pendingCancelDom.closeDisabled ||
        !pendingCancelDom.periodDisabled ||
        !pendingCancelDom.text.includes('Encaixe administrativo E2E') ||
        !pendingCancelDom.text.includes(`CONFIRMED · versão ${cancelRetryInitialVersion}`) ||
        pendingCancelDom.request?.url !== `/api/v1/admin/appointments/${cancelRetryAppointmentId}/cancel` ||
        pendingCancelDom.receipt?.appointmentId !== cancelRetryAppointmentId ||
        pendingCancelDom.receipt?.status !== 'CANCELLED_BY_PROVIDER' ||
        pendingCancelDom.receipt?.version !== cancelRetryInitialVersion + 1
    ) {
        throw new Error(`Cancelamento ainda não estava associado à intenção original após commit: ${JSON.stringify(pendingCancelDom)}`);
    }
    const lostCancelCommitted = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'CANCELLED_BY_PROVIDER' AND version = ${cancelRetryInitialVersion + 1} FROM agendamento.appointment WHERE appointment_id = '${cancelRetryAppointmentId}') AND (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${cancelRetryAppointmentId}' AND reason_code = 'APPOINTMENT_CANCELLED_BY_PROVIDER') = 1 AND (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${cancelRetryAppointmentId}' AND action = 'APPOINTMENT_CANCELLED_BY_PROVIDER') = 1 AND (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${cancelRetryAppointmentId}' AND event_type = 'AppointmentCancelled') = 1 THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (lostCancelCommitted !== 1) throw new Error('POST de cancelamento não confirmou o conjunto transacional antes da resposta perdida.');
    await evaluate('window.__releaseCancelResponse?.()');
    await waitForText('A resposta não chegou; o comando pode ter sido confirmado.', evaluate);
    const uncertainCancelDom = await evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      return {
        text: details?.innerText ?? '',
        retryVisible: [...(details?.querySelectorAll('button') ?? [])].some(button => button.innerText.trim() === 'Repetir exatamente a mesma ação'),
        closeDisabled: [...(details?.querySelectorAll('button') ?? [])].find(button => button.innerText.trim() === 'Fechar detalhes')?.disabled,
        request: window.__pendingCancelRequest,
      };
    })()`);
    if (
        !uncertainCancelDom.text.includes(`Resultado incerto para cancel da reserva ${cancelRetryAppointmentId}`) ||
        !uncertainCancelDom.retryVisible ||
        !uncertainCancelDom.closeDisabled ||
        uncertainCancelDom.request?.body !== JSON.stringify({ expectedVersion: cancelRetryInitialVersion, reason: 'Cancelamento com resposta perdida E2E' })
    ) {
        throw new Error(`UI não preservou o contexto de cancelamento incerto: ${JSON.stringify(uncertainCancelDom)}`);
    }
    await evaluate(`(() => {
      const originalFetch = window.fetch;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        if (String(args[0]).endsWith('/api/v1/admin/appointments/${cancelRetryAppointmentId}/cancel') && args[1]?.method === 'POST') {
          window.__cancelRetryRequest = { url: String(args[0]), body: args[1].body, key: args[1].headers['Idempotency-Key'] };
          window.__cancelRetryReceipt = await response.clone().json();
          window.__cancelRetryReplayHeader = response.headers.get('Idempotency-Replayed');
          window.fetch = originalFetch;
        }
        return response;
      };
    })()`);
    await clickButtonByText('Repetir exatamente a mesma ação', evaluate);
    await waitForText('Estado atualizado e persistido.', evaluate);
    const cancelRetryEvidence = await evaluate(`({
      original: window.__pendingCancelRequest,
      retry: window.__cancelRetryRequest,
      firstReceipt: window.__committedCancelReceipt,
      retryReceipt: window.__cancelRetryReceipt,
      replayHeader: window.__cancelRetryReplayHeader,
    })`);
    if (
        JSON.stringify(cancelRetryEvidence.original) !== JSON.stringify(cancelRetryEvidence.retry) ||
        cancelRetryEvidence.firstReceipt?.operationId !== cancelRetryEvidence.retryReceipt?.operationId ||
        cancelRetryEvidence.retryReceipt?.status !== 'CANCELLED_BY_PROVIDER' ||
        cancelRetryEvidence.retryReceipt?.version !== cancelRetryInitialVersion + 1 ||
        cancelRetryEvidence.replayHeader !== 'true'
    ) {
        throw new Error(`Retry de cancelamento não recuperou a mesma intenção/receipt: ${JSON.stringify(cancelRetryEvidence)}`);
    }
    await waitUntil(() => evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      const text = details?.innerText ?? '';
      return text.includes('CANCELLED_BY_PROVIDER') && text.includes('versão ${cancelRetryInitialVersion + 1}') && text.includes('APPOINTMENT_CANCELLED_BY_PROVIDER') && text.includes('Cancelamento com resposta perdida E2E');
    })()`));
    const reconciledCancelDom = await evaluate(`(() => {
      const details = document.querySelector('.admin-agenda__details');
      const text = details?.innerText ?? '';
      const buttons = [...(details?.querySelectorAll('button') ?? [])];
      return {
        text,
        pendingWarning: text.includes('Resultado incerto') || text.includes('A resposta não chegou'),
        retryVisible: buttons.some(button => button.innerText.trim() === 'Repetir exatamente a mesma ação'),
        closeDisabled: buttons.find(button => button.innerText.trim() === 'Fechar detalhes')?.disabled,
        periodDisabled: [...document.querySelectorAll('button')].find(button => button.innerText.trim() === 'Próximo período')?.disabled,
      };
    })()`);
    if (
        reconciledCancelDom.pendingWarning ||
        reconciledCancelDom.retryVisible ||
        reconciledCancelDom.closeDisabled !== false ||
        reconciledCancelDom.periodDisabled !== false
    ) {
        throw new Error(`Cancelamento reconciliado não limpou alertas/liberou navegação: ${JSON.stringify(reconciledCancelDom)}`);
    }
    await clickButtonByText('Fechar detalhes', evaluate);
    await waitUntil(() => evaluate('!document.querySelector(".admin-agenda__details")'));
    const dateBeforeNavigation = await evaluate(`(() => [...document.querySelectorAll('label')].find(label => label.childNodes[0]?.textContent?.trim() === 'Data de referência')?.querySelector('input')?.value)()`);
    await clickButtonByText('Próximo período', evaluate);
    await waitUntil(() => evaluate(`(() => [...document.querySelectorAll('label')].find(label => label.childNodes[0]?.textContent?.trim() === 'Data de referência')?.querySelector('input')?.value !== ${JSON.stringify(dateBeforeNavigation)})()`));
    const cancelRetryEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = '${cancelRetryAppointmentId}' AND reason_code = 'APPOINTMENT_CANCELLED_BY_PROVIDER') = 1 AND (SELECT count(*) FROM agendamento.audit_event WHERE target_id = '${cancelRetryAppointmentId}' AND action = 'APPOINTMENT_CANCELLED_BY_PROVIDER') = 1 AND (SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = '${cancelRetryAppointmentId}' AND event_type = 'AppointmentCancelled') = 1 AND (SELECT count(*) FROM agendamento.idempotency_record WHERE operation_id = '${cancelRetryEvidence.retryReceipt.operationId}' AND command_scope = 'appointments.admin.cancel_by_provider.v1' AND state = 'COMPLETED') = 1 AND (SELECT status = 'CANCELLED_BY_PROVIDER' AND version = ${cancelRetryInitialVersion + 1} FROM agendamento.appointment WHERE appointment_id = '${cancelRetryAppointmentId}') THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (cancelRetryEffects !== 1) throw new Error('Cancelamento retry duplicou ou omitiu status, versão, história, auditoria, receipt ou outbox.');
    console.log('Browser: cancelamento administrativo independente perdeu a resposta após commit, repetiu o mesmo alvo/body/chave, recuperou o receipt no DOM e manteve efeitos únicos.');

    const allOutcomeEffects = Number(queryPostgres(
        `SELECT CASE WHEN (SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.appointment_id <> '${reconciliationAppointmentId}' AND a.status IN ('CANCELLED_BY_PROVIDER','COMPLETED','NO_SHOW')) = 5 AND (SELECT count(*) FROM agendamento.outbox_event WHERE event_type IN ('AppointmentCancelled','AppointmentCompleted','ClientNoShowRecorded') AND tenant_id = (SELECT tenant_id FROM agendamento.tenant WHERE slug = '${publication.slug}')) = 5 THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (allOutcomeEffects !== 1) throw new Error('As cinco transições terminais não têm evento persistido único por reserva.');
    console.log('Browser: cancelamento com resposta perdida/replay, cancelamento concorrente, conclusão e falta persistidos; detalhes e história renderizam estados terminais.');

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
    if (missingCsrfStatus !== 403 || invalidCsrfStatus !== 403 || csrfProtectedSessionStatus !== 200) {
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

    const sessionBeforeExpiry = (await cookies(command)).find((cookie) => cookie.name === 'AGDSESSION');
    if (!sessionBeforeExpiry) throw new Error('Cookie de sessão não encontrado antes do teste de expiração.');
    const nearExpiryRecord = setSessionIdleMinutes(sessionBeforeExpiry.value, 25, appEnvironment);
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
    if (expiredApiResponse.status !== 401 || expiredApiResponse.body.error?.code !== 'AUTHENTICATION_REQUIRED') {
        throw new Error('Cookie da sessão inativa por mais de 30 minutos acessou a API protegida.');
    }
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Sua sessão expirou ou ainda não foi iniciada.', evaluate);
    if (!(await evaluate("Boolean(document.querySelector('a[href*=oauth2]'))"))) {
        throw new Error('UI não ofereceu novo login após receber 401 da sessão expirada.');
    }
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('Sessão administrativa ativa.', evaluate);
    if (await evaluate('Boolean(document.querySelector(\'input[name="username"]\'))')) {
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

    await waitUntil(() => evaluate("[...document.querySelectorAll('button')].some(button => button.innerText.trim() === 'Publicar página')"));
    await clickButtonByText('Publicar página', evaluate);
    await waitForText('Estado: Publicado', evaluate);
    const republishedPublicPage = await evaluate("fetch('/api/v1/admin/publication', {credentials:'same-origin', cache:'no-store'}).then(response => response.json())");
    if (republishedPublicPage.published !== true) {
        throw new Error('Fixture E2E não republicou o tenant pelo fluxo administrativo antes da reserva pública de AGD-025.');
    }
    console.log('Browser: fixture legal/comercial controlada foi publicada explicitamente pela UI administrativa para o cenário AGD-025.');

    await waitUntil(() =>
        evaluate("Boolean([...document.querySelectorAll('button')].find(button => !button.disabled))"),
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

    const clientPhone = '+5511999991236';
    const demoTenantSlug = queryPostgres(
        `SELECT slug FROM agendamento.tenant WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`,
        appEnvironment,
    );
    await navigate(`http://localhost:5173/public/${encodeURIComponent(demoTenantSlug)}`, command, evaluate);
    await waitForText(catalogName + ' Editado', evaluate);
    await waitUntil(() =>
        evaluate(
            "Boolean([...document.querySelectorAll('button')].find(button => button.innerText.trim() === 'Fazer outra reserva') || (document.getElementById('public-service') && !document.getElementById('public-service').disabled))",
        ),
    );
    const resolvedPublicIntent = await evaluate(
        "Boolean([...document.querySelectorAll('button')].find(button => button.innerText.trim() === 'Fazer outra reserva'))",
    );
    if (resolvedPublicIntent) {
        await clickButtonByText('Fazer outra reserva', evaluate);
    }
    await waitUntil(() =>
        evaluate(
            "Boolean(document.getElementById('public-service') && !document.getElementById('public-service').disabled)",
        ),
    );
    if (resolvedPublicIntent) {
        console.log('Browser: intenção pública anterior resolvida pela ação explícita antes de iniciar a fixture cliente.');
    }
    await setSelectById('public-service', publicService.serviceId, evaluate);
    const clientBookingDate = await evaluate(`(async () => {
      const timeZone = ${JSON.stringify(publication.timeZone)};
      const fields = Object.fromEntries(new Intl.DateTimeFormat('en-US', {timeZone, year:'numeric', month:'2-digit', day:'2-digit'}).formatToParts(new Date()).map(part => [part.type, part.value]));
      const today = new Date(Date.UTC(Number(fields.year), Number(fields.month)-1, Number(fields.day)));
      for (let offset = 1; offset < 30; offset += 1) {
        const candidate = new Date(today);
        candidate.setUTCDate(candidate.getUTCDate() + offset);
        const date = candidate.toISOString().slice(0, 10);
        if (date === ${JSON.stringify(publicDate)}) continue;
        const query = new URLSearchParams({serviceId:${JSON.stringify(publicService.serviceId)}, from:date, to:date});
        const response = await fetch('/api/v1/public/' + encodeURIComponent(${JSON.stringify(publication.slug)}) + '/availability?' + query);
        if (!response.ok) throw new Error('Consulta de disponibilidade da fixture cliente falhou: ' + response.status);
        const availability = await response.json();
        if (availability.days?.[0]?.slots?.length) return date;
      }
      return null;
    })()`);
    if (!clientBookingDate) {
        throw new Error('Não foi encontrada data futura elegível e não consumida para a reserva real da área do cliente.');
    }
    await setFieldById('public-date', clientBookingDate, evaluate);
    await waitUntil(() => evaluate("[...document.querySelectorAll('.public-page__slot')].some(button => !button.disabled)"));
    await evaluate("[...document.querySelectorAll('.public-page__slot')].find(button => !button.disabled)?.click()");
    await setFieldById('public-client-name', 'Cliente da área E2E', evaluate);
    await setFieldById('public-client-phone', clientPhone, evaluate);
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitForText('Reserva confirmada', evaluate);
    const clientBookingId = queryPostgres(
        `SELECT a.appointment_id FROM agendamento.appointment a JOIN agendamento.client c USING (tenant_id, client_id) WHERE a.tenant_id = '${credentials.DEMO_TENANT_ID}' AND c.normalized_phone = '${clientPhone}' AND a.status = 'CONFIRMED' ORDER BY a.created_at DESC LIMIT 1`,
        appEnvironment,
    );
    if (!clientBookingId) throw new Error('O fluxo público não persistiu a reserva da área do cliente.');
    console.log('Browser: preparou reserva pública real vinculada ao telefone que será verificado pelo challenge AGD-024.');
    await navigate(`http://localhost:5173/client-access/${encodeURIComponent(demoTenantSlug)}`, command, evaluate);
    await waitForText('Entre com seu telefone', evaluate);
    await waitUntil(() => evaluate("!document.querySelector('button[type=submit]')?.disabled"));
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__clientAccessRequestAttempts = [];
      window.__clientAccessDropNextRequestResponse = true;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const requestUrl = args[0] instanceof Request ? args[0].url : String(args[0]);
        const requestMethod = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (requestUrl.endsWith('/api/v1/csrf') && requestMethod.toUpperCase() === 'GET' && response.ok) {
          const csrf = await response.clone().json();
          window.__clientAccessLatestCsrf = {headerName: csrf.headerName, token: csrf.token};
        }
        if (requestUrl.includes('/access-challenges') && !requestUrl.endsWith('/verify') && requestMethod.toUpperCase() === 'POST') {
          const requestHeaders = new Headers(args[1]?.headers ?? (args[0] instanceof Request ? args[0].headers : undefined));
          const result = await response.clone().json();
          const csrf = window.__clientAccessLatestCsrf;
          window.__clientAccessRequestAttempts.push({
            body: args[1]?.body ?? null,
            idempotencyKey: requestHeaders.get('Idempotency-Key'),
            status: response.status,
            challengeId: result.challengeId ?? null,
          });
          window.__clientAccessRequestResult = {
            status: response.status,
            challengeAccepted: result.challengeAccepted,
            hasChallengeId: Boolean(result.challengeId),
            errorCode: typeof result.error?.code === 'string' ? result.error.code : null,
            csrfHeaderPresent: Boolean(csrf && requestHeaders.has(csrf.headerName)),
            csrfMatchesLatestResponse: Boolean(csrf && requestHeaders.get(csrf.headerName) === csrf.token),
          };
          window.__clientAccessChallengeId = result.challengeId;
          if (response.ok && window.__clientAccessDropNextRequestResponse) {
            window.__clientAccessDropNextRequestResponse = false;
            window.__clientAccessRequestResponseLost = true;
            throw new TypeError('Simulated lost access request response');
          }
        }
        return response;
      };
    })()`);
    await setFieldById('client-phone', '+' + '1'.repeat(32), evaluate);
    await clickButtonByText('Solicitar código', evaluate);
    await waitForText('Telefone inválido.', evaluate);
    const invalidPhoneState = await evaluate(`(() => ({
      disabled: document.querySelector('#client-phone')?.disabled,
      retry: document.querySelector('button[type=submit]')?.textContent,
      errorCode: window.__clientAccessRequestResult?.errorCode,
      requestBodyLength: JSON.parse(window.__clientAccessRequestAttempts.at(-1)?.body ?? '{}').phone?.length,
    }))()`);
    if (invalidPhoneState.disabled || invalidPhoneState.errorCode !== 'INVALID_PHONE' || invalidPhoneState.requestBodyLength !== 33) {
        throw new Error(`Telefone inválido foi tratado como resultado incerto: ${JSON.stringify(invalidPhoneState)}`);
    }
    await evaluate('window.__clientAccessRequestResult = null; window.__clientAccessChallengeId = null');
    await setFieldById('client-phone', clientPhone, evaluate);
    await clickButtonByText('Solicitar código', evaluate);
    await waitForText('Mantenha os dados e repita a mesma solicitação', evaluate);
    await clickButtonByText('Repetir mesma solicitação', evaluate);
    await waitUntil(() => evaluate('window.__clientAccessRequestAttempts.length === 3'));
    const clientAccessRequestResult = await evaluate('window.__clientAccessRequestResult');
    if (!clientAccessRequestResult.hasChallengeId) {
        throw new Error(`Solicitação neutra sem challengeId para fixture E2E: HTTP ${clientAccessRequestResult.status}, accepted=${clientAccessRequestResult.challengeAccepted}, error=${clientAccessRequestResult.errorCode}, csrfHeader=${clientAccessRequestResult.csrfHeaderPresent}, csrfMatches=${clientAccessRequestResult.csrfMatchesLatestResponse}.`);
    }
    const requestRetryEvidence = await evaluate(`(() => ({
      responseLost: window.__clientAccessRequestResponseLost === true,
      attempts: window.__clientAccessRequestAttempts,
      challengeId: window.__clientAccessChallengeId,
    }))()`);
    if (!requestRetryEvidence.responseLost || requestRetryEvidence.attempts.length !== 3 ||
        requestRetryEvidence.attempts[1].body !== requestRetryEvidence.attempts[2].body ||
        requestRetryEvidence.attempts[1].idempotencyKey !== requestRetryEvidence.attempts[2].idempotencyKey ||
        requestRetryEvidence.attempts[1].challengeId !== requestRetryEvidence.attempts[2].challengeId ||
        requestRetryEvidence.attempts[1].challengeId !== requestRetryEvidence.challengeId) {
        throw new Error(`Retry após resposta perdida da solicitação mudou a intenção original: ${JSON.stringify(requestRetryEvidence)}`);
    }
    const expiredChallengeId = await evaluate('window.__clientAccessChallengeId');
    const emitterSecret = 'e2e-only-synthetic-emitter-secret-000000000000';
    const appPort = appEnvironment.APP_PORT || '8080';
    const expiredCodeResponse = await fetch(`http://localhost:${appPort}/internal/e2e/access-challenges/${expiredChallengeId}/code`, {
        headers: { 'X-E2E-Emitter-Secret': emitterSecret },
        cache: 'no-store',
    });
    if (!expiredCodeResponse.ok) throw new Error('Emissor privado E2E não retornou o código do desafio.');
    const expiredCode = (await expiredCodeResponse.json()).code;
    queryPostgres(
        `UPDATE agendamento.client_access_challenge SET expires_at = clock_timestamp() WHERE challenge_id = '${expiredChallengeId}'`,
        appEnvironment,
    );
    await setFieldById('client-code', expiredCode, evaluate);
    const clientAccessStorageSafe = await evaluate(`(() => {
      const storedValues = [localStorage, sessionStorage].flatMap(store => Array.from({length: store.length}, (_, index) => store.getItem(store.key(index))));
      const persisted = [location.href, JSON.stringify(history.state), ...storedValues].join('|');
      return !persisted.includes(${JSON.stringify(clientPhone)}) && !persisted.includes(${JSON.stringify(expiredCode)});
    })()`);
    if (!clientAccessStorageSafe) throw new Error('Telefone ou código apareceu em URL, histórico ou storage do navegador.');
    await clickButtonByText('Verificar código', evaluate);
    await waitForText('Código inválido, expirado ou temporariamente bloqueado.', evaluate);
    if (await cookies(command).then((items) => items.some((cookie) => cookie.name === 'AGDCLIENT'))) {
        throw new Error('Desafio expirado emitiu cookie de cliente.');
    }

    await clickButtonByText('Solicitar novo código', evaluate);
    await evaluate('window.__clientAccessChallengeId = null');
    await clickButtonByText('Solicitar código', evaluate);
    await waitUntil(() => evaluate('Boolean(window.__clientAccessChallengeId)'));
    const blockedChallengeId = await evaluate('window.__clientAccessChallengeId');
    const blockedCodeResponse = await fetch(`http://localhost:${appPort}/internal/e2e/access-challenges/${blockedChallengeId}/code`, {
        headers: { 'X-E2E-Emitter-Secret': emitterSecret },
        cache: 'no-store',
    });
    if (!blockedCodeResponse.ok) throw new Error('Emissor privado E2E não retornou o código para o teste de tentativas.');
    const blockedCode = (await blockedCodeResponse.json()).code;
    const wrongCode = blockedCode === '000000' ? '000001' : '000000';
    await setFieldById('client-code', wrongCode, evaluate);
    await clickButtonByText('Verificar código', evaluate);
    await waitForText('Código inválido, expirado ou temporariamente bloqueado.', evaluate);
    const csrf = await evaluate("fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'}).then(response => response.json())");
    const lockoutStatuses = await evaluate(`(async () => {
      const statuses = [];
      for (let attempt = 0; attempt < 4; attempt++) {
        const response = await fetch(${JSON.stringify(`/api/v1/public/${demoTenantSlug}/access-challenges/${blockedChallengeId}/verify`)}, {
          method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: {'Content-Type':'application/json', [${JSON.stringify(csrf.headerName)}]:${JSON.stringify(csrf.token)}},
          body: JSON.stringify({code:${JSON.stringify(wrongCode)}})
        });
        statuses.push(response.status);
      }
      return statuses;
    })()`);
    if (lockoutStatuses.some((status) => status !== 401)) {
        throw new Error(`As tentativas inválidas não produziram o bloqueio esperado: ${JSON.stringify(lockoutStatuses)}`);
    }
    const challengeStatus = queryPostgres(
        `SELECT status FROM agendamento.client_access_challenge WHERE challenge_id = '${blockedChallengeId}'`,
        appEnvironment,
    );
    if (challengeStatus !== 'BLOCKED') throw new Error('Quinto erro não bloqueou o desafio no PostgreSQL.');

    await clickButtonByText('Solicitar novo código', evaluate);
    await evaluate('window.__clientAccessChallengeId = null');
    await clickButtonByText('Solicitar código', evaluate);
    await waitUntil(() => evaluate('Boolean(window.__clientAccessChallengeId)'));
    const validChallengeId = await evaluate('window.__clientAccessChallengeId');
    const validCodeResponse = await fetch(`http://localhost:${appPort}/internal/e2e/access-challenges/${validChallengeId}/code`, {
        headers: { 'X-E2E-Emitter-Secret': emitterSecret },
        cache: 'no-store',
    });
    if (!validCodeResponse.ok) throw new Error('Emissor privado E2E não retornou código para verificação válida.');
    const validCode = (await validCodeResponse.json()).code;
    await setFieldById('client-code', validCode, evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__clientVerifyAttempts = [];
      window.__clientRecoveryAttempts = [];
      window.fetch = async (...args) => {
        const requestUrl = args[0] instanceof Request ? args[0].url : String(args[0]);
        const requestMethod = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (requestUrl.startsWith('/api/v1/client/session?slug=') && requestMethod.toUpperCase() === 'GET') {
          window.__clientRecoveryAttempts.push('attempted');
          if (window.__clientRecoveryAttempts.length === 1) throw new TypeError('Simulated recovery transport failure');
          if (window.__clientRecoveryAttempts.length === 2) {
            return new Response(JSON.stringify({error: {code: 'TEMPORARILY_UNAVAILABLE'}}), {
              status: 503, headers: {'Content-Type': 'application/json'},
            });
          }
          if (window.__clientRecoveryAttempts.length === 3) {
            return new Response('{invalid-json', {status: 200, headers: {'Content-Type': 'application/json'}});
          }
        }
        const response = await originalFetch(...args);
        if (requestUrl.endsWith('/verify') && requestMethod.toUpperCase() === 'POST') {
          window.__clientVerifyAttempts.push({url: String(args[0]), body: args[1]?.body ?? null});
          if (response.ok && !window.__clientVerifyResponseLost) {
            window.__clientVerifyResponseLost = true;
            throw new TypeError('Simulated lost verification response after session commit');
          }
        }
        return response;
      };
    })()`);
    await clickButtonByText('Verificar código', evaluate);
    await waitForText('Não foi possível confirmar o resultado. Mantenha o mesmo código', evaluate);
    await clickButtonByText('Tentar novamente', evaluate);
    await waitForText('A verificação ainda não pôde ser reconciliada.', evaluate);
    const uncertainAfterRecoveryNetwork = await evaluate(`({
      verifyButton: [...document.querySelectorAll('button')].find(button => button.innerText === 'Tentar novamente')?.disabled,
      pendingMessage: document.body.innerText.includes('A verificação ainda não pôde ser reconciliada.'),
    })`);
    if (uncertainAfterRecoveryNetwork.verifyButton || !uncertainAfterRecoveryNetwork.pendingMessage) {
        throw new Error(`Falha de transporte na recuperação foi tratada como definitiva: ${JSON.stringify(uncertainAfterRecoveryNetwork)}`);
    }
    await clickButtonByText('Tentar novamente', evaluate);
    await waitForText('A verificação ainda não pôde ser reconciliada.', evaluate);
    const uncertainAfterRecovery503 = await evaluate(`({
      verifyButton: [...document.querySelectorAll('button')].find(button => button.innerText === 'Tentar novamente')?.disabled,
      pendingMessage: document.body.innerText.includes('A verificação ainda não pôde ser reconciliada.'),
      recoveryAttempts: window.__clientRecoveryAttempts.length,
    })`);
    if (uncertainAfterRecovery503.verifyButton || !uncertainAfterRecovery503.pendingMessage || uncertainAfterRecovery503.recoveryAttempts !== 2) {
        throw new Error(`HTTP 503 na recuperação foi tratado como definitiva: ${JSON.stringify(uncertainAfterRecovery503)}`);
    }
    await clickButtonByText('Tentar novamente', evaluate);
    await waitForText('A resposta de recuperação é inválida.', evaluate);
    await clickButtonByText('Tentar novamente', evaluate);
    await waitForText('Suas reservas', evaluate);
    if (!(await evaluate('window.__clientVerifyResponseLost === true'))) {
        throw new Error('A resposta perdida da verificação não foi simulada.');
    }
    const clientCookie = (await cookies(command)).find((cookie) => cookie.name === 'AGDCLIENT');
    if (!clientCookie || !clientCookie.httpOnly || clientCookie.sameSite !== 'Lax' || clientCookie.path !== '/api/v1/client') {
        throw new Error('Cookie de cliente não tem escopo e atributos esperados.');
    }
    const clientSession = await evaluate("fetch('/api/v1/client/session', {credentials:'same-origin', cache:'no-store'}).then(response => response.json())");
    const boundRecovery = await evaluate(`fetch('/api/v1/client/session?slug=' + encodeURIComponent(${JSON.stringify(demoTenantSlug)}) + '&challengeId=' + encodeURIComponent(${JSON.stringify(validChallengeId)}), {credentials:'same-origin', cache:'no-store'}).then(response => response.json())`);
    const adminSessionStatus = await evaluate("fetch('/api/v1/admin/session', {credentials:'same-origin', cache:'no-store'}).then(response => response.status)");
    const verificationRetry = await evaluate('window.__clientVerifyAttempts');
    const recoveryAttempts = await evaluate('window.__clientRecoveryAttempts.length');
    const verificationIntentPreserved = verificationRetry.every(
      attempt => attempt.url === verificationRetry[0]?.url && attempt.body === verificationRetry[0]?.body,
    );
    if (!clientSession.authenticated || !boundRecovery.authenticated || adminSessionStatus !== 401 ||
        verificationRetry.length !== 5 || !verificationIntentPreserved || recoveryAttempts !== 5) {
        throw new Error(`Recuperação de cliente divergiu: client=${clientSession.authenticated}, bound=${boundRecovery.authenticated}, adminStatus=${adminSessionStatus}, verifyAttempts=${verificationRetry.length}, sameIntent=${verificationIntentPreserved}, recoveryAttempts=${recoveryAttempts}.`);
    }
    const clientEffects = queryPostgres(
        `SELECT count(*) FROM agendamento.client_access_session WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`,
        appEnvironment,
    );
    const consumedChallengeStatus = queryPostgres(
        `SELECT status FROM agendamento.client_access_challenge WHERE challenge_id = '${validChallengeId}'`,
        appEnvironment,
    );
    if (clientEffects !== '1' || consumedChallengeStatus !== 'VERIFIED') {
        throw new Error('Retry de verificação duplicou a sessão ou não consumiu o challenge uma única vez.');
    }

    await waitForText('Suas reservas', evaluate);
    const ownedBookingId = queryPostgres(
        `SELECT a.appointment_id FROM agendamento.appointment a JOIN agendamento.client c USING (tenant_id, client_id) WHERE a.tenant_id = '${credentials.DEMO_TENANT_ID}' AND c.normalized_phone = '${clientPhone}' AND a.status = 'CONFIRMED' ORDER BY a.created_at DESC LIMIT 1`,
        appEnvironment,
    );
    if (!ownedBookingId) throw new Error('Reserva pública persistida não foi vinculada ao telefone verificado.');
    await waitUntil(() => evaluate(`Boolean(document.querySelector('li[data-appointment-id="${ownedBookingId}"]'))`));
    await evaluate(`(() => {
      window.__clientListOriginalFetch = window.fetch.bind(window);
      window.__releaseClientPage = null;
      window.__releaseClientRefresh = null;
      window.__holdClientPage = false;
      window.__holdClientRefresh = false;
      window.fetch = async (...args) => {
        const url = args[0] instanceof Request ? args[0].url : String(args[0]);
        const method = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (new URL(url, window.location.href).pathname !== '/api/v1/client/appointments' || method.toUpperCase() !== 'GET') {
          return window.__clientListOriginalFetch(...args);
        }
        if (url.includes('cursor=') && window.__holdClientPage) {
          window.__holdClientPage = false;
          window.__clientPagePending = true;
          await new Promise(resolve => { window.__releaseClientPage = resolve; });
          return new Response(JSON.stringify({items:[], nextCursor:'e2e-next-cursor', hasMore:true}), {status:200, headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
        }
        const response = await window.__clientListOriginalFetch(...args);
        if (!url.includes('cursor=') && window.__holdClientRefresh) {
          window.__holdClientRefresh = false;
          window.__clientRefreshPending = true;
          await new Promise(resolve => { window.__releaseClientRefresh = resolve; });
        }
        if (!url.includes('cursor=') && window.__exposeClientNextPage) {
          window.__exposeClientNextPage = false;
          const page = await response.json();
          return new Response(JSON.stringify({...page, nextCursor:'e2e-controlled-cursor', hasMore:true}), {status:200, headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
        }
        return response;
      };
      window.__exposeClientNextPage = true;
    })()`);
    await waitUntil(() => evaluate("Boolean([...document.querySelectorAll('button')].find(button => button.innerText === 'Atualizar reservas' && !button.disabled))"));
    await clickButtonByText('Atualizar reservas', evaluate);
    await waitUntil(() => evaluate("[...document.querySelectorAll('button')].some(button => button.innerText === 'Carregar mais')"));
    await evaluate("window.__holdClientPage = true");
    await clickButtonByText('Carregar mais', evaluate);
    await waitUntil(() => evaluate('window.__clientPagePending === true'));
    await clickButtonByText('Ver detalhes', evaluate);
    await waitForText('Detalhes da reserva', evaluate);
    await evaluate('window.__releaseClientPage()');
    await waitUntil(() => evaluate("Boolean([...document.querySelectorAll('button')].find(button => button.innerText === 'Carregar mais' && !button.disabled))"));
    const paginationAfterDetails = await evaluate(`({
      details: document.body.innerText.includes('Detalhes da reserva'),
      loading: [...document.querySelectorAll('output')].some(node => node.innerText.includes('Carregando…')),
      loadMoreDisabled: [...document.querySelectorAll('button')].find(button => button.innerText === 'Carregar mais')?.disabled,
    })`);
    if (!paginationAfterDetails.details || paginationAfterDetails.loading || paginationAfterDetails.loadMoreDisabled) {
      throw new Error(`Abrir detalhes deixou a paginação presa após resposta tardia: ${JSON.stringify(paginationAfterDetails)}`);
    }
    await evaluate("window.__holdClientRefresh = true; window.__releaseClientRefresh = null");
    await clickButtonByText('Atualizar reservas', evaluate);
    await waitUntil(() => evaluate('window.__clientRefreshPending === true'));
    await clickButtonByText('Ver detalhes', evaluate);
    await waitForText('Detalhes da reserva', evaluate);
    await evaluate('window.__releaseClientRefresh()');
    await waitUntil(() => evaluate("![...document.querySelectorAll('output')].some(node => node.innerText.includes('Atualizando reservas…'))"));
    const refreshAfterDetails = await evaluate(`({
      details: document.body.innerText.includes('Detalhes da reserva'),
      loading: [...document.querySelectorAll('output')].some(node => node.innerText.includes('Atualizando reservas…')),
      refreshEnabled: ![...document.querySelectorAll('button')].find(button => button.innerText === 'Atualizar reservas')?.disabled,
    })`);
    if (!refreshAfterDetails.details || refreshAfterDetails.loading || !refreshAfterDetails.refreshEnabled) {
      throw new Error(`Abrir detalhes deixou refresh da lista preso: ${JSON.stringify(refreshAfterDetails)}`);
    }
    await evaluate('window.fetch = window.__clientListOriginalFetch');
    console.log('Browser: paginação e refresh da lista permaneceram utilizáveis após abrir detalhes com respostas controladamente pendentes.');
    const listedBooking = await evaluate(`fetch('/api/v1/client/appointments', {credentials:'same-origin', cache:'no-store'}).then(response => response.json()).then(page => page.items.find(item => item.appointmentId === '${ownedBookingId}'))`);
    if (listedBooking?.version !== 1) throw new Error(`A lista deveria manter a versão 1 antes da atualização do detalhe: ${JSON.stringify(listedBooking)}`);
    const detailVersionFixture = queryPostgres(
        `UPDATE agendamento.appointment SET version = 2 WHERE appointment_id = '${ownedBookingId}' AND version = 1 RETURNING version`,
        appEnvironment,
    );
    if (detailVersionFixture !== '2') throw new Error('A fixture concorrente não avançou a versão da reserva para 2.');
    await clickButtonByText('Ver detalhes', evaluate);
    await waitUntil(() => evaluate("document.body.innerText.includes('Versão: 2')"));
    const detailVersionDom = await evaluate(`({
      detailVersion: [...document.querySelectorAll('section')].find(section => section.innerText.includes('Detalhes da reserva'))?.innerText.match(/Versão:\\s*(\\d+)/)?.[1] ?? null,
    })`);
    if (detailVersionDom.detailVersion !== '2' || listedBooking.version !== 1) {
      throw new Error(`A regressão não reproduziu lista v1 versus detalhe v2: lista=${listedBooking.version}, detalhe=${JSON.stringify(detailVersionDom)}`);
    }
    await clickButtonByText('Cancelar esta reserva', evaluate);
    await evaluate(`(() => {
      window.__clientCancellationAttempts = [];
      const originalFetch = window.fetch.bind(window);
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const url = args[0] instanceof Request ? args[0].url : String(args[0]);
        const method = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (url.endsWith('/api/v1/client/appointments/${ownedBookingId}/cancel') && method.toUpperCase() === 'POST') {
          const headers = new Headers(args[1]?.headers ?? (args[0] instanceof Request ? args[0].headers : undefined));
          window.__clientCancellationAttempts.push({url, body: args[1]?.body ?? null, key: headers.get('Idempotency-Key'), status: response.status});
          if (response.ok && window.__dropClientCancellationResponse) {
            window.__dropClientCancellationResponse = false;
            throw new TypeError('Simulated lost client cancellation response after commit');
          }
        }
        return response;
      };
      window.__dropClientCancellationResponse = true;
    })()`);
    await clickButtonByText('Confirmar cancelamento', evaluate);
    await waitForText('Não foi possível confirmar o resultado.', evaluate);
    await waitUntil(() =>
        evaluate(
            "Boolean([...document.querySelectorAll('button')].find(button => button.innerText === 'Repetir a mesma tentativa' && !button.disabled))",
        ),
    );
    const cancellationPendingDom = await evaluate(`({
      message: [...document.querySelectorAll('output[aria-live="polite"], [role=status]')].map(node => node.innerText).join(' '),
      retry: [...document.querySelectorAll('button')].find(button => button.innerText === 'Repetir a mesma tentativa')?.disabled,
      attempts: window.__clientCancellationAttempts,
    })`);
    if (!cancellationPendingDom.message.includes('mesma chave') || cancellationPendingDom.retry) {
      throw new Error('A UI não preservou a intenção de cancelamento após perda da resposta.');
    }
    await clickButtonByText('Repetir a mesma tentativa', evaluate);
    await waitForText('Reserva cancelada.', evaluate);
    const cancellationRetry = await evaluate('window.__clientCancellationAttempts');
    if (cancellationRetry.length !== 2 || cancellationRetry[0].url !== cancellationRetry[1].url ||
        cancellationRetry[0].body !== cancellationRetry[1].body || cancellationRetry[0].key !== cancellationRetry[1].key ||
        !cancellationRetry[0].key || cancellationRetry[0].body !== JSON.stringify({expectedVersion:2}) ||
        cancellationRetry[0].status !== 200 || cancellationRetry[1].status !== 200) {
      throw new Error(`Retry do cancelamento mudou a intenção original: ${JSON.stringify(cancellationRetry)}`);
    }
    const cancellationEffects = queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'CANCELLED_BY_CLIENT' AND version = 3 FROM agendamento.appointment WHERE appointment_id = '${ownedBookingId}') AND (SELECT count(*) = 2 FROM agendamento.appointment_history WHERE appointment_id = '${ownedBookingId}') AND (SELECT count(*) = 1 FROM agendamento.audit_event WHERE target_id = '${ownedBookingId}' AND action = 'APPOINTMENT_CANCELLED_BY_CLIENT') AND (SELECT count(*) = 1 FROM agendamento.outbox_event WHERE aggregate_id = '${ownedBookingId}' AND event_type = 'AppointmentCancelled') AND (SELECT count(*) = 1 FROM agendamento.idempotency_record i WHERE i.command_scope LIKE 'appointments.client.cancel.%' AND i.state = 'COMPLETED' AND i.operation_id = (SELECT operation_id FROM agendamento.outbox_event WHERE aggregate_id = '${ownedBookingId}' AND event_type = 'AppointmentCancelled')) THEN 1 ELSE 0 END`,
        appEnvironment,
    );
    if (cancellationEffects !== '1') throw new Error('Cancelamento/replay não produziu um único conjunto transacional de efeitos.');
    await command('Page.reload');
    await waitForText('Suas reservas', evaluate);
    await waitForText('CANCELLED_BY_CLIENT', evaluate);
    const restoredCancellation = await evaluate(`({
      status: document.querySelector('li[data-appointment-id="${ownedBookingId}"]')?.innerText,
      hasCancelAction: [...(document.querySelector('li[data-appointment-id="${ownedBookingId}"]')?.querySelectorAll('button') ?? [])].some(button => button.innerText === 'Cancelar'),
      hasPrivateDataInUrl: location.href.includes('${clientPhone}') || location.href.includes('${ownedBookingId}'),
    })`);
    if (!restoredCancellation.status?.includes('CANCELLED_BY_CLIENT') || restoredCancellation.hasCancelAction || restoredCancellation.hasPrivateDataInUrl) {
      throw new Error(`Reload não recuperou o estado terminal com segurança: ${JSON.stringify(restoredCancellation)}`);
    }
    console.log('Browser: área autenticada listou reserva pública persistida, cancelou após cutoff permitido, recuperou resposta perdida com a mesma intenção, recarregou o estado terminal; PostgreSQL confirmou efeitos únicos.');

    const differentPhone = '+5511999991133';
    queryPostgres(
        `INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES ('${randomUUID()}', '${credentials.DEMO_TENANT_ID}', 'Cliente E2E outro telefone', '${differentPhone}')`,
        appEnvironment,
    );
    await navigate(`http://localhost:5173/client-access/${encodeURIComponent(demoTenantSlug)}`, command, evaluate);
    await waitForText('Suas reservas', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__oldSessionVerifyResponseLost = false;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const requestUrl = args[0] instanceof Request ? args[0].url : String(args[0]);
        const requestMethod = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (requestUrl.endsWith('/verify') && requestMethod.toUpperCase() === 'POST' && response.status === 401 && !window.__oldSessionVerifyResponseLost) {
          window.__oldSessionVerifyResponseLost = true;
          throw new TypeError('Simulated lost rejected verification response');
        }
        return response;
      };
    })()`);
    const challenge = await evaluate(`(async () => {
      const csrf = await fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'}).then(response => response.json());
      const response = await fetch('/api/v1/public/${encodeURIComponent(demoTenantSlug)}/access-challenges', {
        method:'POST', credentials:'same-origin', cache:'no-store',
        headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':${JSON.stringify(randomUUID())}},
        body:JSON.stringify({phone:${JSON.stringify(differentPhone)}}),
      });
      if (!response.ok) throw new Error('Challenge do telefone diferente foi rejeitado: ' + response.status);
      return response.json();
    })()`);
    const rejectedChallengeId = challenge.challengeId;
    if (!rejectedChallengeId) throw new Error('E2E não recebeu challenge para testar sessão antiga.');
    const rejectedCodeResponse = await fetch(`http://localhost:${appPort}/internal/e2e/access-challenges/${rejectedChallengeId}/code`, {
        headers: { 'X-E2E-Emitter-Secret': emitterSecret },
        cache: 'no-store',
    });
    if (!rejectedCodeResponse.ok) throw new Error('Emissor E2E não retornou código para o challenge de sessão antiga.');
    const rejectedCode = (await rejectedCodeResponse.json()).code;
    const rejectedWrongCode = rejectedCode === '000000' ? '000001' : '000000';
    const rejectedVerification = await evaluate(`(async () => {
      const csrf = await fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'}).then(response => response.json());
      const url = '/api/v1/public/${encodeURIComponent(demoTenantSlug)}/access-challenges/${rejectedChallengeId}/verify';
      const options = {method:'POST', credentials:'same-origin', cache:'no-store', headers:{'Content-Type':'application/json', [csrf.headerName]:csrf.token}, body:JSON.stringify({code:${JSON.stringify(rejectedWrongCode)}})};
      let responseLost = false;
      try { await fetch(url, options); } catch { responseLost = true; }
      const retry = await fetch(url, options);
      const sessionForChallenge = await fetch('/api/v1/client/session?slug=${encodeURIComponent(demoTenantSlug)}&challengeId=${rejectedChallengeId}', {credentials:'same-origin', cache:'no-store'}).then(result => result.json());
      const previousSession = await fetch('/api/v1/client/session', {credentials:'same-origin', cache:'no-store'}).then(result => result.json());
      return {responseLost, retryStatus:retry.status, sessionForChallenge, previousSession};
    })()`);
    const oldSessionDom = await evaluate(`(async () => ({
      rejectedResponseLost: window.__oldSessionVerifyResponseLost,
      clientAreaVisible: document.body.innerText.includes('Suas reservas'),
    }))()`);
    if (!oldSessionDom.rejectedResponseLost || !oldSessionDom.clientAreaVisible ||
        rejectedVerification.retryStatus !== 401 || rejectedVerification.sessionForChallenge.authenticated ||
        !rejectedVerification.previousSession.authenticated) {
        throw new Error(`Sessão anterior confirmou indevidamente challenge de outro telefone: ${JSON.stringify({oldSessionDom, rejectedVerification})}`);
    }
    const sessionRowsAfterRejectedRecovery = queryPostgres(
        `SELECT count(*) FROM agendamento.client_access_session WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`,
        appEnvironment,
    );
    if (sessionRowsAfterRejectedRecovery !== '1') throw new Error('Challenge rejeitado criou sessão adicional.');

    await waitUntil(() => evaluate("Boolean(document.getElementById('client-appointment-filter'))"));
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__clientListJsonPending = false;
      window.__clientListDelayIntercepted = false;
      window.__releaseClientListJson = null;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const requestUrl = args[0] instanceof Request ? args[0].url : String(args[0]);
        const requestMethod = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (requestUrl.includes('/api/v1/client/appointments?') && requestMethod.toUpperCase() === 'GET' && !window.__clientListDelayIntercepted) {
          window.__clientListDelayIntercepted = true;
          return new Proxy(response, {get(target, property) {
            if (property === 'json') return () => new Promise((resolve, reject) => {
              window.__clientListJsonPending = true;
              window.__releaseClientListJson = () => target.json().then(resolve, reject);
            });
            return Reflect.get(target, property, target);
          }});
        }
        return response;
      };
    })()`);
    await setSelectById('client-appointment-filter', 'all', evaluate);
    await waitUntil(() => evaluate('window.__clientListJsonPending === true'));
    await clickButtonByText('Sair', evaluate);
    await waitForText('Sessão encerrada.', evaluate);
    await evaluate('window.__releaseClientListJson?.()');
    await waitUntil(() => evaluate("!document.body.innerText.includes('Suas reservas')"));
    const privateStateAfterDelayedResponse = await evaluate(`({
      clientAreaVisible: document.body.innerText.includes('Suas reservas'),
      appointmentVisible: Boolean(document.querySelector('li[data-appointment-id="${ownedBookingId}"]')),
      sessionEnded: document.body.innerText.includes('Sessão encerrada.'),
    })`);
    if (privateStateAfterDelayedResponse.clientAreaVisible || privateStateAfterDelayedResponse.appointmentVisible || !privateStateAfterDelayedResponse.sessionEnded) {
      throw new Error(`Resposta atrasada restaurou dados privados após logout: ${JSON.stringify(privateStateAfterDelayedResponse)}`);
    }
    const loggedOutClient = await evaluate("fetch('/api/v1/client/session', {credentials:'same-origin', cache:'no-store'}).then(response => response.json())");
    if (loggedOutClient.authenticated) throw new Error('Logout não revogou a sessão do cliente.');
    console.log('Browser: área consultou/cancelou a reserva própria, recuperou resposta perdida, limpou sessão no logout mesmo com lista atrasada; emissor privado também comprovou limites, expiração e isolamento.');

    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('username', evaluate, true);
    await submitLogin(credentials.KEYCLOAK_DEMO_USERNAME, credentials.KEYCLOAK_DEMO_PASSWORD, evaluate);
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitForText('Plano e período de avaliação', evaluate);
    await waitForText('Trial ativo', evaluate);
    const activeTrial = await evaluate(
        "fetch('/api/v1/admin/subscription', {credentials:'same-origin', cache:'no-store'}).then(response => response.json()).then(body => body.subscription)",
    );
    if (activeTrial?.status !== 'TRIALING' || activeTrial.version !== 1) {
        throw new Error('Trial não persistiu como TRIALING após novo login.');
    }
    queryPostgres(
        `WITH expiry AS (SELECT clock_timestamp() - INTERVAL '1 second' AS trial_ends_at) UPDATE agendamento.subscription AS subscription SET trial_ends_at = expiry.trial_ends_at, trial_started_at = expiry.trial_ends_at - INTERVAL '336 hours' FROM expiry WHERE subscription.subscription_id = '${activeTrial.subscriptionId}'`,
        appEnvironment,
    );
    await command('Page.reload');
    await waitForText('Trial encerrado', evaluate);
    const expiredTrial = await evaluate(
        "fetch('/api/v1/admin/subscription', {credentials:'same-origin', cache:'no-store'}).then(async response => ({status:response.status, body:await response.json()}))",
    );
    if (
        expiredTrial.status !== 200 ||
        expiredTrial.body.subscription?.status !== 'SUSPENDED' ||
        expiredTrial.body.subscription?.version !== 2
    ) {
        throw new Error('Expiração não materializou SUSPENDED/v2 após now >= trialEndsAt.');
    }
    const expiredAvailability = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${publicDate}&to=${publicDate}', {cache:'no-store'}).then(response => response.status)`,
    );
    if (expiredAvailability !== 404) {
        throw new Error(`Trial encerrado ainda anunciou disponibilidade pública (HTTP ${expiredAvailability}).`);
    }
    const gatedBookingCountBefore = Number(
        queryPostgres(
            `SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}'`,
            appEnvironment,
        ),
    );
    const deniedNewBooking = await evaluate(`(async () => {
      const csrfResponse = await fetch('/api/v1/csrf', {credentials:'same-origin', cache:'no-store'});
      const csrf = await csrfResponse.json();
      const bytes = crypto.getRandomValues(new Uint8Array(32));
      const recovery = btoa(String.fromCharCode(...bytes)).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
      const key = 'trial-expired-booking-' + crypto.randomUUID();
      const headers = {'Content-Type':'application/json', [csrf.headerName]:csrf.token, 'Idempotency-Key':key, 'X-Booking-Recovery':recovery};
      const body = ${JSON.stringify({
          serviceId: publicService.serviceId,
          startsAt: postTrialBookingCandidate.startsAt,
          client: {name: 'Visitante de demonstração', phone: '+5511999995555'},
      })};
      const intent = await fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/booking-intents/current', {method:'POST', credentials:'same-origin', headers, body:JSON.stringify(body)});
      if (intent.status !== 204) return {stage:'intent', status:intent.status};
      const response = await fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/appointments', {method:'POST', credentials:'same-origin', headers, body:JSON.stringify(body)});
      return {stage:'appointment', status:response.status};
    })()`);
    const gatedBookingCountAfter = Number(
        queryPostgres(
            `SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}'`,
            appEnvironment,
        ),
    );
    if (
        deniedNewBooking.stage !== 'appointment' ||
        deniedNewBooking.status !== 404 ||
        gatedBookingCountAfter !== gatedBookingCountBefore
    ) {
        throw new Error(`Gate comercial não bloqueou atomicamente uma nova reserva após expiração: ${JSON.stringify({deniedNewBooking, gatedBookingCountBefore, gatedBookingCountAfter})}`);
    }
    const existingAppointmentResponse = await evaluate(
        `fetch('/api/v1/admin/appointments/${ownedBookingId}', {credentials:'same-origin', cache:'no-store'}).then(async response => ({status:response.status, body:await response.json()}))`,
    );
    if (
        existingAppointmentResponse.status !== 200 ||
        existingAppointmentResponse.body.appointmentId !== ownedBookingId
    ) {
        throw new Error('Expiração do trial impediu a consulta administrativa da reserva existente.');
    }
    console.log('Browser: trial persistido atravessou reload; vencimento foi materializado em SUSPENDED/v2, bloqueou disponibilidade nova e preservou consulta da reserva existente.');

    await waitForText('Selecionar plano', evaluate);
    const checkoutIntentStorageKey = `agd041.checkout-intent.v2:${authenticatedSession.tenantId}`;
    await clickButtonByText('Selecionar plano', evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__checkoutPostCount = 0;
      window.__csrfHeld = false;
      window.__csrfReleased = false;
      window.__releaseHeldCsrf = null;
      window.fetch = async (input, init) => {
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        if (url.pathname === '/api/v1/admin/subscription/checkout') window.__checkoutPostCount++;
        if (url.pathname === '/api/v1/csrf' && !window.__csrfHeld) {
          window.__csrfHeld = true;
          return await new Promise(resolve => {
            window.__releaseHeldCsrf = () => {
              originalFetch(input, init).then(response => {
                window.__csrfReleased = true;
                resolve(response);
              });
            };
          });
        }
        return originalFetch(input, init);
      };
    })()`);
    const intentBeforeContextChange = await evaluate(`JSON.parse(sessionStorage.getItem(${JSON.stringify(checkoutIntentStorageKey)}))`);
    await clickButtonByText('Iniciar ou recuperar a mesma operação', evaluate);
    await waitUntil(async () => await evaluate('window.__csrfHeld === true'));
    await clickButtonByText('Sair', evaluate);
    await waitForText('Entrar como administrador', evaluate);
    await evaluate('window.__releaseHeldCsrf()');
    await waitUntil(async () => await evaluate('window.__csrfReleased === true'));
    const checkoutAfterContextChange = await evaluate(`({
      postCount: window.__checkoutPostCount,
      intent: JSON.parse(sessionStorage.getItem(${JSON.stringify(checkoutIntentStorageKey)})),
    })`);
    if (
        checkoutAfterContextChange.postCount !== 0 ||
        checkoutAfterContextChange.intent?.key !== intentBeforeContextChange.key ||
        JSON.stringify(checkoutAfterContextChange.intent?.body) !== JSON.stringify(intentBeforeContextChange.body)
    ) {
        throw new Error(`A troca de contexto durante CSRF enviou checkout ou perdeu a intenção original: ${JSON.stringify(checkoutAfterContextChange)}`);
    }
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitUntil(async () =>
        evaluate(
            "Boolean(document.querySelector('input[name=\"username\"]')) || document.body.innerText.includes('Sessão administrativa ativa.')",
        ),
    );
    if (await evaluate("Boolean(document.querySelector('input[name=\"username\"]'))")) {
        await submitLogin(credentials.KEYCLOAK_DEMO_USERNAME, credentials.KEYCLOAK_DEMO_PASSWORD, evaluate);
    }
    await waitForText('Sessão administrativa ativa.', evaluate);
    await waitForText('Operação preparada: MONTHLY', evaluate);
    const checkoutRetryScript = await command('Page.addScriptToEvaluateOnNewDocument', {
        source: `(() => {
          const originalFetch = window.fetch.bind(window);
          window.__checkoutRequests = [];
          window.fetch = async (input, init) => {
            const url = new URL(typeof input === 'string' ? input : input.url, location.href);
            if (url.pathname !== '/api/v1/admin/subscription/checkout') return originalFetch(input, init);
            const headers = new Headers(init?.headers || (input instanceof Request ? input.headers : undefined));
            window.__checkoutRequests.push({key:headers.get('Idempotency-Key'), context:headers.get('X-Expected-Tenant-Context'), body:init?.body || ''});
            const response = await originalFetch(input, init);
            if (window.__checkoutRequests.length === 1 && response.ok) {
              return new Response(JSON.stringify({error:{code:'GATEWAY_TIMEOUT'}}), {status:408, headers:{'Content-Type':'application/json'}});
            }
            return response;
          };
        })();`,
    });
    await command('Page.reload', { ignoreCache: true });
    await waitForText('Trial encerrado', evaluate);
    await waitForText('Operação preparada: MONTHLY', evaluate);
    await clickButtonByText('Iniciar ou recuperar a mesma operação', evaluate);
    await waitForText('Resultado ainda incerto.', evaluate);
    const pendingCheckoutIntent = await evaluate(`({
      requests: window.__checkoutRequests,
      intent: document.querySelector('[aria-label="Operação de checkout pendente"]')?.innerText,
      stored: sessionStorage.getItem(${JSON.stringify(checkoutIntentStorageKey)}),
      discardDisabled: [...document.querySelectorAll('[aria-label="Operação de checkout pendente"] button')].find(button => button.innerText.includes('Descartar'))?.disabled,
    })`);
    if (pendingCheckoutIntent.requests?.length !== 1 || !pendingCheckoutIntent.intent || !pendingCheckoutIntent.stored || pendingCheckoutIntent.discardDisabled !== true) {
        throw new Error(`Intenção de checkout não foi preservada após perda de resposta: ${JSON.stringify(pendingCheckoutIntent)}`);
    }
    await clickButtonByText('Iniciar ou recuperar a mesma operação', evaluate);
    await waitForText('Sessão de checkout hospedado criada.', evaluate);
    const checkoutReplay = await evaluate(`({
      requests: window.__checkoutRequests,
      result: document.querySelector('[aria-label="Resultado do checkout"]')?.innerText,
      intent: sessionStorage.getItem(${JSON.stringify(checkoutIntentStorageKey)}),
    })`);
    if (
        checkoutReplay.requests?.length !== 2 ||
        checkoutReplay.requests[0].key !== checkoutReplay.requests[1].key ||
        checkoutReplay.requests[0].context !== authenticatedSession.tenantId ||
        checkoutReplay.requests[1].context !== authenticatedSession.tenantId ||
        checkoutReplay.requests[0].body !== checkoutReplay.requests[1].body ||
        checkoutReplay.intent !== null ||
        !checkoutReplay.result?.includes('CHECKOUT_READY')
    ) {
        throw new Error(`Retry não recuperou a mesma operação de checkout: ${JSON.stringify(checkoutReplay)}`);
    }
    await command('Page.removeScriptToEvaluateOnNewDocument', { identifier: checkoutRetryScript.identifier });
    const checkoutOperationId = checkoutReplay.result.match(/Operação ([0-9a-f-]{36})/i)?.[1];
    if (!checkoutOperationId) throw new Error('ID lógico do checkout não apareceu no DOM.');
    await command('Page.reload');
    await waitForText('Operações existentes', evaluate);
    const recoverCheckoutButton = `Recuperar operação ${checkoutOperationId}`;
    await waitForButtonByText(recoverCheckoutButton, evaluate);
    await clickButtonByText(recoverCheckoutButton, evaluate);
    await waitForText('Operação recuperada.', evaluate);
    const recoveredAfterReload = await evaluate(`({
      result: document.querySelector('[aria-label="Resultado do checkout"]')?.innerText,
      link: document.querySelector('[aria-label="Resultado do checkout"] a')?.href,
    })`);
    if (!recoveredAfterReload.result?.includes(checkoutOperationId) || !recoveredAfterReload.link?.startsWith('https://')) {
        throw new Error(`Checkout hospedado não foi recuperado após reload: ${JSON.stringify(recoveredAfterReload)}`);
    }
    const invalidEventBody = JSON.stringify({eventId: `invalid-${checkoutOperationId}`, reference: `e2e-${checkoutOperationId}`, sequence: 1, outcome: 'CONFIRMED', amountMinorUnits: 1, currency: 'XTS', occurredAt: new Date().toISOString(), paidThroughAt: new Date(Date.now() + 30 * 86400000).toISOString()});
    const invalidEventResponse = await fetch(`http://localhost:${appEnvironment.APP_PORT || 8080}/api/v1/webhooks/payments/e2e`, {
        method: 'POST', headers: {'Content-Type': 'application/json', 'X-Payment-Signature': 'sha256=invalid'}, body: invalidEventBody,
    });
    if (invalidEventResponse.status !== 401) throw new Error(`Webhook inválido não foi rejeitado: HTTP ${invalidEventResponse.status}`);
    const invalidEventRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_event_inbox WHERE provider_event_id = 'invalid-${checkoutOperationId}'`, appEnvironment));
    const preConfirmationStatus = queryPostgres(`SELECT status FROM agendamento.subscription WHERE subscription_id = '${activeTrial.subscriptionId}'`, appEnvironment);
    if (invalidEventRows !== 0 || preConfirmationStatus !== 'SUSPENDED') {
        throw new Error(`Webhook inválido alterou o estado comercial: ${JSON.stringify({invalidEventRows, preConfirmationStatus})}`);
    }
    const eventSecret = process.env.APP_BILLING_E2E_WEBHOOK_SECRET;
    if (!eventSecret || eventSecret.length < 32) throw new Error('Segredo sintético do webhook E2E indisponível.');
    async function sendSyntheticPaymentEvent(event) {
        const body = JSON.stringify(event);
        const signature = createHmac('sha256', eventSecret).update(body).digest('hex');
        console.log(`Browser: webhook E2E diagnostic secret_bytes=${Buffer.byteLength(eventSecret)} body_length=${Buffer.byteLength(body)} signature_hex_length=${signature.length} body_sha256=${createHash('sha256').update(body).digest('hex')}.`);
        return fetch(`http://localhost:${appEnvironment.APP_PORT || 8080}/api/v1/webhooks/payments/e2e`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'X-Payment-Signature': `sha256=${signature}`},
            body,
        });
    }
    const providerReference = `e2e-${checkoutOperationId}`;
    const paidThroughAt = new Date(Date.now() + 30 * 86400000).toISOString();
    const confirmation = {eventId: `confirmation-${checkoutOperationId}`, reference: providerReference, sequence: 2, outcome: 'CONFIRMED', amountMinorUnits: 1, currency: 'XTS', occurredAt: new Date().toISOString(), paidThroughAt};
    const confirmationResponse = await sendSyntheticPaymentEvent(confirmation);
    if (confirmationResponse.status !== 202) {
        const responseError = await confirmationResponse.clone().json().catch(() => null);
        const errorCode = responseError?.error?.code;
        const safeErrorCode = typeof errorCode === 'string' && /^[A-Z0-9_]{1,64}$/.test(errorCode) ? errorCode : 'unavailable';
        const errorTraceId = responseError?.error?.traceId;
        const safeTraceId = typeof errorTraceId === 'string' && /^[0-9a-f-]{36}$/i.test(errorTraceId) ? errorTraceId : 'unavailable';
        let inboxRows = 'unavailable';
        try {
            inboxRows = queryPostgres(`SELECT count(*) FROM agendamento.billing_event_inbox WHERE provider_code = 'e2e' AND provider_event_id = 'confirmation-${checkoutOperationId}'`, appEnvironment);
        } catch {
            inboxRows = 'unavailable';
        }
        const responsePath = new URL(confirmationResponse.url).pathname;
        throw new Error(`Evidência E2E válida não foi admitida: HTTP ${confirmationResponse.status}, code=${safeErrorCode}, trace=${safeTraceId}, path=${responsePath}, redirected=${confirmationResponse.redirected}, inbox_rows=${inboxRows}`);
    }
    const duplicateResponse = await sendSyntheticPaymentEvent(confirmation);
    if (duplicateResponse.status !== 200) throw new Error(`Webhook repetido não foi reconhecido: HTTP ${duplicateResponse.status}`);
    const olderResponse = await sendSyntheticPaymentEvent({...confirmation, eventId: `older-${checkoutOperationId}`, sequence: 1, outcome: 'DECLINED', paidThroughAt: undefined});
    if (olderResponse.status !== 202) throw new Error(`Evento fora de ordem não foi admitido para dedupe: HTTP ${olderResponse.status}`);
    await command('Page.reload');
    await waitForText('Assinatura ativa', evaluate);
    const checkoutRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_checkout_operation WHERE operation_id = '${checkoutOperationId}' AND state = 'CONFIRMED'`, appEnvironment));
    const paymentHistoryRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.subscription_history WHERE operation_id = '${checkoutOperationId}' AND event_type = 'PAYMENT_CONFIRMED'`, appEnvironment));
    const paymentAuditRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.audit_event WHERE correlation_id = '${checkoutOperationId}' AND action = 'SUBSCRIPTION_PAYMENT_CONFIRMED'`, appEnvironment));
    const paymentOutboxRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.outbox_event WHERE operation_id = '${checkoutOperationId}' AND event_type = 'SubscriptionReactivated'`, appEnvironment));
    const currentSubscription = await evaluate("fetch('/api/v1/admin/subscription', {credentials:'same-origin', cache:'no-store'}).then(response => response.json()).then(body => body.subscription)");
    if (checkoutRows !== 1 || paymentHistoryRows !== 1 || paymentAuditRows !== 1 || paymentOutboxRows !== 1 || currentSubscription?.status !== 'ACTIVE' || currentSubscription?.planCode !== 'MONTHLY') {
        throw new Error(`Confirmação de pagamento sintética não foi aplicada uma única vez: ${JSON.stringify({checkoutRows, paymentHistoryRows, paymentAuditRows, paymentOutboxRows, currentSubscription})}`);
    }
    queryPostgres(`UPDATE agendamento.subscription SET paid_through_at = clock_timestamp() - INTERVAL '6 days' WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}' AND status = 'ACTIVE'`, appEnvironment);
    await command('Page.reload');
    await waitForText('Pagamento pendente — em tolerância', evaluate);
    const dunningDom = await evaluate(`({
      section: document.querySelector('[aria-label="Estado do trial"]')?.innerText,
      attempts: document.querySelector('[aria-label="Tentativas lógicas de cobrança"]')?.innerText,
      warning: document.body.innerText.includes('A agenda e o aceite do transporte não confirmam pagamento.')
        && document.body.innerText.includes('Resultados incertos bloqueiam novas cobranças até reconciliação.')
    })`);
    const cycleRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_dunning_cycle WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}' AND state = 'OPEN'`, appEnvironment));
    const logicalAttempts = Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}'`, appEnvironment));
    if (!dunningDom.section?.includes('Fim da tolerância') || !dunningDom.attempts?.includes('D+0:') || !dunningDom.attempts?.includes('D+3:') || !dunningDom.attempts?.includes('D+6:') || !dunningDom.warning || cycleRows !== 1 || logicalAttempts !== 3) {
        throw new Error(`Estado de inadimplência não persistiu/apareceu corretamente após reload: ${JSON.stringify({dunningDom, cycleRows, logicalAttempts})}`);
    }

    // Test-only state fixture checks that persisted operational states are rendered after reload.
    queryPostgres(`UPDATE agendamento.billing_dunning_attempt a SET state = 'SKIPPED_LATE_RECOVERY', skip_reason = 'LATE_RECOVERY_OLDER_DUE', last_error_code = 'LATE_RECOVERY_SKIPPED' FROM agendamento.billing_dunning_cycle c WHERE a.cycle_id = c.cycle_id AND a.tenant_id = c.tenant_id AND c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}' AND a.logical_sequence = 0`, appEnvironment);
    queryPostgres(`UPDATE agendamento.billing_dunning_attempt a SET state = 'OUTCOME_UNKNOWN', ever_started = TRUE, technical_attempts = 1, last_error_code = 'PROVIDER_TIMEOUT' FROM agendamento.billing_dunning_cycle c WHERE a.cycle_id = c.cycle_id AND a.tenant_id = c.tenant_id AND c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}' AND a.logical_sequence = 3`, appEnvironment);
    queryPostgres(`UPDATE agendamento.billing_dunning_attempt a SET state = 'SCHEDULED', ever_started = FALSE, skip_reason = NULL, next_attempt_at = NULL, last_error_code = NULL FROM agendamento.billing_dunning_cycle c WHERE a.cycle_id = c.cycle_id AND a.tenant_id = c.tenant_id AND c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}' AND a.logical_sequence = 6`, appEnvironment);
    await command('Page.reload');
    await waitForText('Ignorada por recuperação tardia', evaluate);
    const lateRecoveryDom = await evaluate(`({
      attempts: document.querySelector('[aria-label="Tentativas lógicas de cobrança"]')?.innerText,
      unknown: document.body.innerText.includes('Resultado incerto; aguardando reconciliação, sem nova cobrança'),
      scheduled: document.body.innerText.includes('D+6:') && document.body.innerText.includes('Agendada')
    })`);
    if (!lateRecoveryDom.attempts?.includes('Motivo: tentativa anterior vencida e nunca iniciada') || !lateRecoveryDom.unknown || !lateRecoveryDom.scheduled) {
        throw new Error(`Estados persistidos de recuperação/ambiguidade não apareceram corretamente após reload: ${JSON.stringify(lateRecoveryDom)}`);
    }
    queryPostgres(`UPDATE agendamento.billing_dunning_attempt a SET state = 'ACCEPTED', provider_code = 'synthetic', provider_account_id = 'e2e-only', provider_reference = 'transport-accepted-only', last_error_code = NULL FROM agendamento.billing_dunning_cycle c WHERE a.cycle_id = c.cycle_id AND a.tenant_id = c.tenant_id AND c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}' AND a.logical_sequence = 3`, appEnvironment);
    await command('Page.reload');
    await waitForText('Aceita pelo transporte; pagamento ainda não confirmado', evaluate);
    if (!await evaluate("document.body.innerText.includes('pagamento ainda não confirmado')")) {
        throw new Error('O painel confundiu aceite sintético do transporte com pagamento.');
    }
    await command('Page.reload');
    await waitForText('Pagamento pendente — em tolerância', evaluate);
    if (Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_dunning_cycle WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}'`, appEnvironment)) !== 1 || Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}'`, appEnvironment)) !== 3) {
        throw new Error('Reload administrativo duplicou ciclo ou tentativas lógicas de inadimplência.');
    }
    console.log('Browser E2E sintético: após pagamento controlado, fixture administrativa materializa ACTIVE→PAST_DUE; painel mostra paidThrough/graceEnds, D0/D+3/D+6, late-recovery skip, resultado incerto e aceite de transporte sem confundir com pagamento, com um ciclo e três identidades após reload. Estados posteriores são fixtures isoladas de teste; nenhum provider ou débito real foi chamado. Checkout/webhook existentes preservam retry, dedupe e ordem.');

    queryPostgres(`UPDATE agendamento.billing_dunning_attempt a SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL, send_claim_token = NULL, next_attempt_at = NULL, skip_reason = NULL FROM agendamento.billing_dunning_cycle c WHERE a.cycle_id = c.cycle_id AND a.tenant_id = c.tenant_id AND c.tenant_id = '${authenticatedSession.tenantId}' AND c.subscription_id = '${activeTrial.subscriptionId}'`, appEnvironment);
    queryPostgres(`UPDATE agendamento.billing_dunning_cycle SET state = 'PAID', closed_at = clock_timestamp() WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}' AND state IN ('OPEN', 'SUSPENDED')`, appEnvironment);
    queryPostgres(`UPDATE agendamento.subscription SET status = 'ACTIVE', plan_code = 'ANNUAL', paid_through_at = clock_timestamp() + INTERVAL '1 hour', grace_ends_at = NULL, version = version + 1 WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}'`, appEnvironment);
    await waitUntil(async () => Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_annual_renewal_notice WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}' AND paid_through_at = (SELECT paid_through_at FROM agendamento.subscription WHERE subscription_id = '${activeTrial.subscriptionId}') AND state = 'READY' AND obsolete_reason IS NULL AND scheduled_at <= clock_timestamp()`, appEnvironment)) === 1, 125000);
    await command('Page.reload');
    await waitForText('Renovação anual', evaluate);
    const annualNoticeDom = await evaluate(`({
      section: document.querySelector('[aria-label="Renovação anual"]')?.innerText,
      pendingDelivery: document.body.innerText.includes('intenção pronta; não enviada'),
      authorizationUnavailable: document.body.innerText.includes('indisponível ou não confirmada')
    })`);
    const annualNoticeRows = Number(queryPostgres(`SELECT count(*) FROM agendamento.billing_annual_renewal_notice WHERE tenant_id = '${authenticatedSession.tenantId}' AND subscription_id = '${activeTrial.subscriptionId}' AND paid_through_at = (SELECT paid_through_at FROM agendamento.subscription WHERE subscription_id = '${activeTrial.subscriptionId}') AND state = 'READY' AND obsolete_reason IS NULL`, appEnvironment));
    if (!annualNoticeDom.section?.includes('Versão') || !annualNoticeDom.pendingDelivery || !annualNoticeDom.authorizationUnavailable || annualNoticeRows !== 1) {
        throw new Error(`Intenção de aviso anual não persistiu/renderizou como não enviada: ${JSON.stringify({annualNoticeDom, annualNoticeRows})}`);
    }
    await command('Page.reload');
    await waitForText('intenção pronta; não enviada', evaluate);
    console.log('Browser E2E sintético: o worker de AGD-042 materializa a intenção anual no período corrente; PostgreSQL confirma READY sem entrega, e a UI mantém estado/versão após reload. A assinatura foi preparada por fixture isolada; nenhum fornecedor/notificação real.');

    await command('Network.clearBrowserCookies');
    const unlinkedTarget = await browserCdp.command('Target.createTarget', {
        url: 'http://localhost:5173/admin',
    });
    unlinkedTargetId = unlinkedTarget.targetId;
    const unlinkedTargetInfo = await waitForTarget(unlinkedTargetId);
    unlinkedTab = await connectToCdp(unlinkedTargetInfo.webSocketDebuggerUrl);
    await unlinkedTab.command('Page.enable');
    await unlinkedTab.command('Runtime.enable');
    await waitForText('Entrar como administrador', unlinkedTab.evaluate);
    await unlinkedTab.evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('username', unlinkedTab.evaluate, true);
    await submitLogin(
        credentials.KEYCLOAK_UNLINKED_USERNAME,
        credentials.KEYCLOAK_UNLINKED_PASSWORD,
        unlinkedTab.evaluate,
    );
    await waitForText('ADMIN_ACCESS_DENIED', unlinkedTab.evaluate);
    assertNoCredentialsInBackendLogs(appEnvironment, keycloakEnvironment, credentials, [
        clientPhone,
        differentPhone,
        expiredCode,
        blockedCode,
        validCode,
        rejectedCode,
    ]);
    console.log(
        'Browser E2E passed: OIDC login, tenant, 30-minute idle expiry, SSO reauthentication, revocation, CSRF logout, unlinked denial, no test credentials in backend logs.',
    );
} finally {
    if (browserCdp && unlinkedTargetId) {
        await browserCdp.command('Target.closeTarget', { targetId: unlinkedTargetId }).catch(() => {});
    }
    unlinkedTab?.close();
    if (browserCdp && secondTargetId) {
        await browserCdp.command('Target.closeTarget', { targetId: secondTargetId }).catch(() => {});
    }
    secondTab?.close();
    browserCdp?.close();
    socket?.close();
    browser.kill('SIGTERM');
}

async function connectToCdp(webSocketDebuggerUrl, responseHistory = null) {
    const cdpSocket = new WebSocket(webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
        cdpSocket.addEventListener('open', resolve, { once: true });
        cdpSocket.addEventListener('error', reject, { once: true });
    });
    let commandId = 0;
    const pending = new Map();
    cdpSocket.addEventListener('message', ({ data }) => {
        const message = JSON.parse(data);
        if (responseHistory && message.method === 'Network.responseReceived') {
            try {
                const responseUrl = new URL(message.params.response.url);
                if (responseUrl.hostname === 'localhost') {
                    responseHistory.push({
                        path: responseUrl.pathname,
                        status: message.params.response.status,
                        type: message.params.type,
                    });
                    if (responseHistory.length > 20) responseHistory.shift();
                }
            } catch {}
        }
        if (!message.id || !pending.has(message.id)) return;
        const { resolve, reject, timeout } = pending.get(message.id);
        clearTimeout(timeout);
        pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
    });
    const command = (method, params = {}) =>
        new Promise((resolve, reject) => {
            const id = ++commandId;
            const timeout = setTimeout(() => {
                pending.delete(id);
                reject(new Error(`CDP command ${method} did not respond within 30 seconds.`));
            }, 30_000);
            pending.set(id, { resolve, reject, timeout });
            try {
                cdpSocket.send(JSON.stringify({ id, method, params }));
            } catch (error) {
                clearTimeout(timeout);
                pending.delete(id);
                reject(error);
            }
        });
    const evaluate = async (expression) => {
        const result = await command('Runtime.evaluate', {
            expression,
            returnByValue: true,
            awaitPromise: true,
        });
        if (result.exceptionDetails) {
            const description = result.exceptionDetails.exception?.description?.split('\n', 1)[0];
            throw new Error(
                `Execução do navegador falhou: ${result.exceptionDetails.text}${description ? ` (${description})` : ''}`,
            );
        }
        return result.result.value;
    };
    return {
        socket: cdpSocket,
        command,
        evaluate,
        close: () => cdpSocket.close(),
    };
}

async function waitForTarget(targetId) {
    let targetInfo;
    await waitUntil(async () => {
        const targets = await fetch('http://127.0.0.1:9222/json/list').then((response) => response.json());
        targetInfo = targets.find((target) => target.id === targetId);
        return Boolean(targetInfo?.webSocketDebuggerUrl);
    });
    return targetInfo;
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

async function setAdminField(elementType, index, value, evaluate) {
    await evaluate(`(() => {
      const field = document.querySelectorAll('form ' + ${JSON.stringify(elementType)})[${index}];
      if (!field) throw new Error('Catalog form field not found');
      const descriptor = Object.getOwnPropertyDescriptor(HTML${elementType === 'textarea' ? 'TextArea' : elementType === 'select' ? 'Select' : 'Input'}Element.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
}

async function setFieldByAriaLabel(label, value, evaluate) {
    await evaluate(`(() => {
      const field = [...document.querySelectorAll('[aria-label]')].find(item => item.getAttribute('aria-label') === ${JSON.stringify(label)});
      if (!field) throw new Error('Schedule field not found: ' + ${JSON.stringify(label)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
}

async function setAdminAppointmentField(label, value, evaluate) {
    await evaluate(`(() => {
      const form = document.querySelector('form[aria-label="Criar reserva administrativa"]');
      const wrapper = [...form.querySelectorAll('label')].find(item => item.innerText.trim().startsWith(${JSON.stringify(label)}));
      const field = wrapper?.querySelector('input');
      if (!field) throw new Error('Administrative appointment field not found: ' + ${JSON.stringify(label)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
}

async function setAdminAppointmentTextarea(label, value, evaluate) {
    await evaluate(`(() => {
      const form = document.querySelector('form[aria-label="Criar reserva administrativa"]');
      const wrapper = [...form.querySelectorAll('label')].find(item => item.innerText.trim().startsWith(${JSON.stringify(label)}));
      const field = wrapper?.querySelector('textarea');
      if (!field) throw new Error('Administrative appointment textarea not found: ' + ${JSON.stringify(label)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
}

async function setFieldByLabel(label, value, evaluate) {
    await evaluate(`(() => {
      const wrapper = [...document.querySelectorAll('label')].find(item => item.childNodes[0]?.textContent?.trim() === ${JSON.stringify(label)});
      const field = wrapper?.querySelector('input');
      if (!field) throw new Error('Policy field not found: ' + ${JSON.stringify(label)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
}

async function setSelectByLabel(label, value, evaluate) {
    await evaluate(`(() => {
      const wrapper = [...document.querySelectorAll('label')].find(item => item.childNodes[0]?.textContent?.trim() === ${JSON.stringify(label)});
      const field = wrapper?.querySelector('select');
      if (!field) throw new Error('Policy select not found: ' + ${JSON.stringify(label)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
}

async function setSelectById(id, value, evaluate) {
    await evaluate(`(() => {
      const field = document.getElementById(${JSON.stringify(id)});
      if (!field) throw new Error('Select not found: ' + ${JSON.stringify(id)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', {bubbles:true}));
      field.dispatchEvent(new Event('change', {bubbles:true}));
    })()`);
}

async function setFieldById(id, value, evaluate) {
    await evaluate(`(() => {
      const field = document.getElementById(${JSON.stringify(id)});
      if (!field) throw new Error('Input not found: ' + ${JSON.stringify(id)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', {bubbles:true}));
      field.dispatchEvent(new Event('change', {bubbles:true}));
    })()`);
}

async function setTextareaById(id, value, evaluate) {
    await evaluate(`(() => {
      const field = document.getElementById(${JSON.stringify(id)});
      if (!field) throw new Error('Textarea not found: ' + ${JSON.stringify(id)});
      const descriptor = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
      descriptor.set.call(field, ${JSON.stringify(value)});
      field.dispatchEvent(new Event('input', {bubbles:true}));
      field.dispatchEvent(new Event('change', {bubbles:true}));
    })()`);
}

async function clickButtonByText(text, evaluate) {
    await evaluate(`(() => {
      const button = [...document.querySelectorAll('button')].find(item => item.innerText.trim() === ${JSON.stringify(text)});
      if (!button) throw new Error('Button not found: ' + ${JSON.stringify(text)});
      button.click();
    })()`);
}

async function waitForButtonByText(text, evaluate) {
    await waitUntil(() =>
        evaluate(` [...document.querySelectorAll('button')].some(item => item.innerText.trim() === ${JSON.stringify(text)}) `),
    );
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
        /^[0-9a-f]{32}$/i.test(sessionId) || /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(sessionId)
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
    const [maxInactiveInterval, lastAccessTime, expiryTime] = value.split('|').map(Number);
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

function assertNoCredentialsInBackendLogs(appEnvironment, keycloakEnvironment, credentials, sensitiveValues = []) {
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
        ...sensitiveValues,
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
            `JSON.stringify({page: location.origin + location.pathname, title: document.title, heading: document.querySelector("h1")?.innerText, output: document.querySelector("output")?.innerText, alerts: [...document.querySelectorAll('[role="alert"]')].map(item => item.innerText).filter(Boolean).slice(0, 5), bookingError: window.__bookingE2eError, recentHttpResponses: ${JSON.stringify(recentHttpResponses)}})`,
        );
        throw new Error(`Estado ${text} não alcançado: ${diagnostic}`, {
            cause: error,
        });
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
