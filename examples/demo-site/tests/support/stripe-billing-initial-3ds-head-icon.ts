import {crc32,inflateSync} from 'node:zlib';

export type IssuerHeadIconHrefKind='ABSENT'|'EMPTY'|'EMPTY_DATA'|'INLINE_PNG'|'INLINE_ICO'|
 'UNSUPPORTED_TYPE'|'UNSUPPORTED_DATA'|'NETWORK_RASTER'|'OTHER';

const maximumBytes=16384;
const maximumDimension=64;
const pngSignature=Buffer.from('89504e470d0a1a0a','hex');
const dataShape=/^data:(image\/(?:png|x-icon|vnd\.microsoft\.icon))?(;base64|;)?,([A-Za-z0-9+/]*={0,2})$/;
const rasterPath=/^\/?(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*\/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.(?:png|ico)$/;
type Raster='PNG'|'ICO';

function raster(type:string|undefined):Raster|undefined{
 if(type==='image/png')return 'PNG';
 if(type==='image/x-icon'||type==='image/vnd.microsoft.icon')return 'ICO';
}

function networkRaster(href:string):boolean{
 if(href.length>2048)return false;
 if(href.startsWith('https://')||href.startsWith('http://')||href.startsWith('//')){
  const absolute=href.startsWith('//')?'https:'+href:href;
  if(!URL.canParse(absolute))return false;
  const url=new URL(absolute);
  return url.href===absolute&&url.href===url.origin+url.pathname&&rasterPath.test(url.pathname);
 }
 return rasterPath.test(href.startsWith('./')?href.slice(2):href);
}

function dimension(value:number):boolean{return value>=1&&value<=maximumDimension;}

/** One small noninterlaced PNG with header, image data and end chunks only. */
function provedPng(bytes:Buffer,expectedWidth?:number,expectedHeight?:number):boolean{
 if(bytes.length<57||!bytes.subarray(0,8).equals(pngSignature)||bytes.readUInt32BE(8)!==13||
    bytes.toString('latin1',12,16)!=='IHDR')return false;
 const width=bytes.readUInt32BE(16),height=bytes.readUInt32BE(20),depth=bytes[24],color=bytes[25];
 if(!dimension(width)||!dimension(height)||(expectedWidth!==undefined&&expectedWidth!==width)||
    (expectedHeight!==undefined&&expectedHeight!==height)||bytes[26]!==0||bytes[27]!==0||bytes[28]!==0)return false;
 const channels=color===0?1:color===2?3:color===4?2:color===6?4:0;
 if(!channels||!(color===0?[1,2,4,8,16]:[8,16]).includes(depth!))return false;
 const rows=Math.ceil(width*depth!*channels/8)+1;
 const data:Buffer[]=[];
 for(let offset=8;offset+12<=bytes.length;){
  const length=bytes.readUInt32BE(offset),start=offset+8,end=start+length;
  if(end+4>bytes.length||crc32(bytes.subarray(offset+4,end))!==bytes.readUInt32BE(end))return false;
  const chunk=bytes.toString('latin1',offset+4,start);
  if(offset!==8){
   if(chunk==='IDAT'&&length>0)data.push(bytes.subarray(start,end));
   else if(chunk==='IEND'&&length===0&&end+4===bytes.length&&data.length>0){
    // The size bound and row markers rule out disguised or overlong payloads.
    try{
     const compressed=Buffer.concat(data);
     // Node returns this object for info:true. The types also cover the default buffer return.
     const {buffer:scanlines,engine}=inflateSync(compressed,{info:true,maxOutputLength:rows*height}) as unknown as
      {buffer:Buffer;engine:{bytesWritten:number}};
     if(scanlines.length!==rows*height||engine.bytesWritten!==compressed.length)return false;
     for(let position=0;position<scanlines.length;position+=rows)if(scanlines[position]!>4)return false;
     return true;
    }catch{return false;}
   }else return false;
  }
  offset=end+4;
 }
 return false;
}

/** One ICO entry containing a matching PNG or an uncompressed 32-bit DIB. */
function provedIco(bytes:Buffer):boolean{
 if(bytes.length<22||bytes.readUInt16LE(0)!==0||bytes.readUInt16LE(2)!==1||
    bytes.readUInt16LE(4)!==1||bytes[8]!==0||bytes[9]!==0||bytes.readUInt16LE(10)>1||
    ![0,32].includes(bytes.readUInt16LE(12))||bytes.readUInt32LE(14)!==bytes.length-22||
    bytes.readUInt32LE(18)!==22)return false;
 const width=bytes[6]!,height=bytes[7]!;
 if(!dimension(width)||!dimension(height))return false;
 const image=bytes.subarray(22);
 if(image.subarray(0,8).equals(pngSignature))return provedPng(image,width,height);
 const pixelBytes=width*height*4,maskBytes=Math.ceil(width/32)*4*height;
 return image.length===40+pixelBytes+maskBytes&&image.readUInt32LE(0)===40&&image.readInt32LE(4)===width&&
  image.readInt32LE(8)===height*2&&image.readUInt16LE(12)===1&&image.readUInt16LE(14)===32&&
  image.readUInt32LE(16)===0&&[0,pixelBytes,pixelBytes+maskBytes].includes(image.readUInt32LE(20))&&
  image.readUInt32LE(32)===0&&image.readUInt32LE(36)===0;
}

/** Return a closed description without retaining any caller-supplied URL or MIME value. */
export function issuerHeadIconHrefKind(href:string|undefined,type:string|undefined):IssuerHeadIconHrefKind{
 if(href===undefined)return 'ABSENT';
 if(href==='')return 'EMPTY';
 if(!/^data:/i.test(href))return networkRaster(href)?'NETWORK_RASTER':'OTHER';
 if(href.length>Math.ceil(maximumBytes/3)*4+64)return 'UNSUPPORTED_DATA';
 const data=dataShape.exec(href);
 if(!data||data[0]!==href)return 'UNSUPPORTED_DATA';
 const [,mime,encoding,payload]=data;
 const declared=raster(mime),hint=raster(type);
 if(!payload){
  if(encoding===';'&&!declared)return 'UNSUPPORTED_DATA';
  return hint&&(!declared||declared===hint)?'EMPTY_DATA':'UNSUPPORTED_TYPE';
 }
 if(!declared||encoding!==';base64')return 'UNSUPPORTED_DATA';
 const bytes=Buffer.from(payload,'base64');
 if(bytes.length>maximumBytes||bytes.toString('base64')!==payload||
    !(declared==='PNG'?provedPng(bytes):provedIco(bytes)))return 'UNSUPPORTED_DATA';
 if(declared!==hint)return 'UNSUPPORTED_TYPE';
 return declared==='PNG'?'INLINE_PNG':'INLINE_ICO';
}

export function isLocalIssuerHeadIcon(href:string,type:string):boolean{
 const kind=issuerHeadIconHrefKind(href,type);
 return kind==='EMPTY_DATA'||kind==='INLINE_PNG'||kind==='INLINE_ICO';
}
