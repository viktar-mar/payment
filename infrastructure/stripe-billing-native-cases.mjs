import {execFileSync, spawn} from 'node:child_process';
import {createHash, randomBytes, randomUUID, timingSafeEqual} from 'node:crypto';
import * as fs from 'node:fs/promises';
import {createServer} from 'node:net';
import {homedir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {openSandboxUserEnv, parseSandboxUserEnv, requireSandboxUserEnvUnchanged, stripeKeyNames} from './stripe-sandbox-user-env.mjs';
import {startNativePostgres} from './stripe-billing-native-postgres.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const mavenTimeoutMs = 7 * 60_000;
const caseClasses = new Map([
  ['method_isolation', 'StripeSubscriptionMethodIsolationNativeIT'],
  ['plan_upgrade', 'StripeImmediateUpgradeNativeIT'],
  ['plan_schedule', 'StripePlanScheduleNativeIT'],
  ['plan_schedule_boundary', 'StripePlanScheduleBoundaryNativeIT'],
  ['portal', 'StripeBillingPortalNativeIT'],
]);
const portalSources = ['infrastructure/stripe-billing-portal-proof.sql',
  'examples/demo-site/tests/support/stripe-billing-portal-native.mjs', 'examples/demo-site/package.json',
  'examples/demo-site/pnpm-lock.yaml'];

export function selectCase(args, now=Date.now()) {
  if (args.length !== 1 || !caseClasses.has(args[0])) throw new Error('NATIVE_CASE_SELECTION');
  requireLaunchDate(args[0],now);
  return {name:args[0], className:caseClasses.get(args[0])};
}

function requireLaunchDate(name,now) {
  if (name !== 'plan_schedule_boundary') return;
  // The Java fixture creates its clock one month before a boundary 120 seconds ahead.
  // Test both ends of the bounded run to account for crossing UTC midnight.
  const earliest=now+120_000;
  for (const instant of [earliest,earliest+mavenTimeoutMs]) {
    const day=new Date(instant);
    const previousMonthEnd=new Date(Date.UTC(day.getUTCFullYear(),day.getUTCMonth(),0));
    if (day.getUTCDate()>previousMonthEnd.getUTCDate()) throw new Error('NATIVE_SCHEDULE_MONTH_END');
  }
}

async function sourceFiles(name) {
  const paths = ['backend/pom.xml', 'backend/mvnw', 'backend/.mvn', 'backend/src',
    'infrastructure/stripe-billing-native-cases.mjs', 'infrastructure/stripe-billing-native-postgres.mjs', 'infrastructure/stripe-sandbox-user-env.mjs'];
  if (name === 'portal') paths.push(...portalSources);
  const names = execFileSync('/usr/bin/git', ['ls-files','-z','--cached','--others','--exclude-standard','--',...paths],
    {cwd:root, encoding:'utf8', maxBuffer:8*1024*1024}).split('\0').filter(Boolean).sort();
  if (!names.includes('backend/mvnw') || !names.includes('backend/pom.xml') ||
      (name === 'portal' && !portalSources.every(path => names.includes(path)))) throw new Error('NATIVE_SOURCE_INPUTS');
  return names;
}

async function hashSources(directory, names, copyTo) {
  const hash = createHash('sha256');
  for (const name of names) {
    const path = join(directory,name), stat = await fs.lstat(path);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('NATIVE_SOURCE_INPUTS');
    const bytes = await fs.readFile(path), mode = (stat.mode & 0o111) ? 1 : 0;
    hash.update(name+'\0'+mode+'\0'+bytes.length+'\0').update(bytes);
    if (copyTo) {
      await fs.mkdir(dirname(join(copyTo,name)),{recursive:true,mode:0o700});
      await fs.writeFile(join(copyTo,name),bytes,{flag:'wx',mode:mode ? 0o700 : 0o600});
    }
  }
  return hash.digest('hex');
}

function testClassName(className) {
  const testPackage=className==='StripePlanScheduleBoundaryNativeIT'
    ? 'dev.genericpayments.platform.provider.adapter.out.stripe.billing'
    : 'dev.genericpayments.platform.acceptance';
  return testPackage+'.'+className;
}

export function readResult(xml,className) {
  const header = xml.match(/<testsuite\s+([^>]+)>/)?.[1] ?? '';
  const get = key => header.match(new RegExp('(?:^|\\s)'+key+'="([^"]*)"'))?.[1];
  if (get('name') !== testClassName(className)) throw new Error('NATIVE_RESULT_MISSING');
  const counts = {};
  for (const key of ['tests','failures','errors','skipped']) {
    const value = get(key);
    if (!/^(0|[1-9][0-9]*)$/.test(value ?? '')) throw new Error('NATIVE_RESULT_MISSING');
    counts[key] = Number(value);
  }
  return counts;
}

// Only fixed failure categories and source locations from the test boundary can leave the Maven process.
export function failureLocation(log) {
  const failure = log.match(/NATIVE_CASE_FAILED(?: (?:[A-Za-z][A-Za-z0-9]*(?:Exception|Error)|[A-Za-z][A-Za-z0-9]*\.java:\d+|PORTAL_(?:INPUT|BROWSER_LAUNCH|HOSTED_DOCUMENT|RETURN_LINK|RETURN_COMMIT|CONFIGURATION_CLEANUP|CLEANUP|NO_RESULT)|HTTP_\d{3}|parameter_unknown|parameter_missing|resource_missing|card_declined|invoice_no_payment_method_types|parameter_invalid_empty)){1,15}/);
  if (failure) return failure[0];
  for (const category of ['NATIVE_SANDBOX_BINDING',
    'COMPILATION ERROR', 'Could not resolve dependencies', 'Tests are skipped']) {
    if (log.includes(category)) return category;
  }
  return 'NATIVE_CASE_INCOMPLETE';
}

function validNonce(line, nonce) {
  const expected = Buffer.from(nonce+'\n','ascii');
  return line.length === expected.length && timingSafeEqual(line,expected);
}

async function credentialSocket(directory) {
  const path = join(directory,'keys.sock'), nonce = randomBytes(32).toString('hex');
  let spent = false;
  const clients = new Set();
  const server = createServer(socket => {
    clients.add(socket);
    socket.on('error',() => {});
    socket.on('close',() => clients.delete(socket));
    socket.setTimeout(3000,() => socket.destroy());
    let received = Buffer.alloc(0);
    socket.on('data',async bytes => {
      received = Buffer.concat([received,bytes]);
      if (received.length < 65 && !received.includes(10)) return;
      if (spent || !validNonce(received,nonce)) return socket.destroy();
      spent = true;
      socket.pause();
      try {
        const held = await openSandboxUserEnv(fs,() => {});
        try {
          const {assignments} = parseSandboxUserEnv(held.bytes);
          await requireSandboxUserEnvUnchanged(held,fs,() => {});
          socket.end(JSON.stringify(stripeKeyNames.slice(0,2).map(key => assignments[key])));
        } finally {
          held.bytes.fill(0);
          await held.file.close();
        }
      } catch { socket.destroy(); }
    });
  });
  await new Promise((ok,fail) => server.once('error',fail).listen(path,ok));
  await fs.chmod(path,0o600);
  return {path,nonce,spent:() => spent,close:async () => {
    for (const client of clients) client.destroy();
    await new Promise(resolveClose => server.close(resolveClose));
  }};
}

function runMaven(directory, selected, run, keys, database) {
  const backend = join(directory,'backend');
  const env = {HOME:homedir(), PATH:process.env.PATH, LANG:'en_US.UTF-8',
    PAYMENT_NATIVE_DB_PORT:String(database.port),PAYMENT_NATIVE_DB_PASSWORD_FILE:database.passwordFile,
    PAYMENT_STRIPE_BILLING_CASE:selected.name, PAYMENT_NATIVE_RUN_ID:run, PAYMENT_NATIVE_SOCKET:keys.path,
    PAYMENT_NATIVE_NONCE:keys.nonce};
  if (process.env.JAVA_HOME) env.JAVA_HOME=process.env.JAVA_HOME;
  const child=spawn(join(backend,'mvnw'),['-o','-B','-Dsurefire.skipAfterFailureCount=1','-Dtest='+selected.className,'test'],
    {cwd:backend,env,stdio:['ignore','pipe','pipe'],detached:true});
  let tail='', timedOut=false;
  const capture=data=>{tail=(tail+data).slice(-256*1024);};
  child.stdout.on('data',capture);
  child.stderr.on('data',capture);
  function stop() {
    timedOut=true;
    if (child.pid) { try { process.kill(-child.pid,'SIGTERM'); } catch {} }
  }
  process.once('SIGTERM',stop);
  process.once('SIGINT',stop);
  const timeout=setTimeout(stop,mavenTimeoutMs);
  return new Promise((ok,fail) => {
    child.once('error',fail);
    child.once('close',code=>ok({code,tail,timedOut}));
  }).finally(()=>{
    clearTimeout(timeout);
    process.removeListener('SIGTERM',stop);
    process.removeListener('SIGINT',stop);
  });
}

export async function runNative(args) {
  const selected=selectCase(args), run=randomUUID(), directory=await fs.mkdtemp('/private/tmp/billing-native-');
  let keys, database;
  const receipt={run,case:selected.name,source:null,status:'FAILED',counts:null};
  try {
    const names=await sourceFiles(selected.name);
    receipt.source=await hashSources(root,names,directory);
    if (receipt.source !== await hashSources(root,await sourceFiles(selected.name))) throw new Error('NATIVE_SOURCE_CHANGED');
    // The dependency tree is installed locally. Portal reads its own reviewed browser script from the snapshot.
    if (selected.name==='portal') await fs.symlink(join(root,'examples/demo-site/node_modules'),join(directory,'examples/demo-site/node_modules'));
    database=await startNativePostgres(directory);
    keys=await credentialSocket(directory);
    requireLaunchDate(selected.name,Date.now());
    console.log('native_case_started '+selected.name+' run='+run+' sha256='+receipt.source);
    const result=await runMaven(directory,selected,run,keys,database);
    const report=join(directory,'backend/target/surefire-reports/TEST-'+testClassName(selected.className)+'.xml');
    let xml='';
    try {xml=await fs.readFile(report,'utf8');} catch {}
    if(xml) receipt.counts=readResult(xml,selected.className);
    if(receipt.source!==await hashSources(directory,names)) throw new Error('NATIVE_SOURCE_CHANGED');
    const counts=receipt.counts;
    if(result.code!==0 || result.timedOut || !keys.spent() || !counts || counts.tests<1 || counts.failures || counts.errors || counts.skipped) {
      receipt.failure=result.timedOut?'NATIVE_CASE_TIMEOUT':failureLocation(result.tail+'\n'+xml);
    } else {
      receipt.status='PASSED';
    }
  } catch(error) {
    if (error.message==='NATIVE_DATABASE_STOP_UNCONFIRMED') receipt.databaseStopped=false;
    receipt.failure=['NATIVE_CASE_SELECTION','NATIVE_SOURCE_CHANGED','NATIVE_SOURCE_INPUTS','NATIVE_RESULT_MISSING',
      'NATIVE_DATABASE_STOP_UNCONFIRMED','NATIVE_DATABASE_BINDING','NATIVE_DATABASE_START','NATIVE_SCHEDULE_MONTH_END'].includes(error.message)
      ?error.message:'NATIVE_LAUNCH_FAILED';
  } finally {
    await keys?.close();
    if (database) {
      try { await database.close(); receipt.databaseStopped=true; }
      catch { receipt.databaseStopped=false; receipt.status='FAILED'; receipt.failure='NATIVE_DATABASE_STOP_UNCONFIRMED'; }
    }
    if (receipt.databaseStopped!==false) await fs.rm(directory,{recursive:true,force:true});
  }
  const destination=join(root,'.superpowers','native-billing-cases',run);
  await fs.mkdir(destination,{recursive:true,mode:0o700});
  await fs.writeFile(join(destination,'completion.json'),JSON.stringify(receipt,null,2)+'\n',{flag:'wx',mode:0o600});
  return receipt;
}

if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const receipt=await runNative(process.argv.slice(2));
    console.log(JSON.stringify(receipt));
    if(receipt.status!=='PASSED') process.exitCode=1;
  } catch(error) {
    console.error(error.message==='NATIVE_SCHEDULE_MONTH_END'?error.message:'native_case_not_launched');
    process.exitCode=1;
  }
}
