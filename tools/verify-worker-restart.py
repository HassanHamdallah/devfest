from pathlib import Path
import importlib.util,json,subprocess,time,uuid
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('verify',ROOT/'tools/verify-real-execution.py');api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
account=json.loads((ROOT/'.local/verification-account.json').read_text())
token=api.login(account['email'],account['password'])['accessToken']
created=api.call('POST','/submissions',{'problemId':1,'language':'PYTHON','source':'print(sum(map(int,input().split())))','stdin':''},token,str(uuid.uuid4()))
id=created['id'];deadline=time.monotonic()+20
while time.monotonic()<deadline:
    r=api.call('GET','/submissions/'+id,token=token)
    if r['state']=='RUNNING':break
    time.sleep(.1)
else:raise SystemExit('No running job observed; no process was stopped.')
print('Durable running submission observed; restarting only the identified DevFest backend.',flush=True)
subprocess.run(['pwsh','-NoProfile','-File',str(ROOT/'infrastructure/restart-verification-backend.ps1')],check=True)
deadline=time.monotonic()+120
while time.monotonic()<deadline:
    try:
        result=api.call('GET','/submissions/'+id,token=token)
        if result['state'] not in ['QUEUED','RUNNING']:break
    except Exception:pass
    time.sleep(2)
else:raise SystemExit('Recovery did not finish within 120 seconds.')
history=api.call('GET','/submissions/'+id+'/history',token=token)
report={'submissionId':id,'state':result['state'],'attempts':history[0]['attempts'],'generations':len(history),'passed':result['state']=='ACCEPTED' and history[0]['attempts']>=2 and len(history)==1,'realProvider':True}
(ROOT/'docs/worker-restart-results.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
if not report['passed']:raise SystemExit(1)
