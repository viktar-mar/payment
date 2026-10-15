"use client";
import Link from 'next/link';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorTitle } from '../../lib/api/client';
import { ActionDialog } from './ActionDialog';
import type {components} from '../../lib/api/schema';

type OperationSchema=components['schemas']['ProviderOperationView'];
export type ProviderOperation = OperationSchema & Required<Pick<OperationSchema,'operation_id'|'account_id'|'kind'|'status'>>;
type RecoveryIntent={reference:string; event:string; key?:string; accepted?:boolean};
export function ProviderOperationPanel({ paymentId }: {paymentId?:string}) {
  const [rows,setRows]=useState<ProviderOperation[]>([]);
  const [after,setAfter]=useState<string>(); const [revision,setRevision]=useState(0);
  const [error,setError]=useState(''); const [selected,setSelected]=useState<string>();
  const [reference,setReference]=useState(''); const [event,setEvent]=useState('');
  const [pending,setPending]=useState(false); const [result,setResult]=useState('');
  const [owned,setOwned]=useState<RecoveryIntent>();
  const intents=useRef(new Map<string,RecoveryIntent>()); const busy=useRef(false);
  const mutation=useRef<AbortController|undefined>(undefined);
  useEffect(()=>()=>mutation.current?.abort(),[]);
  useEffect(()=>{
    const controller=new AbortController();
    api<ProviderOperation[]>(paymentId?'v1/admin/provider-operations':'v1/admin/provider-operations/unresolved',
      {query:paymentId?{payment_id:paymentId}:{after,limit:20},signal:controller.signal})
      .then(value=>{if(!controller.signal.aborted)setRows(Array.isArray(value)?value:[]);})
      .catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});
    return ()=>controller.abort();
  },[paymentId,after,revision]);
  function open(id:string) {
    const old=intents.current.get(id); setSelected(id); setReference(old?.reference??''); setEvent(old?.event??'');
    setOwned(old?{...old}:undefined);
    setResult(old?.accepted?'Recovery accepted; inspect current state. This does not confirm payment.':''); setError('');
  }
  async function recover(e:FormEvent) {
    e.preventDefault(); if(!selected || busy.current) return;
    if(!reference.trim() || !event.trim() || reference.length>255 || event.length>255 || /[\x00-\x1f\x7f]/.test(reference+event)) {
      setError('Provide the original provider reference and matching creation event.'); return;
    }
    let intent=intents.current.get(selected);
    if(!intent?.key) {intent={reference,event,key:crypto.randomUUID()}; intents.current.set(selected,intent);setOwned({...intent});}
    if(intent.accepted) return;
    busy.current=true; setPending(true); setError(''); mutation.current=new AbortController();
    try {
      await api(`v1/admin/provider-operations/${selected}/recover`,{method:'POST',
        body:{provider_reference:intent.reference,creation_event_id:intent.event},idempotencyKey:intent.key,signal:mutation.current.signal});
      if(mutation.current.signal.aborted) return;
      intent.accepted=true; setOwned({...intent});setResult('Recovery accepted; inspect current state. This does not confirm payment.');
      setRevision(value=>value+1);
    } catch(failure) {
      if(!mutation.current.signal.aborted) setError(`${errorTitle(failure)}. Binding may already be accepted. Check status, then retry the same proof.`);
    } finally {busy.current=false; if(!mutation.current.signal.aborted)setPending(false);}
  }
  return <section className="surface" aria-label="Provider operations"><h2>Provider operations</h2>
    <p>Original operations only. Acknowledgement is not payment settlement.</p>
    <button onClick={()=>setRevision(value=>value+1)}>Check operation status</button>
    {error && !selected && <p role="alert">{error}</p>}
    {!rows.length && <p>No operations in this view.</p>}
    <ul>{rows.map(row=><li key={row.operation_id}>
      <h3>{row.operation_id}</h3><p>{row.kind} · {row.status} · {row.safe_error_code??'No error'}</p>
      <dl><dt>Original account</dt><dd>{row.account_id}</dd><dt>Original Account mode</dt><dd>{row.account_mode??'Unavailable'}</dd><dt>Reference</dt><dd>{row.reference??'Not bound'}</dd>
        <dt>First dispatched</dt><dd>{row.first_dispatched_at??'Not dispatched'}</dd>
        <dt>Retry before</dt><dd>{row.retry_before??'—'}</dd><dt>Next attempt</dt><dd>{row.next_attempt_at??'—'}</dd></dl>
      {row.payment_id && !paymentId && <Link href={`/payments/${row.payment_id}`}>Inspect payment</Link>}
      {['PRODUCT_CREATE','PRICE_CREATE'].includes(row.kind) && row.site_id && <p>Catalog publication requires an audited Admin action. <Link href={`/sites/${row.site_id}/catalog`}>Review catalog publication</Link></p>}
      {(row.manual_review_required || ['UNKNOWN','FAILED'].includes(row.status)) && <button onClick={()=>open(row.operation_id)}>Recover original operation</button>}
    </li>)}</ul>
    {paymentId?<p>Showing at most 100 original operations. <Link href="/provider-operations">Global unresolved operations and dead references</Link></p>:
      <><button disabled={!after} onClick={()=>setAfter(undefined)}>First operations</button><button disabled={rows.length<20} onClick={()=>setAfter(rows.at(-1)?.operation_id)}>Next operations</button></>}
    {selected && <ActionDialog title="Recover original operation" pending={pending} onClose={()=>setSelected(undefined)}>
      <p>Verify the original creation event and provider object. This does not create a new payment or refund.</p>
      <form onSubmit={recover} noValidate>
        <label>Provider reference<input value={reference} maxLength={255} disabled={pending||!!owned?.key} onChange={e=>setReference(e.target.value)}/></label>
        <label>Creation event ID<input value={event} maxLength={255} disabled={pending||!!owned?.key} onChange={e=>setEvent(e.target.value)}/></label>
        {error&&<p role="alert">{error}</p>}<p role="status">{result}</p>
        <button disabled={pending||owned?.accepted}>{owned?.key?'Retry same recovery':'Verify and recover'}</button>
        <button type="button" onClick={()=>setRevision(value=>value+1)}>Check operation status</button>
      </form>
    </ActionDialog>}
  </section>;
}
