"""Stable destinations for actionable workbench items, without credentials."""
def actionable_attention(state):
    servers={row['id']:row for row in state.get('feeds',{}).get('servers',{}).get('data',{}).get('items',[])}
    result={}
    for raw in state['attention']:
        item=dict(raw);kind=item['kind'];key=item['id']
        if kind in ('reminder','task','steps','network'):
            destination=dict(type=kind,id=key)
        elif kind=='server':
            row=servers.get(key,{})
            destination=dict(type='settings' if row.get('error') else 'server',id=row.get('source_id',key) if row.get('error') else key,section='servers')
        elif kind=='integration' and key.startswith('mail.'):
            destination=dict(type='settings',section='mail',id=key[5:])
        elif kind=='integration' and key.startswith('github.'):
            destination=dict(type='settings',section='github',id=key[7:])
        else:
            destination=dict(type='settings',section={'notifications':'devices','servers':'servers','mail':'mail','network':'network'}.get(key,'github'),id='')
        item['destination']=destination
        item['priority']=0 if kind=='reminder' else 1 if kind=='steps' else 2 if kind=='task' else 3
        identity=(destination['type'],destination.get('section',''),destination['id'])
        result.setdefault(identity,item)
    return sorted(result.values(),key=lambda x:x['priority'])
