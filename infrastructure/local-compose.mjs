import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const localPath = name => fileURLToPath(new URL(name, import.meta.url));

export function buildLocalComposeInvocation(args, {platform, uid, gid, env}) {
  if (args.length !== 1 || !['up', 'stop'].includes(args[0])) {
    throw new Error('Usage: node infrastructure/local-compose.mjs up|stop');
  }
  if (!['linux', 'darwin'].includes(platform)) {
    throw new Error('Local Compose requires Linux or macOS with POSIX UID/GID support.');
  }
  if (!Number.isInteger(uid) || uid <= 0 || uid >= 0xffffffff ||
      !Number.isInteger(gid) || gid < 0 || gid >= 0xffffffff) {
    throw new Error('Local Compose requires valid UID/GID and a non-root UID; do not use sudo.');
  }
  return {
    command: 'docker',
    cwd: root,
    args: ['compose', '--env-file', localPath('.env'), '-f', localPath('compose.yaml'),
      '-f', localPath('compose.local-user.yaml'), args[0], ...(args[0] === 'up' ? ['--build', '-d', '--wait'] : [])],
    env: {...env, PAYMENT_LOCAL_UID: String(uid), PAYMENT_LOCAL_GID: String(gid)},
  };
}

if (import.meta.main) {
  try {
    const invocation = buildLocalComposeInvocation(process.argv.slice(2), {
      platform: process.platform, uid: process.getuid?.(), gid: process.getgid?.(), env: process.env,
    });
    const result = spawnSync(invocation.command, invocation.args,
      {cwd: invocation.cwd, env: invocation.env, stdio: 'inherit'});
    if (result.error) console.error('Unable to start Docker for local Compose.');
    process.exitCode = result.status ?? 1;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
