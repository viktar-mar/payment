import {defaultTreeAdapter,parse,type DefaultTreeAdapterTypes} from 'parse5';

const htmlNamespace='http://www.w3.org/1999/xhtml';
const controls=new Set(['button','datalist','fieldset','input','object','output','select','textarea']);
const attribute=(element:DefaultTreeAdapterTypes.Element,name:string)=>element.attrs.find(attr=>attr.name===name)?.value;
type FormRejection='DUPLICATE_ATTRIBUTE'|'BASE'|'OWNER'|'MISSING'|'MULTIPLE'|'METHOD'|'TARGET'|'ENCODING'|
 'ACTION'|'CONTROL'|'FIELD'|'DUPLICATE_FIELD'|'REQUIRED_CRES'|'SIZE';

/** Derive the wire request from the original issuer HTML without running it. */
export function issuerResultForm(html:string,frameName?:string,report?:(code:string)=>void):{action:string;body:string}|undefined{
 const reject=(reason:FormRejection)=>{report?.(`BROWSER_CHALLENGE_CRES_FORM_${reason}`);return undefined;};
 let ambiguous=false;
 const document=parse(html,{onParseError:error=>{if(error.code==='duplicate-attribute')ambiguous=true;}});
 if(ambiguous)return reject('DUPLICATE_ATTRIBUTE');

 let action:string|undefined;
 const fields=new URLSearchParams();
 const pending:{node:DefaultTreeAdapterTypes.Node;inForm:boolean;excluded:boolean}[]=[{node:document,inForm:false,excluded:false}];
 while(pending.length){
  const {node,inForm,excluded}=pending.pop()!;
  let inside=inForm;
  let excludesControls=excluded;
  if(defaultTreeAdapter.isElementNode(node)&&node.namespaceURI===htmlNamespace){
   if(node.tagName==='base')return reject('BASE');
   if(attribute(node,'form')!==undefined)return reject('OWNER');
   if(node.tagName==='form'){
    const target=attribute(node,'target')??'',encoding=attribute(node,'enctype')??'application/x-www-form-urlencoded';
    const destination=attribute(node,'action');
    if(action)return reject('MULTIPLE');
    if(excluded)return reject('CONTROL');
    if(attribute(node,'method')?.toLowerCase()!=='post')return reject('METHOD');
    if(target&&target.toLowerCase()!=='_self'&&(target.startsWith('_')||target!==frameName))return reject('TARGET');
    if(encoding.toLowerCase()!=='application/x-www-form-urlencoded')return reject('ENCODING');
    if(!destination||!URL.canParse(destination))return reject('ACTION');
    const url=new URL(destination);
    if(url.origin!=='https://hooks.stripe.com'||url.href!==destination||url.username||url.password||url.href.includes('#')||url.pathname==='/')return reject('ACTION');
    action=destination;
    inside=true;
   }else if(inside&&controls.has(node.tagName)){
    const name=attribute(node,'name'),value=attribute(node,'value');
    if(node.tagName!=='input'||attribute(node,'type')?.toLowerCase()!=='hidden'||attribute(node,'disabled')!==undefined)return reject('CONTROL');
    if(name!=='cres'&&name!=='threeDSSessionData')return reject('FIELD');
    if(fields.has(name))return reject('DUPLICATE_FIELD');
    if(name==='cres'&&!value)return reject('REQUIRED_CRES');
    fields.append(name,(value??'').replace(/\r\n|[\r\n]/g,'\r\n'));
   }
   if(node.tagName==='datalist'||(node.tagName==='fieldset'&&attribute(node,'disabled')!==undefined))excludesControls=true;
  }
  if('childNodes' in node)for(let i=node.childNodes.length-1;i>=0;i--){
   pending.push({node:node.childNodes[i]!,inForm:inside,excluded:excludesControls});
  }
 }
 if(!action)return reject('MISSING');
 if(!fields.has('cres'))return reject('REQUIRED_CRES');
 const body=fields.toString();
 if(body.length>32768)return reject('SIZE');
 return {action,body};
}
