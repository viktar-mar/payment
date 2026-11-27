import {defaultTreeAdapter,parse,parseFragment,type DefaultTreeAdapterTypes} from 'parse5';

const htmlNamespace='http://www.w3.org/1999/xhtml';
const controls=new Set(['button','datalist','fieldset','input','object','output','select','textarea']);
const classicScriptTypes=new Set(['','text/javascript','application/javascript']);
const structuralElements=new Set(['html','head','body','title','meta']);
const attribute=(element:DefaultTreeAdapterTypes.Element,name:string)=>element.attrs.find(attr=>attr.name===name)?.value;
type LayoutRejection='EXTERNAL_LAYOUT_TAG'|'EXTERNAL_LAYOUT_ATTRIBUTES'|'EXTERNAL_LAYOUT_TEXT'|'EXTERNAL_LAYOUT_HANDLER';
type BaseSixtyFourEvidence='BASE_SIXTY_FOUR'|'FORMAT_BASE_TEXT'|'FORMAT_BASE_BYTES';
type TextStructure='J'|'M'|'B'|'O'|'D'|'T'|'P';
type TextEncoding='N'|'P'|'E'|'B'|'M';
type CompanionJsonTextShape=`TEXT_SHAPE_${'A'|'D'|'S'}${TextStructure}${TextEncoding}${'S'|'L'}${'A'|'U'|'C'}`;
type FoldedCompressionHeader='TEXT_FOLD_GZIP'|'TEXT_FOLD_ZLIB'|'TEXT_FOLD_ZSTD';
type MultilineCompanionText=`TEXT_FOLD_${'JSON'|'TEXT'|'BYTES'|'BASE'|'DATA_JSON'|'DATA'|'FORM'}`|
 FoldedCompressionHeader|'TEXT_LINE_PAIRS'|`TEXT_LINE_OP_${'E'|'C'|'L'|'S'|'R'|'M'}`;
type CompanionJsonText='TEXT_DATA_URI'|'TEXT_URI'|'TEXT_FORM'|'TEXT_ASSIGN_JSON'|'TEXT_ASSIGNMENT'|
 'TEXT_CALL_JSON'|'TEXT_CALL'|'TEXT_JS_LITERAL'|'TEXT_NUMERIC'|'TEXT_BASE_SHAPE'|'TEXT_IDENTIFIER'|
 CompanionJsonTextShape|MultilineCompanionText;
type CompanionJsonShape='FORMAT_BOM'|'FORMAT_COMMENT'|'FORMAT_MARKUP'|'FORMAT_ENTITY'|'FORMAT_PERCENT'|
 'FORMAT_ESCAPE'|'FORMAT_QUOTED'|'FORMAT_OBJECT'|'FORMAT_ARRAY'|'FORMAT_SPACE'|'FORMAT_OTHER'|CompanionJsonText;
type InvalidCompanionJson='BOM'|'HTML_COMMENT'|'HTML_QUOTES'|'PERCENT'|BaseSixtyFourEvidence|
 'CONTROL'|'ESCAPE'|'INCOMPLETE'|'PROPERTY'|'DELIMITER'|'NUMBER'|'TRAILING'|'FORMAT_LIMIT'|CompanionJsonShape;
type CompanionRejection='EXTERNAL_COMPANION_PARENT'|'EXTERNAL_COMPANION_TAG'|'EXTERNAL_COMPANION_TYPE_MISSING'|
 'EXTERNAL_COMPANION_TYPE_JSON_VARIANT'|'EXTERNAL_COMPANION_TYPE_JSON_LD'|'EXTERNAL_COMPANION_TYPE_IMPORTMAP'|
 'EXTERNAL_COMPANION_TYPE_SPECULATION_RULES'|'EXTERNAL_COMPANION_TYPE_OTHER'|'EXTERNAL_COMPANION_HANDLER'|
 'EXTERNAL_COMPANION_ATTRIBUTE_NONCE'|'EXTERNAL_COMPANION_ATTRIBUTES'|
 'EXTERNAL_COMPANION_JSON_EMPTY'|`EXTERNAL_COMPANION_JSON_${InvalidCompanionJson}`;
type FormRejection='DUPLICATE_ATTRIBUTE'|'BASE'|'OWNER'|'MISSING'|'MULTIPLE'|'METHOD'|'TARGET'|'ENCODING'|
 'ACTION'|'CONTROL'|'FIELD'|'DUPLICATE_FIELD'|'REQUIRED_CRES'|'SIZE'|'LITERAL_MARKUP'|
 'MISSING_INLINE'|'MISSING_INLINE_DOM'|'MISSING_INLINE_MARKUP'|'MISSING_EXTERNAL'|'MISSING_TEMPLATE'|'MISSING_HANDLER'|'MISSING_MIXED'|
 'EXTERNAL_COUNT_MULTIPLE_SOURCES'|'EXTERNAL_COUNT_RECOGNIZED_JS_COMPANION'|
 'EXTERNAL_PARENT'|'EXTERNAL_TAG'|'EXTERNAL_TYPE'|'EXTERNAL_ATTRIBUTES'|'EXTERNAL_TEXT'|'EXTERNAL_URL'|LayoutRejection|CompanionRejection;
type FormProof={action:string;body:string};
type ScriptPlacement='head'|'body';
type ExternalScript={scriptUrl:string;placement:ScriptPlacement};
type ScriptElement={node:Element;text:string;src:string|undefined;classic:boolean;recognizedJsType:boolean};
type LiteralInsertion={markup:string;method:'write'|'insertAdjacentHTML'};
type Reporter=(code:string)=>void;
type RefusedScriptLocator=(script:ExternalScript)=>void;
type Element=DefaultTreeAdapterTypes.Element;
const htmlWhitespace=(value:string)=>/^[\t\n\f\r ]*$/.test(value);
const whitespaceNode=(node:DefaultTreeAdapterTypes.Node)=>defaultTreeAdapter.isTextNode(node)&&htmlWhitespace(node.value);
const hasHandler=(element:Element)=>element.attrs.some(attr=>attr.name.startsWith('on'));
const supportedScriptAttributes=(element:Element)=>element.attrs.every(attr=>attr.name==='type'||attr.name==='src');
const createsForm=(source:string)=>/\bcreateElement\s*\(\s*['"]form['"]/.test(source);
const normalizedScriptType=(value:string)=>value.replace(/^[\t\n\f\r ]+|[\t\n\f\r ]+$/g,'').toLowerCase();

export function isIssuerScriptPath(pathname:string):boolean{
 return /^\/(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*\/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.js$/.test(pathname);
}

/** Derive the wire request or locate its sole external script in the original issuer HTML. */
export function issuerResultForm(html:string,frameName?:string,report?:Reporter):FormProof|undefined;
export function issuerResultForm(html:string,frameName:string|undefined,report:Reporter|undefined,
 documentUrl:string|undefined,locateRefusedScript?:RefusedScriptLocator):FormProof|ExternalScript|undefined;
export function issuerResultForm(html:string,frameName?:string,report?:Reporter,documentUrl?:string,
 locateRefusedScript?:RefusedScriptLocator):FormProof|ExternalScript|undefined{
 let ambiguous=false;
 const document=parse(html,{sourceCodeLocationInfo:true,onParseError:error=>{if(error.code==='duplicate-attribute')ambiguous=true;}});
 let result:FormProof|ExternalScript|FormRejection=ambiguous?'DUPLICATE_ATTRIBUTE':readForm(document,frameName);
 if(result==='MISSING')result=readLiteralForm(document,frameName,documentUrl,locateRefusedScript);
 if(typeof result==='string')return reportFormRejection(result,report);
 return result;
}

function reportFormRejection(rejection:FormRejection,report:Reporter|undefined):undefined{
 report?.('BROWSER_CHALLENGE_CRES_FORM_'+rejection);
}

/** Prove a form from the exact source of the external script in the decision document. */
export function externalIssuerResultForm(source:Uint8Array,frameName?:string,report?:Reporter,
 placement:ScriptPlacement='body'):FormProof|undefined{
 if(source.byteLength===0||source.byteLength>65536){report?.('BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED');return;}
 let code:string;
 try{code=new TextDecoder('utf-8',{fatal:true}).decode(source);}
 catch{report?.('BROWSER_CHALLENGE_CRES_SCRIPT_UTF8');return;}
 const insertion=literalInsertion(code);
 if(!insertion){
  report?.(createsForm(code)?'BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED_DOM':'BROWSER_CHALLENGE_CRES_SCRIPT_UNSUPPORTED');
  return;
 }
 // A classic parser-blocking script in the head has no body yet. document.write
 // inserts the form into the parser stream, which opens the body before submit.
 if(placement==='head'&&insertion.method==='insertAdjacentHTML'){
  report?.('BROWSER_CHALLENGE_CRES_SCRIPT_HEAD_INSERTION');
  return;
 }
 const proof=readLiteralFragment(insertion.markup,frameName);
 if(typeof proof==='string')return reportFormRejection(proof,report);
 return proof;
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
function literalInsertion(source:string):LiteralInsertion|undefined{
 const code=source.trim();
 const start=/^(?:(document\.write)\s*\(\s*|document\.body\.insertAdjacentHTML\s*\(\s*(?:"beforeend"|'beforeend')\s*,\s*)/.exec(code);
 const end=/\)\s*;\s*document\.forms\[0\]\.submit\s*\(\s*\)\s*;?$/.exec(code);
 if(!start||!end||start[0].length>=end.index)return;
 const method=start[1]?'write':'insertAdjacentHTML';
 const literal=code.slice(start[0].length,end.index).trim();
 if(/^'[^'\\\u0000-\u001f]*'$/u.test(literal))return {markup:literal.slice(1,-1),method};
 if(!literal.startsWith('"'))return;
 try{const value:unknown=JSON.parse(literal);if(typeof value==='string')return {markup:value,method};}
 catch{/* Expressions and other JavaScript string syntax are outside this subset. */}
}

function canonicalScriptUrl(src:string,documentUrl:string):string|undefined{
 if(!URL.canParse(documentUrl))return;
 const document=new URL(documentUrl);
 if(document.protocol!=='https:'||document.href!==documentUrl||document.username||document.password||documentUrl.includes('#'))return;
 let path=src;
 if(src.startsWith(document.origin+'/'))path=src.slice(document.origin.length);
 else if(src.startsWith('./'))path=src.slice(1);
 else if(!src.startsWith('/'))path='/'+src;
 if(!isIssuerScriptPath(path))return;
 const script=new URL(src,document);
 if(script.origin===document.origin&&script.href===script.origin+script.pathname&&isIssuerScriptPath(script.pathname))return script.href;
}

function documentElementRejection(element:Element):LayoutRejection|undefined{
 if(hasHandler(element))return 'EXTERNAL_LAYOUT_HANDLER';
 if(element.namespaceURI!==htmlNamespace||!structuralElements.has(element.tagName))return 'EXTERNAL_LAYOUT_TAG';
 if(element.tagName==='meta'){
  if(element.attrs.length!==1||attribute(element,'charset')?.toLowerCase()!=='utf-8')return 'EXTERNAL_LAYOUT_ATTRIBUTES';
 }else if(element.attrs.length!==0)return 'EXTERNAL_LAYOUT_ATTRIBUTES';
}

function companionStructureRejection({node}:ScriptElement):CompanionRejection|undefined{
 const placement=node.parentNode?.nodeName,location=node.sourceCodeLocation;
 if(placement!=='head'&&placement!=='body')return 'EXTERNAL_COMPANION_PARENT';
 if(!location?.startTag||!location.endTag)return 'EXTERNAL_COMPANION_TAG';
 const type=attribute(node,'type');
 if(type===undefined)return 'EXTERNAL_COMPANION_TYPE_MISSING';
 if(type!=='application/json'){
  switch(normalizedScriptType(type)){
   case 'application/json':return 'EXTERNAL_COMPANION_TYPE_JSON_VARIANT';
   case 'application/ld+json':return 'EXTERNAL_COMPANION_TYPE_JSON_LD';
   case 'importmap':return 'EXTERNAL_COMPANION_TYPE_IMPORTMAP';
   case 'speculationrules':return 'EXTERNAL_COMPANION_TYPE_SPECULATION_RULES';
   default:return 'EXTERNAL_COMPANION_TYPE_OTHER';
  }
 }
 // Script ids are outside the Document and form named lookups used by the literal.
 if(node.attrs.some(attr=>attr.name!=='type'&&attr.name!=='id')){
  if(hasHandler(node))return 'EXTERNAL_COMPANION_HANDLER';
  if(node.attrs.length===2&&attribute(node,'nonce')!==undefined)return 'EXTERNAL_COMPANION_ATTRIBUTE_NONCE';
  return 'EXTERNAL_COMPANION_ATTRIBUTES';
 }
}

function companionRejection(script:ScriptElement):CompanionRejection|undefined{
 const structure=companionStructureRejection(script);
 if(structure)return structure;
 if(htmlWhitespace(script.text))return 'EXTERNAL_COMPANION_JSON_EMPTY';
 try{JSON.parse(script.text);}
 catch(error){return `EXTERNAL_COMPANION_JSON_${invalidCompanionJson(script.text,error)}`;}
}

function parsesJson(text:string):boolean{
 try{JSON.parse(text);return true;}
 catch{return false;}
}

// Match only fixed V8 message prefixes. Unrecognized messages fall back to the observed format.
const jsonSyntaxReasons=[
 [/^Bad control character in string literal in JSON at position /,'CONTROL'],
 [/^Bad (?:escaped character|Unicode escape) in JSON at position /,'ESCAPE'],
 [/^(?:Unexpected end of JSON input$|Unterminated string in JSON at position )/,'INCOMPLETE'],
 [/^Expected (?:property name or '}'|double-quoted property name) in JSON at position /,'PROPERTY'],
 [/^Expected (?:':' after property name|',' or '}' after property value|',' or '\]' after array element) in JSON at position /,'DELIMITER'],
 [/^(?:No number after minus sign|Unterminated fractional number|Exponent part is missing a number|Unexpected number) in JSON at position /,'NUMBER'],
 [/^Unexpected non-whitespace character after JSON at position /,'TRAILING'],
] as const;

function canonicalBaseSixtyFour(value:string):Buffer|undefined{
 if(!/^[A-Za-z0-9+/_-]+={0,2}$/.test(value))return;
 const bytes=Buffer.from(value,'base64');
 const standard=bytes.toString('base64');
 const paddedUrlSafe=standard.replace(/\+/g,'-').replace(/\//g,'_');
 if(value===standard||value===paddedUrlSafe||value===bytes.toString('base64url'))return bytes;
}

function baseSixtyFourEvidence(bytes:Buffer):BaseSixtyFourEvidence{
 let decoded:string;
 try{decoded=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);}
 catch{return 'FORMAT_BASE_BYTES';}
 return parsesJson(decoded)?'BASE_SIXTY_FOUR':'FORMAT_BASE_TEXT';
}

const baseSixtyFourShape=/^[A-Za-z0-9+/_-]+=*$/;
const formText=/^[A-Za-z0-9_.%~*+-]+=[^&\s]*(?:&[A-Za-z0-9_.%~*+-]+=[^&\s]*)+$/;
const jsonWhitespace=/[ \t\r\n]/;

function trimJsonWhitespace(text:string):string{
 let start=0,end=text.length;
 while(start<end&&jsonWhitespace.test(text.charAt(start)))start++;
 while(end>start&&jsonWhitespace.test(text.charAt(end-1)))end--;
 return text.slice(start,end);
}

function foldedCompressionHeader(bytes:Buffer):FoldedCompressionHeader|undefined{
 // These fixed prefixes do not prove that the remaining bytes form a complete stream.
 if(bytes[0]===0x1f&&bytes[1]===0x8b&&bytes[2]===8)return 'TEXT_FOLD_GZIP';
 if(bytes[0]===0x28&&bytes[1]===0xb5&&bytes[2]===0x2f&&bytes[3]===0xfd)return 'TEXT_FOLD_ZSTD';
 if(bytes.length>=2){
  const cmf=bytes.readUInt8(0);
  if((cmf&0x0f)===8&&(cmf>>4)<=7&&bytes.readUInt16BE(0)%31===0)return 'TEXT_FOLD_ZLIB';
 }
}

const foldedBaseOutcomes={
 FORMAT_BASE_TEXT:'TEXT_FOLD_TEXT',
 FORMAT_BASE_BYTES:'TEXT_FOLD_BYTES',
} as const;

function hasMultilinePairs(value:string):boolean{
 let delimiter:string|undefined,pairs=0;
 for(const line of value.split(/\r\n?|\n/)){
  if(/^[ \t]*$/.test(line))continue;
  const pair=/^[ \t]*[A-Za-z_][A-Za-z0-9_.-]{0,63}[ \t]*([=:])[ \t]*(\S)/.exec(line);
  if(!pair)return false;
  const [,separator,firstValue]=pair;
  if(separator==='='&&(firstValue==='='||firstValue==='>'))return false;
  if(delimiter!==undefined&&separator!==delimiter)return false;
  delimiter=separator;
  pairs++;
 }
 return pairs>=2;
}

const multilineOperatorGroups=[
 ['E',/(?<![<>!])=/],
 ['C',/[<>!]/],
 ['L',/[&|]/],
 ['S',/\//],
 ['R',/[*%^~]/],
] as const;

function multilineCompanionText(value:string):MultilineCompanionText|'TEXT_SHAPE_AONLA'{
 // This probe runs only for the bounded ASCII multiline operator shape.
 const compact=value.replace(/[ \t\r\n]/g,'');
 if(baseSixtyFourShape.test(compact)){
  const bytes=canonicalBaseSixtyFour(compact);
  if(!bytes)return 'TEXT_FOLD_BASE';
  const evidence=baseSixtyFourEvidence(bytes);
  if(evidence==='BASE_SIXTY_FOUR')return 'TEXT_FOLD_JSON';
  return foldedCompressionHeader(bytes)??foldedBaseOutcomes[evidence];
 }
 const dataEnvelope=/^data:[^,\s]*,/i.exec(value)?.[0];
 if(dataEnvelope){
  const bytes=/;base64,$/i.test(dataEnvelope)?canonicalBaseSixtyFour(compact.slice(dataEnvelope.length)):undefined;
  return bytes&&baseSixtyFourEvidence(bytes)==='BASE_SIXTY_FOUR'?'TEXT_FOLD_DATA_JSON':'TEXT_FOLD_DATA';
 }
 if(value.includes('&')&&formText.test(value.split('&').map(trimJsonWhitespace).join('&')))return 'TEXT_FOLD_FORM';
 if(hasMultilinePairs(value))return 'TEXT_LINE_PAIRS';
 const [group,other]=multilineOperatorGroups.filter(([,pattern])=>pattern.test(value));
 return group?`TEXT_LINE_OP_${other?'M':group[0]}`:'TEXT_SHAPE_AONLA';
}

function companionTextStructure(value:string):TextStructure{
 // Only the first opener of each kind can start a candidate. The entire
 // suffix must be strict JSON, with at most one final semicolon removed.
 const end=value.endsWith(';')?-1:undefined;
 if([value.indexOf('{'),value.indexOf('[')].some(start=>start>=0&&parsesJson(value.slice(start,end))))return 'J';
 if(/<(?:\/?[A-Za-z]|[!?])/.test(value))return 'M';
 if(/[()[\]{}]/.test(value))return 'B';
 if(/[=<>!&|*/%^~]/.test(value))return 'O';
 if(/[:;,]/.test(value))return 'D';
 if(/^[\p{L}\p{M}\p{N}\p{White_Space}_+\-]+$/u.test(value))return 'T';
 return 'P';
}

const companionTextEncodingMarkers=[
 ['P',/%[0-9a-f]{2}/i],
 ['E',/&(?:#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);/],
 ['B',/\\(?:[bfnrtv0"'\\/]|x[0-9a-fA-F]{2}|u(?:[0-9a-fA-F]{4}|\{[0-9a-fA-F]{1,6}\}))/],
] as const;

function companionJsonTextShape(value:string):CompanionJsonTextShape|MultilineCompanionText{
 // The caller already bounded and JSON-whitespace-trimmed the failed text.
 // Each position below is a fixed category, independent of raw values.
 const start=/^[0-9]/.test(value)?'D':/^[+-]/.test(value)?'S':'A';
 const structure=companionTextStructure(value);
 const markers=companionTextEncodingMarkers.filter(([,pattern])=>pattern.test(value));
 const encoding:TextEncoding=markers.length>1?'M':markers[0]?.[0]??'N';
 const lineBreak=value.search(/[\r\n\u0085\u2028\u2029]/);
 const layout=lineBreak>=0&&/[^\p{White_Space}]/u.test(value.slice(lineBreak+1))?'L':'S';
 const charset=/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\p{Cf}]/u.test(value)?'C':/[^\x00-\x7f]/.test(value)?'U':'A';
 const shape=`TEXT_SHAPE_${start}${structure}${encoding}${layout}${charset}` as const;
 return shape==='TEXT_SHAPE_AONLA'?multilineCompanionText(value):shape;
}

function companionJsonText(value:string):CompanionJsonText{
 if(/^data:[^,\s]*,\S*$/i.test(value))return 'TEXT_DATA_URI';
 if(/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(value))return 'TEXT_URI';

 // Recognize an identifier or dotted member, or a declaration with a single name.
 const member=/^[A-Za-z_$][A-Za-z0-9_$]*(?:[ \t\r\n]*\.[ \t\r\n]*[A-Za-z_$][A-Za-z0-9_$]*)*[ \t\r\n]*/.exec(value)?.[0];
 const declaration=/^(?:var|let|const)[ \t\r\n]+[A-Za-z_$][A-Za-z0-9_$]*[ \t\r\n]*/.exec(value)?.[0];
 const assignmentStart=(declaration??member)?.length;
 const assignment=assignmentStart!==undefined&&/^=(?!=|>)/.test(value.slice(assignmentStart));
 const end=value.endsWith(';')?-1:undefined;
 if(assignment&&parsesJson(value.slice(assignmentStart+1,end)))return 'TEXT_ASSIGN_JSON';
 if(member&&value.charAt(member.length)==='('){
  const argument=value.slice(member.length+1,end);
  const close=/\)[ \t\r\n]*$/.exec(argument);
  return close&&parsesJson(argument.slice(0,close.index))?'TEXT_CALL_JSON':'TEXT_CALL';
 }
 // A single pair also looks like an assignment. Require a second pair.
 if(formText.test(value))return 'TEXT_FORM';
 if(assignment)return 'TEXT_ASSIGNMENT';
 if(/^(?:undefined|NaN|[+-]?Infinity)$/.test(value)||/^(?:true|false|null)$/i.test(value))return 'TEXT_JS_LITERAL';
 if(/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value))return 'TEXT_NUMERIC';
 if(/[+/=]/.test(value)&&baseSixtyFourShape.test(value))return 'TEXT_BASE_SHAPE';
 if(member?.length===value.length)return 'TEXT_IDENTIFIER';
 return companionJsonTextShape(value);
}

function companionJsonShape(value:string):CompanionJsonShape{
 if(value.startsWith('<!--')||value.startsWith('//')||value.startsWith('/*'))return 'FORMAT_COMMENT';
 switch(value.charAt(0)){
  case '\uFEFF':return 'FORMAT_BOM';
  case '<':return 'FORMAT_MARKUP';
  case '&':return 'FORMAT_ENTITY';
  case '%':return 'FORMAT_PERCENT';
  case '\\':return 'FORMAT_ESCAPE';
  case '"':case "'":case '`':return 'FORMAT_QUOTED';
  case '{':return 'FORMAT_OBJECT';
  case '[':return 'FORMAT_ARRAY';
 }
 if(/^[\p{White_Space}\p{Cc}]/u.test(value))return 'FORMAT_SPACE';
 if(/^[A-Za-z0-9_+-]/.test(value))return companionJsonText(value);
 return 'FORMAT_OTHER';
}

function invalidCompanionJson(text:string,error:unknown):InvalidCompanionJson{
 if(text.length>65536||Buffer.byteLength(text,'utf8')>65536)return 'FORMAT_LIMIT';
 // These candidates only describe the original failure. Never combine them or
 // admit a companion based on a transformed parse.
 const value=trimJsonWhitespace(text);
 if(value.startsWith('\uFEFF')&&parsesJson(value.slice(1)))return 'BOM';
 if(value.startsWith('<!--')&&value.endsWith('-->')){
  const content=value.slice(4,-3);
  if(!/<!--|--!?>/.test(content)&&parsesJson(content))return 'HTML_COMMENT';
 }
 const unquoted=value.replace(/&(?:quot|QUOT|#0*34|#[xX]0*22);/g,'"');
 if(unquoted!==value&&parsesJson(unquoted))return 'HTML_QUOTES';
 if(/%[0-9a-f]{2}/i.test(value)){
  try{JSON.parse(decodeURIComponent(value));return 'PERCENT';}
  catch{/* The decoded text must be strict JSON. */}
 }
 const bytes=canonicalBaseSixtyFour(value);
 const baseSixtyFour=bytes&&baseSixtyFourEvidence(bytes);
 if(baseSixtyFour==='BASE_SIXTY_FOUR')return baseSixtyFour;
 if(error instanceof SyntaxError){
  for(const [prefix,reason] of jsonSyntaxReasons)if(prefix.test(error.message))return reason;
 }
 return baseSixtyFour??companionJsonShape(value);
}

function externalScriptReference(external:ScriptElement,src:string,documentUrl:string,
 layout:LayoutRejection|undefined):ExternalScript|FormRejection{
 const placement=external.node.parentNode?.nodeName;
 if(placement!=='head'&&placement!=='body')return 'EXTERNAL_PARENT';
 if(!external.node.sourceCodeLocation?.endTag)return 'EXTERNAL_TAG';
 if(!external.classic)return 'EXTERNAL_TYPE';
 if(!supportedScriptAttributes(external.node))return 'EXTERNAL_ATTRIBUTES';
 if(!htmlWhitespace(external.text))return 'EXTERNAL_TEXT';
 if(layout)return layout;
 const scriptUrl=canonicalScriptUrl(src,documentUrl);
 return scriptUrl?{scriptUrl,placement}:'EXTERNAL_URL';
}

function readLiteralForm(document:DefaultTreeAdapterTypes.Document,frameName:string|undefined,
 documentUrl:string|undefined,locateRefusedScript?:RefusedScriptLocator):FormProof|ExternalScript|FormRejection{
 const shapes=new Set<FormRejection>();
 const pending:DefaultTreeAdapterTypes.Node[]=[document];
 const scripts:ScriptElement[]=[];
 let layout:LayoutRejection|undefined;
 while(pending.length){
  const node=pending.pop()!;
  if(defaultTreeAdapter.isElementNode(node)){
   if(hasHandler(node))shapes.add('MISSING_HANDLER');
   if(node.namespaceURI===htmlNamespace&&node.tagName==='template')shapes.add('MISSING_TEMPLATE');
   if(node.namespaceURI===htmlNamespace&&node.tagName==='script'){
    const declaredType=attribute(node,'type');
    const type=normalizedScriptType(declaredType??'');
    const text=node.childNodes.filter(defaultTreeAdapter.isTextNode).map(child=>child.value).join('');
    const src=attribute(node,'src');
    const classic=classicScriptTypes.has(type);
    const executable=classic||type==='module';
    const recognizedJsType=executable&&(declaredType!==undefined||attribute(node,'language')===undefined);
    scripts.push({node,text,src,classic,recognizedJsType});
    if(executable){
     if(src!==undefined)shapes.add('MISSING_EXTERNAL');
     else if(createsForm(text))shapes.add('MISSING_INLINE_DOM');
     else if(/\b(?:document\s*\.\s*write(?:ln)?|insertAdjacentHTML|innerHTML)\b/.test(text))shapes.add('MISSING_INLINE_MARKUP');
     else shapes.add('MISSING_INLINE');
    }
   }else layout??=documentElementRejection(node);
  }else if(defaultTreeAdapter.isTextNode(node)&&!whitespaceNode(node)&&
           !['script','title'].includes(node.parentNode?.nodeName??''))layout??='EXTERNAL_LAYOUT_TEXT';
  if('childNodes' in node)for(const child of node.childNodes)pending.push(child);
 }
 const external=scripts.find(script=>script.src!==undefined);
 if(external?.src!==undefined&&documentUrl!==undefined){
  const companions=scripts.filter(script=>script!==external);
  if(companions.some(script=>script.src!==undefined))return 'EXTERNAL_COUNT_MULTIPLE_SOURCES';
  if(companions.some(script=>script.recognizedJsType))return 'EXTERNAL_COUNT_RECOGNIZED_JS_COMPANION';
  // Report the first rejected companion in the reverse parsed traversal above.
  for(const companion of companions){
   const rejection=companionRejection(companion);
   if(rejection){
    if(locateRefusedScript&&rejection.startsWith('EXTERNAL_COMPANION_JSON_')&&
       rejection!=='EXTERNAL_COMPANION_JSON_EMPTY'&&rejection!=='EXTERNAL_COMPANION_JSON_FORMAT_LIMIT'&&
       companions.every(script=>!companionStructureRejection(script))){
     try{
      const reference=externalScriptReference(external,external.src,documentUrl,layout);
      if(typeof reference!=='string')locateRefusedScript(reference);
     }catch{/* A diagnostic locator cannot change the original refusal. */}
    }
    return rejection;
   }
  }
  return externalScriptReference(external,external.src,documentUrl,layout);
 }
 const [script]=scripts;
 if(!layout&&scripts.length===1&&script&&script.src===undefined&&script.classic&&
    script.node.parentNode?.nodeName==='body'&&script.node.sourceCodeLocation?.endTag&&supportedScriptAttributes(script.node)){
  const insertion=literalInsertion(script.text);
  if(insertion)return readLiteralFragment(insertion.markup,frameName);
 }
 return shapes.size>1?'MISSING_MIXED':shapes.values().next().value??'MISSING';
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
