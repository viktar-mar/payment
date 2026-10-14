// Harmless fixture-only preload: intercept before any private file can be opened.
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
const read=fs.promises.readFile;
fs.promises.readFile=async function(path,...options){
  const name=String(path);
  if(/(?:^|\/)\.env(?:$|[?#])/.test(name)||name.includes('private.env')){
    process.once('beforeExit',()=>{process.exitCode=77;});
    throw new Error('Synthetic forbidden configuration-read trap');
  }
  return read.call(this,path,...options);
};
syncBuiltinESMExports();
