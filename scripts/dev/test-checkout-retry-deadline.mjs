import assert from 'node:assert/strict';
import {createDeadlineWaiter} from './checkout-retry-deadline.mjs';

let elapsed = 0;
let nextTimerId = 0;
const activeTimers = new Map();
const pauseDurations = [];
const waiter = createDeadlineWaiter(100, {
    now: () => elapsed,
    pollIntervalMs: 25,
    setTimer: (callback, duration) => {
        const timerId = ++nextTimerId;
        activeTimers.set(timerId, {callback, dueAt: elapsed + duration});
        return timerId;
    },
    clearTimer: (timerId) => activeTimers.delete(timerId),
    pause: async (duration) => {
        pauseDurations.push(duration);
        elapsed += duration;
    },
});

let documentChecks = 0;
await waiter.wait(() => ({done: ++documentChecks === 2, diagnostic: {stage: 'new-document', checks: documentChecks}}), 'new document');
assert.equal(elapsed, 25, 'the earlier stage must consume the shared budget');
assert.equal(waiter.remainingMs(), 75, 'the next stage must inherit only the remaining budget');

let requestChecks = 0;
await assert.rejects(
    waiter.wait(() => ({done: false, diagnostic: {stage: 'first-request', count: ++requestChecks}}), 'first request'),
    /Orçamento único de 100ms esgotado em first request/,
);
assert.equal(elapsed, 100, 'all stages together must stop at the single deadline');
assert.deepEqual(pauseDurations, [25, 25, 25, 25], 'polling pauses must not reset or exceed the shared budget');
assert.equal(requestChecks, 3, 'no check should run after the budget expires');
assert.equal(activeTimers.size, 0, 'deadline timers must be cleared after success and failure');

let abortObserved = false;
const pendingWaiter = createDeadlineWaiter(20, {
    now: () => elapsed,
    setTimer: (callback, duration) => {
        const timerId = ++nextTimerId;
        activeTimers.set(timerId, {callback, dueAt: elapsed + duration});
        return timerId;
    },
    clearTimer: (timerId) => activeTimers.delete(timerId),
    pause: async (duration) => {
        elapsed += duration;
    },
});
const expireNextTimer = () => {
    const [timerId, timer] = activeTimers.entries().next().value;
    activeTimers.delete(timerId);
    elapsed = timer.dueAt;
    timer.callback();
};
await assert.rejects(
    pendingWaiter.wait((signal) => new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => {
            abortObserved = true;
            reject(new Error('evaluation aborted'));
        }, {once: true});
        queueMicrotask(expireNextTimer);
    }), 'pending CDP evaluation'),
    /Orçamento único de 20ms esgotado em pending CDP evaluation/,
);
assert.equal(abortObserved, true, 'deadline expiration must abort the in-flight browser evaluation');
assert.equal(activeTimers.size, 0, 'no deadline or command timer may remain active after abort');

let oneShotAborted = false;
const oneShotWaiter = createDeadlineWaiter(20, {
    now: () => elapsed,
    setTimer: (callback, duration) => {
        const timerId = ++nextTimerId;
        activeTimers.set(timerId, {callback, dueAt: elapsed + duration});
        return timerId;
    },
    clearTimer: (timerId) => activeTimers.delete(timerId),
});
await assert.rejects(
    oneShotWaiter.run((signal) => new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => {
            oneShotAborted = true;
            reject(new Error('command aborted'));
        }, {once: true});
        queueMicrotask(expireNextTimer);
    }), 'checkout click'),
    /Orçamento único de 20ms esgotado em checkout click/,
);
assert.equal(oneShotAborted, true, 'single CDP actions must be cancelled when the shared budget expires');
assert.equal(activeTimers.size, 0, 'no single-action timer may remain active after abort');

console.log('checkout retry deadline harness passed: shared budget, timeout, pending-check/action abort and timer cleanup verified without a real-time wait');
