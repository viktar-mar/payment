import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import { boundedJson } from './smoke-stage-3b.mjs';

const executeFile = promisify(execFile);
const options = { timeout: 10000, maxBuffer: 8388608 };
function owners(env) {
  if (env.PAYMENT_SMOKE_ENV_ONLY !== 'true'
      || !/^[a-z0-9][a-z0-9_-]+$/.test(env.PAYMENT_E2E_COMPOSE_PROJECT ?? '')) throw new Error('owned_configuration');
  const entries = [[env.PAYMENT_SMOKE_BACKEND_OWNER, 'backend'], [env.PAYMENT_SMOKE_RECEIVER_OWNER, 'callback-receiver']];
  if (entries.some(([id]) => !/^[0-9a-f]{64}$/.test(id ?? '')) || entries[0][0] === entries[1][0]) throw new Error('owned_configuration');
  return entries;
}

export async function verifyStage2Ownership(env, execute = executeFile) {
  const entries = owners(env);
  for (const [id, service] of entries) {
    const result = await execute('docker', ['inspect', '--format', '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}', id], options);
    if (result.stdout.trim() !== `${id}|${env.PAYMENT_E2E_COMPOSE_PROJECT}|${service}`) throw new Error('owned_identity');
  }
  return entries;
}

export async function stage2OwnedLogs(env, execute = executeFile) {
  const since = env.PAYMENT_SMOKE_LOG_SINCE;
  if (!since || !Number.isFinite(Date.parse(since)) || new Date(since).toISOString() !== since) throw new Error('owned_log_boundary');
  const entries = await verifyStage2Ownership(env, execute);
  let logs = '';
  for (const [id] of entries) {
    const result = await execute('docker', ['logs', '--since', since, '--tail', '2000', id], options);
    logs += result.stdout + (result.stderr ?? '');
  }
  return logs;
}

export async function stage2OwnedReceiver(env, path, method, status, body) {
  owners(env);
  const url = new URL(env.PAYMENT_SMOKE_RECEIVER_URL);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('owned_receiver_endpoint');
  if (!((path === '/configure' && method === 'POST' && status === 204) || (path === '/inspection' && method === 'GET' && status === 200))) throw new Error('owned_receiver_operation');
  const response = await fetch(new URL(path, url), { method, redirect: 'error', signal: AbortSignal.timeout(10000), ...(body === undefined ? {} : { headers: {'content-type':'application/json'}, body:JSON.stringify(body) }) });
  if (response.status !== status) throw new Error('owned_receiver_status');
  return boundedJson(response);
}

export async function stage2OwnedSettlement(env, paymentId, post) {
  if (env.PAYMENT_FAKE_AUTO_SUCCESS !== 'false') return;
  owners(env);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(paymentId)) throw new Error('owned_payment_identity');
  await post(`/api/v1/admin/payments/${paymentId}/fake-events`, {target:'PAYMENT', event_type:'PAYMENT_SUCCEEDED', sequence:7}, 202, {'Idempotency-Key':randomUUID()});
}
