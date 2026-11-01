export function createDeadlineWaiter(timeoutMs, options = {}) {
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
        throw new TypeError('timeoutMs must be a positive finite number');
    }

    const now = options.now ?? (() => performance.now());
    const setTimer = options.setTimer ?? setTimeout;
    const clearTimer = options.clearTimer ?? clearTimeout;
    const pause = options.pause ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
    const pollIntervalMs = options.pollIntervalMs ?? 250;
    const deadline = now() + timeoutMs;
    const remainingMs = () => Math.max(0, deadline - now());
    const run = async (operation, label, diagnostic = () => ({stage: label})) => {
        const remaining = remainingMs();
        if (remaining <= 0) throw deadlineError(timeoutMs, label, diagnostic());

        const controller = new AbortController();
        let timer;
        let deadlineElapsed = false;
        try {
            const deadlinePromise = new Promise((_, reject) => {
                timer = setTimer(() => {
                    deadlineElapsed = true;
                    controller.abort();
                    reject(new Error('shared deadline elapsed'));
                }, remaining);
            });
            return await Promise.race([Promise.resolve().then(() => operation(controller.signal)), deadlinePromise]);
        } catch (error) {
            if (deadlineElapsed || remainingMs() <= 0) throw deadlineError(timeoutMs, label, diagnostic());
            throw error;
        } finally {
            if (timer !== undefined) clearTimer(timer);
            controller.abort();
        }
    };

    async function wait(check, label) {
        let lastDiagnostic = {stage: label, state: 'not-observed'};
        while (true) {
            const remaining = remainingMs();
            if (remaining <= 0) throw deadlineError(timeoutMs, label, lastDiagnostic);

            const controller = new AbortController();
            let timer;
            let deadlineElapsed = false;
            try {
                const deadlinePromise = new Promise((_, reject) => {
                    timer = setTimer(() => {
                        deadlineElapsed = true;
                        controller.abort();
                        reject(new Error('shared deadline elapsed'));
                    }, remaining);
                });
                const observed = await Promise.race([Promise.resolve().then(() => check(controller.signal)), deadlinePromise]);
                if (observed?.diagnostic !== undefined) lastDiagnostic = observed.diagnostic;
                if (observed === true || observed?.done === true) return observed;
            } catch {
                if (deadlineElapsed || remainingMs() <= 0) throw deadlineError(timeoutMs, label, lastDiagnostic);
                lastDiagnostic = {stage: label, state: 'browser-evaluation-unavailable'};
            } finally {
                if (timer !== undefined) clearTimer(timer);
                controller.abort();
            }

            const remainingAfterCheck = remainingMs();
            if (remainingAfterCheck <= 0) throw deadlineError(timeoutMs, label, lastDiagnostic);
            await pause(Math.min(pollIntervalMs, remainingAfterCheck));
        }
    }

    return {run, wait, remainingMs};
}

function deadlineError(timeoutMs, label, diagnostic) {
    return new Error(`Orçamento único de ${timeoutMs}ms esgotado em ${label}; último diagnóstico: ${JSON.stringify(diagnostic)}`);
}
