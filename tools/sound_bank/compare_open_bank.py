#!/usr/bin/env python3
"""Candidate coverage of the published MyTTS bank using local MiniLM embeddings.

This is metadata retrieval, never an auditory-quality or automatic replacement
approval. Input model: sentence-transformers/all-MiniLM-L6-v2, pinned separately.
Requires numpy and onnxruntime; BERT WordPiece vocabulary supplied with --model.
"""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re
import unicodedata

import numpy as np
import onnxruntime as ort


class Encoder:
    def __init__(self, folder):
        self.vocab = {s.strip(): i for i, s in enumerate((folder/'vocab.txt').read_text().splitlines())}
        opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
        self.session=ort.InferenceSession(str(folder/'model.onnx'),opts,providers=['CPUExecutionProvider'])

    def tokens(self, text):
        text=''.join(c for c in unicodedata.normalize('NFD', text.lower()) if unicodedata.category(c)!='Mn')
        pieces=[]
        for word in re.findall(r'[a-z0-9]+|[^\w\s]',text):
            if len(word)>100:
                pieces.append(self.vocab['[UNK]']);continue
            start=0;current=[]
            while start<len(word):
                end=len(word);found=None
                while end>start:
                    part=('##' if start else '')+word[start:end]
                    if part in self.vocab:
                        found=self.vocab[part];break
                    end-=1
                if found is None:
                    current=[self.vocab['[UNK]']];break
                current.append(found);start=end
            pieces.extend(current)
        return [self.vocab['[CLS]']]+pieces[:126]+[self.vocab['[SEP]']]

    def encode(self, texts):
        vectors=[]
        for start in range(0,len(texts),32):
            batch=[self.tokens(t) for t in texts[start:start+32]];length=max(map(len,batch))
            ids=np.zeros((len(batch),length),dtype=np.int64);mask=np.zeros_like(ids)
            for i,row in enumerate(batch):ids[i,:len(row)]=row;mask[i,:len(row)]=1
            available={'input_ids':ids,'attention_mask':mask,'token_type_ids':np.zeros_like(ids)}
            outputs=self.session.run(None,{x.name:available[x.name] for x in self.session.get_inputs()})
            hidden=outputs[0]
            mean=(hidden*mask[:,:,None]).sum(axis=1)/mask.sum(axis=1)[:,None]
            vectors.append(mean/np.maximum(np.linalg.norm(mean,axis=1,keepdims=True),1e-9))
            if start%320==0:print(f'Embedding {min(start+32,len(texts))}/{len(texts)}',flush=True)
        return np.concatenate(vectors).astype(np.float32)


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--bank',type=Path,required=True)
    ap.add_argument('--current',type=Path,required=True);ap.add_argument('--model',type=Path,required=True)
    args=ap.parse_args();root=args.bank
    old=json.loads(args.current.read_text())['sounds']
    new=[json.loads(p.read_text()) for p in sorted((root/'state').glob('*.json'))]
    new=[r for r in new if r['status']=='done']
    old_text=[r['key'].replace('_',' ')+'. '+r['prompt'] for r in old]
    new_text=[r['title']+'. '+r.get('description','')[:650]+'. '+', '.join(r.get('tags',[]))+'. '+r.get('category','') for r in new]
    cache=root/'coverage-embeddings.npz'
    signature=hashlib.sha256(json.dumps(old_text+new_text).encode()).hexdigest()
    if cache.exists():
        c=np.load(cache)
        if str(c['signature'])==signature:ov,nv=c['old'],c['new']
        else:cache.unlink()
    if not cache.exists():
        encoder=Encoder(args.model);allv=encoder.encode(old_text+new_text);ov,nv=allv[:len(old)],allv[len(old):]
        np.savez_compressed(cache,old=ov,new=nv,signature=signature)
    similarity=ov@nv.T
    rows=[]
    for i,item in enumerate(old):
        top=np.argsort(similarity[i])[::-1][:8]
        candidates=[]
        for j in top:
            r=new[int(j)];candidates.append(dict(id=r['id'],score=round(float(similarity[i,j]),4),
                title=r['title'],description=r.get('description',''),bucket=r['bucket'],file=r['file'],
                source_url=r['source_url']))
        rows.append(dict(key=item['key'],hint=item.get('hint') or '',prompt=item['prompt'],candidates=candidates))
    (root/'coverage-candidates.json').write_text(json.dumps(dict(current_kinds=len(old),new_files=len(new),
         method='MiniLM metadata retrieval; candidates require semantic/listening review',matches=rows),ensure_ascii=False,indent=2)+'\n')
    with (root/'coverage-top.csv').open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.writer(f);w.writerow(['key','hint','prompt','best_score','best_title','bucket','file','source_url'])
        for r in rows:
            c=r['candidates'][0];w.writerow([r['key'],r['hint'],r['prompt'],c['score'],c['title'],c['bucket'],c['file'],c['source_url']])
    print('Saved coverage-candidates.json and coverage-top.csv',flush=True)


if __name__=='__main__':main()
