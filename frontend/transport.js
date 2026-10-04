export class DeskClient {
  constructor(request,onExpired) { this.request=request; this.onExpired=onExpired; this.listeners=new Set(); this.errors=new Set(); this.retry=500; this.darkMode=false; }
  command(action,payload={}) { return this.request('/api/command',{method:'POST',body:{action,payload}}); }
  subscribe(callback,error) {
    this.listeners.add(callback); this.errors.add(error);
    if(this.state) callback(this.state);
    if(!this.socket) this.open();
    return Promise.resolve(()=>{
      this.listeners.delete(callback); this.errors.delete(error);
      if(!this.listeners.size) this.close();
    });
  }
  open() {
    if(!this.listeners.size) return;
    const url=new URL('/api/ws',location.href); url.protocol=location.protocol==='https:'?'wss:':'ws:';
    const socket=new WebSocket(url); this.socket=socket;
    socket.onmessage=event=>{
      try {const message=JSON.parse(event.data); if(message.type==='snapshot') {this.retry=500;this.state=message.state;for(const callback of this.listeners) callback(this.state);}}
      catch {for(const error of this.errors) error('状态同步失败，正在重新连接'); socket.close();}
    };
    socket.onclose=event=>{
      if(this.socket!==socket) return;
      this.socket=null;
      if(event.code===4401) {this.onExpired();return;}
      for(const error of this.errors) error('连接中断，正在重新连接');
      this.scheduleRetry();
    };
  }
  scheduleRetry() {
    if(!this.listeners.size) return;
    this.timer=setTimeout(async()=>{
      try {await this.request('/api/session');this.open();}
      catch {this.scheduleRetry();}
    },this.retry);
    this.retry=Math.min(this.retry*2,15000);
  }
  close() {clearTimeout(this.timer);const socket=this.socket;this.socket=null;socket?.close();this.state=null;}
}
