import {execFile, spawn} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {readFile, writeFile} from 'node:fs/promises';
import {createServer} from 'node:net';
import {homedir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {promisify} from 'node:util';

const execute = promisify(execFile);
const user = 'billing_native';

async function availablePort() {
  const socket = createServer();
  await new Promise((resolve,reject) => socket.once('error',reject).listen(0,'127.0.0.1',resolve));
  const {port} = socket.address();
  await new Promise((resolve,reject) => socket.close(error => error ? reject(error) : resolve()));
  return port;
}

// The launcher owns directory (mkdtemp, 0700). Only signal the postgres child spawned here.
// SCRAM and the postmaster PID/data-directory check bind the selected TCP port to this run.
export async function startNativePostgres(directory) {
  const env = {HOME:homedir(),PATH:process.env.PATH,LANG:'C'};
  const data = join(directory,'pgdata'), passwordFile = join(directory,'database.key');
  const password = randomBytes(32).toString('hex');
  await writeFile(passwordFile,password+'\n',{mode:0o600,flag:'wx'});
  await execute('initdb',['-D',data,'-U',user,'--pwfile',passwordFile,'-A','scram-sha-256',
    '--no-locale','--encoding=UTF8'],{env,timeout:45000});
  const port = await availablePort();
  const child = spawn('postgres',['-D',data,'-h','127.0.0.1','-p',String(port),'-k',directory],
    {env,stdio:'ignore'});
  let ended = false;
  const exited = new Promise(resolve => {
    child.once('error',() => {ended=true; resolve();});
    child.once('close',() => {ended=true; resolve();});
  });
  const stop = () => { if (!ended) child.kill('SIGINT'); };
  process.once('exit',stop);
  process.once('SIGINT',stop);
  process.once('SIGTERM',stop);
  async function close() {
    try {
      stop();
      await Promise.race([exited,delay(10000,undefined,{ref:false})]);
      if (!ended) {
        child.kill('SIGQUIT');
        await Promise.race([exited,delay(5000,undefined,{ref:false})]);
      }
      if (!ended) throw Error('NATIVE_DATABASE_STOP_UNCONFIRMED');
    } finally {
      process.removeListener('exit',stop);
      process.removeListener('SIGINT',stop);
      process.removeListener('SIGTERM',stop);
    }
  }
  try {
    const deadline=Date.now()+15000;
    while (!ended && Date.now()<deadline) {
      let pidData;
      try { pidData=(await readFile(join(data,'postmaster.pid'),'utf8')).split('\n'); } catch {}
      if (pidData?.[7]?.trim()==='ready') {
        if (pidData[0]!==String(child.pid) || pidData[1]!==data || pidData[3]!==String(port))
          throw Error('NATIVE_DATABASE_BINDING');
        const {stdout}=await execute('psql',['-X','-w','-A','-t','-h','127.0.0.1','-p',String(port),
          '-U',user,'-d','postgres','-c',"select current_setting('data_directory')"],
          {env:{...env,PGPASSWORD:password},timeout:5000});
        if (stdout.trim()!==data) throw Error('NATIVE_DATABASE_BINDING');
        return {port,passwordFile,close};
      }
      await delay(75);
    }
    throw Error('NATIVE_DATABASE_START');
  } catch (error) {
    await close();
    throw error;
  }
}
