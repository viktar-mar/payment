import ts from 'typescript';

type Fields=readonly (readonly [string,string])[];
type Companion={id:string;actionKey:string;fieldsKey:string;action:string;fields:Fields};
const localIdentifier=/^[A-Za-z_$][A-Za-z0-9_$]*$/;
const reservedBindings=new Set([
 'JSON','atob','document','Object','eval','arguments','await','yield','let',
 'undefined','NaN','Infinity','window','self','globalThis','location','top','parent','frames',
]);

function matchesPath(node:ts.Node|undefined,...path:string[]):boolean{
 let current=node;
 for(let index=path.length-1;index>0;index--){
  if(!current||!ts.isPropertyAccessExpression(current)||current.questionDotToken||
     !ts.isIdentifier(current.name)||current.name.getText()!==path[index])return false;
  current=current.expression;
 }
 return !!current&&ts.isIdentifier(current)&&current.getText()===path[0];
}

function callArguments(node:ts.Node|undefined,length:number,...path:string[]):ts.NodeArray<ts.Expression>|undefined{
 if(node&&ts.isCallExpression(node)&&!node.questionDotToken&&!node.typeArguments&&
    node.arguments.length===length&&matchesPath(node.expression,...path))return node.arguments;
}

function plainString(node:ts.Node|undefined,value:string):boolean{
 if(!node||!ts.isStringLiteral(node)||node.text!==value)return false;
 const spelled=node.getText();
 return spelled==="'"+value+"'"||spelled==='"'+value+'"';
}

function declaration(list:ts.Node|undefined):ts.VariableDeclaration|undefined{
 if(!list||!ts.isVariableDeclarationList(list)||list.declarations.length!==1||
    (list.flags&ts.NodeFlags.BlockScoped)!==ts.NodeFlags.Const)return;
 const [binding]=list.declarations;
 if(binding&&!binding.type&&!binding.exclamationToken)return binding;
}

function localName(node:ts.Node|undefined):string|undefined{
 if(!node||!ts.isIdentifier(node))return;
 const name=node.getText();
 if(localIdentifier.test(name)&&!reservedBindings.has(name))return name;
}

function initializedLocal(statement:ts.Statement|undefined):{name:string;value:ts.Expression}|undefined{
 if(!statement||!ts.isVariableStatement(statement)||statement.modifiers?.length)return;
 const binding=declaration(statement.declarationList),name=localName(binding?.name);
 if(name&&binding?.initializer)return {name,value:binding.initializer};
}

function expression(statement:ts.Statement|undefined):ts.Expression|undefined{
 if(statement&&ts.isExpressionStatement(statement))return statement.expression;
}

function assigned(statement:ts.Statement|undefined,local:string,key:string):ts.Expression|undefined{
 const value=expression(statement);
 if(value&&ts.isBinaryExpression(value)&&value.operatorToken.kind===ts.SyntaxKind.EqualsToken&&
    matchesPath(value.left,local,key))return value.right;
}

function inputBindings(loop:ts.ForOfStatement):[string,string]|undefined{
 const binding=declaration(loop.initializer);
 if(loop.awaitModifier||!binding||binding.initializer||!ts.isArrayBindingPattern(binding.name)||
    binding.name.elements.length!==2)return;
 const names:string[]=[];
 for(const element of binding.name.elements){
  if(!ts.isBindingElement(element)||element.propertyName||element.dotDotDotToken||element.initializer)return;
  const name=localName(element.name);
  if(!name)return;
  names.push(name);
 }
 return [names[0]!,names[1]!];
}

function boundedScript(source:string):ts.SourceFile|undefined{
 if(!source.length||source.length>65536||/[^\t\n\f\r\x20-\x7e]/.test(source))return;
 let failed=false,tokens=0;
 const scanner=ts.createScanner(ts.ScriptTarget.Latest,false,ts.LanguageVariant.Standard,source,()=>{failed=true;});
 for(let kind=scanner.scan();kind!==ts.SyntaxKind.EndOfFileToken;kind=scanner.scan()){
  if(kind===ts.SyntaxKind.WhitespaceTrivia||kind===ts.SyntaxKind.NewLineTrivia)continue;
  if(failed||kind<=ts.SyntaxKind.LastTriviaToken||++tokens>192)return;
 }
 if(failed)return;
 const parsed=ts.createSourceFile('issuer.js',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 const diagnostics=(parsed as ts.SourceFile&{parseDiagnostics?:readonly ts.Diagnostic[]}).parseDiagnostics;
 if(Array.isArray(diagnostics)&&diagnostics.length===0)return parsed;
}

/** Derive one DOM-built form by matching syntax. The issuer program is never executed. */
export function proveIssuerDomForm(source:string,data:Companion):{action:string;fields:Fields}|undefined{
 if(!localIdentifier.test(data.actionKey)||!localIdentifier.test(data.fieldsKey)||data.actionKey===data.fieldsKey||
    !data.fields.length||data.fields.length>2||
    data.fields.some(([key,value])=>(key!=='cres'&&key!=='threeDSSessionData')||typeof value!=='string')||
    !data.fields.some(([key,value])=>key==='cres'&&value.length>0)||
    new Set(data.fields.map(([key])=>key)).size!==data.fields.length)return;
 const file=boundedScript(source);
 if(!file||file.statements.length!==7)return;
 const [decode,create,method,action,loop,attach,submit]=file.statements;
 const envelope=initializedLocal(decode),form=initializedLocal(create);
 if(!envelope||!form||!loop||!ts.isForOfStatement(loop)||!ts.isBlock(loop.statement)||
    loop.statement.statements.length!==5)return;
 const [createInput,type,name,value,append]=loop.statement.statements;
 const input=initializedLocal(createInput),bindings=inputBindings(loop);
 if(!input||!bindings||new Set([envelope.name,form.name,input.name,...bindings]).size!==5)return;

 const block=callArguments(envelope.value,1,'JSON','parse')?.[0];
 const text=callArguments(block,1,'atob')?.[0];
 if(!text||!ts.isPropertyAccessExpression(text)||text.questionDotToken||text.name.getText()!=='textContent'||
    !plainString(callArguments(text.expression,1,'document','getElementById')?.[0],data.id))return;
 if(!plainString(callArguments(form.value,1,'document','createElement')?.[0],'form'))return;
 const post=assigned(method,form.name,'method');
 if((!plainString(post,'POST')&&!plainString(post,'post'))||
    !matchesPath(assigned(action,form.name,'action'),envelope.name,data.actionKey))return;

 if(!matchesPath(callArguments(loop.expression,1,'Object','entries')?.[0],envelope.name,data.fieldsKey)||
    !plainString(callArguments(input.value,1,'document','createElement')?.[0],'input')||
    !plainString(assigned(type,input.name,'type'),'hidden')||
    !matchesPath(assigned(name,input.name,'name'),bindings[0])||
    !matchesPath(assigned(value,input.name,'value'),bindings[1])||
    !matchesPath(callArguments(expression(append),1,form.name,'append')?.[0],input.name))return;
 if(!matchesPath(callArguments(expression(attach),1,'document','body','append')?.[0],form.name)||
    !callArguments(expression(submit),0,form.name,'submit'))return;
 return {action:data.action,fields:data.fields};
}
