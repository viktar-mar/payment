import {defaultTreeAdapter,parse,parseFragment,type DefaultTreeAdapterTypes} from 'parse5';

const htmlNamespace='http://www.w3.org/1999/xhtml';
const controls=new Set(['button','datalist','fieldset','input','object','output','select','textarea']);
const attribute=(element:DefaultTreeAdapterTypes.Element,name:string)=>element.attrs.find(attr=>attr.name===name)?.value;
type FormRejection='DUPLICATE_ATTRIBUTE'|'BASE'|'OWNER'|'MISSING'|'MULTIPLE'|'METHOD'|'TARGET'|'ENCODING'|
 'ACTION'|'CONTROL'|'FIELD'|'DUPLICATE_FIELD'|'REQUIRED_CRES'|'SIZE'|'LITERAL_MARKUP'|
 'MISSING_INLINE'|'MISSING_INLINE_DOM'|'MISSING_INLINE_MARKUP'|'MISSING_EXTERNAL'|'MISSING_TEMPLATE'|'MISSING_HANDLER'|'MISSING_MIXED';
type FormProof={action:string;body:string};
type Element=DefaultTreeAdapterTypes.Element;
const htmlWhitespace=(value:string)=>/^[\t\n\f\r ]*$/.test(value);
const whitespaceNode=(node:DefaultTreeAdapterTypes.Node)=>defaultTreeAdapter.isTextNode(node)&&htmlWhitespace(node.value);
const hasHandler=(element:Element)=>element.attrs.some(attr=>attr.name.startsWith('on'));

/** Derive the wire request from the original issuer HTML without running it. */
export function issuerResultForm(html:string,frameName?:string,report?:(code:string)=>void):FormProof|undefined{
 let ambiguous=false;
 const document=parse(html,{sourceCodeLocationInfo:true,onParseError:error=>{if(error.code==='duplicate-attribute')ambiguous=true;}});
 let result=ambiguous?'DUPLICATE_ATTRIBUTE':readForm(document,frameName);
 if(result==='MISSING')result=readLiteralForm(document,frameName);
 if(typeof result==='string'){report?.(`BROWSER_CHALLENGE_CRES_FORM_${result}`);return;}
 return result;
}

function readForm(document:DefaultTreeAdapterTypes.ParentNode,frameName:string|undefined):FormProof|FormRejection{
 let action:string|undefined;
 const fields=new URLSearchParams();
 const pending:{node:DefaultTreeAdapterTypes.Node;inForm:boolean;excluded:boolean}[]=[{node:document,inForm:false,excluded:false}];
 while(pending.length){
  const {node,inForm,excluded}=pending.pop()!;
  let inside=inForm;
  let excludesControls=excluded;
  if(defaultTreeAdapter.isElementNode(node)&&node.namespaceURI===htmlNamespace){
   if(node.tagName==='base')return 'BASE';
   if(attribute(node,'form')!==undefined)return 'OWNER';
   if(node.tagName==='form'){
    const target=attribute(node,'target')??'',encoding=attribute(node,'enctype')??'application/x-www-form-urlencoded';
    const destination=attribute(node,'action');
    if(action)return 'MULTIPLE';
    if(excluded)return 'CONTROL';
    if(attribute(node,'method')?.toLowerCase()!=='post')return 'METHOD';
    if(target&&target.toLowerCase()!=='_self'&&(target.startsWith('_')||target!==frameName))return 'TARGET';
    if(encoding.toLowerCase()!=='application/x-www-form-urlencoded')return 'ENCODING';
    if(!destination||!URL.canParse(destination))return 'ACTION';
    const url=new URL(destination);
    if(url.origin!=='https://hooks.stripe.com'||url.href!==destination||url.username||url.password||url.href.includes('#')||url.pathname==='/')return 'ACTION';
    action=destination;
    inside=true;
   }else if(inside&&controls.has(node.tagName)){
    const name=attribute(node,'name'),value=attribute(node,'value');
    if(node.tagName!=='input'||attribute(node,'type')?.toLowerCase()!=='hidden'||attribute(node,'disabled')!==undefined)return 'CONTROL';
    if(name!=='cres'&&name!=='threeDSSessionData')return 'FIELD';
    if(fields.has(name))return 'DUPLICATE_FIELD';
    if(name==='cres'&&!value)return 'REQUIRED_CRES';
    fields.append(name,(value??'').replace(/\r\n|[\r\n]/g,'\r\n'));
   }
   if(node.tagName==='datalist'||(node.tagName==='fieldset'&&attribute(node,'disabled')!==undefined))excludesControls=true;
  }
  if('childNodes' in node)for(let i=node.childNodes.length-1;i>=0;i--){
   pending.push({node:node.childNodes[i]!,inForm:inside,excluded:excludesControls});
  }
 }
 if(!action)return 'MISSING';
 if(!fields.has('cres'))return 'REQUIRED_CRES';
 const body=fields.toString();
 if(body.length>32768)return 'SIZE';
 return {action,body};
}

/** Recognize one literal insertion and submission. Parse the string, never run the program. */
function literalInsertion(source:string):string|undefined{
 const code=source.trim();
 const start=/^(?:document\.write\s*\(\s*|document\.body\.insertAdjacentHTML\s*\(\s*(?:"beforeend"|'beforeend')\s*,\s*)/.exec(code);
 const end=/\)\s*;\s*document\.forms\[0\]\.submit\s*\(\s*\)\s*;?$/.exec(code);
 if(!start||!end||start[0].length>=end.index)return;
 const literal=code.slice(start[0].length,end.index).trim();
 if(/^'[^'\\\u0000-\u001f]*'$/u.test(literal))return literal.slice(1,-1);
 if(!literal.startsWith('"'))return;
 try{const value:unknown=JSON.parse(literal);if(typeof value==='string')return value;}
 catch{/* Expressions and other JavaScript string syntax are outside this subset. */}
}

function readLiteralForm(document:DefaultTreeAdapterTypes.Document,frameName:string|undefined):FormProof|FormRejection{
 const shapes=new Set<FormRejection>();
 const pending:DefaultTreeAdapterTypes.Node[]=[document];
 let script:string|undefined,plain=true;
 while(pending.length){
  const node=pending.pop()!;
  if(defaultTreeAdapter.isElementNode(node)){
   if(hasHandler(node)){shapes.add('MISSING_HANDLER');plain=false;}
   if(node.namespaceURI!==htmlNamespace)plain=false;
   else if(node.tagName==='template'){shapes.add('MISSING_TEMPLATE');plain=false;}
   else if(node.tagName==='script'){
    const type=(attribute(node,'type')??'').replace(/^[\t\n\f\r ]+|[\t\n\f\r ]+$/g,'').toLowerCase();
    const text=node.childNodes.filter(defaultTreeAdapter.isTextNode).map(child=>child.value).join('');
    if(['','text/javascript','application/javascript','module'].includes(type)){
     if(attribute(node,'src')!==undefined)shapes.add('MISSING_EXTERNAL');
     else if(/\bcreateElement\s*\(\s*['"]form['"]/.test(text))shapes.add('MISSING_INLINE_DOM');
     else if(/\b(?:document\s*\.\s*write(?:ln)?|insertAdjacentHTML|innerHTML)\b/.test(text))shapes.add('MISSING_INLINE_MARKUP');
     else shapes.add('MISSING_INLINE');
    }
    if(script!==undefined||node.parentNode?.nodeName!=='body'||!node.sourceCodeLocation?.endTag||
       !['','text/javascript','application/javascript'].includes(type)||node.attrs.some(attr=>attr.name!=='type'))plain=false;
    script=text;
   }else if((node.tagName==='html'||node.tagName==='head'||node.tagName==='body'||node.tagName==='title')&&node.attrs.length===0){
    // The single producer can only run alongside the structural document elements.
   }else if(node.tagName==='meta'&&node.attrs.length===1&&attribute(node,'charset')?.toLowerCase()==='utf-8'){
    // The caller also decodes the paired response as UTF-8.
   }else plain=false;
  }else if(defaultTreeAdapter.isTextNode(node)&&!whitespaceNode(node)&&
           !['script','title'].includes(node.parentNode?.nodeName??''))plain=false;
  if('childNodes' in node)for(const child of node.childNodes)pending.push(child);
 }
 const markup=plain&&script!==undefined?literalInsertion(script):undefined;
 if(markup===undefined)return shapes.size>1?'MISSING_MIXED':shapes.values().next().value??'MISSING';
 return readLiteralFragment(markup,frameName);
}

function readLiteralFragment(markup:string,frameName:string|undefined):FormProof|FormRejection{
 let ambiguous=false;
 const fragment=parseFragment(markup,{sourceCodeLocationInfo:true,onParseError:error=>{if(error.code==='duplicate-attribute')ambiguous=true;}});
 if(ambiguous)return 'DUPLICATE_ATTRIBUTE';
 const [form,...extra]=fragment.childNodes.filter(node=>!whitespaceNode(node));
 if(extra.length||!form||!defaultTreeAdapter.isElementNode(form)||form.namespaceURI!==htmlNamespace||
    form.tagName!=='form'||hasHandler(form)||attribute(form,'id')==='forms'||attribute(form,'name')==='forms')return 'LITERAL_MARKUP';
 // Fragment parsing can silently discard document tags or a nested form start.
 // Prove that the literal source itself contains only the allowed tag spans.
 const {startTag,endTag}=form.sourceCodeLocation??{};
 if(!startTag||!endTag||!htmlWhitespace(markup.slice(0,startTag.startOffset))||
    !htmlWhitespace(markup.slice(endTag.endOffset)))return 'LITERAL_MARKUP';
 let position=startTag.endOffset;
 for(const node of form.childNodes){
  if(whitespaceNode(node))continue;
  if(!defaultTreeAdapter.isElementNode(node)||node.namespaceURI!==htmlNamespace||node.tagName!=='input'||
     hasHandler(node)||attribute(node,'id')==='submit')return 'LITERAL_MARKUP';
  const input=node.sourceCodeLocation?.startTag;
  if(!input||input.startOffset<position||!htmlWhitespace(markup.slice(position,input.startOffset)))return 'LITERAL_MARKUP';
  position=input.endOffset;
 }
 if(endTag.startOffset<position||!htmlWhitespace(markup.slice(position,endTag.startOffset)))return 'LITERAL_MARKUP';
 return readForm(fragment,frameName);
}
