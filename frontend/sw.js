/* Upgrade old installations without caching personal data or creating web notifications. */
self.addEventListener('install',event=>event.waitUntil(self.skipWaiting()));
self.addEventListener('activate',event=>event.waitUntil((async()=>{
  const subscription=await self.registration.pushManager?.getSubscription();
  if(subscription) await subscription.unsubscribe();
  for(const notification of await self.registration.getNotifications()) notification.close();
  await self.clients.claim();
})()));
