import assert from 'node:assert/strict';
import test from 'node:test';
import {crc32,deflateSync} from 'node:zlib';
// @ts-ignore Native Node executes the same icon proof as the browser guard.
import {isLocalIssuerHeadIcon,issuerHeadIconHrefKind} from './stripe-billing-initial-3ds-head-icon.ts';

function pngChunk(type:string,bytes:Uint8Array){
 const chunk=Buffer.alloc(12+bytes.length);
 chunk.writeUInt32BE(bytes.length);
 chunk.write(type,4,'latin1');
 chunk.set(bytes,8);
 chunk.writeUInt32BE(crc32(chunk.subarray(4,8+bytes.length)),8+bytes.length);
 return chunk;
}

function tinyPng(width=1,compressed=deflateSync(Buffer.alloc(5)),dataTag='IDAT'){
 const header=Buffer.alloc(13);
 header.writeUInt32BE(width);
 header.writeUInt32BE(1,4);
 header[8]=8;
 header[9]=6;
 return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),pngChunk('IHDR',header),
  pngChunk(dataTag,compressed),pngChunk('IEND',Buffer.alloc(0))]);
}

function onePixelIco(png?:Buffer){
 const dib=Buffer.alloc(48);
 dib.writeUInt32LE(40);
 dib.writeInt32LE(1,4);
 dib.writeInt32LE(2,8);
 dib.writeUInt16LE(1,12);
 dib.writeUInt16LE(32,14);
 const content=png??dib;
 const header=Buffer.alloc(22);
 header.writeUInt16LE(1,2);
 header.writeUInt16LE(1,4);
 header[6]=1;
 header[7]=1;
 header.writeUInt16LE(1,10);
 header.writeUInt16LE(32,12);
 header.writeUInt32LE(content.length,14);
 header.writeUInt32LE(22,18);
 return Buffer.concat([header,content]);
}

const url=(bytes:Uint8Array,type:string)=>'data:'+type+';base64,'+Buffer.from(bytes).toString('base64');
const png='image/png',ico='image/x-icon',vndIco='image/vnd.microsoft.icon';

test('admit only the exact empty data forms and known favicon types',()=>{
 for(const href of ['data:,','data:;base64,']){
  assert.equal(issuerHeadIconHrefKind(href,png),'EMPTY_DATA');
  assert.equal(isLocalIssuerHeadIcon(href,ico),true);
 }
 for(const type of [png,ico,vndIco])for(const suffix of [',',';,',';base64,']){
  assert.equal(isLocalIssuerHeadIcon('data:'+type+suffix,type),true);
 }
 assert.equal(isLocalIssuerHeadIcon('data:'+ico+';,',vndIco),true);
 assert.equal(isLocalIssuerHeadIcon('data:'+png+',',ico),false);
 assert.equal(issuerHeadIconHrefKind('data:,',undefined),'UNSUPPORTED_TYPE');
 assert.equal(issuerHeadIconHrefKind('data:,','image/svg+xml'),'UNSUPPORTED_TYPE');
});

test('admit small canonical PNG and ICO data with either ICO media alias',()=>{
 assert.equal(issuerHeadIconHrefKind(url(tinyPng(),png),png),'INLINE_PNG');
 assert.equal(issuerHeadIconHrefKind(url(onePixelIco(),ico),vndIco),'INLINE_ICO');
 assert.equal(issuerHeadIconHrefKind(url(onePixelIco(tinyPng()),vndIco),ico),'INLINE_ICO');
 assert.equal(isLocalIssuerHeadIcon(url(onePixelIco(),vndIco),png),false);
 assert.equal(isLocalIssuerHeadIcon(url(onePixelIco(),png),png),false);
});

test('refuse fake, malformed, oversized or differently encoded raster data',()=>{
 const badCrc=tinyPng();
 badCrc[badCrc.length-1]=badCrc[badCrc.length-1]!^1;
 const wrongDimensions=onePixelIco(tinyPng());
 wrongDimensions[6]=2;
 const wrongType=onePixelIco();
 wrongType.writeUInt16LE(2,2);
 const embeddedActive=onePixelIco(Buffer.from('<svg/>'));
 for(const href of [
  url(Buffer.from('89504e470d0a1a0a','hex'),png),
  url(Buffer.concat([tinyPng(),Buffer.from('trailing')]),png),url(tinyPng(65),png),url(badCrc,png),
  url(tinyPng(1,Buffer.concat([deflateSync(Buffer.alloc(5)),Buffer.from('trailing')])),png),
  url(tinyPng(1,undefined,'ÉDAT'),png),
  url(embeddedActive,ico),url(wrongDimensions,ico),url(wrongType,ico),url(Buffer.alloc(65536),png),
  url(tinyPng(),png)+'=',url(tinyPng(),png).replace(/,(.{10})/,',$1\n'),
 ])assert.equal(issuerHeadIconHrefKind(href,href.startsWith('data:'+ico)?ico:png),'UNSUPPORTED_DATA',href.slice(0,35));
});

test('classify network and unsupported icon URLs without returning raw values',()=>{
 assert.equal(issuerHeadIconHrefKind(undefined,png),'ABSENT');
 assert.equal(issuerHeadIconHrefKind('',ico),'EMPTY');
 for(const href of ['/fixture.ico','./fixture.png','fixture.png','https://foreign.example/path/fixture.ico','//foreign.example/i.png']){
  assert.equal(issuerHeadIconHrefKind(href,png),'NETWORK_RASTER');
  assert.equal(isLocalIssuerHeadIcon(href,png),false);
 }
 for(const href of ['data:image/svg+xml,<svg/>','data:image/svg+xml,','data:text/html,','DATA:,','data:,x',
  'data:;base64,PHN2Zz4=','data:image/png,hello','data:image/png;charset=utf-8,']) {
  assert.equal(issuerHeadIconHrefKind(href,png),'UNSUPPORTED_DATA');
  assert.equal(isLocalIssuerHeadIcon(href,png),false);
 }
 for(const href of ['file:///private/fixture.ico','blob:https://issuer.example/fixture.png','https://issuer.example/test.ico?fixture=x',' data:,']) {
  assert.equal(issuerHeadIconHrefKind(href,png),'OTHER');
  assert.equal(isLocalIssuerHeadIcon(href,png),false);
 }
});
