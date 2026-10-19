import test from 'node:test';
import assert from 'node:assert/strict';
import {cp,lstat,mkdir,mkdtemp,readdir,readFile,rm,symlink} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {basename,dirname,join,matchesGlob,relative,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {build} from 'vite';
// @ts-expect-error Native Node source type erasure.
import {exercise} from './form-load-manual.test.ts';

test('actual Vite graph shares SDK across manual/automatic entries and excludes diagnostics from production',async t=>{
  const root=fileURLToPath(new URL('../..',import.meta.url)),directory=await mkdtemp(join(tmpdir(),'payment-manual-graph-'));
  try{
    await build({root,configFile:root+'/vite.config.ts',mode:'form-diagnostic',logLevel:'silent',build:{outDir:directory}});
    const assets=await readdir(join(directory,'assets')),manual=assets.find(name=>/^formManual-.*\.js$/.test(name)),automatic=assets.find(name=>/^formDiagnostic-.*\.js$/.test(name));
    assert.ok(manual);assert.ok(automatic);
    const manualCode=await readFile(join(directory,'assets',manual),'utf8'),automaticCode=await readFile(join(directory,'assets',automatic),'utf8');
    const shared=assets.filter(name=>name.endsWith('.js')&&manualCode.includes(name)&&automaticCode.includes(name));assert.ok(shared.length>0);
    for(const name of assets)assert.doesNotMatch(await readFile(join(directory,'assets',name),'utf8'),/node:http|node:crypto|createManualFormGateway/);
    for(const scenario of ['no-click','synchronous-ready','delayed-ready','frame-only','late-session'])await t.test(scenario,()=>exercise(pathToFileURL(join(directory,'assets',manual)).href,scenario,true));
    await build({root,configFile:root+'/vite.config.ts',mode:'production',logLevel:'silent',build:{outDir:directory,emptyOutDir:true}});
    const documents=await readdir(directory);assert.ok(documents.includes('index.html'));assert.ok(documents.includes('payment-return.html'));
    assert.equal(documents.includes('form-load-manual.html'),false);assert.equal(documents.includes('form-load-diagnostic.html'),false);
    for(const name of await readdir(join(directory,'assets')))assert.doesNotMatch(await readFile(join(directory,'assets',name),'utf8'),/manual-session|manual-observation|Ручная проверка/);
  }finally{await rm(directory,{recursive:true,force:true});}
});

// Catch omitted Docker COPY inputs (including transitive runtime/declaration
// imports), not just successful builds with the entire checkout available.
test('Docker COPY filesystem typechecks and builds diagnostic and production entries offline',async t=>{
  const root=fileURLToPath(new URL('../..',import.meta.url)),repository=resolve(root,'../..');
  const directory=await mkdtemp(join(tmpdir(),'payment-manual-docker-'));
  const publicInfrastructure=[
    'stripe-form-diagnostic-schema.mjs','stripe-form-diagnostic-schema.d.mts',
    'stripe-form-diagnostic-environment.mjs','stripe-form-diagnostic-environment.d.mts',
    'stripe-form-diagnostic-network.d.mts',
  ];
  const allowedSources=new Set([
    'frontend/package.json','frontend/pnpm-lock.yaml','frontend/pnpm-workspace.yaml',
    'frontend/sdk/package.json','frontend/sdk',
    'examples/demo-site/package.json','examples/demo-site/pnpm-lock.yaml',
    'examples/demo-site/index.html','examples/demo-site/payment-return.html',
    'examples/demo-site/form-load-diagnostic.html','examples/demo-site/form-load-manual.html',
    'examples/demo-site/tsconfig.json','examples/demo-site/vite.config.ts','examples/demo-site/src',
    ...publicInfrastructure.map(name=>'infrastructure/'+name),
  ]);
  const rules=(await readFile(join(repository,'.dockerignore'),'utf8')).split(/\r?\n/).map(s=>s.trim()).filter(s=>s&&!s.startsWith('#'));
  const excluded=(path:string)=>{
    const names:string[]=[];
    for(let name=relative(repository,path);name&&name!=='.';name=dirname(name))names.push(name);
    let ignored=false;
    for(const rule of rules){
      const negate=rule.startsWith('!'),pattern=negate?rule.slice(1):rule;
      if(names.some(name=>matchesGlob(name,pattern)))ignored=!negate;
    }
    return ignored;
  };
  const linkDependencies=async(project:string)=>{
    const destination=join(directory,project,'node_modules');await mkdir(destination,{recursive:true});
    for(const name of await readdir(join(repository,project,'node_modules'))){
      if(name.startsWith('.'))continue;
      if(name==='@generic-payments'){
        await mkdir(join(destination,name),{recursive:true});
        await symlink(join(directory,'frontend/sdk'),join(destination,name,'sdk'),'dir');
      }else await symlink(join(repository,project,'node_modules',name),join(destination,name),'dir');
    }
  };
  const typecheck=(project:string,args:string[])=>{
    const cwd=join(directory,project);
    const result=spawnSync(process.execPath,[join(cwd,'node_modules/typescript/bin/tsc'),...args],{
      cwd,env:{PATH:'/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',NO_COLOR:'1'},
      timeout:30000,maxBuffer:1048576,stdio:['ignore','pipe','pipe'],encoding:'utf8',
    });
    assert.equal(result.error,undefined,'isolated compiler must execute');
    assert.equal(result.signal,null,'isolated compiler must settle');
    assert.equal(result.status,0,'isolated '+project+' typecheck failed:\n'+result.stdout+result.stderr);
    assert.equal(result.stdout,'');assert.equal(result.stderr,'');
  };
  try{
    const dockerfile=(await readFile(join(root,'Dockerfile'),'utf8')).replace(/\\\r?\n/g,' ').split(/\r?\n/);
    let buildStage=false,reachedDemoBuild=false;
    for(const line of dockerfile.map(s=>s.trim())){
      if(/^FROM\s/i.test(line)){
        if(buildStage)break;
        buildStage=/\sAS build$/i.test(line);continue;
      }
      if(buildStage&&/^WORKDIR\s/.test(line))assert.equal(line,'WORKDIR /workspace','fixture requires the build-stage workspace');
      if(buildStage&&/^RUN\s+cd examples\/demo-site\s+&&\s+pnpm build\b/.test(line)){reachedDemoBuild=true;break;}
      if(!buildStage||!/^COPY\s/.test(line))continue;
      const parts=line.split(/\s+/).slice(1),destination=parts.pop()!;
      assert.ok(destination.startsWith('./'),'fixture supports relative build-stage COPY destinations only');
      assert.ok(parts.length===1||destination.endsWith('/'),'multi-source COPY requires a directory destination');
      for(const source of parts){
        // Never copy private env/runtime infrastructure or a broad repository
        // tree into this fixture, even if a future Dockerfile regresses.
        assert.ok(allowedSources.has(source),'unexpected build-stage COPY source: '+source);
        assert.equal(excluded(join(repository,source)),false,'COPY input excluded by .dockerignore: '+source);
        const target=join(directory,destination.endsWith('/')?join(destination,basename(source)):destination);
        assert.ok(target.startsWith(directory+'/'),'COPY destination must stay inside its generated fixture');
        await mkdir(dirname(target),{recursive:true});
        await cp(join(repository,source),target,{recursive:true,filter:async path=>{
          if(excluded(path))return false;
          assert.equal((await lstat(path)).isSymbolicLink(),false,'source tree must not escape through symlinks');return true;
        }});
      }
    }
    assert.equal(reachedDemoBuild,true,'fixture must reach the actual demo build command, not copy later-stage inputs');
    await linkDependencies('frontend/sdk');await linkDependencies('examples/demo-site');
    // Rebuild the copied SDK; demo package resolution must not fall back to a
    // stale SDK installation or source files outside the staged COPY tree.
    typecheck('frontend/sdk',['-p','tsconfig.json']);
    for(const mode of ['form-diagnostic','production'])await t.test(mode,async()=>{
      typecheck('examples/demo-site',['--noEmit']);
      assert.deepEqual((await readdir(join(directory,'infrastructure'))).sort(),[...publicInfrastructure].sort());
      const demo=join(directory,'examples/demo-site');
      await build({root:demo,configFile:join(demo,'vite.config.ts'),mode,logLevel:'silent'});
      const documents=await readdir(join(demo,'dist'));
      if(mode==='form-diagnostic'){
        assert.ok(documents.includes('form-load-manual.html'));assert.ok(documents.includes('form-load-diagnostic.html'));
        assert.equal(documents.includes('index.html'),false);
      }else{
        assert.ok(documents.includes('index.html'));assert.ok(documents.includes('payment-return.html'));
        assert.equal(documents.includes('form-load-manual.html'),false);assert.equal(documents.includes('form-load-diagnostic.html'),false);
      }
      for(const name of await readdir(join(demo,'dist/assets'))){
        const code=await readFile(join(demo,'dist/assets',name),'utf8');
        assert.doesNotMatch(code,/node:http|node:crypto|createManualFormGateway|PAYMENT_STRIPE_SANDBOX_SECRET_KEY/);
        if(mode==='production')assert.doesNotMatch(code,/manual-session|manual-observation|Ручная проверка/);
      }
    });
  }finally{await rm(directory,{recursive:true,force:true});}
});
