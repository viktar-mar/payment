import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {spawnSync} from 'node:child_process';
import {runManualRefundRemainder, runManualRefundRemainderCli, runManualRefundRemainderEntry} from './stripe-manual-refund-remainder.mjs';

const options = {
  env: {
    PAYMENT_STRIPE_SANDBOX_ENABLED: 'true',
    PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS: 'true',
    PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER: 'true'
  },
  runId: '11111111-2222-4333-8444-555555555555',
  expectedSource: 'a'.repeat(40),
  daemon: 'unix:///Users/synthetic/docker.sock'
};
const prior = '34941d87-12d3-4c74-8c5a-b62b7c914390';
const uid = n => 'bbbbbbbb-cccc-4ddd-8eee-' + String(n).padStart(12, '0');
const defer = () => {
  let resolve,
    reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return {promise, resolve, reject};
};
const env = () => ({
  ...options.env,
  PAYMENT_STRIPE_SANDBOX_RUN_ID: options.runId,
  PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE: options.expectedSource,
  PAYMENT_STRIPE_SANDBOX_DAEMON: options.daemon
});
function fixture() {
  let time = 1000;
  const calls = [],
    seen = [],
    timers = new Map(),
    abort = new AbortController();
  const limits = {deadline: 1801000, signal: abort.signal};
  const action = (name, value = true) => (...args) => {
    calls.push(name);
    seen.push([name, args]);
    return Promise.resolve(value);
  };
  const target = {
    original: {originalRunId: '031b5ae7-6603-4339-b1b3-77231291ff34', ports: {backend: 18080}},
    priorRunId: prior,
    priorSource: 'b'.repeat(40),
    priorRefundId: uid(1),
    remainderKey: 's5a-refund:' + prior + ':remainder',
    callbackVersionBefore: 2,
    callbackVersionAfter: 3
  };
  const baseline = {
    refundId: uid(1),
    providerReference: 're_first',
    providerEventId: 'evt_first',
    cashEventId: uid(3),
    amountMinor: 700,
    totalMinor: 700,
    providerVerified: true,
    webhookVerified: true,
    journalVerified: true,
    callbackDeliveryVerified: true
  };
  const remainder = {
    refundId: uid(2),
    providerReference: 're_second',
    providerEventId: 'evt_second',
    cashEventId: uid(4),
    amountMinor: 1300,
    totalMinor: 2000,
    providerVerified: true,
    webhookVerified: true,
    journalVerified: true,
    callbackSignatureVerified: true
  };
  const final = {
    priorPartialVerified: true,
    priorCallbackDeliveryVerified: true,
    remainderConfirmed: true,
    providerVerified: true,
    webhookVerified: true,
    journalVerified: true,
    callbackSignatureVerified: true,
    finalTotalVerified: true
  };
  const state = {admit: action('admit', 'READY'), target: () => target, close: action('state-close')};
  const runtime = {
    resources: () => target,
    restore: action('restore'),
    verify: action('verify'),
    verifyForwardTarget: action('forward'),
    verifyWebhookSecret: action('secret'),
    stop: action('runtime-stop')
  };
  const cli = {
    validateBinding: action('binding'),
    startListener: action('listener'),
    bindRemainderTarget: action('bind-target'),
    stopListener: action('listener-stop')
  };
  const session = {
    verifyBaseline: action('baseline', baseline),
    rotateCallback: action('rotation'),
    refundRemainder: action('remainder', remainder),
    verifyFinal: action('final', final),
    diagnostic: () => null,
    close: action('session-close')
  };
  const deps = {
    now: () => time,
    setTimeout: (fn, delay) => {
      const id = {};
      timers.set(id, {fn, at: time + delay});
      return id;
    },
    clearTimeout: id => timers.delete(id),
    createState: action('state', state),
    createRuntime: action('runtime', runtime),
    createCli: action('cli', cli),
    createSession: action('session', session)
  };
  for (const name of [
    'createBrowser',
    'runBrowser',
    'prepare',
    'login',
    'authorizeCli',
    'reconcile',
    'cancel',
    'delete',
    'providerWrite'
  ]) Object.defineProperty(deps, name, {
    get() {
      assert.fail('forbidden controller capability ' + name);
    }
  });
  const turn = async () => {
    for (let i = 0; i < 400; i++) await Promise.resolve();
  };
  const tick = async at => {
    time = at;
    for (const [id, t] of [...timers]) if (t.at <= time) {
      timers.delete(id);
      t.fn();
    }
    await turn();
  };
  return {
    calls,
    seen,
    timers,
    abort,
    limits,
    state,
    runtime,
    cli,
    session,
    deps,
    target,
    baseline,
    remainder,
    final,
    turn,
    tick
  };
}

test('controller composes accepted factories and all twelve proofs under the original authority', async () => {
  const f = fixture(),
    value = await runManualRefundRemainder(options, f.limits, f.deps);
  assert.equal(value.exitCode, 0);
  assert.equal(value.report.result, 'SUCCEEDED');
  assert.equal(value.report.phase, 'CLEANUP');
  assert.deepEqual(f.calls, [
    'state',
    'admit',
    'runtime',
    'restore',
    'cli',
    'binding',
    'listener',
    'bind-target',
    'session',
    'baseline',
    'rotation',
    'remainder',
    'final',
    'session-close',
    'listener-stop',
    'runtime-stop',
    'state-close'
  ]);
  assert.equal(Object.values(value.report).filter(v => v === true).length, 12);
  assert.deepEqual(f.seen.find(([n]) => n === 'state')[1], [options]);
  assert.deepEqual(f.seen.find(([n]) => n === 'runtime')[1], [{options, state: f.state}]);
  const cliInput = f.seen.find(([n]) => n === 'cli')[1][0];
  assert.deepEqual(Object.keys(cliInput).sort(), [
    'approvedBinding',
    'backendPort',
    'cliDirectory',
    'env',
    'originalRunId',
    'persistWebhookSecret',
    'priorRunId',
    'verifyForwardTarget'
  ]);
  assert.equal(cliInput.priorRunId, prior);
  assert.equal(cliInput.backendPort, 18080);
  assert.equal(cliInput.cliDirectory, new URL('../test-results/stripe-sandbox/031b5ae7-6603-4339-b1b3-77231291ff34/cli', import.meta.url).pathname);
  assert.equal(cliInput.verifyForwardTarget, f.runtime.verifyForwardTarget);
  assert.equal(cliInput.persistWebhookSecret, f.runtime.verifyWebhookSecret);
  assert.deepEqual(f.seen.find(([n]) => n === 'bind-target')[1], [f.target, f.seen.find(([n]) => n === 'admit')[1][0]]);
  for (const name of ['admit', 'restore', 'binding', 'listener', 'baseline', 'rotation', 'remainder', 'final']) assert.equal(f.seen.find(([n]) => n === name)[1][0].deadline, 1801000);
  for (const name of ['session-close', 'listener-stop', 'runtime-stop', 'state-close']) assert.deepEqual(f.seen.find(([n]) => n === name)[1], [{deadline: 1921000}]);
  assert.equal(f.timers.size, 0);
});
test('admission denial is FAILED only with no acquired side effects and proved close', async () => {
  const f = fixture();
  f.state.admit = async () => 'DENIED';
  let v = await runManualRefundRemainder(options, f.limits, f.deps);
  assert.equal(v.report.result, 'FAILED');
  assert.equal(v.exitCode, 1);
  assert.deepEqual(f.calls, ['state', 'state-close']);
  assert.equal(v.report.listener_closed, true);
  assert.equal(v.report.runtime_closed, true);
  const g = fixture();
  g.state.admit = async () => 'DENIED';
  g.state.close = async () => {
    throw null;
  };
  v = await runManualRefundRemainder(options, g.limits, g.deps);
  assert.equal(v.report.result, 'PENDING');
});
for (const outcome of ['PENDING', 'UNKNOWN', null]) test('unknown fence ' + outcome + ' stays PENDING', async () => {
  const f = fixture();
  f.state.admit = async () => outcome;
  const v = await runManualRefundRemainder(options, f.limits, f.deps);
  assert.equal(v.report.result, 'PENDING');
  assert.equal(f.calls.includes('runtime'), false);
});
for (const [owner, method, phase] of [
  ['deps', 'createState', 'ADMISSION'],
  ['state', 'admit', 'ADMISSION'],
  ['deps', 'createRuntime', 'RESTORE'],
  ['runtime', 'restore', 'RESTORE'],
  ['deps', 'createCli', 'BINDING'],
  ['cli', 'validateBinding', 'BINDING'],
  ['cli', 'startListener', 'LISTENER'],
  ['cli', 'bindRemainderTarget', 'BASELINE'],
  ['deps', 'createSession', 'BASELINE'],
  ['session', 'verifyBaseline', 'BASELINE'],
  ['session', 'rotateCallback', 'ROTATION'],
  ['session', 'refundRemainder', 'REMAINDER'],
  ['session', 'verifyFinal', 'VERIFY']
]) test(method + ' failure latches PENDING without retry or later financial dispatch', async () => {
  const f = fixture();
  let count = 0;
  f[owner][method] = () => {
    count++;
    throw new Proxy({}, {
      get() {
        assert.fail('raw failure inspected');
      }
    });
  };
  const v = await runManualRefundRemainder(options, f.limits, f.deps);
  assert.equal(v.report.result, 'PENDING');
  assert.equal(v.report.phase, phase);
  assert.equal(v.exitCode, 1);
  assert.equal(count, 1);
  if (!['refundRemainder', 'verifyFinal'].includes(method)) assert.equal(f.calls.includes('remainder'), false);
});
for (const [method, proof] of [['verifyBaseline', 'baseline'], ['refundRemainder', 'remainder'], ['verifyFinal', 'final']]) test(method + ' requires complete exact own-data proof before promotion', async () => {
  const fields = Object.keys(fixture()[proof]);
  for (const field of fields) {
    const f = fixture(),
      bad = {...f[proof]};
    delete bad[field];
    f.session[method] = async () => bad;
    const v = await runManualRefundRemainder(options, f.limits, f.deps);
    assert.equal(v.report.result, 'PENDING', field);
    assert.equal(v.exitCode, 1);
    if (proof === 'baseline') assert.equal(f.calls.includes('rotation'), false);
  }
  for (const mutate of [
    p => ({...p, error: 'secret_canary'}),
    p => Object.assign(Object.create({}), p),
    p => Object.defineProperty({...p}, fields[0], {
      enumerable: true, get() {
        assert.fail('proof getter');
      }
    })
  ]) {
    const f = fixture();
    f.session[method] = async () => mutate(f[proof]);
    assert.equal((await runManualRefundRemainder(options, f.limits, f.deps)).exitCode, 1);
  }
});
test('wrong prior identity or reused remainder identity cannot become proof', async () => {
  for (const change of [
    f => {
      f.baseline.refundId = uid(99);
    },
    f => {
      f.remainder.refundId = uid(1);
    },
    f => {
      f.remainder.cashEventId = uid(3);
    },
    f => {
      f.remainder.amountMinor = 700;
    },
    f => {
      f.remainder.callbackSignatureVerified = false;
    }
  ]) {
    const f = fixture();
    change(f);
    const v = await runManualRefundRemainder(options, f.limits, f.deps);
    assert.equal(v.exitCode, 1);
    assert.equal(v.report.remainder_confirmed, false);
  }
});
for (const [factory, resource, close] of [
  ['createState', 'state', 'state-close'],
  ['createRuntime', 'runtime', 'runtime-stop'],
  ['createCli', 'cli', 'listener-stop'],
  ['createSession', 'session', 'session-close']
]) test('abort retains and closes late ' + resource + ' acquisition before return', async () => {
  const f = fixture(),
    late = defer();
  f.deps[factory] = () => late.promise;
  const running = runManualRefundRemainder(options, f.limits, f.deps);
  await f.turn();
  f.abort.abort();
  await f.turn();
  late.resolve(f[resource]);
  const v = await running;
  assert.equal(v.report.result, 'PENDING');
  assert.ok(f.calls.includes(close));
  assert.equal(f.calls.includes('remainder'), false);
});
test('late restore and session work settle before exact runtime stop, never resuming authority', async () => {
  for (const [owner, method] of [['runtime', 'restore'], ['session', 'refundRemainder']]) {
    const f = fixture(),
      late = defer();
    f[owner][method] = () => late.promise;
    const running = runManualRefundRemainder(options, f.limits, f.deps);
    await f.turn();
    await f.tick(1801000);
    assert.equal(f.calls.includes('runtime-stop'), false);
    late.resolve(owner === 'runtime' ? true : f.remainder);
    const v = await running;
    assert.equal(v.report.result, 'PENDING');
    assert.equal(v.report.final_total_verified, false);
    assert.ok(f.calls.includes('runtime-stop'));
    assert.equal(f.calls.filter(n => n === 'rotation').length <= 1, true);
  }
});
test('failed first cleanup stays pending and independent cleanup still runs once', async () => {
  const f = fixture();
  f.cli.stopListener = async () => {
    f.calls.push('listener-stop');
    throw null;
  };
  f.session.close = async () => {
    f.calls.push('session-close');
    throw null;
  };
  const v = await runManualRefundRemainder(options, f.limits, f.deps);
  assert.equal(v.report.result, 'PENDING');
  assert.equal(v.report.listener_closed, false);
  assert.equal(v.report.runtime_closed, true);
  assert.ok(f.calls.includes('state-close'));
  assert.equal(f.calls.filter(n => n === 'listener-stop').length, 1);
});
test('hung session or listener close cannot consume the independent runtime stop opportunity', async () => {
  for (const [owner, method, cutoff] of [['session', 'close', 1861000], ['cli', 'stopListener', 11000]]) {
    const f = fixture(),
      late = defer();
    f[owner][method] = () => late.promise;
    const running = runManualRefundRemainder(options, f.limits, f.deps);
    await f.turn();
    await f.tick(cutoff);
    assert.ok(f.calls.includes('runtime-stop'), owner + ' close suppressed stop');
    late.resolve(true);
    const v = await running;
    assert.equal(v.report.result, 'PENDING');
    if (owner === 'session') assert.equal(v.report.runtime_closed, false);
  }
});
test('stalled runtime stop cannot suppress independent state closure', async () => {
  const f = fixture(),
    late = defer();
  f.runtime.stop = () => late.promise;
  const running = runManualRefundRemainder(options, f.limits, f.deps);
  await f.turn();
  assert.ok(f.calls.includes('state-close'));
  late.reject(null);
  const v = await running;
  assert.equal(v.report.result, 'PENDING');
  assert.equal(v.report.runtime_closed, false);
});
test('unsettled restore cannot be stopped early even in the final cleanup reserve', async () => {
  const f = fixture(),
    late = defer();
  f.runtime.restore = () => late.promise;
  const running = runManualRefundRemainder(options, f.limits, f.deps);
  await f.turn();
  await f.tick(1801000);
  await f.tick(1901000);
  await f.tick(1911000);
  assert.equal(f.calls.includes('runtime-stop'), false);
  late.resolve(true);
  await f.turn();
  const v = await running;
  assert.equal(v.report.result, 'PENDING');
  assert.equal(f.calls.filter(n => n === 'runtime-stop').length, 1);
});
test('first copied diagnostic cannot be replaced by successful or failed cleanup', async () => {
  const f = fixture();
  let diagnostic = {stage: 'DETAIL', reason: 'INTEGRITY', probes: 4};
  f.session.diagnostic = () => diagnostic;
  f.session.refundRemainder = async () => {
    throw 'secret_canary';
  };
  f.session.close = async () => {
    diagnostic.reason = 'EXHAUSTED';
    diagnostic = {stage: 'CLEANUP', reason: 'UNCONFIRMED', probes: 0};
    throw null;
  };
  const v = await runManualRefundRemainder(options, f.limits, f.deps);
  assert.deepEqual(v.report.diagnostic, {stage: 'DETAIL', reason: 'INTEGRITY', probes: 4});
  assert.equal(JSON.stringify(v).includes('canary'), false);
});
test('raw admission rejects accessor and malformed flags or args before dependencies or private snapshots', async () => {
  let reads = 0;
  const trap = new Proxy({}, {
    get() {
      reads++;
      throw null;
    }
  });
  for (const flags of [
    {...options.env, PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND: 'true'},
    Object.defineProperty({...options.env}, 'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND', {
      enumerable: true, get() {
        reads++;
        throw null;
      }
    })
  ]) assert.equal((await runManualRefundRemainder({...options, env: flags}, {deadline: 1801000}, trap)).report.result, 'FAILED');
  assert.equal(reads, 0);
  for (const args of [['unexpected'], Object.defineProperty([], 'extra', {
    get() {
      reads++;
      throw null;
    }
  })]) {
    const raw = env();
    Object.defineProperty(raw, 'PAYMENT_STRIPE_SANDBOX_RUN_ID', {
      get() {
        reads++;
        throw null;
      }
    });
    const v = await runManualRefundRemainderCli({env: raw, args}, {write: async () => {}});
    assert.equal(v.exitCode, 1);
  }
  assert.equal(reads, 0);
});
test('CLI writes exactly one bounded finite JSON and removes subscriptions', async () => {
  const f = fixture(),
    lines = [];
  let removed = false;
  const raw = env();
  Object.defineProperty(raw, 'STRIPE_SECRET_KEY', {
    get() {
      assert.fail('private env read');
    }
  });
  const v = await runManualRefundRemainderCli({env: raw, args: []}, {
    ...f.deps, write: async text => lines.push(text), subscribe: () => () => {
      removed = true;
    }
  });
  assert.equal(v.exitCode, 0);
  assert.equal(lines.length, 1);
  assert.equal(JSON.parse(lines[0]).result, 'SUCCEEDED');
  assert.ok(Buffer.byteLength(lines[0]) <= 4096);
  assert.equal(removed, true);
});
test('stalled or rejected output never emits a second line or returns exit0', async () => {
  for (const rejected of [false, true]) {
    const f = fixture(),
      late = defer();
    let writes = 0;
    const running = runManualRefundRemainderCli({env: env(), args: []}, {
      ...f.deps,
      write: () => {
        writes++;
        return rejected ? Promise.reject('private_canary') : late.promise;
      },
      subscribe: () => () => {}
    });
    await f.turn();
    if (!rejected) await f.tick(1921000);
    const v = await running;
    assert.equal(v.exitCode, 1);
    assert.equal(v.report.result, 'PENDING');
    assert.equal(writes, 1);
    late.resolve();
  }
});
test('entry stream error settles with nonzero exit and one bounded attempt', async () => {
  const f = fixture(),
    stream = new EventEmitter(),
    lines = [],
    codes = [];
  stream.write = (text, cb) => {
    lines.push(text);
    cb(Error('private_canary'));
  };
  await runManualRefundRemainderEntry({env: env(), args: []}, {stdout: stream, exit: code => codes.push(code)}, {...f.deps, subscribe: () => () => {}});
  assert.deepEqual(codes, [1]);
  assert.equal(lines.length, 1);
});
test('synthetic child entry refuses empty opt-ins with real exit1 and finite stdout', () => {
  const url = new URL('./stripe-manual-refund-remainder.mjs', import.meta.url).href;
  const script = `import {runManualRefundRemainderEntry} from ${JSON.stringify(url)}; await runManualRefundRemainderEntry({env:{},args:[]},{stdout:process.stdout,exit:code=>process.exit(code)},{createState:()=>{throw Error('must never run')}});`;
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', script], {env: {PATH: '/usr/bin:/bin'}, encoding: 'utf8', timeout: 5000});
  assert.equal(child.status, 1);
  assert.equal(child.stderr, '');
  const report = JSON.parse(child.stdout);
  assert.equal(report.result, 'FAILED');
  assert.ok(Buffer.byteLength(child.stdout) <= 4096);
});
test('synthetic child exits1 after unsettled listener or stdout despite late success', () => {
  const url = new URL('./stripe-manual-refund-remainder.mjs', import.meta.url).href;
  for (const failure of ['listener', 'stdout']) {
    const script = `import {runManualRefundRemainderEntry} from ${JSON.stringify(url)};
      import assert from 'node:assert/strict';
      const options=${JSON.stringify(options)},prior=${JSON.stringify(prior)},uid=${uid.toString()};
      ${fixture.toString()}
      const env=${env.toString()};const f=fixture();
      // Synthetic owned work keeps the process live unless entry really exits.
      setInterval(()=>{},1000);
      ${failure === 'listener' ? "f.cli.stopListener=()=>new Promise(()=>{});" : ""}
      const output=${failure === 'stdout' ? "{once(){},removeListener(){},write(){}}" : "process.stdout"};
      const running=runManualRefundRemainderEntry({env:env(),args:[]},{stdout:output,exit:code=>process.exit(code)},{...f.deps,subscribe:()=>()=>{}});
      await f.turn();await f.tick(11000);await f.tick(1921000);await running;`;
    const child = spawnSync(process.execPath, ['--input-type=module', '-e', script], {env: {PATH: '/usr/bin:/bin'}, encoding: 'utf8', timeout: 5000});
    assert.equal(child.status, 1, child.stderr);
    assert.equal(child.stderr, '');
    if (failure === 'listener') {
      assert.equal(JSON.parse(child.stdout).result, 'PENDING');
      assert.equal(JSON.parse(child.stdout).listener_closed, false);
    }
    else assert.equal(child.stdout, '');
  }
});
test('cleanup ceiling never renews for late acquisition, state denial or malformed limits', async () => {
  const f = fixture(),
    late = defer();
  f.deps.createRuntime = () => late.promise;
  const running = runManualRefundRemainder(options, f.limits, f.deps);
  await f.turn();
  f.abort.abort();
  await f.turn();
  await f.tick(1921000);
  const v = await running;
  assert.equal(v.report.result, 'PENDING');
  assert.equal(v.report.runtime_closed, false);
  late.resolve(f.runtime);
  await f.turn();
  assert.equal(f.calls.includes('restore'), false);
  assert.equal(f.calls.includes('runtime-stop'), false);
  let touched = 0;
  const trap = new Proxy({}, {
    get() {
      touched++;
      throw null;
    }
  });
  for (const [limits, deps] of [
    [{deadline: 0}, trap],
    [{deadline: 1801001}, {
      now: () => 1000, createState: () => {
        touched++;
        throw null;
      }
    }],
    [Object.defineProperty({}, 'deadline', {
      enumerable: true, get() {
        touched++;
        throw null;
      }
    }), trap]
  ]) {
    const bad = await runManualRefundRemainder(options, limits, deps);
    assert.equal(bad.report.result, 'FAILED');
  }
  assert.equal(touched, 0);
});
test('oversized, canary and accessor proofs never flow into report or financial permission', async () => {
  let reads = 0;
  for (const make of [
    p => ({...p, providerReference: 're_' + 'x'.repeat(5000)}),
    p => ({...p, extra: 'secret_canary'}),
    p => Object.defineProperty({...p}, 'providerVerified', {
      enumerable: true, get() {
        reads++;
        throw 'secret_canary';
      }
    })
  ]) {
    const f = fixture();
    f.session.verifyBaseline = async () => make(f.baseline);
    const v = await runManualRefundRemainder(options, f.limits, f.deps);
    assert.equal(v.report.result, 'PENDING');
    assert.equal(f.calls.includes('rotation'), false);
    assert.equal(JSON.stringify(v).includes('canary'), false);
    assert.ok(Buffer.byteLength(JSON.stringify(v.report)) <= 4096);
  }
  assert.equal(reads, 0);
});
