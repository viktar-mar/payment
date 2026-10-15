import {expect,it} from 'vitest';
import {existsSync,readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {sanitizeStripeReturnLocation} from './payment-return';

it('removes Stripe capability before application boot',()=>{
  const file=new URL('../payment-return.html',import.meta.url);
  const html=readFileSync(existsSync(file)?file:new URL('../index.html',import.meta.url),'utf8');
  const location=new URL('https://shop.example/payment-return?payment_intent=pi_demo&payment_intent_client_secret=generated-sentinel&redirect_status=succeeded#capability');
  const history={replaceState:(_state:unknown,_title:string,path:string)=>{location.href=new URL(path,location).href;}};
  for(const script of html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)) {
    if(script[1]!.includes('type="module"')) break;
    runInNewContext(script[2]!,{location,history});
  }
  expect(location.href).toBe('https://shop.example/payment-return');
});
it('clears all query and fragment input without changing the exact origin and path',()=>{
  const input=new URL('https://shop.example/payment-return?payment_intent=pi_demo&payment_intent_client_secret=generated-sentinel&redirect_status=succeeded#fragment');
  const result=sanitizeStripeReturnLocation(input);
  expect(result.href).toBe('https://shop.example/payment-return');
  expect(input.search).not.toBe('');
});
