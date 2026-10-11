/** Each login panel owns one cancellable polling loop. */
export class QrLogin {
  constructor({request,onState,onLogin,schedule=(...args)=>setTimeout(...args),cancel=id=>clearTimeout(id),now=()=>Date.now()}) {
    Object.assign(this,{request,onState,onLogin,schedule,cancel,now});this.epoch=0;this.timer=null;
  }
  stop() {this.epoch++;if(this.timer!==null)this.cancel(this.timer);this.timer=null;}
  async start() {
    this.stop();const epoch=this.epoch;this.onState({status:'loading'});
    try {
      const code=await this.request('/api/qr-login',{method:'POST'});
      if(epoch!==this.epoch)return;
      this.onState({status:'waiting',image:code.image});
      const expires=this.now()+code.expires_in*1000;
      const poll=async()=>{
        if(epoch!==this.epoch)return;
        try {
          if(this.now()>=expires){this.onState({status:'expired'});return;}
          const result=await this.request('/api/qr-login/'+code.id);
          if(epoch!==this.epoch)return;
          if(result.status==='approved') {this.stop();await this.onLogin(result);return;}
          this.onState({status:result.status,image:code.image});
          if(['waiting','scanned'].includes(result.status))this.timer=this.schedule(poll,1500);
        } catch(error) {if(epoch===this.epoch)this.onState({status:'error',message:error.message});}
      };
      this.timer=this.schedule(poll,1500);
    } catch(error) {if(epoch===this.epoch)this.onState({status:'error',message:error.message});}
  }
}
