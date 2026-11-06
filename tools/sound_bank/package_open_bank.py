#!/usr/bin/env python3
"""Prepare immutable GitHub prerelease assets; never uploads anything.

Usage: package_open_bank.py --bank listening/open-bank --out listening/open-bank/release-ready
Individual files are split across releases (<900 assets), with optional archives
of 100 clips for bulk download. review/excluded records are never packaged.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import zipfile

import prepare_bigsoundbank as bank

CURATED={
 'door_open':['1702','2428','2429','2430','0305','1159','1160'],
 'door_close':['2431','2432','2433','1704'],
 'door_slam':['0103','0223','1339','1501'],
 'door_knock':['0095','0374'], 'door_creak':['0305','0539','0616','1159','1160'],
 'floor_creak':['0518']+[str(i) for i in range(3051,3061)],
 'phone':['0250','0251','0252','0253','0063','0375'],
 'phone_dial':['0002','2631'], 'phone_hangup':['3353','3354','3355','3356','3368','3371'],
 'phone_vibrate':['0022','0023','1033','1034','1035','1036'],
 'car_start':['0189','0872'], 'car_engine':['0290','0291','0966'],
 'car_horn':['0258','0259'],
 'cry_baby':['0233','0873','0876','0877','0881','0883','0962','0963'],
 'baby':['0233','0873','0881','0883','0962','0963'],
 'cry_child':[str(i) for i in range(2548,2553)],
 'cough':['2187','2183','2184','2185','2186'],
 'breath':['2195','1103'], 'breath_heavy':['1103'], 'panting':['1103'],
 'panting_woman':['1345'], 'breathing_calm':['2195'],
 'nose_blow':['1096','3308','3309'],
 'burp':['1707','1708','1709','1710','2364','3250','3251','3252','3253','3254'],
 'book_page':['0362','2212'],
}
# There is no adult sobbing or grinding-teeth recording in the source catalog.
BLOCK={'cry','cry_man','cry_woman','crying_woman_far','wail_grief','whimper_woman',
       'teeth_grind','bed_creak','leather_creak','corset_creak','ship_creak','dock_creak'}
GROUPS={
 'forest':'forest woodland woods', 'river':'river stream brook creek brooklet',
 'sea':'sea ocean beach shore waves', 'street':'street urban city crossroads',
 'road':'road highway motorway', 'market':'market marketplace bazaar',
 'restaurant':'restaurant cafe cafeteria pub bar tavern', 'church':'church cathedral chapel',
 'station':'station railway platform', 'train':'train subway metro', 'airport':'airport airfield',
 'harbor':'harbor harbour port dock', 'office':'office', 'apartment':'apartment flat',
 'kitchen':'kitchen', 'pool':'pool swimming', 'farm':'farm barn cows countryside rural field',
 'park':'park garden', 'cave':'cave cavern', 'rain':'rain rainfall raining',
 'wind':'wind windy', 'birds':'bird birds birdsong', 'crowd':'crowd crowds people chatter',
}

def tokens(s):return set(re.findall('[a-z]+',s.lower()))

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--bank',type=Path,required=True);ap.add_argument('--out',type=Path,required=True)
 args=ap.parse_args();root=args.bank.resolve();out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
 records=[r for r in bank.records(root) if r['status']=='done' and r['bucket'] in ['events','scenes']]
 byid={r['id']:r for r in records}
 report=json.loads((root/'coverage-report.json').read_text())['matches']
 aliases=collections.defaultdict(list)
 for row in report:
  if row['key'] in BLOCK or row['key'] in CURATED:continue
  if row['status']!='direct_candidate':continue
  for c in row['candidates']:
   if c['match']=='direct_candidate' and c['score']>=.60 and c['id'] in byid and byid[c['id']]['bucket']=='events':
    aliases[c['id']].append(row['key'])
 for kind,ids in CURATED.items():
  for sid in ids:
   if sid in byid and byid[sid]['bucket']=='events':aliases[sid].append(kind)
 base='https://github.com/davnozdu/supertonic-android/releases/download'
 plan=[];summary={}
 credits=(root/'CREDITS.md').read_text()
 for channel in ['events','scenes']:
  chosen=sorted([r for r in records if r['bucket']==channel],key=lambda r:int(r['id']))
  tag=f'open-sounds-{channel}-v1';folder=out/tag;folder.mkdir(exist_ok=True)
  public=[];part_assets=collections.defaultdict(list)
  for i,r in enumerate(chosen):
   source=root/r['file'];assert source.stat().st_size==r['bytes'] and bank.digest(source)==r['sha256'],source
   assert r.get('normalization',{}).get('version')=='project-ref-events22-scenes26-peak3-v2'
   part=f'{tag}-files-{i//900+1}'
   text=r['title']+' '+r.get('description','')+' '+' '.join(r.get('tags',[]))
   words=tokens(text)
   concepts=[name for name,group in GROUPS.items() if words & tokens(group)]
   public.append(dict(id=r['id'],file=source.name,title=r['title'],description=r.get('description',''),
    tags=r.get('tags',[]),match_tags=sorted(words),search_aliases_ru=r.get('search_aliases_ru',[]),
    concepts=concepts if channel=='scenes' else [],kinds=sorted(set(aliases[r['id']])) if channel=='events' else [],
    bytes=r['bytes'],sha256=r['sha256'],seconds=r['seconds'],url='',
    author=r.get('author_display',''),source_url=r['source_url'],license=r['license'],license_url=r.get('license_url',''),
    loudness=r['normalization'].get('after'),normalization=r['normalization']['method']))
  archives=[]
  for start in range(0,len(chosen),100):
   name=f'{channel}-{start//100+1:03}.zip';path=folder/name
   with zipfile.ZipFile(path,'w',zipfile.ZIP_STORED,allowZip64=True) as z:
    for r in chosen[start:start+100]:z.write(root/r['file'],Path(r['file']).name)
   with zipfile.ZipFile(path) as z:assert z.testzip() is None
   archives.append(dict(file=name,url=f'{base}/{tag}/{name}',bytes=path.stat().st_size,sha256=bank.digest(path)))
  for i,item in enumerate(public):
   item['archive']=archives[i//100]
  manifest=dict(schema=1,version=1,channel=channel,priority='primary',fallback='legacy then on-device generation' if channel=='events' else 'on-device generation',
    credits_url=f'{base}/{tag}/CREDITS.md',sounds=public,archives=archives)
  bank.atomic_json(folder/'manifest.json',manifest)
  assert (folder/'manifest.json').stat().st_size<8*1024*1024
  shutil.copyfile(root/channel/'sounds.db',folder/'sounds.db')
  (folder/'CREDITS.md').write_text(credits)
  (folder/'README.md').write_text(f'# MyTTS real recordings: {channel}\n\nPrimary bank; CC0 recording sources in CREDITS.md.\n'
    'Original lengths retained for scenes; events at most eight seconds.\n'
    'Archives contain 100 files each (last may be smaller); individual URL and SHA256 in manifest.json.\n'
    'Review/excluded material is not included.\n')
  plan.append(dict(tag=tag,prerelease=True,assets=[p.name for p in folder.iterdir() if p.is_file()]))
  summary[channel]=dict(files=len(chosen),bytes=sum(r['bytes'] for r in chosen),archives=len(archives),
      mapped_kinds=len({k for r in public for k in r['kinds']}),manifest_bytes=(folder/'manifest.json').stat().st_size)
 bank.atomic_json(out/'release-plan.json',dict(releases=plan,summary=summary))
 files=[p for p in out.rglob('*') if p.is_file() and p.name!='SHA256SUMS']
 (out/'SHA256SUMS').write_text('\n'.join(bank.digest(p)+'  '+str(p.relative_to(out)) for p in sorted(files))+'\n')
 print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
