import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Native Node executes the same form parser as Playwright.
import {issuerResultForm} from './stripe-billing-initial-3ds-result-form.ts';

const action='https://hooks.stripe.com/fixture/result?one=1&amp;two=2';
const result='<input type="hidden" name="cres" value="YQ==">';
const session='<input type="hidden" name="threeDSSessionData" value="a+b&#13;c">';
const form=(fields=result,attributes='')=>'<form method="POST" action="'+action+'" '+attributes+'>'+fields+'</form>';

test('derive the exact CRes action and ordered wire bytes from the issuer form',()=>{
 assert.deepEqual(issuerResultForm('<!doctype html>'+form(session+result,'target="_self"')),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=a%2Bb%0D%0Ac&cres=YQ%3D%3D',
 });
});

test('derive the exact wire body for a named current frame and an implicit empty session value',()=>{
 const emptySession='<input type="hidden" name="threeDSSessionData">';
 const html=form(emptySession+result,'target="stripe-challenge-frame"');
 assert.deepEqual(issuerResultForm(html,'stripe-challenge-frame'),{
  action:'https://hooks.stripe.com/fixture/result?one=1&two=2',
  body:'threeDSSessionData=&cres=YQ%3D%3D',
 });
 assert.equal(issuerResultForm(html),undefined);
 assert.equal(issuerResultForm(html,'Stripe-challenge-frame'),undefined);
});

for(const target of ['_blank','_parent','_top'])test('refuse '+target+' even if supplied as the current frame name',()=>{
 assert.equal(issuerResultForm(form(result,`target="${target}"`),target),undefined);
});

for(const [name,html] of Object.entries({
 'a form in a script': '<script>const html = '+JSON.stringify(form())+'</script>',
 'a form in a comment': '<!-- '+form()+' -->',
 'a form in a template': '<template>'+form()+'</template>',
 'multiple forms': form()+form(),
 'a foreign destination': form().replace('hooks.stripe.com','hooks.stripe.com.fixture.test'),
 'a relative destination': form().replace('https://hooks.stripe.com',''),
 'a form with duplicate action attributes': form(result,'action="https://fixture.test"'),
 'a different frame target': form(result,'target="_blank"'),
 'a document-wide frame target': '<base target="_blank">'+form(),
 'a disabled input': form(result.replace('type=','disabled type=')),
 'a form under a disabled fieldset': '<fieldset disabled>'+form()+'</fieldset>',
 'a form under a datalist': '<datalist>'+form()+'</datalist>',
 'a control under a datalist in the form': form('<datalist>'+result+'</datalist>'),
 'a missing CRes value': form(result.replace(' value="YQ=="','')),
 'a destination with an empty fragment': form().replace('two=2','two=2#'),
 'a duplicate CRes': form(result+result),
 'an extra successful control': form(result+'<input type="hidden" name="other" value="value">'),
 'an externally associated control': form(result,'id="notify"')+'<input form="notify" type="hidden" name="cres" value="YQ">',
 'a non-hidden input': form(result.replace('hidden','text')),
}))test('refuse '+name+' as CRes transport evidence',()=>{
 assert.equal(issuerResultForm(html),undefined);
});

for(const [reason,html] of Object.entries({
 DUPLICATE_ATTRIBUTE:form(result,'action="https://private.fixture.test"'),
 BASE:'<base target="_blank">'+form(),
 OWNER:form()+'<input form="private-fixture">',
 MISSING:'<script>document.write("private fixture")</script>',
 MULTIPLE:form()+form(),
 METHOD:form().replace('method="POST"','method="get"'),
 TARGET:form(result,'target="private-fixture"'),
 ENCODING:form(result,'enctype="multipart/form-data"'),
 ACTION:form().replace('hooks.stripe.com','private.fixture.test'),
 CONTROL:form(result.replace('type="hidden"','type="text"')),
 FIELD:form(result+'<input type="hidden" name="private-fixture" value="private fixture">'),
 DUPLICATE_FIELD:form(result+result),
 REQUIRED_CRES:form(session),
 SIZE:form(result.replace('YQ==','X'.repeat(32769))),
}))test('report the fixed CRes form rejection '+reason,()=>{
 const reported:string[]=[];
 assert.equal(issuerResultForm(html,undefined,code=>reported.push(code)),undefined);
 assert.deepEqual(reported,['BROWSER_CHALLENGE_CRES_FORM_'+reason]);
});
