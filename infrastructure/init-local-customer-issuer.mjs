import {generateKeyPairSync} from 'node:crypto';
import {lstatSync, mkdirSync, realpathSync, writeFileSync} from 'node:fs';
import {dirname} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

function existingRegularFile(file) {
  try {
    if (!lstatSync(file).isFile()) throw new Error('Issuer fixture must be a regular file.');
    return true;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

/** Create local-only private fixtures without reading or replacing existing keys. */
export function initializeLocalCustomerIssuer(file) {
  if (existingRegularFile(file)) return 'preserved';
  mkdirSync(dirname(file), {recursive: true});
  const rsa = generateKeyPairSync('rsa', {modulusLength: 2048});
  const ec = generateKeyPairSync('ec', {namedCurve: 'prime256v1'});
  const keys = [
    {...rsa.privateKey.export({format: 'jwk'}), kid: 'local-fixture-rs', alg: 'RS256', use: 'sig'},
    {...ec.privateKey.export({format: 'jwk'}), kid: 'local-fixture-es', alg: 'ES256', use: 'sig'},
  ];
  try {
    writeFileSync(file, JSON.stringify({keys}, null, 2) + '\n', {flag: 'wx', mode: 0o600});
    return 'created';
  } catch (error) {
    if (error.code === 'EEXIST' && existingRegularFile(file)) return 'preserved';
    throw error;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  try {
    const status = initializeLocalCustomerIssuer(fileURLToPath(new URL('./fixtures/customer-issuer-private.jwks.json', import.meta.url)));
    console.log(status === 'created' ? 'Local-only issuer fixture created.' : 'Existing local issuer fixture preserved.');
  } catch {
    console.error('Unable to initialize local issuer fixture.');
    process.exitCode = 1;
  }
}
