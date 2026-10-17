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
let browserCdp;
let secondTab;
let secondTargetId;
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
    socket = await connectToCdp(target.webSocketDebuggerUrl, recentHttpResponses);
    const { command, evaluate } = socket;

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
    const authenticatedSession = await evaluate(
        "fetch('/api/v1/admin/session', {credentials:'same-origin'}).then(response => response.json())",
    );
    if (
        !authenticatedSession.authenticated ||
        authenticatedSession.tenantId !== credentials.DEMO_TENANT_ID
    ) {
        throw new Error(
            `Sessão autenticada diverge do tenant de demonstração: esperado ${credentials.DEMO_TENANT_ID}, recebido ${authenticatedSession.tenantId}.`,
        );
    }
    const tenantDisplayed = await evaluate(
        "document.querySelector('p code')?.textContent === " +
            JSON.stringify(authenticatedSession.tenantId),
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
    if (!publicService?.serviceId) throw new Error('Serviço ativo de demonstração não encontrado para a página pública.');
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
    const selectedStart = await evaluate(`fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${publicDate}&to=${publicDate}').then(response => response.json()).then(result => result.days[0].slots[0].startsAt)`);
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
    const browserVersion = await fetch('http://127.0.0.1:9222/json/version').then(response => response.json());
    browserCdp = await connectToCdp(browserVersion.webSocketDebuggerUrl);
    const secondTarget = await browserCdp.command('Target.createTarget', {url:'about:blank'});
    secondTargetId = secondTarget.targetId;
    const secondTargetInfo = await waitForTarget(secondTargetId);
    secondTab = await connectToCdp(secondTargetInfo.webSocketDebuggerUrl);
    await secondTab.command('Page.enable');
    await secondTab.command('Runtime.enable');
    await navigate(`http://localhost:5173/public/${encodeURIComponent(publication.slug)}`, secondTab.command, secondTab.evaluate);
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
          window.fetch = originalFetch;
          throw new TypeError('Simulated lost appointment response after commit');
        }
        return response;
      };
    })()`);
    await clickButtonByText('Confirmar reserva', evaluate);
    await waitForText('Não foi possível confirmar o resultado do envio.', evaluate);
    await waitForText('Recuperação da reserva', secondTab.evaluate);
    await waitForText('Reserva original recuperada.', secondTab.evaluate);
    const appointmentIdInEachTab = await Promise.all([
        evaluate("document.querySelector('.public-page__confirmation strong')?.textContent"),
        secondTab.evaluate("document.querySelector('.public-page__confirmation strong')?.textContent"),
    ]);
    if (!appointmentIdInEachTab[0] || appointmentIdInEachTab[0] !== appointmentIdInEachTab[1]) {
        throw new Error('Duas abas não recuperaram a mesma reserva original.');
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
    if (!storedIntent?.hasKey || !storedIntent?.hasCapability || storedIntent.containsName || storedIntent.containsPhone) {
        throw new Error(`Recovery storage contém formato inválido ou PII: ${JSON.stringify(storedIntent)}`);
    }
    const unresolvedIntentLocked = await evaluate(`(() => {
      const slotButtons = [...document.querySelectorAll('.public-page__slot')];
      return document.getElementById('public-service').disabled
        && document.getElementById('public-date').disabled
        && document.getElementById('public-client-name').disabled
        && document.getElementById('public-client-phone').disabled
        && slotButtons.length > 0 && slotButtons.every(button => button.disabled)
        && !document.querySelector('.public-page__booking-form button[type="submit"]').disabled;
    })()`);
    if (!unresolvedIntentLocked) throw new Error('A interface permitiu alterar uma intenção de reserva ainda não resolvida.');
    await command('Page.reload');
    await waitForText('Reserva original recuperada.', evaluate);
    await waitForText('Reserva confirmada', evaluate);
    const restoredDate = await evaluate(
        "document.getElementById('public-date')?.value",
    );
    if (restoredDate !== publicDate) {
        throw new Error('Reload não restaurou a data da intenção original.');
    }
    if (!(await evaluate(`document.body.innerText.includes(${JSON.stringify(publicService.name)}) && document.body.innerText.includes(${JSON.stringify(publication.timeZone)})`))) {
        throw new Error('Confirmação não exibiu serviço e timezone esperados.');
    }
    const persistedBookingCount = Number(queryPostgres(
        `SELECT count(*) FROM agendamento.appointment a JOIN agendamento.tenant t USING (tenant_id) WHERE t.slug = '${publication.slug}' AND a.status = 'CONFIRMED'`,
        appEnvironment,
    ));
    if (persistedBookingCount !== 1) throw new Error('Retry após resposta perdida não manteve uma única reserva persistida.');
    const slotsAfterConfirmation = await evaluate(`fetch('/api/v1/public/${encodeURIComponent(publication.slug)}/availability?serviceId=${encodeURIComponent(publicService.serviceId)}&from=${publicDate}&to=${publicDate}').then(response => response.json()).then(result => result.days[0].slots)`);
    if (slotsAfterConfirmation.some(slot => slot.startsAt === selectedStart)) {
        throw new Error('Disponibilidade continuou oferecendo o horário já confirmado.');
    }
    console.log('Browser: visitante perdeu a resposta após commit, recarregou e recuperou a reserva persistida; storage não contém PII e permaneceu uma única reserva.');
    await clickButtonByText('Fazer outra reserva', evaluate);
    await waitUntil(async () => (await evaluate(
        `localStorage.getItem('kronos.public-booking.intent.' + ${JSON.stringify(publication.slug)}) === null && !document.getElementById('public-service').disabled`,
    )));
    await waitUntil(async () => (await evaluate(
        "[...document.querySelectorAll('.public-page__slot')].some(button => !button.disabled)",
    )));
    const anotherSlotCanBeSelected = await evaluate(
        "[...document.querySelectorAll('.public-page__slot')].some(button => !button.disabled)",
    );
    if (!anotherSlotCanBeSelected) {
        throw new Error('Receipt recuperado bloqueou permanentemente uma nova intenção explícita.');
    }
    await command('Emulation.setDeviceMetricsOverride', {width:390,height:844,deviceScaleFactor:1,mobile:true});
    if (!(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'))) {
        throw new Error('A página pública excedeu a largura do viewport móvel.');
    }
    await command('Emulation.setDeviceMetricsOverride', {width:1280,height:900,deviceScaleFactor:1,mobile:false});
    if (!(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'))) {
        throw new Error('A página pública excedeu a largura do viewport desktop.');
    }
    console.log(`Browser: visitante consultou ${publicDate}, confirmou um de ${publicSlotsBeforeSelection} horários; layout móvel/desktop sem overflow.`);
    await navigate('http://localhost:5173/admin', command, evaluate);
    await waitForText('Estado: Publicado', evaluate);
    await clickButtonByText('Despublicar página', evaluate);
    await waitForText('Página pública despublicada.', evaluate);
    const unpublishedRead = await evaluate(
        `fetch('/api/v1/public/${encodeURIComponent(publication.slug)}').then(response => response.status)`,
    );
    if (unpublishedRead !== 404) throw new Error('Despublicar não bloqueou uma leitura pública direta.');
    console.log('Browser: despublicação impediu leituras públicas diretas.');
    const exceptionDate = new Date(Date.now() + 45 * 86400_000)
        .toISOString()
        .slice(0, 10);
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
    await waitUntil(async () => (await evaluate(
        `fetch('/api/v1/admin/schedule/exceptions?from=${exceptionDate}&to=${exceptionDate}', {credentials:'same-origin'}).then(response => response.json()).then(result => result.exceptions.length)`,
    )) === 0);
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
    if (exceptionRows.exceptions.length !== 1 || exceptionRows.exceptions[0].kind !== 'SPECIAL' || exceptionRows.exceptions[0].periods[0].localStart !== '09:00') {
        throw new Error(`Horário especial não persistiu após recarregar: ${JSON.stringify(exceptionRows.exceptions.map(item => ({kind:item.kind,periods:item.periods,version:item.version,timeZone:item.timeZone})))}`);
    }
    const outsideRangeDate = new Date(Date.now() + 60 * 86400_000)
        .toISOString()
        .slice(0, 10);
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
    await setFieldByLabel('Motivo do cancelamento', 'Concluído E2E', evaluate);
    await clickButtonByText('Cancelar bloqueio', evaluate);
    await waitUntil(async () => (await evaluate(
        `fetch('/api/v1/admin/blocked-periods?from=${blockDay}&to=${blockDay}', {credentials:'same-origin'}).then(response => response.json()).then(rows => rows[0]?.status)`,
    )) === 'CANCELLED');
    console.log('Browser: exceção fechada, horário especial, restauração e bloqueio cancelado persistem após reload.');
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
    if (concurrentUpdateStatus !== 200) throw new Error('Comando concorrente não atualizou a grade para o teste de CAS.');
    await clickButtonByText('Salvar grade semanal', evaluate);
    await waitForText('Outra sessão salvou esta grade.', evaluate);
    if (await evaluate("document.querySelector('[aria-label=\"Segunda-feira início 1\"]').value") !== '08:00') {
        throw new Error('Conflito de versão descartou o rascunho local.');
    }
    await evaluate('window.confirm = () => true');
    await clickButtonByText('Recarregar versão atual (substitui campos locais)', evaluate);
    await waitForText('Configuração recarregada.', evaluate);
    if (await evaluate("document.querySelector('[aria-label=\"Segunda-feira início 1\"]').value") !== '10:00') {
        throw new Error('Recuperação explícita do conflito não carregou a versão atual.');
    }
    console.log('Browser: conflito CAS preservou rascunho e recuperação explícita carregou a versão vigente.');
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
    if (browserCdp && secondTargetId) {
        await browserCdp.command('Target.closeTarget', {targetId:secondTargetId}).catch(() => {});
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
            const description = result.exceptionDetails.exception?.description
                ?.split('\n', 1)[0];
            throw new Error(
                `Execução do navegador falhou: ${result.exceptionDetails.text}${description ? ` (${description})` : ''}`,
            );
        }
        return result.result.value;
    };
    return { socket: cdpSocket, command, evaluate, close: () => cdpSocket.close() };
}

async function waitForTarget(targetId) {
    let targetInfo;
    await waitUntil(async () => {
        const targets = await fetch('http://127.0.0.1:9222/json/list').then(response => response.json());
        targetInfo = targets.find(target => target.id === targetId);
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
            `JSON.stringify({page: location.origin + location.pathname, title: document.title, heading: document.querySelector("h1")?.innerText, output: document.querySelector("output")?.innerText, alerts: [...document.querySelectorAll('[role="alert"]')].map(item => item.innerText).filter(Boolean).slice(0, 5), bookingError: window.__bookingE2eError, recentHttpResponses: ${JSON.stringify(recentHttpResponses)}})`,
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
