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
 'an input excluded by a datalist ancestor': form('<datalist>'+result+'</datalist>'),
 'a duplicate CRes': form(result+result),
 'an extra successful control': form(result+'<input type="hidden" name="other" value="value">'),
 'an externally associated control': form(result,'id="notify"')+'<input form="notify" type="hidden" name="cres" value="YQ">',
 'a non-hidden input': form(result.replace('hidden','text')),
}))test('refuse '+name+' as CRes transport evidence',()=>{
 assert.equal(issuerResultForm(html),undefined);
});
