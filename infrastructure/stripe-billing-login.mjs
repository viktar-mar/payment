import {execFile} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
import {billingCheck,billingError} from './stripe-billing-limits.mjs';
const ROOT=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const need=value=>{if(!value)throw billingError('LOGIN');};
export function billingAuthorization(raw) {
 need(typeof raw==='string'&&raw.length<65536);
 const names=['browser_url','verification_code','next_step'],value=Object.create(null);let cursor=0;
 const quoted=/"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/y;
 const whitespace=()=>{while(/[ \t\r\n]/.test(raw[cursor]??'!'))cursor++;};
 const punctuation=char=>{whitespace();need(raw[cursor++]===char);};
 const string=()=>{whitespace();quoted.lastIndex=cursor;const match=quoted.exec(raw);need(match);cursor=quoted.lastIndex;return JSON.parse(match[0]);};
 punctuation('{');for(let i=0;i<3;i++){if(i)punctuation(',');const key=string();need(names.includes(key)&&!Object.hasOwn(value,key));punctuation(':');value[key]=string();}punctuation('}');whitespace();need(cursor===raw.length&&value.next_step==='stripe login --complete-device');
 const url=new URL(value.browser_url),code=value.verification_code;
 need(url.protocol==='https:'&&['access.stripe.com','dashboard.stripe.com'].includes(url.hostname)&&!url.port&&!url.username&&!url.password&&!url.hash&&!/[\s\\\x00-\x1f\x7f]/.test(value.browser_url)&&!/(?:[spr]k_(?:test|live)_|whsec_|client_secret|%0[0-9a-f])/i.test(decodeURIComponent(value.browser_url))&&/^[A-Za-z0-9](?:[A-Za-z0-9 -]{2,126}[A-Za-z0-9])$/.test(code)&&!/(?:[spr]k_(?:test|live)|whsec|client.secret)/i.test(code));
 return `Authorize the isolated Sandbox CLI at ${url.href} using code ${code}. Keep this terminal open.\n`;
}
export async function completeBillingLogin(cli,configuration,limits,dependencies={}) {
 const execute=dependencies.execFile??execFile,output=dependencies.output??(text=>process.stdout.write(text));
 async function run(descriptor,complete) {
  billingCheck(limits);const directory=ROOT+'/test-results/stripe-sandbox/'+configuration.runId+'/cli';
  const expected=['-p','(version 1)(allow default)(deny process-exec (literal "/usr/bin/security"))','/opt/homebrew/bin/stripe','--config',directory+'/config/stripe/config.toml','--project-name','default','--color','off','--log-level','error','login',...(complete?['--complete-device']:[])];
  const env={PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',HOME:directory+'/home',XDG_CONFIG_HOME:directory+'/config',NO_COLOR:'1'};
  need(descriptor?.file==='/usr/bin/sandbox-exec'&&JSON.stringify(descriptor.args)===JSON.stringify(expected)&&descriptor.options?.cwd===ROOT&&descriptor.options.shell===false&&JSON.stringify(descriptor.options.env)===JSON.stringify(env)&&descriptor.options.maxBuffer===65536&&descriptor.options.timeout===(complete?600000:180000));
  return new Promise((resolveDone,reject)=>{
   const controller=new AbortController();let child,callback=false,closed=false,failed=false,settled=false,stdout='',stderr='',grace;
   const finish=()=>{if(!callback||!closed||settled)return;settled=true;clearTimeout(timer);clearTimeout(grace);limits.signal?.removeEventListener('abort',abort);try{billingCheck(limits);need(!failed&&typeof stdout==='string'&&typeof stderr==='string'&&Buffer.byteLength(stdout)+Buffer.byteLength(stderr)<=65536);resolveDone({stdout,stderr});}catch{reject(billingError('LOGIN'));}};
   const abort=()=>{failed=true;controller.abort();grace??=setTimeout(()=>{if(!settled){settled=true;clearTimeout(timer);limits.signal?.removeEventListener('abort',abort);reject(billingError('LOGIN'));}},10000);};
   const timer=setTimeout(abort,Math.max(1,Math.min(limits.deadline-Date.now(),descriptor.options.timeout)));
   limits.signal?.addEventListener('abort',abort,{once:true});
   try{child=execute(descriptor.file,descriptor.args,{...descriptor.options,signal:controller.signal,timeout:0},(error,out,err)=>{callback=true;failed||=!!error;stdout=out;stderr=err;finish();});child.on('error',abort);child.once('close',(code,signal)=>{closed=true;failed||=code!==0||!!signal;finish();});if(limits.signal?.aborted)abort();}
   catch{abort();}
  });
 }
 const first=await run(await cli.loginInvocation(limits),false);need(first.stderr==='');output(billingAuthorization(first.stdout));
 await run(await cli.completeLoginInvocation(limits),true);return true;
}
