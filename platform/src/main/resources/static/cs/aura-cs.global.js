var AuraCS=(function(e){Object.defineProperty(e,Symbol.toStringTag,{value:`Module`});var t=`aura-cs-visitor-token`,n=class{apiBase;siteKey;storage;session=null;constructor(e,t,n){this.apiBase=e,this.siteKey=t,this.storage=n}get current(){return this.session}async open(e){let n={},r=this.storage?.getItem(t);r&&(n.visitorToken=r),e&&(n.externalUserId=e.externalUserId,n.userHash=e.userHash);let i=await fetch(`${this.apiBase}/api/public/cs/session`,{method:`POST`,headers:{"Content-Type":`application/json`,"X-Site-Key":this.siteKey},body:JSON.stringify(n)});if(!i.ok)throw Error(await this.describeFailure(i));let a=await i.json();return this.storage?.setItem(t,a.visitorToken),this.session=a,a}async send(e,t){if(!this.session)throw Error(`session not open`);let n=await fetch(`${this.apiBase}/api/public/cs/message`,{method:`POST`,headers:{"Content-Type":`application/json`,Authorization:`Bearer ${this.session.token}`},body:JSON.stringify({conversationPid:this.session.conversationPid,message:e,clientMsgId:`cs-${Date.now()}-${Math.random().toString(36).slice(2,8)}`})});if(!n.ok||!n.body){t.onError(await this.describeFailure(n));return}let r=n.body.getReader(),i=new TextDecoder,a=``;for(;;){let{done:e,value:n}=await r.read();if(e)break;a+=i.decode(n,{stream:!0});let o=a.split(`

`);a=o.pop()??``;for(let e of o)this.dispatch(e,t)}}dispatch(e,t){let n=`message`,r=[];for(let t of e.split(`
`))t.startsWith(`event:`)?n=t.slice(6).trim():t.startsWith(`data:`)&&r.push(t.slice(5).trim());if(r.length===0)return;let i;try{i=JSON.parse(r.join(`
`))}catch{return}n===`chunk`&&typeof i.content==`string`?t.onChunk(i.content):n===`done`?t.onDone(typeof i.content==`string`?i.content:``):n===`error`&&t.onError(typeof i.error==`string`?i.error:`unknown error`)}async describeFailure(e){try{let t=await e.text(),n=/"(?:message|error|reason)"\s*:\s*"([^"]+)"/.exec(t);if(n)return n[1];if(t)return t.slice(0,200)}catch{}return`request failed (${e.status})`}},r=360,i=`#2563eb`,a=class{options;host;root;client;panel;messages;input;sendButton;open=!1;busy=!1;started=!1;constructor(e){this.options=e,this.client=new n(e.apiBase,e.siteKey,o()),this.host=document.createElement(`div`),this.host.setAttribute(`data-aura-cs`,`root`),this.root=this.host.attachShadow({mode:`open`}),document.body.appendChild(this.host),this.render(i)}render(e){this.root.innerHTML=`
      <style>
        :host { all: initial; }
        .launcher {
          position: fixed; right: 20px; bottom: 20px; z-index: 2147483000;
          width: 56px; height: 56px; border-radius: 50%; border: 0; cursor: pointer;
          background: ${e}; color: #fff; font-size: 24px; line-height: 1;
          box-shadow: 0 6px 20px rgba(0,0,0,.18);
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
        }
        .launcher:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
        .panel {
          position: fixed; right: 20px; bottom: 88px; z-index: 2147483000;
          width: ${r}px; max-width: calc(100vw - 40px);
          height: 480px; max-height: calc(100vh - 120px);
          display: none; flex-direction: column;
          background: #fff; color: #111827; border-radius: 12px; overflow: hidden;
          box-shadow: 0 12px 40px rgba(0,0,0,.22);
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif; font-size: 14px;
        }
        .panel[data-open="true"] { display: flex; }
        .header { padding: 12px 16px; background: ${e}; color: #fff; font-weight: 600; }
        .messages { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
        .msg { padding: 8px 12px; border-radius: 10px; max-width: 80%; white-space: pre-wrap; word-break: break-word; }
        .msg[data-from="visitor"] { align-self: flex-end; background: ${e}; color: #fff; }
        .msg[data-from="agent"] { align-self: flex-start; background: #f3f4f6; color: #111827; }
        .msg[data-from="system"] { align-self: center; background: #fef2f2; color: #991b1b; font-size: 12px; }
        .composer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #e5e7eb; }
        textarea {
          flex: 1; resize: none; height: 40px; padding: 8px 10px; font: inherit; color: inherit;
          border: 1px solid #d1d5db; border-radius: 8px; background: #fff;
        }
        textarea:focus-visible { outline: 2px solid ${e}; outline-offset: -1px; border-color: ${e}; }
        button.send {
          height: 40px; padding: 0 16px; border: 0; border-radius: 8px; cursor: pointer;
          background: ${e}; color: #fff; font: inherit; font-weight: 600;
        }
        button.send:disabled { opacity: .5; cursor: not-allowed; }
        button.send:focus-visible { outline: 2px solid ${e}; outline-offset: 2px; }
      </style>
      <button class="launcher" part="launcher" data-testid="cs-launcher" aria-label="Chat">&#128172;</button>
      <section class="panel" part="panel" data-testid="cs-panel" data-open="false" role="dialog" aria-label="Chat">
        <header class="header" data-testid="cs-header">Chat</header>
        <div class="messages" data-testid="cs-messages"></div>
        <div class="composer">
          <textarea data-testid="cs-input" placeholder="Type a message"></textarea>
          <button class="send" data-testid="cs-send">Send</button>
        </div>
      </section>
    `;let t=this.root.querySelector(`.launcher`);this.panel=this.root.querySelector(`.panel`),this.messages=this.root.querySelector(`.messages`),this.input=this.root.querySelector(`textarea`),this.sendButton=this.root.querySelector(`button.send`),t.addEventListener(`click`,()=>void this.toggle()),this.sendButton.addEventListener(`click`,()=>void this.submit()),this.input.addEventListener(`keydown`,e=>{e.key===`Enter`&&!e.shiftKey&&(e.preventDefault(),this.submit())})}async toggle(){this.open=!this.open,this.panel.setAttribute(`data-open`,String(this.open)),this.open&&!this.started&&(this.started=!0,await this.start())}async start(){try{let e=await this.client.open(this.options.identity);e.themeColor&&this.retheme(e.themeColor),e.welcomeMessage&&this.append(`agent`,e.welcomeMessage)}catch(e){this.append(`system`,e instanceof Error?e.message:`could not start chat`),this.started=!1}}retheme(e){let t=this.messages?[...this.messages.children].map(e=>({from:e.dataset.from??`agent`,text:e.textContent??``})):[];this.render(/^#[0-9a-fA-F]{3,8}$/.test(e)?e:i),this.panel.setAttribute(`data-open`,String(this.open));for(let e of t)this.append(e.from,e.text)}async submit(){let e=this.input.value.trim();if(!e||this.busy)return;this.input.value=``,this.append(`visitor`,e),this.setBusy(!0);let t=this.append(`agent`,``),n=``;await this.client.send(e,{onChunk:e=>{n+=e,t.textContent=n,this.scrollToEnd()},onDone:e=>{t.textContent=e||n,this.setBusy(!1),this.scrollToEnd()},onError:e=>{t.remove(),this.append(`system`,e),this.setBusy(!1)}})}setBusy(e){this.busy=e,this.sendButton.disabled=e}append(e,t){let n=document.createElement(`div`);return n.className=`msg`,n.dataset.from=e,n.setAttribute(`data-testid`,`cs-msg-${e}`),n.textContent=t,this.messages.appendChild(n),this.scrollToEnd(),n}scrollToEnd(){this.messages.scrollTop=this.messages.scrollHeight}};function o(){try{let e=`__aura_cs__`;return window.localStorage.setItem(e,`1`),window.localStorage.removeItem(e),window.localStorage}catch{return null}}var s=null;function c(e={}){let t=l(),n=e.siteKey??t?.getAttribute(`data-site-key`)??``,r=e.apiBase??t?.getAttribute(`data-api-base`)??u(t);return n?s||(s=new a({apiBase:r,siteKey:n,identity:e.identity}),s):(console.warn(`[AuraCS] no site key: add data-site-key to the script tag, or pass siteKey to init()`),null)}function l(){let e=document.currentScript;return e&&typeof e.getAttribute==`function`?e:document.querySelector(`script[data-site-key]`)}function u(e){if(e?.src)try{return new URL(e.src).origin}catch{}return window.location.origin}return typeof document<`u`&&l()?.hasAttribute(`data-site-key`)&&(document.readyState===`loading`?document.addEventListener(`DOMContentLoaded`,()=>c()):c()),e.init=c,e})({});
//# sourceMappingURL=aura-cs.global.js.map