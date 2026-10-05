#!/usr/bin/env python3
"""Package already downloaded, pinned upstream files. No model conversion.
Pass --source DIR --metadata HuggingFace-blobs.json --manifest PATH.
Source contains flattened ONNX/voice files, config.json and espeak-data/.
"""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

REV = 'd649c57b239b18c4c384378127cbf01dba039bc1'
ESPEAK_REV = '4870adfa25b1a32b4361592f1be8a40337c58d6c'
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--metadata', type=Path, required=True)
parser.add_argument('--manifest', type=Path, required=True)
args = parser.parse_args()
metadata = json.loads(args.metadata.read_text())
assert metadata['sha'] == REV
upstream = {r['rfilename']: r for r in metadata['siblings']}

def verified(remote, local):
    data = local.read_bytes(); row = upstream[remote]
    assert len(data) == row['size'], remote
    if 'lfs' in row:
        assert hashlib.sha256(data).hexdigest() == row['lfs']['sha256'], remote
    else:
        assert hashlib.sha1(('blob '+str(len(data))+'\0').encode()+data).hexdigest() == row['blobId'], remote
    return data

def entry(path, name=None):
    data = path.read_bytes()
    return {'name': name or path.name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

for name in ['model_quantized.onnx', 'model_dima_quantized.onnx']:
    verified('onnx/'+name, args.source/name)
for voice in ['sveta', 'masha', 'dima']:
    verified('voices/'+voice+'.bin', args.source/(voice+'.bin'))
verified('config.json', args.source/'config.json')
verified('README.md', args.source/'README.txt')
espeak = sorted((args.source/'espeak-data').rglob('*'))
entries = []
with zipfile.ZipFile(args.source/'espeak-data.zip', 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in espeak:
        if not path.is_file(): continue
        name = path.relative_to(args.source).as_posix()
        data = verified(name, path)
        info = zipfile.ZipInfo(name, date_time=(1980,1,1,0,0,0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, data, compresslevel=9)
        entries.append(entry(path, name))
assert {r['name'] for r in entries} == {
    'espeak-data/phondata', 'espeak-data/phonindex', 'espeak-data/phontab', 'espeak-data/intonations',
    'espeak-data/ru_dict', 'espeak-data/en_dict', 'espeak-data/lang/zle/ru', 'espeak-data/lang/gmw/en'}
(args.source/'NOTICE.txt').write_text(
    f'Kokoro-RU v2 by zaakirio\nhttps://huggingface.co/zaakirio/kokoro-ru/tree/{REV}\n'
    'Weights: OpenRAIL, per upstream model card (included README.txt).\n'
    'Russian G2P: zaakirio/ru_g2p.py, Apache-2.0; ported in MyTTS KokoroG2p.kt.\n'
    'IPA mapping: hexgrad/Misaki espeak.py, Apache-2.0.\n'
    f'eSpeak NG 1.52.0 source: https://github.com/espeak-ng/espeak-ng/tree/{ESPEAK_REV}\n'
    'eSpeak NG runtime and dictionary data: GPL-3.0-or-later; original compiled acute-aware Russian data supplied by Kokoro-RU.\n'
    'Model and voice files are unchanged upstream ONNX Q8 and float32 style packs.\n', encoding='utf-8')
names = ['model_quantized.onnx','model_dima_quantized.onnx','sveta.bin','masha.bin','dima.bin','config.json',
         'espeak-data.zip','NOTICE.txt','README.txt','LICENSE_APACHE2.txt','LICENSE_ESPEAK.txt']
manifest = {'repo':'zaakirio/kokoro-ru','revision':REV,'espeak_revision':ESPEAK_REV,'sample_rate':24000,
            'voices':['sveta','masha','dima'],'files':[entry(args.source/n) for n in names], 'espeak_entries':entries}
args.manifest.parent.mkdir(parents=True, exist_ok=True)
args.manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('Verified package:',sum(r['size'] for r in manifest['files']),'bytes;',len(entries),'phonemizer files')
