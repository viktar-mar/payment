const STRIPE_PREDICATE='{{$present := false}}{{$disabled := false}}{{range .Config.Env}}{{$n := index (split . "=") 0}}'+
  '{{if or (eq $n "PAYMENT_STRIPE_SANDBOX_SECRET_KEY") (eq $n "PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY") (eq $n "PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET")}}{{$present = true}}{{end}}'+
  '{{if eq . "PAYMENT_STRIPE_SANDBOX_ENABLED=false"}}{{$disabled = true}}{{end}}{{end}}'+
  '"StripeKeyPresent":{{$present}},"SandboxDisabled":{{$disabled}}';
export const CONTAINER_FORMAT='{ '+STRIPE_PREDICATE+',"Id":{{json .ID}},"Name":{{json .Name}},"Image":{{json .Image}},"Labels":{{json .Config.Labels}},'+
  '"State":{"Status":{{json .State.Status}},"Running":{{json .State.Running}},"ExitCode":{{json .State.ExitCode}},"StartedAt":{{json .State.StartedAt}},'+
  '"Health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}""{{end}}},"Mounts":{{json .Mounts}},'+
  '"Networks":{ {{$first := true}}{{range $name, $network := .NetworkSettings.Networks}}{{if not $first}},{{end}}{{$first = false}}{{json $name}}:{"NetworkID":{{json $network.NetworkID}}}{{end}} },'+
  '"PortBindings":{{json .HostConfig.PortBindings}},"Ports":{{json .NetworkSettings.Ports}},"User":{{json .Config.User}},'+
  '"ReadonlyRootfs":{{json .HostConfig.ReadonlyRootfs}},"NetworkMode":{{json .HostConfig.NetworkMode}},"CapAdd":{{json .HostConfig.CapAdd}},"CapDrop":{{json .HostConfig.CapDrop}}}';
export const NETWORK_FORMAT='{"Id":{{json .ID}},"Name":{{json .Name}},"Labels":{{json .Labels}},"Driver":{{json .Driver}},"Scope":{{json .Scope}}}';
export const VOLUME_FORMAT='{"Name":{{json .Name}},"CreatedAt":{{json .CreatedAt}},"Driver":{{json .Driver}},"Scope":{{json .Scope}},"Labels":{{json .Labels}}}';
const fail=()=>{throw null;};
const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const fullId=v=>typeof v==='string'&&/^[0-9a-f]{64}$/.test(v);
function canonical(v){return Array.isArray(v)?v.map(canonical):object(v)?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;}
export const sameOwnedProjection=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
const same=sameOwnedProjection;
export function proveOwnedPorts(actual,expected){
  if(actual===null&&Object.keys(expected).length===0)return;
  if(!object(actual)||Object.keys(actual).sort().join(',')!==Object.keys(expected).sort().join(','))fail();
  for(const key of Object.keys(expected))if(!same(actual[key],expected[key]))fail();
}
/** Shared projection policy only: callers must await the indicated infrastructure proof. */
export function proveOwnedContainer(value,{service,project,runId,network,sourceImage,recordedContainer,expectedMounts,expectedPorts,liveRequired=false,neverStarted=false,cleanupOnly=false,backendStripeEnabled=false}){
  const helper=service.startsWith('issuer-'),networkName=network.Name;let infrastructureRequired=false;
  const labels={'payment.sandbox.project':project,'payment.sandbox.run':runId,'payment.sandbox.kind':'container','com.docker.compose.project':project,'com.docker.compose.service':service};
  if(!fullId(value.Id)||(recordedContainer&&value.Id!==recordedContainer.id)||value.Name!=='/'+project+'-'+service||value.Image!==sourceImage.id||!object(value.Labels))fail();
  for(const [key,label] of Object.entries(labels))if(value.Labels[key]!==label)fail();
  if(!Array.isArray(value.Mounts)||value.Mounts.length!==expectedMounts.length)fail();
  for(const expected of expectedMounts){const mount=value.Mounts.find(candidate=>candidate.Destination===expected.Destination);if(!mount||Object.entries(expected).some(([key,want])=>mount[key]!==want))fail();}
  if(!object(value.Networks))fail();
  if(helper){
    const names=Object.keys(value.Networks),planned=neverStarted&&!liveRequired&&value.State?.Status==='created'&&value.State.Running===false&&value.State.StartedAt==='0001-01-01T00:00:00Z';
    const stoppedCleanup=cleanupOnly&&!liveRequired&&value.State?.Status==='exited'&&value.State.Running===false&&value.State.ExitCode===0&&recordedContainer?.status==='exited'&&recordedContainer.startedAt!=='0001-01-01T00:00:00Z'&&recordedContainer.startedAt===value.State.StartedAt;
    const actual=value.Networks.none?.NetworkID;
    if(names.length>1||(names.length===1&&(names[0]!=='none'||(!fullId(actual)&&!(actual===''&&(planned||stoppedCleanup))))))fail();
  }else{
    if(Object.keys(value.Networks).length!==1||!object(value.Networks[networkName]))fail();
    const actual=value.Networks[networkName].NetworkID,planned=!liveRequired&&value.State?.Status==='created'&&value.State.Running===false&&value.State.StartedAt==='0001-01-01T00:00:00Z';
    const stoppedCleanup=cleanupOnly&&!liveRequired&&value.State?.Status==='exited'&&value.State.Running===false&&recordedContainer?.startedAt!=='0001-01-01T00:00:00Z'&&recordedContainer?.startedAt===value.State.StartedAt;
    if(actual!==network.Id&&!(actual===''&&(planned||stoppedCleanup)))fail();
    if(![networkName,network.Id].includes(value.NetworkMode))fail();
    infrastructureRequired=planned||stoppedCleanup;
  }
  if(helper&&value.NetworkMode!=='none')fail();
  proveOwnedPorts(value.PortBindings,expectedPorts);
  if(typeof value.StripeKeyPresent!=='boolean'||typeof value.SandboxDisabled!=='boolean')fail();
  if(service!=='backend'&&value.StripeKeyPresent)fail();
  if(service==='backend'&&(backendStripeEnabled?(!value.StripeKeyPresent||value.SandboxDisabled):value.StripeKeyPresent||!value.SandboxDisabled))fail();
  const expectedUser=helper?(service==='issuer-installer'?'0:0':'10001:10001'):service==='callback-receiver'?'node':sourceImage.user;
  if(value.User!==expectedUser||value.ReadonlyRootfs!==helper)fail();
  if(helper){const capAdd=Array.isArray(value.CapAdd)?value.CapAdd.map(cap=>cap==='CAP_CHOWN'?'CHOWN':cap==='CAP_DAC_OVERRIDE'?'DAC_OVERRIDE':cap):value.CapAdd;if(!same(value.CapDrop,['ALL'])||!same(capAdd??[],service==='issuer-installer'?['CHOWN','DAC_OVERRIDE']:[]))fail();}
  if(!object(value.State)||typeof value.State.Running!=='boolean'||typeof value.State.Status!=='string'||typeof value.State.StartedAt!=='string')fail();
  if(neverStarted&&(value.State.Running||value.State.Status!=='created'||value.State.StartedAt!=='0001-01-01T00:00:00Z'))fail();
  if(liveRequired){if(!value.State.Running||value.State.Status!=='running'||value.State.Health!=='healthy'||!object(value.Ports))fail();proveOwnedPorts(Object.fromEntries(Object.entries(value.Ports).filter(([port,binding])=>!(service==='mailpit'&&port==='1110/tcp'&&binding===null))),expectedPorts);}
  return {safe:{id:value.Id,name:value.Name.slice(1),image:value.Image,service,status:value.State.Status,startedAt:value.State.StartedAt,networkId:helper?null:network.Id,mounts:expectedMounts,ports:expectedPorts},state:value.State,infrastructureRequired};
}

const INITIAL_ENTRYPOINT=['java','-Dloader.path=/app/initial-runtime','-Dloader.main=dev.genericpayments.acceptance.runtime.OwnedBillingFirstPaymentRuntime','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'];
const EXECUTION_FORMAT='"Entrypoint":{{json .Config.Entrypoint}},"Cmd":{{json .Config.Cmd}}';
export function firstPaymentImageFormat(source){if(!/^[a-f0-9]{40}$/.test(source))fail();return '{"Id":{{json .ID}},"User":{{json .Config.User}},'+EXECUTION_FORMAT+',"RuntimeSource":{{$ok := false}}{{range .Config.Env}}{{if eq . "PAYMENT_RUNTIME_SOURCE_SHA='+source+'"}}{{$ok = true}}{{end}}{{end}}{{$ok}}}';}
/** Only boolean equality leaves Docker: private capability/env values never enter inspect output. */
export function firstPaymentContainerFormat(expected){
 const checks=Object.entries(expected).map(([k,v],i)=>'{{$v'+i+' := false}}{{range .Config.Env}}{{if eq . '+JSON.stringify(k+'='+v)+'}}{{$v'+i+' = true}}{{end}}{{end}}');
 const excluded=['PAYMENT_STRIPE_BILLING_ACCEPTANCE','PAYMENT_STAGE4_TEST_RUNTIME','PAYMENT_STRIPE_ACCEPTANCE','PAYMENT_STRIPE_NATIVE_ACCEPTANCE',...['ALLOW_TEST_OPERATIONS','FULL_RUN','OWNED_RUN','REMAINING_ONLY','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT','MANUAL_3DS','MANUAL_REFUND','MANUAL_REFUND_REMAINDER'].map(k=>'PAYMENT_STRIPE_SANDBOX_'+k)];
 const disabled='{{$disabled := true}}{{range .Config.Env}}{{$pair := split . "="}}{{$name := index $pair 0}}{{if or '+excluded.map(k=>'(eq $name '+JSON.stringify(k)+')').join(' ')+'}}{{if and (ne . (print $name "=")) (ne . (print $name "=false"))}}{{$disabled = false}}{{end}}{{end}}{{end}}';
 return CONTAINER_FORMAT.slice(0,-1)+','+EXECUTION_FORMAT+',"RuntimeIdentity":'+checks.join('')+disabled+'{{and $disabled '+checks.map((_,i)=>'$v'+i).join(' ')+'}}}';
}
export function proveFirstPaymentRuntime(v,kind){if(!same(v.Entrypoint,INITIAL_ENTRYPOINT)||v.Cmd!==null&&!same(v.Cmd,[])||v[kind==='image'?'RuntimeSource':'RuntimeIdentity']!==true)fail();}
