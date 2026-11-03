import test,{mock,after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,writeFile,chmod,rm} from 'node:fs/promises';
import childProcess from 'node:child_process';
import net from 'node:net';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {CONTAINER_FORMAT,firstPaymentContainerFormat,firstRenewalContainerFormat} from './stripe-sandbox-ownership.mjs';

const forbidden=()=>{throw new Error('synthetic_probe_forbids_network_or_unapproved_process');};
mock.method(globalThis,'fetch',forbidden);mock.method(net,'connect',forbidden);mock.method(net,'createConnection',forbidden);mock.method(net,'createServer',forbidden);
const spawnSync=childProcess.spawnSync;
mock.method(childProcess,'exec',forbidden);mock.method(childProcess,'execFile',forbidden);mock.method(childProcess,'spawn',forbidden);mock.method(childProcess,'spawnSync',forbidden);
after(()=>mock.restoreAll());

// Break: the image probe emits bodies or collapses curl's failure exit to one.
for(const lane of ['payment','renewal'])test('dedicated '+lane+' actual probe discards body and retains curl failure offline',async()=>{
 const source=await readFile(new URL('../backend/Dockerfile.billing-first-'+lane,import.meta.url),'utf8');
 const health=/HEALTHCHECK ([^\n]+) \\\n\s+CMD ([^\n]+)/.exec(source);assert.ok(health);
 assert.equal(health[1],'--interval=10s --timeout=3s --start-period=300s --retries=12');
 const directory=await mkdtemp(join(tmpdir(),'synthetic-health-probe-'));
 try{
  const fake=join(directory,'curl');
  await writeFile(fake,`#!/bin/sh
discard=false
write=false
fail=false
silent=false
target=false
while [ "$#" -gt 0 ]; do
 case "$1" in
  --fail) fail=true; shift ;;
  --silent) silent=true; shift ;;
  --output) [ "$2" = /dev/null ] || exit 96; discard=true; shift 2 ;;
  --write-out) [ "$2" = '%{http_code}' ] || exit 97; write=true; shift 2 ;;
  http://127.0.0.1:8080/actuator/health/readiness) target=true; shift ;;
  *) exit 98 ;;
 esac
done
[ "$fail" = true ] && [ "$silent" = true ] && [ "$target" = true ] || exit 99
if [ "$discard" != true ]; then printf private-response-body-sentinel; fi
if [ "$write" = true ]; then printf '%s' "$FAKE_HTTP"; fi
exit "$FAKE_EXIT"
`);await chmod(fake,0o700);
  for(const [exit,http] of [[0,'200'],[22,'503'],[22,'401'],[22,'403'],[22,'404'],[7,'000'],[28,'000']]){
   // Only an absolute shell is permitted. PATH contains solely our synthetic curl.
   const result=spawnSync('/bin/sh',['-c',health[2]],{env:{PATH:directory,FAKE_EXIT:String(exit),FAKE_HTTP:http},encoding:'utf8',timeout:2000,maxBuffer:1024});
   assert.equal(result.error,undefined);assert.equal(result.status,exit);assert.equal(result.stdout,http);assert.equal(result.stderr,'');
  }
 }finally{await rm(directory,{recursive:true,force:true});}
});

test('default backend retains its existing health grace',async()=>{
 const source=await readFile(new URL('../backend/Dockerfile',import.meta.url),'utf8');
 assert.match(source,/HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12/);
});

// Structural policy check only: no Docker or Go interpreter is launched.
test('only dedicated projections reduce latest health attempt to closed hints without emitting logs',()=>{
 assert.doesNotMatch(CONTAINER_FORMAT,/BackendHealthFailure|\.State\.Health\.Log|\.Output/);
 for(const format of [firstPaymentContainerFormat({MODE:'true'}),firstRenewalContainerFormat({MODE:'true'})]){
  assert.match(format,/"BackendHealthFailure":/);
  assert.match(format,/\{\{range \.State\.Health\.Log\}\}\{\{\$last = \.\}\}\{\{end\}\}/);
  assert.equal((format.match(/\.Output/g)??[]).length,1);
  assert.ok(format.indexOf('$out := $last.Output')>format.indexOf('{{range .State.Health.Log}}{{$last = .}}{{end}}{{end}}'),'read only the final selected record');
  assert.doesNotMatch(format,/\{\{(?:json |print )?(?:\$last|\.State\.Health)(?:\.(?:Output|Log|Error))?\s*\}\}|"(?:Output|Log|Error)":/);
  for(const code of ['HTTP_UNAVAILABLE','HTTP_REJECTED','HTTP_ERROR','CONNECTION_FAILED','PROBE_TIMEOUT','UNAVAILABLE'])assert.ok(format.includes('"'+code+'"'));
  assert.match(format,/eq \$last.ExitCode 22/);assert.match(format,/eq \$out "503"/);assert.match(format,/eq \$out "401"/);assert.match(format,/eq \$out "403"/);
  assert.match(format,/eq \(len \$out\) 3/);
  assert.ok(format.includes('(eq (printf "%T" $out) "string")'));
  assert.ok(format.includes('(eq (printf "%T" $last.ExitCode) "int")'));
  for(const index of [0,1,2]){assert.ok(format.includes('(ge (index $out '+index+') 48)'));assert.ok(format.includes('(le (index $out '+index+') 57)'));}
  assert.match(format,/ge \$out "400"/);assert.match(format,/le \$out "599"/);
 }
});
