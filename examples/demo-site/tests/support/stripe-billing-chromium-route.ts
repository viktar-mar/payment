import {createRequire} from 'node:module';
import type {Page,Request} from '@playwright/test';

type ClientObject={_connection?:{toImpl?:(client:unknown)=>unknown}};
type DriverFrame={
 _id:string;_page:DriverPage;_currentDocument:object;
 parentFrame():DriverFrame|null;_isDetached():boolean;
};
type DriverRequest={frame():DriverFrame;method():string;url():string};
type DriverRoute={
 request:DriverRequest;session:object;_requestId:string;_interceptionId:string;
 _originalRequestRoute?:{_alreadyContinuedParams?:unknown;_fulfilled?:boolean};
};
type DriverPage={
 frameManager:{frame(id:string):DriverFrame|undefined};
 delegate:{
  _targetId:string;
  _sessions:Map<string,{_targetId:string;_client:object}>;
  _networkManager:{_requestIdToRequest:Map<string,DriverRoute>};
 };
};
export type ChromiumRouteEvidence={
 targetId:string;frameId:string;networkId:string;current:()=>boolean;
};
const refuse=()=>Error('billing_BROWSER');
const protocolFixtures=new WeakSet<object>();

export function registerChromiumProtocolFixture(page:object){
 if('_connection' in page)throw refuse();
 protocolFixtures.add(page);
}

/** The in-process Chromium driver keeps the original Network and Fetch IDs of
 * each intercepted request. The public Playwright Request omits both IDs. */
export function chromiumRouteReader(page:Page){
 if(protocolFixtures.has(page))return undefined;
 const connection=(page as ClientObject)._connection;
 if(typeof connection?.toImpl!=='function')throw refuse();
 const load=createRequire(import.meta.url);
 const testPackage=load.resolve('@playwright/test/package.json');
 const playwrightPackage=createRequire(testPackage).resolve('playwright/package.json');
 if(load(testPackage).version!=='1.63.0'||
    createRequire(playwrightPackage)('playwright-core/package.json').version!=='1.63.0')throw refuse();
 const impl=connection.toImpl(page) as DriverPage;
 const main=connection.toImpl(page.mainFrame()) as DriverFrame;
 const {delegate,frameManager}=impl;
 const pending=delegate._networkManager._requestIdToRequest;
 if(!(pending instanceof Map)||!(delegate._sessions instanceof Map)||
    main._page!==impl||main._id!==delegate._targetId||frameManager.frame(main._id)!==main)throw refuse();
 return {
  mainId:main._id,
  capture(request:Request):ChromiumRouteEvidence|null{
   try{
    const frame=request.frame(),original=connection.toImpl!(request) as DriverRequest;
    const sourceFrame=connection.toImpl!(frame) as DriverFrame;
    if((request as ClientObject)._connection!==connection||(frame as ClientObject)._connection!==connection||
       original.frame()!==sourceFrame||request.redirectedFrom()||request.serviceWorker())return null;
    const symbols=Object.getOwnPropertySymbols(original).filter(key=>key.description==='InterceptableRequest');
    if(symbols.length!==1)return null;
    const route=(original as DriverRequest&Record<symbol,DriverRoute>)[symbols[0]!]!;
    const {session,_requestId:networkId,_interceptionId:interception}=route;
    const binding=[...delegate._sessions].filter(([,entry])=>entry._client===session);
    if(binding.length!==1||!networkId||!interception)return null;
    const [targetId,target]=binding[0]!;
    if(target._targetId!==targetId)return null;
    const ancestry=new Map<DriverFrame,object>();
    for(let node:DriverFrame|null=sourceFrame;node;node=node.parentFrame()){
     if(ancestry.has(node))return null;
     ancestry.set(node,node._currentDocument);
    }
    if(!ancestry.has(main)||![...ancestry.keys()].some(frame=>frame._id===targetId))return null;
    const frameId=sourceFrame._id,url=request.url(),method=request.method();
    const current=()=>{
     if(pending.get(networkId)!==route||route.request!==original||route.session!==session||
        route._requestId!==networkId||route._interceptionId!==interception||
        original.frame()!==sourceFrame||original.url()!==url||original.method()!==method||
        delegate._sessions.get(targetId)!==target||sourceFrame._id!==frameId||
        !route._originalRequestRoute||route._originalRequestRoute._alreadyContinuedParams||
        route._originalRequestRoute._fulfilled)return false;
     const chain=[...ancestry];
     return chain.every(([node,document],index)=>node._page===impl&&!node._isDetached()&&
      frameManager.frame(node._id)===node&&node._currentDocument===document&&node.parentFrame()===(chain[index+1]?.[0]??null));
    };
    return current()?{targetId,frameId,networkId,current}:null;
   }catch{return null;}
  },
 };
}
