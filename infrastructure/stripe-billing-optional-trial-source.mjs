// Keep these inputs aligned with COPY in the dedicated backend Dockerfile and the demo-site
// Dockerfile. Also cover both Docker ignore mechanisms and the owned runtime's host bind.
const inputs=[
  '.dockerignore',
  'backend/.dockerignore',
  'backend/Dockerfile.billing-optional-trial',
  'backend/Dockerfile.billing-optional-trial.dockerignore',
  'backend/pom.xml',
  'backend/src',
  'backend/export-billing-optional-trial-runtime.sh',
  'frontend/package.json',
  'frontend/pnpm-lock.yaml',
  'frontend/pnpm-workspace.yaml',
  'frontend/sdk',
  'examples/demo-site/Dockerfile',
  'examples/demo-site/Dockerfile.dockerignore',
  'examples/demo-site/package.json',
  'examples/demo-site/pnpm-lock.yaml',
  'examples/demo-site/index.html',
  'examples/demo-site/payment-return.html',
  'examples/demo-site/subscription-return.html',
  'examples/demo-site/form-load-diagnostic.html',
  'examples/demo-site/form-load-manual.html',
  'examples/demo-site/manual-payment.html',
  'examples/demo-site/billing-first-payment.html',
  'examples/demo-site/billing-failed-renewal.html',
  'examples/demo-site/nginx.conf',
  'examples/demo-site/src',
  'examples/demo-site/tsconfig.json',
  'examples/demo-site/vite.config.ts',
  'infrastructure/callback-receiver.mjs',
  'infrastructure/compose.yaml',
  'infrastructure/compose.stripe-sandbox.yaml',
  'infrastructure/stripe-manual-form-evidence.mjs',
  'infrastructure/stripe-manual-form-evidence.d.mts',
  'infrastructure/stripe-form-diagnostic-schema.mjs',
  'infrastructure/stripe-form-diagnostic-schema.d.mts',
  'infrastructure/stripe-form-diagnostic-environment.mjs',
  'infrastructure/stripe-form-diagnostic-environment.d.mts',
  'infrastructure/stripe-form-diagnostic-network.d.mts',
];

// Include case variants on case-insensitive source volumes. Exclude only the SDK output
// spellings at the SDK root, which the root Docker ignore file drops.
const generated=['frontend/sdk/node_modules','frontend/sdk/dist'];
const paths=[...inputs.map(path=>':(top,literal,icase)'+path),...generated.map(path=>':(top,literal,exclude)'+path)];

/** Only empty output is accepted. Git ignore files are deliberately outside this query. */
export async function hasOnlyTrackedOptionalTrialInputs(execute,settings){
  const result=await execute('/usr/bin/git',['-c','core.fsmonitor=false','ls-files','--others','-z','--',...paths],settings);
  return result?.stdout===''&&result?.stderr==='';
}
