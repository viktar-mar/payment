import {defaultTreeAdapter,parse,type DefaultTreeAdapterTypes} from 'parse5';

const htmlNamespace='http://www.w3.org/1999/xhtml';
const controls=new Set(['button','datalist','fieldset','input','object','output','select','textarea']);
const attribute=(element:DefaultTreeAdapterTypes.Element,name:string)=>element.attrs.find(attr=>attr.name===name)?.value;

/** Derive the wire request from the original issuer HTML without running it. */
export function issuerResultForm(html:string):{action:string;body:string}|undefined{
 let ambiguous=false;
 const document=parse(html,{onParseError:error=>{if(error.code==='duplicate-attribute')ambiguous=true;}});
 if(ambiguous)return;

 let action:string|undefined;
 const fields=new URLSearchParams();
 const pending:{node:DefaultTreeAdapterTypes.Node;inForm:boolean}[]=[{node:document,inForm:false}];
 while(pending.length){
  const {node,inForm}=pending.pop()!;
  let inside=inForm;
  if(defaultTreeAdapter.isElementNode(node)&&node.namespaceURI===htmlNamespace){
   if(node.tagName==='base'||attribute(node,'form')!==undefined)return;
   if(node.tagName==='form'){
    const target=attribute(node,'target')??'',encoding=attribute(node,'enctype')??'application/x-www-form-urlencoded';
    const destination=attribute(node,'action');
    if(action||!destination||!URL.canParse(destination)||attribute(node,'method')?.toLowerCase()!=='post'||
       !['','_self'].includes(target.toLowerCase())||encoding.toLowerCase()!=='application/x-www-form-urlencoded')return;
    const url=new URL(destination);
    if(url.origin!=='https://hooks.stripe.com'||url.href!==destination||url.username||url.password||url.hash||url.pathname==='/')return;
    action=destination;
    inside=true;
   }else if(inside&&controls.has(node.tagName)){
    const name=attribute(node,'name'),value=attribute(node,'value');
    if(node.tagName!=='input'||attribute(node,'type')?.toLowerCase()!=='hidden'||attribute(node,'disabled')!==undefined||
       (name!=='cres'&&name!=='threeDSSessionData')||value===undefined||fields.has(name))return;
    fields.append(name,value.replace(/\r\n|[\r\n]/g,'\r\n'));
   }
  }
  if('childNodes' in node)for(let i=node.childNodes.length-1;i>=0;i--)pending.push({node:node.childNodes[i]!,inForm:inside});
 }
 const body=fields.toString();
 if(action&&fields.get('cres')&&body.length<=32768)return {action,body};
}
