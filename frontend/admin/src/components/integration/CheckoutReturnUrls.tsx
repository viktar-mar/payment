"use client";
import {useEffect,useState,type FormEvent} from 'react';
import {api,errorTitle} from '../../lib/api/client';
import type {components} from '../../lib/api/schema';
type Policy=Required<components['schemas']['CheckoutReturnUrlPolicy']>;
export function CheckoutReturnUrls({siteId}:{siteId:string}) {
  const [rows,setRows]=useState<Policy[]>([]);const [revision,setRevision]=useState(0);
  const [origin,setOrigin]=useState('');const [url,setUrl]=useState('');const [version,setVersion]=useState(-1);
  const [pending,setPending]=useState(false);const [error,setError]=useState('');const [saved,setSaved]=useState(false);
  const path=`v1/admin/sites/${siteId}/checkout-return-urls`;
  useEffect(()=>{const controller=new AbortController();
    api<Policy[]>(path,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setRows(value);})
      .catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});return ()=>controller.abort();
  },[path,revision]);
  async function submit(event:FormEvent) {
    event.preventDefault();if(pending)return;setPending(true);setError('');setSaved(false);
    try {await api(path,{method:'PUT',body:{origin,return_url:url,expected_version:version}});
      setVersion(version+1);setSaved(true);setRevision(value=>value+1);
    }catch(failure){setError(`${errorTitle(failure)}. Refresh and select the current policy before retrying.`);}
    finally{setPending(false);}
  }
  return <section className="surface" aria-label="Checkout return URLs"><h2>Checkout return URLs</h2>
    <p>Use an exact allowed Site origin and same-origin return page. Changes are versioned and audited.</p>
    <button disabled={pending} onClick={()=>setRevision(value=>value+1)}>Refresh return URLs</button>
    <ul>{rows.map(row=><li key={row.origin}>{row.origin} → {row.return_url} · Version {row.version}
      <button disabled={pending} onClick={()=>{setOrigin(row.origin);setUrl(row.return_url);setVersion(row.version);setSaved(false);}}>Edit {row.origin}</button></li>)}</ul>
    <button disabled={pending} onClick={()=>{setOrigin('');setUrl('');setVersion(-1);setSaved(false);}}>New return URL</button>
    <form onSubmit={submit}><label>Exact origin<input required maxLength={255} value={origin} disabled={pending||version>=0} onChange={event=>setOrigin(event.target.value)}/></label>
      <label>Return URL<input required maxLength={2048} value={url} disabled={pending} onChange={event=>setUrl(event.target.value)}/></label>
      <button disabled={pending}>Save return URL</button></form>
    {error&&<p role="alert">{error}</p>}{saved&&<p role="status">Return URL saved.</p>}
  </section>;
}
