// Synthetic values approximate the shape of the closed issuer witness.
// The retained witness carries lexical cues only, so the schema and recipe
// here are a local fixture, with no issuer source or transaction values.
export function syntheticIssuerDomForm(action:string,fields:Record<string,string>){
 const id='fixture-dom-data';
 const json=JSON.stringify({destination:action,controls:fields})
  .replace(/[^\x00-\x7f]/g,character=>'\\u'+character.charCodeAt(0).toString(16).padStart(4,'0'));
 const folded=Buffer.from(json,'ascii').toString('base64').match(/.{1,37}/g)!.join('\n');
 const script=`
const envelope = JSON.parse(atob(document.getElementById('${id}').textContent));
const form = document.createElement('form');
form.method = 'POST';
form.action = envelope.destination;
for (const [name, value] of Object.entries(envelope.controls)) {
 const input = document.createElement('input');
 input.type = 'hidden';
 input.name = name;
 input.value = value;
 form.append(input);
}
document.body.append(form);
form.submit();
`;
 return {id,json,folded,script};
}
