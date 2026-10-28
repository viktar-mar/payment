import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {expect,it} from 'vitest';

const execute=promisify(execFile);
// Removing the request-entry scrub must leak the synthetic return query through
// real Connect debug / Vite malformed-URI warnings and fail these assertions.
const scenario=String.raw`
import {build,createServer,preview} from 'vite';
import {createServer as createHttpServer} from 'node:http';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
const embedded=process.argv[1]==='embedded';
const isPreview=process.argv[1]==='preview';
const sentinel='generated-vite-query-sentinel';
const messages=[];const observed=[];
const record=message=>{messages.push(String(message));};
const logger={info:record,warn:record,warnOnce:record,error:record,clearScreen(){},hasErrorLogged(){return false;},hasWarned:false};
let vite;let http;let ownedBuild;
try {
  const observe=server=>{server.middlewares.use((req,_res,next)=>{observed.push({url:req.url,originalUrl:req.originalUrl});next();});};
  if(isPreview){
    ownedBuild=await mkdtemp(join(tmpdir(),'final-fix-vite-preview-'));
    await build({configFile:'vite.config.ts',envFile:false,customLogger:logger,build:{outDir:ownedBuild,emptyOutDir:true}});
    vite=await preview({configFile:'vite.config.ts',envFile:false,customLogger:logger,build:{outDir:ownedBuild},
      preview:{host:'127.0.0.1',port:0,strictPort:true},
      plugins:[{name:'observe-owned-preview-requests',configurePreviewServer:observe}]});
    http=vite.httpServer;
  }else{
  vite=await createServer({configFile:'vite.config.ts',envFile:false,customLogger:logger,
    server:{host:'127.0.0.1',port:0,strictPort:true,middlewareMode:embedded,hmr:false,preTransformRequests:false},
    plugins:[{name:'observe-owned-test-requests',configureServer:observe}]});
  if(embedded){http=createHttpServer((req,res)=>{req.originalUrl??=req.url;vite.middlewares(req,res);});await new Promise(resolve=>http.listen(0,'127.0.0.1',resolve));}
  else {await vite.listen();http=vite.httpServer;}
  }
  // Emulate an embedding parent's independent originalUrl before Vite entry.
  http.prependListener('request',req=>{
    const fixture=req.headers['x-owned-original'];
    if(fixture==='return')req.originalUrl='/payment-return.html#'+sentinel;
    if(fixture==='index')req.originalUrl='/?payment_intent_client_secret='+sentinel;
    if(fixture==='invalid')req.originalUrl='/invalid%?payment_intent_client_secret='+sentinel;
  });
  const origin='http://127.0.0.1:'+http.address().port;
  const results=[];
  const returnPaths=['/payment-return','/payment-return.html','/%70ayment-return','/%70ayment-return.html'];
  for(const path of returnPaths) {
    for(const suffix of ['', '&bad=%']) {
      const response=await fetch(origin+path+'?payment_intent_client_secret='+sentinel+suffix,{redirect:'error',signal:AbortSignal.timeout(5000)});
      const body=await response.text();
      results.push({status:response.status,returnPage:body.includes('<h1>Payment return</h1>'),noReferrer:response.headers.get('referrer-policy')==='no-referrer'});
    }
  }
  const publicQuery='/?site_key=public-site&price_key=public-price&locale=en';
  const response=await fetch(origin+publicQuery,{redirect:'error',signal:AbortSignal.timeout(5000)});
  await response.text();
  const publicNearMatch='/payment-return.html-extra?site_key=public-site';
  await (await fetch(origin+publicNearMatch,{signal:AbortSignal.timeout(5000)})).text();
  await (await fetch(origin+'/?site_key=public-warning-check&bad=%',{signal:AbortSignal.timeout(5000)})).text();
  await (await fetch(origin+'/payment-return%?payment_intent_client_secret='+sentinel,{signal:AbortSignal.timeout(5000)})).text();
  const rewrittenStatuses=[];
  for(const [path,original] of [['/','return'],['/payment-return.html','index'],['/','invalid']]) {
    const response=await fetch(origin+path+'?payment_intent_client_secret='+sentinel,{headers:{'x-owned-original':original},signal:AbortSignal.timeout(5000)});
    rewrittenStatuses.push(response.status);await response.text();
  }
  console.log(JSON.stringify({results,rewrittenStatuses,publicQueryPreserved:observed.some(request=>request.url===publicQuery&&request.originalUrl===publicQuery),
    publicNearMatchPreserved:observed.some(request=>request.url===publicNearMatch&&request.originalUrl===publicNearMatch),
    returnPathsPreserved:returnPaths.every(path=>observed.some(request=>request.url===path&&request.originalUrl===path)),
    publicWarningLogged:messages.some(message=>message.includes('public-warning-check')),
    downstreamReturnQuery:observed.some(request=>Object.values(request).some(url=>url?.includes(sentinel))),loggerLeak:messages.some(message=>message.includes(sentinel))}));
} finally {
  if(embedded&&http)await new Promise(resolve=>{http.close(resolve);http.closeAllConnections();});
  if(vite)await vite.close();
  if(ownedBuild)await rm(ownedBuild,{recursive:true,force:true});
}
`;

it.each(['standalone','embedded','preview'])('keeps return capabilities out of actual Vite %s request/error/debug logs',async mode=>{
  const {stdout,stderr}=await execute(process.execPath,['--input-type=module','-e',scenario,mode],{
    cwd:process.cwd(),timeout:25000,maxBuffer:1024*1024,
    env:{...process.env,DEBUG:'connect:dispatcher,vite:transform,vite:html,vite:time'},
  }).catch(()=>{throw new Error('Owned Vite child failed; captured output withheld');});
  const result=JSON.parse(stdout.trim().split('\n').at(-1)!);
  expect(result.results).toEqual(Array.from({length:8},()=>({status:200,returnPage:true,noReferrer:true})));
  expect(result.publicQueryPreserved).toBe(true);
  expect(result.publicNearMatchPreserved).toBe(true);
  expect(result.returnPathsPreserved).toBe(true);
  if(mode!=='preview')expect(result.publicWarningLogged).toBe(true);
  expect(result.rewrittenStatuses).toEqual([200,200,200]);
  expect(stderr.includes('connect:dispatcher')).toBe(true);
  // Assert booleans so failures never print captured URL/log content.
  expect(result.loggerLeak,'Vite logger retained return capability').toBe(false);
  expect(stderr.includes('generated-vite-query-sentinel'),'debug stderr retained return capability').toBe(false);
  expect(stdout.includes('generated-vite-query-sentinel'),'stdout retained return capability').toBe(false);
  expect(result.downstreamReturnQuery,'downstream middleware retained return capability').toBe(false);
},30000);
