"""DevFest-only load harness. Modes isolate browsing, autosave, polling and execution.
Never point this at TeamQora. Bursts require --allow-execution-burst and a vetted private Judge0.
"""
from pathlib import Path
import argparse,concurrent.futures,json,time,uuid,statistics,threading,sys,secrets,datetime
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
from importlib.util import spec_from_file_location,module_from_spec
spec=spec_from_file_location('verify',ROOT/'tools/verify-real-execution.py');api=module_from_spec(spec);spec.loader.exec_module(api)
parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['browse','draft','poll','run','submit']);parser.add_argument('--users',type=int,default=80);parser.add_argument('--seconds',type=int,default=30);parser.add_argument('--allow-execution-burst',action='store_true');args=parser.parse_args()
if not 1<=args.users<=80:raise SystemExit('Use 1–80 users.')
if args.mode in ['run','submit']:
    if not args.allow_execution_burst:raise SystemExit('Execution bursts require --allow-execution-burst and a vetted execution service.')
    if 'ce.judge0.com' in api.config().get('JUDGE0_URL',''):raise SystemExit('Refusing to load-test the shared public Judge0 endpoint. Configure dedicated vetted capacity.')
c=api.config();admin=api.login(c['DEVFEST_ADMIN_EMAIL'],c['DEVFEST_ADMIN_PASSWORD'])['accessToken']
file=ROOT/'.local/load-accounts.json'
accounts=json.loads(file.read_text()) if file.exists() else []
for i in range(len(accounts),args.users):
    a={'email':f'load-{i+1:03d}@devfest.local','name':f'Load participant {i+1:03d}','password':secrets.token_urlsafe(18)}
    api.call('POST','/admin/participants',a,admin);accounts.append(a);file.write_text(json.dumps(accounts,indent=2))
# Token preparation is deliberately outside latency measurements; throttle authentication.
cachefile=ROOT/'.local/load-tokens.json'
cache=json.loads(cachefile.read_text()) if cachefile.exists() else {}
if time.time()-cache.get('created',0)<600 and len(cache.get('tokens',[]))>=args.users:
    tokens=cache['tokens'][:args.users]
else:
    tokens=[]
    for i,a in enumerate(accounts[:args.users]):
        try:tokens.append(api.login(a['email'],a['password'])['accessToken'])
        except RuntimeError as e:
            if 'HTTP 429' not in str(e):raise
            print('Preparing sessions within login rate limit...',flush=True)
            time.sleep(61);tokens.append(api.login(a['email'],a['password'])['accessToken'])
        time.sleep(.1)
    cachefile.write_text(json.dumps({'created':time.time(),'tokens':tokens}))
barrier=threading.Barrier(args.users);latencies=[];errors=[];ids=[];timings=[];lock=threading.Lock()
start=time.monotonic()
def measured(method,path,body=None,token=None,key=None):
    begin=time.monotonic()
    try:return api.call(method,path,body,token,key)
    finally:
        with lock:latencies.append((time.monotonic()-begin)*1000)
def user(index):
    token=tokens[index];barrier.wait();end=time.monotonic()+args.seconds
    try:
        if args.mode in ['run','submit']:
            key=str(uuid.uuid4());body={'problemId':1,'language':'PYTHON','source':'print(sum(map(int,input().split())))','stdin':'2 3\n' if args.mode=='run' else ''}
            created=measured('POST','/runs' if args.mode=='run' else '/submissions',body,token,key);id=created['id']
            with lock:ids.append(id)
            deadline=time.monotonic()+1800
            while time.monotonic()<deadline:
                result=measured('GET','/submissions/'+id,token=token)
                if result['state'] not in ['QUEUED','RUNNING']:
                    history=api.call('GET','/submissions/'+id+'/history',token=token)[0]
                    parse=lambda x:datetime.datetime.fromisoformat(x.replace('Z','+00:00'))
                    wait=(parse(history['startedAt'])-parse(result['receivedAt'])).total_seconds() if history['startedAt'] else None
                    execution=(parse(history['finishedAt'])-parse(history['startedAt'])).total_seconds() if history['startedAt'] else None
                    with lock:timings.append({'id':id,'state':result['state'],'queueSeconds':wait,'evaluationSeconds':execution,'tests':result['total']})
                    return
                time.sleep(2)
            raise RuntimeError('Queue did not drain within 30 minutes')
        while time.monotonic()<end:
            if args.mode=='browse':
                for path in ['/contest','/problems','/scoreboard']:measured('GET',path,token=token)
                time.sleep(2)
            elif args.mode=='draft':
                d=measured('GET','/drafts/1/PYTHON',token=token);measured('PUT','/drafts/1/PYTHON',{'source':f'# load user {index} at {time.time()}\n','version':d['version']},token);time.sleep(3)
            else:
                rows=measured('GET','/submissions',token=token)
                if rows:measured('GET','/submissions/'+rows[0]['id'],token=token)
                time.sleep(2)
    except Exception as e:
        with lock:errors.append({'user':index,'error':str(e)[:250]})
with concurrent.futures.ThreadPoolExecutor(max_workers=args.users) as pool:list(pool.map(user,range(args.users)))
elapsed=time.monotonic()-start
ordered=sorted(latencies);percentile=lambda n:ordered[min(len(ordered)-1,int(len(ordered)*n))] if ordered else None
# Every acknowledged execution ID must still be readable. Counts are independent of verdict success.
preserved=0
for id in ids:
    try:api.call('GET','/submissions/'+id,token=admin);preserved+=1
    except Exception:pass
report={'mode':args.mode,'users':args.users,'seconds':round(elapsed,2),'requests':len(latencies),'apiP50Ms':percentile(.5),'apiP95Ms':percentile(.95),'apiP99Ms':percentile(.99),'errors':errors,'acknowledgedSubmissions':len(ids),'preservedSubmissions':preserved,'completedEvaluations':len(timings),'throughputEvaluationsPerSecond':len(timings)/elapsed,'evaluationTimings':timings,'pass':not errors and all(t['state']=='ACCEPTED' for t in timings) and (not ids or preserved==len(ids)==len(timings)) and bool(ordered) and percentile(.95)<1000}
(ROOT/f'docs/load-{args.mode}.json').write_text(json.dumps(report,indent=2));print(json.dumps({k:v for k,v in report.items() if k not in ['evaluationTimings']},indent=2))
