import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';

function preserveRegularFile(path) {
  let file;
  try {
    file = lstatSync(path);
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
  if (!file.isFile()) throw new Error('Sandbox env must be a regular file; symlinks are rejected.');
  if (file.mode & 0o177) {
    console.warn('Warning: existing Sandbox env permissions are broader than 0600; restrict them before adding or loading keys.');
  }
  return true;
}

export function initializeSandboxEnv(path) {
  if (preserveRegularFile(path)) return 'preserved';
  const template = readFileSync(new URL('./stripe-sandbox.env.example', import.meta.url));
  try {
    writeFileSync(path, template, { flag: 'wx', mode: 0o600 });
    return 'created';
  } catch (error) {
    // Another process may have created the path since lstat; never follow it.
    if (error.code === 'EEXIST' && preserveRegularFile(path)) return 'preserved';
    throw error;
  }
}

if (process.argv[1] && pathToFileURL(realpathSync(process.argv[1])).href === import.meta.url) {
  try {
    console.log(initializeSandboxEnv(fileURLToPath(new URL('./stripe-sandbox.env.local', import.meta.url))));
  } catch {
    console.error('Sandbox env initialization failed; ensure the destination is a writable regular file, not a symlink.');
    process.exitCode = 1;
  }
}
