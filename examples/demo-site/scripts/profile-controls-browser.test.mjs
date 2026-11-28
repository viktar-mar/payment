import test from 'node:test';
import assert from 'node:assert/strict';
import {chromium} from '@playwright/test';
import {openProfileOperator} from './profile-operator.mjs';
import {buildRehearsal} from './profile-checkout-rehearsal.mjs';
import {forwardProfileClick} from '../tests/support/profile-button.ts';

test('two trusted operator clicks reach one simulated checkout despite a late previous poll',async()=>{
  const browser=await chromium.launch(process.platform==='darwin'?{channel:'chrome'}:{});
  let operator,release;
  try{
    operator=await openProfileOperator({rehearsal:true});
    const tab=await browser.newPage({serviceWorkers:'block'});
    const checkout=await browser.newPage({serviceWorkers:'block'});
    const html=await buildRehearsal(),url='http://127.0.0.1:4173/checkout';
    await checkout.route('**/*',r=>r.request().url()===url?r.fulfill({contentType:'text/html',body:html}):r.abort());
    await checkout.goto(url);
    let polls=0,held;
    const stale=new Promise(resolve=>{held=resolve;});
    const hold=new Promise(resolve=>{release=resolve;});
    await tab.route('**/state',async route=>{
      const response=await route.fetch();
      if(await response.text()==='initial'&&++polls===2){held();await hold;}
      await route.fulfill({response});
    });
    async function forward(which,id){
      const target=checkout.locator('#'+id+':enabled');
      await target.waitFor({timeout:5000});
      return forwardProfileClick(target,which,{deadline:Date.now()+15000,signal:AbortSignal.timeout(15000)},
        operator.wait,()=>assert.equal(checkout.url(),url));
    }
    await tab.goto(operator.url);
    const initial=forward('initial','pay');
    await tab.locator('#initial:enabled').waitFor();
    await stale;
    await tab.getByRole('button',{name:'Pay',exact:true}).click();
    await initial;
    const method=forward('method','confirm-method');
    await tab.locator('#method:enabled').waitFor();
    const late=tab.waitForResponse(r=>r.url().endsWith('/state'));
    release();await late;
    await tab.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    assert.equal(await tab.locator('#initial').isEnabled(),false);
    await tab.getByRole('button',{name:'Confirm test method',exact:true}).click();
    await method;
    await checkout.locator('body[data-phase="METHOD_CONFIRMED"]').waitFor();
    assert.equal(await checkout.locator('#pay').isEnabled(),false);
    assert.equal(await checkout.locator('#confirm-method').isEnabled(),false);
  }finally{release?.();try{await browser.close();}finally{await operator?.close();}}
});

test('one profile Pay activates checkout when a focused provider frame expands on blur',async()=>{
  const browser=await chromium.launch(process.platform==='darwin'?{channel:'chrome'}:{});
  let operator;
  try{
    operator=await openProfileOperator({rehearsal:true});
    const tab=await browser.newPage({serviceWorkers:'block'});
    const checkout=await browser.newPage({serviceWorkers:'block'});
    const html=await buildRehearsal(),url='http://127.0.0.1:4173/checkout';
    await checkout.route('**/*',r=>r.request().url()===url?r.fulfill({contentType:'text/html',body:html}):r.abort());
    await checkout.goto(url);
    const target=checkout.locator('#pay');
    await checkout.locator('#pay:enabled').waitFor({timeout:5000});
    await checkout.evaluate(()=>{
      const frame=document.createElement('iframe');
      frame.title='Synthetic payment input frame';
      frame.height='50';
      document.querySelector('#provider').append(frame);
      const input=frame.contentDocument.createElement('input');
      input.setAttribute('aria-label','Synthetic card field');
      frame.contentDocument.body.append(input);
      input.addEventListener('blur',()=>{frame.height='300';},{once:true});
      document.querySelector('#pay').addEventListener('click',event=>{
        if(!event.isTrusted)return;
        const body=document.body;
        body.dataset.payClicks=String(Number(body.dataset.payClicks??'0')+1);
      });
    });
    await checkout.frameLocator('iframe').getByRole('textbox',{name:'Synthetic card field'}).fill('123');
    assert.equal(await checkout.locator('iframe').evaluate(frame=>frame.contentDocument.activeElement.tagName),'INPUT');
    await tab.goto(operator.url);
    const forwarded=forwardProfileClick(target,'initial',{deadline:Date.now()+10000,signal:AbortSignal.timeout(10000)},
      operator.wait,()=>assert.equal(checkout.url(),url));
    void forwarded.catch(()=>{});
    await tab.getByRole('button',{name:'Pay',exact:true}).click({timeout:5000});
    await forwarded;
    assert.equal(await checkout.locator('iframe').count(),1);
    assert.equal(await checkout.locator('iframe').getAttribute('height'),'300');
    assert.equal(await checkout.locator('body').getAttribute('data-pay-clicks'),'1');
    await checkout.locator('body[data-phase="METHOD_READY"]').waitFor({timeout:5000});
  }finally{try{await browser.close();}finally{await operator?.close();}}
});
