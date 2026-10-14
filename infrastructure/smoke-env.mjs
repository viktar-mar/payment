/** Explicit test boundary; absent opt-out preserves the standalone fallback. */
export async function loadSmokeEnv(environment, read) {
  const env={...environment};
  if(env.PAYMENT_SMOKE_ENV_ONLY==='true')return env;
  try {for(const line of (await read()).split(/\r?\n/)) {
    const m=/^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
    if(m&&env[m[1]]===undefined)env[m[1]]=m[2].replace(/^(['"])(.*)\1$/,'$2');
  }} catch { /* Exported environments need no configuration file. */ }
  return env;
}
