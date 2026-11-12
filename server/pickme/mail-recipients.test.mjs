import {test} from 'node:test';
import assert from 'node:assert/strict';
import {loadMailRecipients} from './mail-recipients.mjs';
const contacts=[{name:'친구 1'}];
test('mail is disabled without private recipients',()=>assert.deepEqual(loadMailRecipients('',contacts),[]));
test('private recipients must match a contact and a usable address',()=>{
 assert.deepEqual(loadMailRecipients('[{"name":"친구 1","email":"friend@example.com"}]',contacts),[{name:'친구 1',email:'friend@example.com'}]);
 for(const value of ['{}','bad','[{"name":"unknown","email":"friend@example.com"}]','[{"name":"친구 1","email":"friend@example.invalid"}]','[{"name":"친구 1","email":"bad\\r\\nBcc:evil@example.com"}]']) assert.throws(()=>loadMailRecipients(value,contacts));
});
