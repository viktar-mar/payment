// Keep the denied browser on the owned demo server, at a different Origin.
export function alternateLoopbackOrigin(origin) {
  const url = new URL(origin);
  if (!['http:', 'https:'].includes(url.protocol) ||
      !['127.0.0.1', 'localhost'].includes(url.hostname)) {
    throw new Error('Owned loopback demo URL required');
  }
  url.hostname = url.hostname === '127.0.0.1' ? 'localhost' : '127.0.0.1';
  return url.origin;
}
