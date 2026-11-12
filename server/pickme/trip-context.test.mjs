import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateTrip} from './trip-context.mjs';
const now=Date.now();
const trip={source:'pleos',capturedAt:now,origin:{latitude:37.5665,longitude:126.978},destination:{latitude:37.5445,longitude:127.0374,label:'서울숲'},heading:90};
test('Pleos snapshot requires fresh, finite coordinates and preserves destination',()=>{
 assert.equal(validateTrip(trip,now).destination.label,'서울숲');
 assert.equal(validateTrip(null,now),null);
 assert.throws(()=>validateTrip({...trip,capturedAt:now-301000},now));
 assert.throws(()=>validateTrip({...trip,origin:{latitude:NaN,longitude:127}},now));
 assert.throws(()=>validateTrip({...trip,origin:{latitude:0,longitude:0}},now));
 assert.throws(()=>validateTrip({...trip,source:'fabricated'},now));
});
