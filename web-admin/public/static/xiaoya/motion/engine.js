/* Fengyun native vector-motion runtime v1.0.0 | MIT | Not a Lottie/Rive player.
 * Pure Canvas 2D; no DOM, Path2D, image, font or external dependency is required.
 * All clocks are supplied by callers. Import with require() in a mini program.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FengyunMotion = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const cache = new Map();
  const clamp = (v,a,b) => Math.max(a,Math.min(b,v));
  const mix = (a,b,p) => a+(b-a)*p;
  function commands(d) {
    if (Array.isArray(d)) return d;
    if (cache.has(d)) return cache.get(d);
    const tokens=d.match(/[MLCQZ]|[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?/g)||[];
    const out=[]; const counts={M:2,L:2,C:6,Q:4,Z:0}; let i=0;
    while(i<tokens.length){
      const op=tokens[i++];
      if(!Object.prototype.hasOwnProperty.call(counts,op)) throw new Error('Unsupported path command '+op);
      const arr=[op];for(let j=0;j<counts[op];j++){const n=Number(tokens[i++]);if(!Number.isFinite(n))throw new Error('Invalid path');arr.push(n);}
      out.push(arr);
    }
    if(cache.size>8000) cache.clear();cache.set(d,out);return out;
  }
  function trace(ctx,d) {
    ctx.beginPath();
    for(const c of commands(d)){
      if(c[0]==='M')ctx.moveTo(c[1],c[2]);
      else if(c[0]==='L')ctx.lineTo(c[1],c[2]);
      else if(c[0]==='C')ctx.bezierCurveTo(c[1],c[2],c[3],c[4],c[5],c[6]);
      else if(c[0]==='Q')ctx.quadraticCurveTo(c[1],c[2],c[3],c[4]);
      else ctx.closePath();
    }
  }
  function paint(ctx,v){
    if(typeof v==='string')return v;
    const g=ctx.createLinearGradient(v.x1,v.y1,v.x2,v.y2);
    for(const stop of v.stops)g.addColorStop(stop[0],stop[1]);return g;
  }
  function valueAt(keys,t){
    if(t<=keys[0][0])return keys[0][1];
    for(let i=1;i<keys.length;i++){
      const b=keys[i],a=keys[i-1];
      if(t<=b[0]){
        let p=(t-a[0])/(b[0]-a[0]||1);p=p*p*(3-2*p);return mix(a[1],b[1],p);
      }
    }return keys[keys.length-1][1];
  }
  function sample(pack,clipName,time){
    const name=clipName||pack.default_clip, clip=pack.clips[name];
    if(!clip)throw new Error('Clip not present: '+name);
    if(!Number.isFinite(time)||time<0)throw new Error('Time must be a finite nonnegative number');
    const t=clip.loop ? time%clip.duration : clamp(time,0,clip.duration), map={};
    for(const tr of clip.tracks){
      const o=map[tr.target]||(map[tr.target]={});o[tr.property]=valueAt(tr.keys,t);
    }
    return map;
  }
  function drawNode(ctx,n,overrides){
    const ov=overrides[n.id]||{};
    const alpha=ov.opacity!==undefined?ov.opacity:(n.opacity===undefined?1:n.opacity);
    if(alpha<=0)return;
    ctx.save();ctx.globalAlpha*=clamp(alpha,0,1);
    if(n.type==='group'){
      const t=n.transform||{x:0,y:0,r:0,sx:1,sy:1};
      ctx.translate(t.x+(ov.dx||0),t.y+(ov.dy||0));ctx.rotate(t.r*Math.PI/180);ctx.scale(t.sx,t.sy);
      const p=n.pivot||[0,0];
      if(ov.rotation!==undefined||ov.scaleX!==undefined||ov.scaleY!==undefined){
        ctx.translate(p[0],p[1]);ctx.rotate((ov.rotation||0)*Math.PI/180);
        ctx.scale(ov.scaleX===undefined?1:ov.scaleX,ov.scaleY===undefined?1:ov.scaleY);ctx.translate(-p[0],-p[1]);
      }
      if(n.clip){trace(ctx,n.clip);ctx.clip();}
      for(const child of n.children)drawNode(ctx,child,overrides);
    }else if(n.type==='path'){
      trace(ctx,n.commands||n.d);
      if(n.fill&&n.fill!=='none'){ctx.fillStyle=paint(ctx,n.fill);ctx.fill();}
      if(n.stroke){ctx.strokeStyle=paint(ctx,n.stroke);ctx.lineWidth=n.sw||1;ctx.lineCap='round';ctx.lineJoin='round';ctx.stroke();}
    }else{ctx.restore();throw new Error('Unknown node type');}
    ctx.restore();
  }
  function draw(ctx,scene,options){
    const o=options||{}, w=o.width||scene.width,h=o.height||scene.height;
    const scale=Math.min(w/scene.width,h/scene.height);
    ctx.save();ctx.translate((o.x||0)+(w-scene.width*scale)/2,(o.y||0)+(h-scene.height*scale)/2);ctx.scale(scale,scale);
    if(o.opacity!==undefined)ctx.globalAlpha*=o.opacity;
    for(const n of scene.nodes)drawNode(ctx,n,o.overrides||{});
    ctx.restore();
  }
  function render(ctx,pack,clipName,time,options){
    const o=options||{},w=o.width||(ctx.canvas&&ctx.canvas.width)||512,h=o.height||(ctx.canvas&&ctx.canvas.height)||512;
    ctx.save();ctx.setTransform(1,0,0,1,0,0);
    if(o.clear!==false)ctx.clearRect(0,0,w,h);
    if(o.background){ctx.fillStyle=o.background;ctx.fillRect(0,0,w,h);}
    draw(ctx,pack.scene,{width:w,height:h,overrides:sample(pack,clipName,time||0)});ctx.restore();
  }
  function eventsBetween(pack,name,a,b){
    const clip=pack.clips[name];if(!clip)throw new Error('Clip not present');
    // One-shot clips only; events are presentation notifications, never score commands.
    if(clip.loop||b<a)return [];
    return (clip.events||[]).filter(e=>e.time>a&&e.time<=Math.min(b,clip.duration));
  }
  function blendColor(a,b,p){
    if(typeof a!=='string'||typeof b!=='string'||!/^#[0-9a-f]{6}$/i.test(a)||!/^#[0-9a-f]{6}$/i.test(b))return p<.5?a:b;
    let s='#';for(let i=1;i<7;i+=2)s+=Math.round(mix(parseInt(a.slice(i,i+2),16),parseInt(b.slice(i,i+2),16),p)).toString(16).padStart(2,'0');return s;
  }
  function blendPaint(a,b,p){
    if(!a||!b)return p<.5?a:b;
    if(typeof a==='string'&&typeof b==='string')return blendColor(a,b,p);
    if(typeof a==='object'&&typeof b==='object'&&a.stops.length===b.stops.length){
      const c={type:'linear',stops:[]};for(const k of ['x1','y1','x2','y2'])c[k]=mix(a[k],b[k],p);
      for(let i=0;i<a.stops.length;i++)c.stops.push([mix(a.stops[i][0],b.stops[i][0],p),blendColor(a.stops[i][1],b.stops[i][1],p)]);return c;
    }return p<.5?a:b;
  }
  function compatible(a,b){return a.length===b.length&&a.every((c,i)=>c[0]===b[i][0]&&c.length===b[i].length);}
  function keyedChildren(nodes){return nodes.map((n,i)=>({key:n.id||'@'+i,node:n}));}
  function prepChildren(a,b){
    const as=keyedChildren(a),bs=keyedChildren(b),map=new Map(bs.map(v=>[v.key,v.node])),seen=new Set();
    const pairs=as.map(v=>{seen.add(v.key);return prep(v.node,map.get(v.key));});
    for(const v of bs)if(!seen.has(v.key))pairs.push(prep(null,v.node));return pairs;
  }
  function prep(a,b){
    if(!a||!b)return {a,b,mode:'appear'};
    if(a.type!==b.type)return {a,b,mode:'cross'};
    if(a.type==='group')return {a,b,mode:'group',children:prepChildren(a.children,b.children)};
    const ac=commands(a.d),bc=commands(b.d);
    return {a,b,mode:compatible(ac,bc)?'path':'cross',ac,bc};
  }
  function fade(n,alpha){
    return {type:'group',transform:{x:0,y:0,r:0,sx:1,sy:1},opacity:alpha,children:[n]};
  }
  function blendNode(pr,p){
    const a=pr.a,b=pr.b;
    if(pr.mode==='appear')return fade(a||b,a?1-p:clamp((p-.08)/.92,0,1));
    if(pr.mode==='cross')return {type:'group',transform:{x:0,y:0,r:0,sx:1,sy:1},children:[fade(a,1-p),fade(b,p)]};
    const n=Object.assign({},p<.5?a:b);
    n.opacity=mix(a.opacity===undefined?1:a.opacity,b.opacity===undefined?1:b.opacity,p);
    if(pr.mode==='group'){
      n.transform={};for(const k of ['x','y','r','sx','sy'])n.transform[k]=mix(a.transform[k],b.transform[k],p);
      n.children=pr.children.map(pr=>blendNode(pr,p));
      if(a.clip&&b.clip){const ac=commands(a.clip),bc=commands(b.clip);if(compatible(ac,bc))n.clip=ac.map((c,i)=>c.map((v,j)=>j?mix(v,bc[i][j],p):v));}
    }else{
      n.commands=pr.ac.map((c,i)=>c.map((v,j)=>j?mix(v,pr.bc[i][j],p):v));n.fill=blendPaint(a.fill,b.fill,p);n.stroke=blendPaint(a.stroke,b.stroke,p);n.sw=mix(a.sw||0,b.sw||0,p);
    }
    return n;
  }
  function prepareGrowth(fromScene,toScene){
    if(fromScene.width!==toScene.width||fromScene.height!==toScene.height)throw new Error('Canvas mismatch');
    const pairs=prepChildren(fromScene.nodes,toScene.nodes);
    return function(progress){
      const p=clamp(progress,0,1);
      if(p===0)return fromScene;if(p===1)return toScene;
      const eased=p*p*(3-2*p);
      return {width:fromScene.width,height:fromScene.height,nodes:pairs.map(pr=>blendNode(pr,eased))};
    };
  }
  function createPlayer(canvas,pack,options){
    const o=options||{},ctx=canvas.getContext('2d');
    if(!ctx)throw new Error('Canvas 2D is not available');
    const request=o.requestFrame||((cb)=>canvas.requestAnimationFrame(cb));
    const cancel=o.cancelFrame||((id)=>canvas.cancelAnimationFrame(id));
    let current=pack,clip=pack.default_clip,playing=false,disposed=false,id=null,last=null,t=0;
    function drawNow(){render(ctx,current,clip,t,{background:o.background});}
    function stopLoop(){playing=false;if(id!==null)cancel(id);id=null;last=null;}
    function frame(now){
      if(!playing||disposed)return;
      if(last!==null){const prev=t;t+=Math.max(0,Math.min((now-last)/1000,.1));if(o.onEvent)for(const e of eventsBetween(current,clip,prev,t))o.onEvent(e);}
      last=now;
      try{drawNow();}catch(e){stopLoop();if(o.onError)o.onError(e);return;}
      if(!current.clips[clip].loop&&t>=current.clips[clip].duration){
        stopLoop();if(o.onComplete)o.onComplete(clip);return;
      }id=request(frame);
    }
    function check(){if(disposed)throw new Error('Player destroyed');}
    const api={
      play(name){check();if(name&&!current.clips[name])throw new Error('Unknown clip '+name);stopLoop();clip=name||current.default_clip;t=0;playing=true;drawNow();id=request(frame);return api;},
      pause(){check();stopLoop();return api;},
      resume(){check();if(!playing){playing=true;last=null;id=request(frame);}return api;},
      seek(time){check();if(!Number.isFinite(time)||time<0)throw new Error('Invalid seek');t=time;last=null;drawNow();return api;},
      setPackage(next){check();stopLoop();current=next;clip=next.default_clip;t=0;drawNow();return api;},
      destroy(){stopLoop();disposed=true;},
      getState(){return {clip,time:t,playing,destroyed:disposed};}
    };
    drawNow();return api;
  }
  return {version:'1.0.0',commands,sample,draw,render,prepareGrowth,eventsBetween,createPlayer};
});
