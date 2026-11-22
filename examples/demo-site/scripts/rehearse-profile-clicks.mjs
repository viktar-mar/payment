import {mkdtemp} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {chromium} from '@playwright/test';
import {buildRehearsal} from './profile-checkout-rehearsal.mjs';
import {openProfileOperator,writeProfileHandoff} from './profile-operator.mjs';
import {forwardProfileClick} from '../tests/support/profile-button.ts';

const html=await buildRehearsal();
const operator=await openProfileOperator({rehearsal:true});
let browser;
try {
  const file=join(await mkdtemp(join(tmpdir(),'profile-buttons-')),'operator.html');
  await writeProfileHandoff(file,operator);
  browser=await chromium.launch({channel:'chrome',headless:true});
  const context=await browser.newContext({serviceWorkers:'block',acceptDownloads:false});
  const simulation='http://127.0.0.1:4173/checkout';
  const page=await context.newPage();
  let served=false,unexpected=false;
  await context.route('**/*',route=>{
    if(!served&&route.request().url()===simulation&&route.request().isNavigationRequest()){
      served=true;return route.fulfill({contentType:'text/html; charset=utf-8',body:html});
    }
    unexpected=true;return route.abort();
  });
  // A normal loopback port is required by Chrome's restricted-port policy.
  // The rehearsal document itself is fulfilled in memory.
  await page.goto(simulation);
  for(const [action,id] of [['initial','pay'],['method','confirm-method']]){
    await page.locator('#'+id+':enabled').waitFor({timeout:10000});
    if(action==='initial')console.log('OPEN '+pathToFileURL(file).href);
    const limits={deadline:Date.now()+180000,signal:AbortSignal.timeout(180000)};
    await forwardProfileClick(page.locator('#'+id),action,limits,operator.wait,()=>{
      if(!served||unexpected||page.url()!==simulation)throw Error('Rehearsal page changed');
    });
    console.log(action+' click delivered');
  }
  await page.locator('body[data-phase="METHOD_CONFIRMED"]').waitFor({timeout:5000});
  if(unexpected)throw Error('Unexpected rehearsal request');
  console.log('Same checkout document: METHOD_CONFIRMED');
}finally{
  try{await browser?.close();}finally{await operator.close();}
}
