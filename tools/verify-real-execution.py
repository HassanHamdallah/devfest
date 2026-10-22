"""Runs only synthetic example programs through the configured real provider. Never prints credentials."""
from pathlib import Path
import json,time,uuid,urllib.request,urllib.error,secrets,datetime
ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:8081/api/v1'
def call(method,path,body=None,token=None,key=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as r: return json.load(r)
    except urllib.error.HTTPError as e: raise RuntimeError(f'HTTP {e.code}: {e.read().decode()[:200]}') from None

def config():
    return dict(line.split('=',1) for line in (ROOT/'backend/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
def login(email,password):return call('POST','/auth/login',{'email':email,'password':password})
def prepare():
    c=config();admin=login(c['DEVFEST_ADMIN_EMAIL'],c['DEVFEST_ADMIN_PASSWORD'])['accessToken']
    now=datetime.datetime.now(datetime.timezone.utc)
    call('PUT','/admin/contest',{'title':'DevFest Programming Contest','startAt':(now-datetime.timedelta(hours=1)).isoformat(),'endAt':(now+datetime.timedelta(hours=8)).isoformat()},admin)
    file=ROOT/'.local/verification-account.json'
    if file.exists(): account=json.loads(file.read_text())
    else:
        account={'email':'participant@devfest.local','name':'Development Participant','password':secrets.token_urlsafe(18)}
        call('POST','/admin/participants',account,admin);file.write_text(json.dumps(account,indent=2))
    return admin,login(account['email'],account['password'])['accessToken']
def wait(token,id):
    deadline=time.monotonic()+300
    while time.monotonic()<deadline:
        result=call('GET','/submissions/'+id,token=token)
        if result['state'] not in ('QUEUED','RUNNING'):return result
        time.sleep(2)
    raise RuntimeError('Evaluation did not finish within five minutes; check queue and provider.')
def main():
    admin,token=prepare();records=[]
    cases=[('custom input','runs',1,'PYTHON','print(sum(map(int,input().split())))','4 9\n','ACCEPTED')]
    for number,name in [(1,'sum'),(2,'balanced')]:
        for language,extension in [('PYTHON','py'),('CPP','cpp'),('JAVA','java')]:
            cases.append((name+' '+language,'submissions',number,language,(ROOT/f'infrastructure/reference-solutions/{name}.{extension}').read_text(),'','ACCEPTED'))
    cases += [('wrong answer','submissions',1,'PYTHON','print(123)','','WRONG_ANSWER'),('compile error','runs',1,'CPP','this is not valid c++','','COMPILATION_ERROR'),('runtime error','runs',1,'PYTHON','print(1/0)','','RUNTIME_ERROR'),('infinite loop','runs',1,'PYTHON','while True: pass','','TIME_LIMIT_EXCEEDED'),('excessive output','runs',1,'PYTHON','print("X"*1000000)','','RUNTIME_ERROR'),('excessive memory','runs',1,'PYTHON','x=bytearray(1024*1024*1024)','','MEMORY_LIMIT_EXCEEDED')]
    for name,kind,problem,language,source,stdin,expected in cases:
        start=time.monotonic();key=str(uuid.uuid4());body={'problemId':problem,'language':language,'source':source,'stdin':stdin}
        created=call('POST','/'+kind,body,token,key);duplicate=call('POST','/'+kind,body,token,key);assert created['id']==duplicate['id']
        result=wait(token,created['id']);record={'case':name,'expected':expected,'actual':result['state'],'passed':expected==result['state'],'seconds':round(time.monotonic()-start,2),'id':created['id'],'tests':result['total'],'stdoutMatches':result['stdout']=='13\n' if name=='custom input' else None}
        records.append(record);print(json.dumps(record),flush=True)
        (ROOT/'docs/real-execution-results.json').write_text(json.dumps({'provider':'configured Judge0; synthetic examples only','records':records},indent=2))
        time.sleep(3)
    # Real rejudge reuses the immutable original test version and keeps evaluation history.
    accepted=records[1]['id'];call('POST','/admin/submissions/'+accepted+'/rejudge',{},admin);result=wait(token,accepted)
    records.append({'case':'real rejudge','actual':result['state'],'passed':result['state']=='ACCEPTED' and len(call('GET','/submissions/'+accepted+'/history',token=token))==2})
    (ROOT/'docs/real-execution-results.json').write_text(json.dumps({'provider':'configured Judge0; synthetic examples only','records':records},indent=2))
    print('Real execution checks complete. See docs/real-execution-results.json. No mocks were used.',flush=True)
if __name__=='__main__':main()
