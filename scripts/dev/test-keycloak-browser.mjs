import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
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
        "document.querySelector('p code')?.textContent === " + JSON.stringify(authenticatedSession.tenantId),
    );
    if (!tenantDisplayed) throw new Error('Tenant autorizado não apareceu na UI.');
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
    await clickButtonByText('Recarregar configuração persistida', evaluate);
    await waitForText('Configuração recarregada.', evaluate);
    const persistedSchedule = await evaluate(
        "fetch('/api/v1/admin/schedule/weekly-periods', {credentials:'same-origin'}).then(response => response.json())",
    );
    const persistedPolicy = await evaluate(
        "fetch('/api/v1/admin/availability-policy', {credentials:'same-origin'}).then(response => response.json())",
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
        persistedPolicy.reminderOffsetHours !== 6
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
    console.log(
        `Browser: visitante consultou ${publicDate}, confirmou duas intenções sem duplicação entre ${publicSlotsBeforeSelection} horários; layout móvel/desktop sem overflow.`,
    );
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Estado: Publicado', evaluate);
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
    await clickButtonByText('Marcar falta', evaluate);
    await clickButtonByText('Confirmar ação', evaluate);
    await waitForText('Estado atualizado e persistido.', evaluate);
    await waitUntil(() => evaluate(`(() => {
      const text = document.querySelector('.admin-agenda__details')?.innerText ?? '';
      return text.includes('NO_SHOW') && text.includes('versão 2') && text.includes('APPOINTMENT_NO_SHOW');
    })()`));

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
        `SELECT CASE WHEN (SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.status IN ('CANCELLED_BY_PROVIDER','COMPLETED','NO_SHOW')) = 4 AND (SELECT count(*) FROM agendamento.outbox_event WHERE event_type IN ('AppointmentCancelled','AppointmentCompleted','ClientNoShowRecorded') AND tenant_id = (SELECT tenant_id FROM agendamento.tenant WHERE slug = '${publication.slug}')) = 4 THEN 1 ELSE 0 END`,
        appEnvironment,
    ));
    if (allOutcomeEffects !== 1) throw new Error('Os quatro estados terminais não têm evento persistido único por reserva.');
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
    await evaluate(`(() => {
      const row = document.querySelector('li[data-appointment-id="${ownedBookingId}"]');
      [...row.querySelectorAll('button')].find(button => button.innerText === 'Cancelar').click();
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
        !cancellationRetry[0].key || cancellationRetry[0].status !== 200 || cancellationRetry[1].status !== 200) {
      throw new Error(`Retry do cancelamento mudou a intenção original: ${JSON.stringify(cancellationRetry)}`);
    }
    const cancellationEffects = queryPostgres(
        `SELECT CASE WHEN (SELECT status = 'CANCELLED_BY_CLIENT' AND version = 2 FROM agendamento.appointment WHERE appointment_id = '${ownedBookingId}') AND (SELECT count(*) = 2 FROM agendamento.appointment_history WHERE appointment_id = '${ownedBookingId}') AND (SELECT count(*) = 1 FROM agendamento.audit_event WHERE target_id = '${ownedBookingId}' AND action = 'APPOINTMENT_CANCELLED_BY_CLIENT') AND (SELECT count(*) = 1 FROM agendamento.outbox_event WHERE aggregate_id = '${ownedBookingId}' AND event_type = 'AppointmentCancelled') AND (SELECT count(*) = 1 FROM agendamento.idempotency_record i WHERE i.command_scope LIKE 'appointments.client.cancel.%' AND i.state = 'COMPLETED' AND i.operation_id = (SELECT operation_id FROM agendamento.outbox_event WHERE aggregate_id = '${ownedBookingId}' AND event_type = 'AppointmentCancelled')) THEN 1 ELSE 0 END`,
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
    await waitForText('Entre com seu telefone', evaluate);
    await setFieldById('client-phone', differentPhone, evaluate);
    await evaluate(`(() => {
      const originalFetch = window.fetch.bind(window);
      window.__oldSessionVerifyResponseLost = false;
      window.fetch = async (...args) => {
        const response = await originalFetch(...args);
        const requestUrl = args[0] instanceof Request ? args[0].url : String(args[0]);
        const requestMethod = args[1]?.method ?? (args[0] instanceof Request ? args[0].method : 'GET');
        if (requestUrl.includes('/access-challenges') && !requestUrl.endsWith('/verify') && requestMethod.toUpperCase() === 'POST') {
          window.__clientAccessChallengeId = (await response.clone().json()).challengeId;
        }
        if (requestUrl.endsWith('/verify') && requestMethod.toUpperCase() === 'POST' && response.status === 401 && !window.__oldSessionVerifyResponseLost) {
          window.__oldSessionVerifyResponseLost = true;
          throw new TypeError('Simulated lost rejected verification response');
        }
        return response;
      };
    })()`);
    await clickButtonByText('Solicitar código', evaluate);
    await waitUntil(() => evaluate("Boolean(document.querySelector('#client-code'))"));
    const rejectedChallengeId = await evaluate('window.__clientAccessChallengeId ?? null');
    if (!rejectedChallengeId) {
        throw new Error('E2E não capturou o challenge para testar sessão antiga.');
    }
    const rejectedCodeResponse = await fetch(`http://localhost:${appPort}/internal/e2e/access-challenges/${rejectedChallengeId}/code`, {
        headers: { 'X-E2E-Emitter-Secret': emitterSecret },
        cache: 'no-store',
    });
    if (!rejectedCodeResponse.ok) throw new Error('Emissor E2E não retornou código para o challenge de sessão antiga.');
    const rejectedCode = (await rejectedCodeResponse.json()).code;
    const rejectedWrongCode = rejectedCode === '000000' ? '000001' : '000000';
    await setFieldById('client-code', rejectedWrongCode, evaluate);
    await clickButtonByText('Verificar código', evaluate);
    await waitForText('Não foi possível confirmar o resultado.', evaluate);
    await clickButtonByText('Tentar novamente', evaluate);
    await waitForText('Código inválido, expirado ou temporariamente bloqueado.', evaluate);
    const oldSessionDom = await evaluate(`(async () => ({
      rejectedResponseLost: window.__oldSessionVerifyResponseLost,
      successVisible: document.body.innerText.includes('Acesso verificado neste tenant.'),
      rejectionVisible: document.body.innerText.includes('Código inválido, expirado ou temporariamente bloqueado.'),
      session: await fetch('/api/v1/client/session?slug=${encodeURIComponent(demoTenantSlug)}&challengeId=${rejectedChallengeId}', {credentials:'same-origin', cache:'no-store'}).then(response => response.json()),
    }))()`);
    if (!oldSessionDom.rejectedResponseLost || oldSessionDom.successVisible || !oldSessionDom.rejectionVisible ||
        oldSessionDom.session.authenticated) {
        throw new Error(`Sessão anterior confirmou indevidamente challenge de outro telefone: ${JSON.stringify(oldSessionDom)}`);
    }
    const sessionRowsAfterRejectedRecovery = queryPostgres(
        `SELECT count(*) FROM agendamento.client_access_session WHERE tenant_id = '${credentials.DEMO_TENANT_ID}'`,
        appEnvironment,
    );
    if (sessionRowsAfterRejectedRecovery !== '1') throw new Error('Challenge rejeitado criou sessão adicional.');

    await command('Page.reload');
    await waitForText('Suas reservas', evaluate);
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

    await command('Network.clearBrowserCookies');
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Entrar como administrador', evaluate);
    await evaluate("document.querySelector('a[href*=oauth2]').click()");
    await waitForText('username', evaluate, true);
    await submitLogin(credentials.KEYCLOAK_UNLINKED_USERNAME, credentials.KEYCLOAK_UNLINKED_PASSWORD, evaluate);
    await waitForText('ADMIN_ACCESS_DENIED', evaluate);
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
        const { resolve, reject } = pending.get(message.id);
        pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
    });
    const command = (method, params = {}) =>
        new Promise((resolve, reject) => {
            const id = ++commandId;
            pending.set(id, { resolve, reject });
            cdpSocket.send(JSON.stringify({ id, method, params }));
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

async function clickButtonByText(text, evaluate) {
    await evaluate(`(() => {
      const button = [...document.querySelectorAll('button')].find(item => item.innerText.trim() === ${JSON.stringify(text)});
      if (!button) throw new Error('Button not found: ' + ${JSON.stringify(text)});
      button.click();
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
