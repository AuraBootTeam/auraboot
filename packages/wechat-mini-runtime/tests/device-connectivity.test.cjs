const test=require('node:test');
const assert=require('node:assert/strict');
const runtime=require('../runtime');
const config=()=>({apiBaseUrl:'https://dev.bootnap.com',runtimeName:'procurement-main-validation',target:'device',deviceHealthVerified:false});
function fixture(platform='ios',result={status:'UP',runtime:'procurement-main-validation'}){
 const calls=[];return {calls,wx:{getDeviceInfo:()=>({platform}),request(o){calls.push(o);o.success({statusCode:200,data:result,header:{'X-Trace-Id':o.header.traceparent.split('-')[1]}});}}};
}
const verify=data=>data.status==='UP'&&data.runtime==='procurement-main-validation';
test('physical-device probe sends anonymous GET and returns evidence without changing the business guard',async()=>{
 const c=config(),f=fixture();const receipt=await runtime.probeDeviceConnectivity({wxApi:f.wx,config:c,path:'/srm/health',verify});
 assert.equal(f.calls.length,1);assert.equal(f.calls[0].method,'GET');assert.equal(f.calls[0].url,'https://dev.bootnap.com/srm/health');assert.equal(f.calls[0].header.Authorization,undefined);assert.equal(f.calls[0].data,undefined);
 assert.equal(receipt.platform,'ios');assert.equal(receipt.traceId,f.calls[0].header.traceparent.split('-')[1]);assert.equal(receipt.runtimeName,c.runtimeName);assert.equal(c.deviceHealthVerified,false);assert.throws(()=>runtime.target(c),{code:'CONFIGURATION_REQUIRED'});
});
test('desktop and devtools cannot create phone connectivity evidence',async()=>{
 for(const platform of ['devtools','mac','windows','ohos_pc','']){const f=fixture(platform);await assert.rejects(runtime.probeDeviceConnectivity({wxApi:f.wx,config:config(),path:'/srm/health',verify}),{code:'PHYSICAL_DEVICE_REQUIRED'});assert.equal(f.calls.length,0);}
});
test('Android and HarmonyOS phones use the same evidence guard',async()=>{
 for(const platform of ['android','ohos']){const f=fixture(platform);assert.equal((await runtime.probeDeviceConnectivity({wxApi:f.wx,config:config(),verify})).platform,platform);}
});
test('loopback, invalid probe path, and missing verifier never reach the network',async()=>{
 for(const extra of [{config:{...config(),apiBaseUrl:'http://127.0.0.1:6472'}},{path:'/../health'},{path:'//other.example'},{path:'/health?token=secret'},{verify:undefined}]){const f=fixture();await assert.rejects(runtime.probeDeviceConnectivity({wxApi:f.wx,config:config(),path:'/srm/health',verify,...extra}));assert.equal(f.calls.length,0);}
});
test('unhealthy, wrong runtime, missing trace, wrong trace and transport failure cannot admit business requests',async()=>{
 for(const kind of ['unhealthy','wrong-runtime','missing-trace','wrong-trace','transport']){
  const c=config(),f=fixture();f.wx.request=o=>{f.calls.push(o);if(kind==='transport')return o.fail({errMsg:'private raw failure'});o.success({statusCode:200,data:{status:kind==='unhealthy'?'DOWN':'UP',runtime:kind==='wrong-runtime'?'edu':'procurement-main-validation'},header:kind==='missing-trace'?{}:{'x-trace-id':kind==='wrong-trace'?'0'.repeat(32):o.header.traceparent.split('-')[1]}});};
  await assert.rejects(runtime.probeDeviceConnectivity({wxApi:f.wx,config:c,verify}),{code:'DEVICE_CONNECTIVITY_FAILED'});assert.equal(c.deviceHealthVerified,false);assert.equal(f.calls.length,1);
 }
});
