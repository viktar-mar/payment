import {defaultTreeAdapter as tree,parse,parseFragment,type DefaultTreeAdapterTypes} from 'parse5';
// @ts-ignore Native Node executes the same form proof as Playwright.
import {externalIssuerResultForm,issuerResultForm} from './stripe-billing-initial-3ds-result-form.ts';
// @ts-ignore Native Node executes the same closed icon classification as Playwright.
import {describeIssuerHeadIconHref,type IssuerHeadIconDataRejection,type IssuerHeadIconHrefKind} from './stripe-billing-initial-3ds-head-icon.ts';

const limit=65536;
const foldJson='BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_JSON';
const foldDataJson='BROWSER_CHALLENGE_CRES_FORM_EXTERNAL_COMPANION_JSON_TEXT_FOLD_DATA_JSON';
const fixtureOrigin='https://issuer-replay.test';
const fixtureDocumentUrl=fixtureOrigin+'/fixture/decision';
const fixtureFrame='fixture-issuer-frame';
const fixtureAction='https://hooks.stripe.com/fixture-followup';
const syntheticCres=Buffer.from(JSON.stringify({
 messageType:'CRes',messageVersion:'2.2.0',
 threeDSServerTransID:'11111111-2222-4333-8444-555555555555',
 acsTransID:'11111111-2222-4333-8444-555555555554',challengeCompletionInd:'Y',transStatus:'Y',
})).toString('base64url');

type Checkpoint=typeof foldJson|typeof foldDataJson;
type Placement='head'|'body';
type Element=DefaultTreeAdapterTypes.Element;
type Span={startOffset:number;endOffset:number};
type Rewrite=Span&{value:string;strict?:string};
type CaptureSource={html:Uint8Array;script:Uint8Array;documentUrl:string;scriptUrl:string;frameName?:string};
export type IssuerDecisionReplayFixture={
 decisionHtml:string;strictDecisionHtml:string;scriptSource:string;
 documentUrl:string;scriptUrl:string;frameName:string;checkpoint:Checkpoint;
};

const htmlNamespace='http://www.w3.org/1999/xhtml';
const htmlWhitespace=/^[ \t\r\n\f]*$/;
const jsonSpace=/[ \t\r\n]/;
const attribute=(node:Element,name:string)=>node.attrs.find(attr=>attr.name===name)?.value;
const byteWidth=(value:string)=>Buffer.byteLength(value,'utf8');
const isCheckpoint=(code:string):code is Checkpoint=>code===foldJson||code===foldDataJson;

function decode(bytes:Uint8Array,ignoreBOM=false):string|undefined{
 if(!bytes.length||bytes.length>limit)return;
 try{return new TextDecoder('utf-8',{fatal:true,ignoreBOM}).decode(bytes);}
 catch{return;}
}

// The only source-derived characters rendered verbatim are selected whitespace.
// Construct them from constants, independently of any original string slice.
function whitespace(value:string):string{
 return value.replace(/[\s\S]/g,char=>{
  switch(char){
   case '\t':return '\t';
   case '\r':return '\r';
   case '\n':return '\n';
   case '\f':return '\f';
   default:return ' ';
  }
 });
}

// Require every non-whitespace byte to be covered by a recognized span.
// HTML parsing can silently drop otherwise unsupported tags.
function reconstruct(source:string,rewrites:Rewrite[],strict=false):string|undefined{
 let position=0;
 const parts:string[]=[];
 for(const {startOffset,endOffset,value,strict:control} of rewrites.sort((a,b)=>a.startOffset-b.startOffset)){
  if(startOffset<position||endOffset<startOffset)return;
  const gap=source.slice(position,startOffset);
  if(!htmlWhitespace.test(gap))return;
  parts.push(whitespace(gap),strict?control??value:value);
  position=endOffset;
 }
 const tail=source.slice(position);
 if(!htmlWhitespace.test(tail))return;
 return parts.join('')+whitespace(tail);
}

/** Replace JSON values with fixed syntax of the same byte width. The width is
 * needed to retain Base64 line boundaries and padding without retaining data. */
function syntheticJson(text:string,accept:(candidate:string)=>boolean):string|undefined{
 let parsed:unknown;
 try{parsed=JSON.parse(text);}
 catch{return;}
 let start=0,end=text.length;
 while(start<end&&jsonSpace.test(text.charAt(start)))start++;
 while(end>start&&jsonSpace.test(text.charAt(end-1)))end--;
 const prefix=whitespace(text.slice(0,start)),suffix=whitespace(text.slice(end));
 const width=byteWidth(text.slice(start,end));
 const templates:readonly [string,string,string][]=parsed===null?[['null','','']]:
  typeof parsed==='boolean'?[[width===4?'true':'false','','']]:
  typeof parsed==='number'?[[width===1?'0':width===2?'10':'0e','0','']]:
  typeof parsed==='string'?[['"','x','"'],['"?','x','"'],['"x?','x','"'],['"xx?','x','"']]:
  Array.isArray(parsed)?[['[',' ',']'],['["?"',' ',']'],['["x?"',' ',']'],['["xx?"',' ',']']]:
  [['{',' ','}'],['{"x":"?"',' ','}'],['{"x":"x?"',' ','}'],['{"x":"xx?"',' ','}']];
 for(const [left,filler,right] of templates){
  const remaining=width-left.length-right.length;
  if(remaining<0||!filler&&remaining!==0)continue;
  const replacement=prefix+left+filler.repeat(remaining)+right+suffix;
  if(accept(replacement))return replacement;
 }
}

function foldedCompanion(contents:string,checkpoint:Checkpoint):{value:string;strict:string}|undefined{
 const leading=/^[ \t\r\n]*/.exec(contents)![0];
 const afterLeading=contents.slice(leading.length);
 const envelope=/^data:(application\/json)?;base64,/i.exec(afterLeading);
 if(Boolean(envelope)!==(checkpoint===foldDataJson))return;
 const payload=envelope?afterLeading.slice(envelope[0].length):contents;
 const compact=payload.replace(/[ \t\r\n]/g,'');
 if(!/[\r\n]/.test(payload)||!/^[A-Za-z0-9+/_-]+={0,2}$/.test(compact))return;
 const bytes=Buffer.from(compact,'base64'),standard=bytes.toString('base64');
 const paddedUrl=standard.replace(/\+/g,'-').replace(/\//g,'_');
 if(compact!==standard&&compact!==paddedUrl&&compact!==bytes.toString('base64url'))return;
 const decoded=decode(bytes,true);
 if(decoded===undefined)return;
 const standardSymbols=/[+/]/.test(compact),urlSymbols=/[-_]/.test(compact);
 const padded=compact.endsWith('=');
 function encode(candidate:string){
  const b64=Buffer.from(candidate,'utf8').toString('base64');
  const alphabet=urlSymbols?b64.replace(/\+/g,'-').replace(/\//g,'_'):b64;
  return padded?alphabet:alphabet.replace(/=+$/,'');
 }
 const json=syntheticJson(decoded,candidate=>{
  const encoded=encode(candidate);
  return encoded.length===compact.length&&/[+/]/.test(encoded)===standardSymbols&&/[-_]/.test(encoded)===urlSymbols;
 });
 if(!json)return;
 const rebuilt=encode(json);
 let offset=0;
 const parts=payload.split(/([ \t\r\n]+)/).map(part=>{
  if(jsonSpace.test(part))return whitespace(part);
  const chunk=rebuilt.slice(offset,offset+part.length);
  offset+=part.length;
  return chunk;
 });
 const header=envelope?whitespace(leading)+(envelope[1]?'data:application/json;base64,':'data:;base64,'):'';
 return {value:header+parts.join(''),strict:json};
}

function replayScriptSrc(src:string){
 if(src.startsWith('https://'))return fixtureOrigin+'/fixture/producer.js';
 if(src.startsWith('/'))return '/fixture/producer.js';
 if(src.startsWith('./'))return './producer.js';
 return 'producer.js';
}

function decisionLayout(html:string,source:CaptureSource,checkpoint:Checkpoint){
 let malformed=false;
 const document=parse(html,{sourceCodeLocationInfo:true,onParseError:error=>{if(error.code!=='missing-doctype')malformed=true;}});
 if(malformed)return;
 const rewrites:Rewrite[]=[];
 const pending:DefaultTreeAdapterTypes.Node[]=[...document.childNodes].reverse();
 let placement:Placement|undefined,src:string|undefined,folded:Rewrite|undefined,companionId=0;
 while(pending.length){
  const node=pending.pop()!;
  if(tree.isTextNode(node))continue;
  if(tree.isDocumentTypeNode(node)){
   if(!node.sourceCodeLocation)return;
   const {startOffset,endOffset}=node.sourceCodeLocation;
   if(!/^<!doctype[ \t\r\n\f]+html[ \t\r\n\f]*>$/i.test(html.slice(startOffset,endOffset)))return;
   rewrites.push({...node.sourceCodeLocation,value:'<!doctype html>'});
   continue;
  }
  if(!tree.isElementNode(node)||node.namespaceURI!==htmlNamespace)return;
  const {tagName,sourceCodeLocation,parentNode,attrs}=node;
  const start=sourceCodeLocation?.startTag,end=sourceCodeLocation?.endTag;
  switch(tagName){
   case 'html':case 'head':case 'body':
    if(attrs.length)return;
    if(start)rewrites.push({...start,value:'<'+tagName+'>'});
    if(end)rewrites.push({...end,value:'</'+tagName+'>'});
    pending.push(...[...node.childNodes].reverse());
    break;
   case 'meta':
    if(!start||attrs.length!==1||attribute(node,'charset')?.toLowerCase()!=='utf-8')return;
    rewrites.push({...start,value:'<meta charset="utf-8">'});
    break;
   case 'title':
    if(!start||!end||attrs.length||!node.childNodes.every(tree.isTextNode))return;
    rewrites.push({startOffset:start.startOffset,endOffset:end.endOffset,value:'<title>Replay fixture</title>'});
    break;
   case 'script':{
    if(!start||!end||!node.childNodes.every(tree.isTextNode))return;
    const parent=parentNode?.nodeName;
    if(parent!=='head'&&parent!=='body')return;
    const contents=html.slice(start.endOffset,end.startOffset);
    const originalSrc=attribute(node,'src');
    const type=attribute(node,'type');
    let open:string,body:{value:string;strict?:string};
    if(originalSrc!==undefined){
     if(src||attrs.some(attr=>attr.name!=='src'&&attr.name!=='type')||!htmlWhitespace.test(contents))return;
     const normalized=type?.trim().toLowerCase();
     if(normalized!==undefined&&normalized!==''&&normalized!=='text/javascript'&&normalized!=='application/javascript')return;
     src=replayScriptSrc(originalSrc);
     placement=parent;
     const typeAttribute=normalized===undefined?'':' type="'+normalized+'"';
     open='<script'+typeAttribute+' src="'+src+'">';
     body={value:whitespace(contents)};
    }else{
     if(type!=='application/json'||attrs.some(attr=>attr.name!=='type'&&attr.name!=='id'))return;
     const id=attribute(node,'id')===undefined?'':' id="fixture-data-'+(++companionId)+'"';
     open='<script type="application/json"'+id+'>';
     const strict=syntheticJson(contents,()=>true);
     if(strict)body={value:strict};
     else{
      if(folded)return;
      const replacement=foldedCompanion(contents,checkpoint);
      if(!replacement)return;
      body=replacement;
      folded={startOffset:start.endOffset,endOffset:end.startOffset,...body};
     }
    }
    rewrites.push({...start,value:open},{startOffset:start.endOffset,endOffset:end.startOffset,...body},{...end,value:'</script>'});
    break;
   }
   default:return;
  }
 }
 if(!src||!placement||!folded?.strict)return;
 const originalControl=html.slice(0,folded.startOffset)+folded.strict+html.slice(folded.endOffset);
 const originalPair=issuerResultForm(originalControl,source.frameName,undefined,source.documentUrl);
 if(!originalPair||!('scriptUrl' in originalPair)||originalPair.placement!==placement||originalPair.scriptUrl!==source.scriptUrl)return;
 const decisionHtml=reconstruct(html,rewrites),strictDecisionHtml=reconstruct(html,rewrites,true);
 if(decisionHtml===undefined||strictDecisionHtml===undefined)return;
 const scriptUrl=new URL(src,fixtureDocumentUrl).href;
 const proof=issuerResultForm(strictDecisionHtml,fixtureFrame,undefined,fixtureDocumentUrl);
 if(!proof||!('scriptUrl' in proof)||proof.scriptUrl!==scriptUrl||proof.placement!==placement)return;
 const reported:string[]=[];
 if(issuerResultForm(decisionHtml,fixtureFrame,code=>reported.push(code),fixtureDocumentUrl)||
    reported.length!==1||reported[0]!==checkpoint)return;
 return {decisionHtml,strictDecisionHtml,scriptUrl,placement};
}

function syntheticForm(markup:string):string|undefined{
 let malformed=false;
 const fragment=parseFragment(markup,{sourceCodeLocationInfo:true,onParseError:()=>{malformed=true;}});
 if(malformed)return;
 const form=fragment.childNodes.find(tree.isElementNode);
 if(!form||form.tagName!=='form')return;
 const {startTag,endTag}=form.sourceCodeLocation??{};
 if(!startTag||!endTag)return;
 let open='<form';
 for(const {name,value} of form.attrs){
  switch(name){
   case 'method':open+=' method="post"';break;
   case 'action':open+=' action="'+fixtureAction+'"';break;
   case 'target':open+=' target="'+(!value?'':value.toLowerCase()==='_self'?'_self':fixtureFrame)+'"';break;
   case 'enctype':open+=' enctype="application/x-www-form-urlencoded"';break;
   default:return;
  }
 }
 const rewrites:Rewrite[]=[{...startTag,value:open+'>'},{...endTag,value:'</form>'}];
 for(const node of form.childNodes){
  if(tree.isTextNode(node))continue;
  if(!tree.isElementNode(node)||node.tagName!=='input'||!node.sourceCodeLocation?.startTag)return;
  const field=attribute(node,'name');
  if(field!=='cres'&&field!=='threeDSSessionData')return;
  let input='<input';
  for(const {name,value} of node.attrs){
   switch(name){
    case 'type':input+=' type="hidden"';break;
    case 'name':input+=' name="'+field+'"';break;
    case 'value':input+=' value="'+(field==='cres'?syntheticCres:value?'fixture&amp;session':'')+'"';break;
    default:return;
   }
  }
  rewrites.push({...node.sourceCodeLocation.startTag,value:input+'>'});
 }
 return reconstruct(markup,rewrites);
}

function syntheticProducer(script:string,bytes:Uint8Array,frameName:string|undefined,placement:Placement):string|undefined{
 // Use the live proof for syntax semantics, then support only the explicit
 // two producer recipes and the form attributes this replay can reconstruct.
 if(!externalIssuerResultForm(bytes,frameName,undefined,placement))return;
 const code=script.trim();
 const write=/^document\.write\s*\(\s*/.exec(code);
 const adjacent=/^document\.body\.insertAdjacentHTML\s*\(\s*(['"])beforeend\1\s*,\s*/.exec(code);
 const start=write??adjacent;
 const end=/\)\s*;\s*document\.forms\[0\]\.submit\s*\(\s*\)\s*(;)?$/.exec(code);
 if(!start||!end||start[0].length>=end.index)return;
 const literal=code.slice(start[0].length,end.index).trim();
 const single=/^'[^'\\\u0000-\u001f]*'$/u.test(literal);
 let original:unknown;
 try{original=single?literal.slice(1,-1):JSON.parse(literal);}
 catch{return;}
 if(typeof original!=='string')return;
 const form=syntheticForm(original);
 if(form===undefined)return;
 const insertion=write?'document.write(':adjacent?.[1]==='"'?
  'document.body.insertAdjacentHTML("beforeend", ':'document.body.insertAdjacentHTML(\'beforeend\', ';
 const replacement=insertion+(single?"'"+form+"'":JSON.stringify(form))+');document.forms[0].submit()'+(end[1]?';':'');
 if(byteWidth(replacement)>limit||!externalIssuerResultForm(Buffer.from(replacement),fixtureFrame,undefined,placement))return;
 return replacement;
}

/** Build a bounded replay from supported shapes. Original values stay in memory. */
export function anonymizeIssuerDecisionReplay(source:CaptureSource):IssuerDecisionReplayFixture|undefined{
 const html=decode(source.html),script=decode(source.script);
 if(html===undefined||script===undefined)return;
 let checkpoint:Checkpoint|undefined;
 const result=issuerResultForm(html,source.frameName,code=>{if(isCheckpoint(code))checkpoint=code;},source.documentUrl);
 if(result||!checkpoint)return;
 const layout=decisionLayout(html,source,checkpoint);
 if(!layout||byteWidth(layout.decisionHtml)>limit||byteWidth(layout.strictDecisionHtml)>limit)return;
 const {decisionHtml,strictDecisionHtml,scriptUrl,placement}=layout;
 const scriptSource=syntheticProducer(script,source.script,source.frameName,placement);
 if(scriptSource===undefined)return;
 return {decisionHtml,strictDecisionHtml,scriptSource,documentUrl:fixtureDocumentUrl,scriptUrl,frameName:fixtureFrame,checkpoint};
}

type LayoutParent='DOCUMENT'|'HTML'|'HEAD'|'BODY'|'OTHER';
type LayoutElement=Exclude<LayoutParent,'DOCUMENT'>|'DOCTYPE'|'COMMENT'|'META'|'TITLE'|'PRODUCER'|'COMPANION';
type LayoutAttribute='TYPE'|'SRC'|'ID'|'CHARSET'|'OTHER';
type HeadKind='LINK_STYLESHEET'|'LINK_ICON'|'LINK_PRELOAD'|'LINK_OTHER'|'BASEFONT'|'BGSOUND'|'OTHER';
type LayoutShape={
 element:LayoutElement;parent:LayoutParent;tags:'CLOSED'|'OPEN'|'IMPLIED'|'VOID';attributes:LayoutAttribute[];
 companion?:'STRICT_JSON'|'REJECTED_JSON';
 source?:'ABSOLUTE'|'ROOT_RELATIVE'|'DOT_RELATIVE'|'RELATIVE';
 headKind?:HeadKind;
 iconHrefKind?:IssuerHeadIconHrefKind;
 iconDataRejection?:IssuerHeadIconDataRejection;
};

const scriptMarkers=[
 ['CREATE_FORM',/\bcreateElement\s*\(\s*['"]form['"]/],
 ['DOCUMENT_WRITE',/\bdocument\s*\.\s*write(?:ln)?\s*\(/],
 ['ADJACENT_HTML',/\binsertAdjacentHTML\s*\(/],
 ['INNER_HTML',/\binnerHTML\b/],
 ['ATOB',/\batob\s*\(/],
 ['JSON_PARSE',/\bJSON\s*\.\s*parse\s*\(/],
 ['LOOKUP_ID',/\bgetElementById\s*\(/],
 ['SELECT',/\bquerySelector(?:All)?\s*\(/],
 ['SUBMIT',/\.(?:submit|requestSubmit)\s*\(/],
] as const;

export type IssuerDecisionReplayShape={
 checkpoint:string;producerPlacement:Placement;layout:LayoutShape[];layoutTruncated:boolean;htmlParseError:boolean;
 scriptProof:string;scriptMarkers:(typeof scriptMarkers)[number][0][];
};

function layoutParent(name:string|undefined):LayoutParent{
 switch(name){
  case '#document':return 'DOCUMENT';
  case 'html':return 'HTML';
  case 'head':return 'HEAD';
  case 'body':return 'BODY';
  default:return 'OTHER';
 }
}

function headKind(node:Element):HeadKind{
 if(node.namespaceURI!==htmlNamespace)return 'OTHER';
 switch(node.tagName){
  case 'basefont':return 'BASEFONT';
  case 'bgsound':return 'BGSOUND';
  case 'link':{
   const rel=attribute(node,'rel')?.toLowerCase().match(/[^\t\n\f\r ]+/g)?.join(' ');
   switch(rel){
    case 'stylesheet':return 'LINK_STYLESHEET';
    case 'icon':case 'shortcut icon':return 'LINK_ICON';
    case 'preload':return 'LINK_PRELOAD';
    default:return 'LINK_OTHER';
   }
  }
  default:return 'OTHER';
 }
}

function shapeFor(node:DefaultTreeAdapterTypes.ChildNode):LayoutShape|undefined{
 const parent=layoutParent(node.parentNode?.nodeName);
 if(tree.isDocumentTypeNode(node))return {element:'DOCTYPE',parent,tags:'VOID',attributes:[]};
 if(tree.isCommentNode(node))return {element:'COMMENT',parent,tags:'CLOSED',attributes:[]};
 if(!tree.isElementNode(node))return;
 const src=attribute(node,'src');
 let element:LayoutElement;
 switch(node.tagName){
  case 'html':element='HTML';break;
  case 'head':element='HEAD';break;
  case 'body':element='BODY';break;
  case 'meta':element='META';break;
  case 'title':element='TITLE';break;
  case 'script':element=src===undefined?'COMPANION':'PRODUCER';break;
  default:element='OTHER';
 }
 const location=node.sourceCodeLocation;
 const tags=location?.endTag?'CLOSED':!location?.startTag?'IMPLIED':element==='META'?'VOID':'OPEN';
 const attributes=new Set<LayoutAttribute>();
 for(const {name} of node.attrs){
  switch(name){
   case 'type':attributes.add('TYPE');break;
   case 'src':attributes.add('SRC');break;
   case 'id':attributes.add('ID');break;
   case 'charset':attributes.add('CHARSET');break;
   default:attributes.add('OTHER');
  }
 }
 const shape:LayoutShape={element,parent,tags,attributes:[...attributes]};
 if(element==='OTHER'&&parent==='HEAD'){
  shape.headKind=headKind(node);
  if(shape.headKind==='LINK_ICON'){
   const icon=describeIssuerHeadIconHref(attribute(node,'href'),attribute(node,'type'));
   shape.iconHrefKind=icon.kind;
   if(icon.kind==='UNSUPPORTED_DATA')shape.iconDataRejection=icon.dataRejection;
  }
 }
 if(element==='COMPANION'){
  const contents=node.childNodes.filter(tree.isTextNode).map(child=>child.value).join('');
  try{JSON.parse(contents);shape.companion='STRICT_JSON';}
  catch{shape.companion='REJECTED_JSON';}
 }
 if(element==='PRODUCER'&&src!==undefined){
  shape.source=src.startsWith('https://')?'ABSOLUTE':src.startsWith('/')?'ROOT_RELATIVE':src.startsWith('./')?'DOT_RELATIVE':'RELATIVE';
 }
 return shape;
}

/** Summarize a paired refusal using only fixed categories when the producer
 * or companion cannot be safely reconstructed. Markers are lexical cues. */
export function describeIssuerDecisionReplay(source:CaptureSource):IssuerDecisionReplayShape|undefined{
 const html=decode(source.html);
 if(html===undefined||!source.script.byteLength||source.script.byteLength>limit)return;
 const observed:{checkpoint?:string;placement?:Placement}={};
 const result=issuerResultForm(html,source.frameName,code=>{observed.checkpoint=code;},source.documentUrl,reference=>{
  if(reference.scriptUrl===source.scriptUrl)observed.placement=reference.placement;
 });
 const {checkpoint,placement}=observed;
 if(result||!checkpoint||!placement)return;
 let htmlParseError=false;
 const parsed=parse(html,{sourceCodeLocationInfo:true,onParseError:()=>{htmlParseError=true;}});
 const pending=[...parsed.childNodes].reverse(),layout:LayoutShape[]=[];
 let layoutTruncated=false;
 while(pending.length){
  const node=pending.pop()!;
  const shape=shapeFor(node);
  if(!shape)continue;
  if(layout.length===32){layoutTruncated=true;break;}
  layout.push(shape);
  if(tree.isElementNode(node))pending.push(...[...node.childNodes].reverse());
 }
 // Both proof functions emit fixed codes and retain their existing admission rules.
 let scriptProof='UNSUPPORTED';
 if(externalIssuerResultForm(source.script,source.frameName,code=>{scriptProof=code;},placement))scriptProof='PROVEN';
 const script=decode(source.script)??'';
 return {checkpoint,producerPlacement:placement,layout,layoutTruncated,htmlParseError,scriptProof,
  scriptMarkers:scriptMarkers.filter(([,pattern])=>pattern.test(script)).map(([marker])=>marker)};
}
