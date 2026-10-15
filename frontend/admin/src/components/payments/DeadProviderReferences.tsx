"use client";
import {useEffect,useState} from 'react';
import {api,errorTitle} from '../../lib/api/client';
import type {components} from '../../lib/api/schema';
type Receipt=Required<components['schemas']['DeadProviderReferenceView']>;
export function DeadProviderReferences() {
  const [rows,setRows]=useState<Receipt[]>([]); const [after,setAfter]=useState<string>();
  const [error,setError]=useState(''); const [revision,setRevision]=useState(0);
  useEffect(()=>{
    const controller=new AbortController();
    api<Receipt[]>('v1/admin/provider-operations/dead-references',{query:{after,limit:20},signal:controller.signal})
      .then(value=>{if(!controller.signal.aborted){setRows(value);setError('');}})
      .catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});
    return ()=>controller.abort();
  },[after,revision]);
  return <section className="surface" aria-label="Dead provider references"><h2>Dead provider references</h2>
    <p>Unmatched or exhausted receipts may have no local payment. Inspection does not retry or apply money.</p>
    <button onClick={()=>setRevision(value=>value+1)}>Refresh dead references</button>{error&&<p role="alert">{error}</p>}
    {!rows.length&&<p>No dead reference receipts in this view.</p>}
    <ul>{rows.map(row=><li key={row.receipt_id}><h3>{row.receipt_id}</h3><p>{row.status} · {row.object_type} · {row.safe_error_code}</p>
      <p>Account {row.account_id} · Original Account mode: {row.account_mode??'Unavailable'}</p><p>Event {row.event_id} · Object {row.object_id}</p><p>Occurred {row.occurred_at} · Received {row.received_at} · Attempts {row.attempt_count}</p></li>)}</ul>
    <button disabled={!after} onClick={()=>setAfter(undefined)}>First references</button>
    <button disabled={rows.length<20} onClick={()=>setAfter(rows.at(-1)?.receipt_id)}>Next references</button>
  </section>;
}
