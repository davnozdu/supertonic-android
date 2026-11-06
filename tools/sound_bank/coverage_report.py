import pathlib,json,re,collections,csv,argparse
import numpy as np
ap=argparse.ArgumentParser();ap.add_argument('--bank',type=pathlib.Path,required=True);args=ap.parse_args();root=args.bank
old=json.loads((root/'current-bank-v11.json').read_text())['sounds']
new=[json.loads(p.read_text()) for p in sorted((root/'state').glob('*.json'))]
full=[r for r in new if 'original_sha256' in r]
c=np.load(root/'coverage-embeddings.npz')
assert len(full)==len(c['new']), 'Embedding ID alignment changed'
indices=[i for i,r in enumerate(full) if r['status']=='done']
new=[full[i] for i in indices]
sim=c['old']@c['new'][indices].T

def stem(t):
 t=t.lower()
 if len(t)>4 and t.endswith('ies'):t=t[:-3]+'y'
 elif len(t)>4 and t.endswith('ing'):
  t=t[:-3]
  if len(t)>2 and t[-1]==t[-2]:t=t[:-1]
 elif len(t)>4 and t.endswith('ed'):t=t[:-2]
 elif len(t)>3 and t.endswith('s') and not t.endswith('ss'):t=t[:-1]
 if len(t)>4 and t.endswith('e'):t=t[:-1]
 return t

def words(text):return {stem(t) for t in re.findall('[a-z]+',text.lower())}
ALIASES={
 'hangup':'hangup hung', 'animal':'animal cattle cow',
 'creak':'creak squeak squeaking creaking', 'laughter':'laugh laughter laughing',
 'laugh':'laugh laughter chuckle', 'cry':'crying cries sob sobbing weep weeping',
 'crying':'crying cries sob sobbing weep weeping', 'cough':'cough coughing',
 'man':'man male', 'woman':'woman female', 'old':'old elderly aged',
 'child':'child kid boy girl children', 'baby':'baby infant newborn',
 'breath':'breath breathing breathless panting', 'panting':'pant panting breathless',
 'sigh':'sigh sighing', 'shaky':'shaky trembling trembling',
 'wood':'wood wooden timber parquet floorboards', 'stone':'stone concrete cobblestones',
 'footsteps':'footsteps step walk walking', 'stairs':'stairs staircase stair',
 'close':'close shut closing closed', 'open':'open opening opened',
 'slam':'slam slamming slammed', 'knock':'knock knocking knocks tap',
 'car':'car automobile automotive', 'engine':'engine motor', 'start':'start starting ignition',
 'horn':'horn honk honking', 'phone':'phone telephone', 'telephone':'phone telephone',
 'dial':'dial dialing dialling', 'hangup':'hangup handset',
 'water':'water sea ocean wave waves', 'sea':'sea ocean wave waves shore',
 'brook':'brook brooklet stream river', 'running':'running flowing run flow',
 'pour':'pour pouring fill filling', 'splash':'splash splashing',
 'dishes':'dish dishes plate plates crockery', 'cutlery':'cutlery fork spoon knife',
 'cupboard':'cupboard closet', 'cloth':'cloth fabric clothes clothing',
 'cloths':'cloth fabric', 'bag':'bag suitcase handbag', 'zip':'zip zipper',
 'cat':'cat kitten meow', 'dog':'dog puppy bark', 'bird':'bird birds',
 'cattle':'cattle cow cows', 'low':'lowing moo moos',
 'bicycle':'bicycle bike', 'axe':'axe ax', 'chop':'chop chopping split splitting',
 'gunshot':'gunshot gunfire shot shooting', 'pistol':'pistol gun handgun',
 'rifle':'rifle gun', 'whiz':'whiz whizzing whistle',
 'gun':'gun firearm', 'applause':'applause clap clapping',
 'clap':'clap applause clapping', 'paper':'paper papers sheet', 'page':'page pages',
 'match':'match matches', 'fire':'fire flame flames fireplace',
 'campfire':'campfire bonfire fire', 'floor':'floor floorboard parquet',
 'thunder':'thunder storm thunderstorm', 'rain':'rain rainfall raining',
 'coins':'coin coins', 'coin':'coin coins', 'keys':'key keys', 'bell':'bell bells doorbell',
 'alarm':'alarm buzzer', 'clock':'clock clocks pendulum timer',
 'pen':'pen writing ballpoint', 'ballpoint':'ballpoint pen', 'typewriter':'typewriter typewriting',
 'chest':'chest trunk box', 'bolt':'bolt latch', 'blade':'blade knife sword',
 'glass':'glass glasses', 'break':'break broken breaking shatter smash burst',
 'drop':'drop fall falling dropped', 'set':'set put place placed',
 'cut':'cut cutting slice slicing', 'grind':'grind grinding gnash gnashing',
 'bark':'bark barking', 'meow':'meow mewing', 'growl':'growl growling',
 'roar':'roar roaring', 'hiss':'hiss hissing', 'snore':'snore snoring',
 'clear':'clear clearing', 'throat':'throat', 'sneeze':'sneeze sneezing',
 'chewing':'chew chewing eating', 'chew':'chew chewing eating',
 'clink':'clink clinking tinkling', 'rattle':'rattle rattling',
 'rustle':'rustle rustling', 'squeal':'squeal squealing screech screeching',
 'towards':'approach approaching', 'away':'away departure departing',
 'brake':'brake brakes braking', 'gravel':'gravel grit pebbles',
 'snow':'snow snowy', 'night':'night nighttime nocturnal', 'morning':'morning dawn waking',
}

def anchors(token):return words(ALIASES.get(token,token))
sets=[words(r['title']+' '+r.get('description','')+' '+' '.join(r.get('tags',[]))) for r in new]
titles=[words(r['title']) for r in new]
results=[]
for i,o in enumerate(old):
 tokens=[t for t in o['key'].split('_') if t not in ['single','small','big','heavy','soft']]
 req=[anchors(t) for t in tokens]
 frequencies=[sum(bool(a & doc) for doc in sets) for a in req]
 primary=int(np.argmin(frequencies)) if frequencies else 0
 querywords=words(o['prompt'])
 # The underlying action matters: pen clicking cannot replace pen writing.
 actions=[]
 for term,triggers in [('write','writing writes written'),('shuffle','shuffling shuffled'),
                       ('open','opening opened'),('close','closing closed'),
                       ('knock','knocking knocks'),('creak','creaking squeaking'),
                       ('crying','crying sobbing weeping')]:
  if querywords & words(triggers): actions.append(words(ALIASES.get(term,term)+' '+triggers))
 candidates=[]
 for j,r in enumerate(new):
  if req and not (req[primary] & sets[j]):continue
  raw=(r['title']+' '+r.get('description','')).lower()
  if o['key'] in ['cry','cry_man','cry_woman','cry_child','cry_baby','crying_woman_far']:
   if not re.search(r'\b(crying|sobbing|weeping|whimpering|cries|sob|weeps|whimpers)\b',raw):continue
   if o['key'] in ['cry_man','cry_woman','crying_woman_far']:
    actor=anchors('man' if o['key']=='cry_man' else 'woman')
    if not actor & sets[j]:continue
  if o['key']=='teeth_grind' and not re.search(r'\b(grind|grinding|gnash|gnashing)\b',raw):continue
  score=float(sim[i,j]);matches=sum(bool(x&sets[j]) for x in req);cover=matches/max(len(req),1)
  action_ok=not actions or any(a&sets[j] for a in actions)
  if score<.36 or not matches:continue
  exact=cover==1 and action_ok and score>=.46 and o['key']!='cry'
  partial=(cover>=.5 and score>=.43) or (matches>=1 and score>=.6)
  if not exact and not partial:continue
  titlecover=sum(bool(x&titles[j]) for x in req)/max(len(req),1)
  rank=score+.14*cover+.05*titlecover+(.12 if exact else 0)
  candidates.append(dict(id=r['id'],file=r['file'],title=r['title'],description=r.get('description',''),
      bucket=r['bucket'],score=round(score,4),anchor_coverage=cover,action_ok=bool(action_ok),
      match='direct_candidate' if exact else 'partial_candidate',rank=rank,source_url=r['source_url']))
 candidates.sort(key=lambda x:x['rank'],reverse=True)
 usable=[c for c in candidates if c['bucket']!='review']
 direct=[c for c in usable if c['match']=='direct_candidate']
 if direct:status='direct_candidate';best=direct[0]
 elif usable:status='partial_candidate';best=usable[0]
 elif candidates:status='review_only';best=candidates[0]
 else:status='not_found';best=None
 results.append(dict(key=o['key'],hint=o.get('hint') or '',prompt=o['prompt'],status=status,best=best,candidates=candidates[:8]))
summary=dict(total_kinds=len(old),current_clips=sum(len(o['variants']) for o in old),
    new_audio_files=len(new),counts=dict(collections.Counter(r['status'] for r in results)),
    caveat='Metadata candidates, not verified listening coverage. A cropped 8-second clip may omit an action described for the full source. Semantic retrieval plus explicit object/action anchors; quality-review files excluded from usable counts.')
(root/'coverage-report.json').write_text(json.dumps(dict(summary=summary,matches=results),ensure_ascii=False,indent=2)+'\n')
with (root/'coverage-report.csv').open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.writer(f);w.writerow(['key','hint','prompt','status','best_title','bucket','score','file','source_url'])
 for r in results:
  b=r['best'] or {};w.writerow([r['key'],r['hint'],r['prompt'],r['status'],b.get('title',''),b.get('bucket',''),b.get('score',''),b.get('file',''),b.get('source_url','')])
print(json.dumps(summary,ensure_ascii=False,indent=2))
for r in results:
 if any(t in r['key'] for t in ['creak','cry','cough','pant','nose_blow','burp','teeth','phone','door_open','door_close']):
  print(r['key'],r['status'],(r['best'] or {}).get('title'),(r['best'] or {}).get('bucket'))
