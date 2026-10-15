import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { PaymentDetails } from './PaymentDetails';
import { details } from './test-fixtures';
import {ProviderOperationPanel} from './ProviderOperationPanel';
import {DeadProviderReferences} from './DeadProviderReferences';

afterEach(() => vi.unstubAllGlobals());
it('makes original unresolved Stripe operations visible without exposing internal facts', async () => {
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.includes('provider-operations') ? [{
    operation_id: 'original-operation', payment_id: 'payment-1', account_id: 'original-account', account_mode:'LIVE',
    kind: 'PAYMENT_PREPARE', status: 'UNKNOWN', reference: null, first_dispatched_at: '2026-10-01T10:00:00Z',
    retry_before: '2026-10-02T09:00:00Z', next_attempt_at: '2026-10-02T10:00:00Z',
    safe_error_code: 'PROVIDER_UNAVAILABLE', manual_review_required: true,
    credentials_ref: 'credential-sentinel', fingerprint: 'fingerprint-sentinel',
  }] : { ...details, payment: { ...details.payment, provider: 'STRIPE', status: 'UNKNOWN' } }));
  render(<PaymentDetails paymentId="payment-1" />);
  expect(await screen.findByText('original-operation')).toBeInTheDocument();
  expect(within(screen.getByRole('region',{name:'Provider operations'})).getByText('LIVE')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Recover original operation' })).toBeInTheDocument();
  expect(document.body.textContent).not.toMatch(/credential-sentinel|fingerprint-sentinel/);
});
it('retains the same proof and key after a postcommit error and reopening, without claiming settlement',async()=>{
  const requests:RequestInit[]=[];
  document.cookie='XSRF-TOKEN=csrf-fixture';
  vi.stubGlobal('fetch',async(_url:string,init:RequestInit)=>{
    if(init.method==='POST') {requests.push(init); return requests.length===1?Response.json({title:'Provider temporarily unavailable'},{status:503}):Response.json({status:'ACCEPTED'},{status:202});}
    return Response.json([{operation_id:'operation-1',account_id:'account-1',kind:'PAYMENT_PREPARE',status:'UNKNOWN',manual_review_required:true}]);
  });
  render(<ProviderOperationPanel paymentId="payment-1"/>); const user=userEvent.setup();
  await user.click(await screen.findByRole('button',{name:'Recover original operation'}));
  await user.type(screen.getByLabelText('Provider reference'),'pi_original'); await user.type(screen.getByLabelText('Creation event ID'),'evt_original');
  await user.click(screen.getByRole('button',{name:'Verify and recover'}));
  expect(await screen.findByRole('alert')).toHaveTextContent('Binding may already be accepted');
  await user.click(screen.getByRole('button',{name:'Cancel'}));
  await user.click(screen.getByRole('button',{name:'Recover original operation'}));
  expect(screen.getByLabelText('Provider reference')).toBeDisabled();
  await user.click(screen.getByRole('button',{name:'Retry same recovery'}));
  await waitFor(()=>expect(requests).toHaveLength(2));
  expect(new Headers(requests[0]!.headers).get('Idempotency-Key')).toBe(new Headers(requests[1]!.headers).get('Idempotency-Key'));
  expect(new Headers(requests[1]!.headers).get('X-XSRF-TOKEN')).toBe('csrf-fixture');
  expect(JSON.parse(requests[1]!.body as string)).toEqual({provider_reference:'pi_original',creation_event_id:'evt_original'});
  expect(await within(screen.getByRole('dialog')).findByRole('status')).toHaveTextContent('This does not confirm payment');
});
it('shows catalog follow-up and dead unmatched references independently without recovery actions on receipts',async()=>{
  vi.stubGlobal('fetch',async(url:string)=>Response.json(url.includes('dead-references')?[{
    receipt_id:'dead-1',account_id:'account-1',account_mode:'SANDBOX',event_id:'evt_foreign',object_id:'pi_foreign',object_type:'PAYMENT',
    occurred_at:'2026-10-01T00:00:00Z',received_at:'2026-10-01T00:00:01Z',attempt_count:12,status:'DEAD',safe_error_code:'PROCESSING_FAILED',
  }]:[{operation_id:'catalog-1',account_id:'account-1',site_id:'site-1',kind:'PRICE_CREATE',status:'ACKNOWLEDGED',manual_review_required:true,safe_error_code:'CATALOG_PUBLICATION_REQUIRED'}]));
  render(<><ProviderOperationPanel/><DeadProviderReferences/></>);
  expect(await screen.findByRole('link',{name:'Review catalog publication'})).toHaveAttribute('href','/sites/site-1/catalog');
  expect(await screen.findByText('dead-1')).toBeInTheDocument();
  expect(within(screen.getByRole('region',{name:'Dead provider references'})).getByText(/Original Account mode: SANDBOX/)).toBeInTheDocument();
  expect(within(screen.getByRole('region',{name:'Dead provider references'})).queryByRole('button',{name:/Recover/})).not.toBeInTheDocument();
});
