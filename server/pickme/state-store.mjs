import {readFileSync,writeFileSync,mkdirSync,renameSync,chmodSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
// This file contains session credentials. Keep it on the private persistent disk only.
export function createStateStore(filename) {
 const file=resolve(filename); let state={};
 try {
  state=JSON.parse(readFileSync(file,'utf8'));
  if(!state || Array.isArray(state) || typeof state!=='object') throw new Error('Invalid state');
  chmodSync(file,0o600);
 } catch(error) {
  if(error.code!=='ENOENT') throw new Error('Cannot load private relay state; refusing to overwrite it');
 }
 const flush=()=>{
  mkdirSync(dirname(file),{recursive:true,mode:0o700});
  const temporary=file+'.tmp';
  writeFileSync(temporary,JSON.stringify(state),{mode:0o600});
  chmodSync(temporary,0o600);renameSync(temporary,file);
 };
 return {get:key=>structuredClone(state[key]),set(key,value){
   if(['__proto__','constructor','prototype'].includes(key)) throw new Error('Invalid state key');
   state[key]=structuredClone(value);flush();
 },flush};
}
