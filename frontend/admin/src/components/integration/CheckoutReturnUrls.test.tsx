import {render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {expect,it,vi,afterEach} from 'vitest';
import {CheckoutReturnUrls} from './CheckoutReturnUrls';
afterEach(()=>vi.unstubAllGlobals());
it('submits the loaded version and exact origin and requires refresh after a conflict',async()=>{
  const writes:unknown[]=[];
  vi.stubGlobal('fetch',async(_url:string,init:RequestInit)=>{
    if(init.method==='PUT'){writes.push(JSON.parse(init.body as string));return Response.json({title:'Return URL version conflict'},{status:409});}
    return Response.json([{origin:'https://shop.test',return_url:'https://shop.test/payment-return',version:7}]);
  });
  render(<CheckoutReturnUrls siteId="site-1"/>);const user=userEvent.setup();
  await user.click(await screen.findByRole('button',{name:'Edit https://shop.test'}));
  await user.clear(screen.getByLabelText('Return URL'));await user.type(screen.getByLabelText('Return URL'),'https://shop.test/new-return');
  await user.click(screen.getByRole('button',{name:'Save return URL'}));
  await waitFor(()=>expect(writes).toEqual([{origin:'https://shop.test',return_url:'https://shop.test/new-return',expected_version:7}]));
  expect(await screen.findByRole('alert')).toHaveTextContent('version conflict');
});
