import assert from 'node:assert/strict';
import test from 'node:test';
import {crc32,deflateSync} from 'node:zlib';
// @ts-ignore Native Node executes the same icon proof as the browser guard.
import {describeIssuerHeadIconHref,isLocalIssuerHeadIcon,issuerHeadIconHrefKind} from './stripe-billing-initial-3ds-head-icon.ts';

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

test('ASCII case in the data URL header does not change the raster proof',()=>{
 const payload=tinyPng().toString('base64');
 for(const prefix of ['DATA:image/png;base64,','data:IMAGE/PNG;base64,','data:image/png;BaSe64,','DaTa:ImAgE/PnG;BASE64,']){
  assert.equal(issuerHeadIconHrefKind(prefix+payload,png),'INLINE_PNG');
  assert.equal(isLocalIssuerHeadIcon(prefix+payload,ico),false);
  assert.equal(isLocalIssuerHeadIcon(prefix+payload+'=',png),false);
 }
 assert.equal(issuerHeadIconHrefKind('DATA:,',png),'EMPTY_DATA');
 assert.equal(isLocalIssuerHeadIcon('DATA:IMAGE/SVG+XML;BASE64,PHN2Zz4=',png),false);
 assert.equal(isLocalIssuerHeadIcon('DATA:IMAGE/PNG;charset=utf-8;base64,'+payload,png),false);
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
 for(const href of ['data:image/svg+xml,<svg/>','data:image/svg+xml,','data:text/html,','data:,x',
  'data:;base64,PHN2Zz4=','data:image/png,hello','data:image/png;charset=utf-8,']) {
  assert.equal(issuerHeadIconHrefKind(href,png),'UNSUPPORTED_DATA');
  assert.equal(isLocalIssuerHeadIcon(href,png),false);
 }
 for(const href of ['file:///private/fixture.ico','blob:https://issuer.example/fixture.png','https://issuer.example/test.ico?fixture=x',' data:,']) {
  assert.equal(issuerHeadIconHrefKind(href,png),'OTHER');
  assert.equal(isLocalIssuerHeadIcon(href,png),false);
 }
});

test('name the first failed data URL and raster check without retaining input',()=>{
 const badCrc=tinyPng();
 badCrc[badCrc.length-1]=badCrc[badCrc.length-1]!^1;
 const badPixelFormat=tinyPng().subarray(16,29);
 badPixelFormat[9]=3;
 const palettedPng=Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),pngChunk('IHDR',badPixelFormat),
  tinyPng().subarray(33)]);
 const oversizedIco=onePixelIco();
 oversizedIco[6]=65;
 const inconsistentIco=onePixelIco();
 inconsistentIco.writeInt32LE(8,22+4);
 for(const [href,type,dataRejection] of [
  ['data:'+'A'.repeat(23000),png,'URL_LIMIT'],
  ['data:image/png;base64',png,'MISSING_COMMA'],
  ['data:image/png\n;base64,',png,'HEADER_SYNTAX'],
  ['data:image/svg+xml,',png,'MEDIA_TYPE'],
  ['data:;,',png,'PARAMETERS'],
  ['data:image/png;charset=utf-8;base64,',png,'PARAMETERS'],
  ['data:;base64,TQ==',png,'MEDIA_TYPE_REQUIRED'],
  ['data:image/png;,TQ==',png,'ENCODING'],
  ['data:image/png;base64,TQ%3D%3D',png,'BASE64_SYNTAX'],
  ['data:image/png;base64,TR==',png,'BASE64_CANONICAL'],
  [url(Buffer.alloc(16385),png),png,'DECODED_LIMIT'],
  ['data:image/png;base64,TQ==',png,'PNG_HEADER'],
  [url(tinyPng(65),png),png,'PNG_DIMENSIONS'],
  [url(palettedPng,png),png,'PNG_PIXEL_FORMAT'],
  [url(tinyPng().subarray(0,-1),png),png,'PNG_CHUNK_BOUNDARY'],
  [url(badCrc,png),png,'PNG_CHUNK_CRC'],
  [url(tinyPng(1,undefined,'sRGB'),png),png,'PNG_CHUNK_TYPE'],
  [url(Buffer.concat([tinyPng(),Buffer.from('trailing')]),png),png,'PNG_CHUNK_ORDER'],
  [url(tinyPng(1,deflateSync(Buffer.alloc(7))),png),png,'PNG_IMAGE_DATA'],
  ['data:image/x-icon;base64,TQ==',ico,'ICO_DIRECTORY'],
  [url(oversizedIco,ico),ico,'ICO_DIMENSIONS'],
  [url(inconsistentIco,ico),ico,'ICO_DIB'],
 ] as const){
  assert.deepEqual(describeIssuerHeadIconHref(href,type),{kind:'UNSUPPORTED_DATA',dataRejection});
  assert.equal(isLocalIssuerHeadIcon(href,type),false);
 }
 assert.deepEqual(describeIssuerHeadIconHref(url(tinyPng(),png),ico),{kind:'UNSUPPORTED_TYPE'});
});
