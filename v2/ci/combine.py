"""Require every matrix job and every declared suite from the same source commit."""
import json
from pathlib import Path
import sys
from gates import MANIFEST, require

root=Path(sys.argv[1]);sha=sys.argv[2]
specs=json.loads(MANIFEST.read_text())['suites']
seen={}
for job in ['sdk','server','security']:
    paths=list(root.glob('**/'+job+'/result.json'))
    require(len(paths)==1,'Missing/duplicate matrix job: '+job)
    result=json.loads(paths[0].read_text())
    require(result['job']==job and result['sourceCommit']==sha and result['passed'] is True,'Wrong source or failed job')
    expected={s['id']:s for s in specs if s['job']==job}
    require({s['id'] for s in result['suites']}==expected.keys(),'Missing or extra suite')
    for suite in result['suites']:
        require(suite['id'] not in seen,'Duplicate suite')
        spec=expected[suite['id']]
        require(suite['passed']>=spec['minimum'] and suite['failed']==0 and suite['skipped']==len(spec.get('allowedSkips',[])),'Failed/empty/skipped suite')
        seen[suite['id']]=suite
summary=dict(sourceCommit=sha,passed=True,suites=list(seen.values()))
(root/'required-suites-result.json').write_text(json.dumps(summary,indent=2)+'\n')
print('All required V2 jobs and suites passed; no unexpected skips.')
