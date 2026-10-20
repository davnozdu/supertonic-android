"""One-time Shtorm export, with pinned original weights and verified inference graphs.

python tools/export_shtorm.py        -> fast (the app's base package, release shtorm-pocket-v2)
python tools/export_shtorm.py slow   -> slow: only the graphs that differ from the published fast ones
                                        (release shtorm-pocket-v2-slow); tokenizer, voice and the rest are shared."""
import hashlib,json,pathlib,subprocess,sys,urllib.request
import yaml
ROOT=pathlib.Path(__file__).resolve().parents[1]
REPO='ArtShtorm/Shtorm-PocketTTS-RU'
variant=sys.argv[1] if len(sys.argv)>1 else 'fast'
assert variant in ('fast','slow'),variant
out=ROOT/('build/shtorm-model' if variant=='fast' else 'build/shtorm-model-slow');out.mkdir(parents=True,exist_ok=True)
meta=json.load(urllib.request.urlopen(f'https://huggingface.co/api/models/{REPO}'))
revision=meta['sha']
config_url=f'https://huggingface.co/{REPO}/resolve/{revision}/config_{variant}.yaml'
config=yaml.safe_load(urllib.request.urlopen(config_url))
config['weights_path']=f'hf://{REPO}/model_{variant}.safetensors@{revision}'
config['flow_lm']['lookup_table']['tokenizer_path']=f'hf://{REPO}/tokenizer.model@{revision}'
config_path=out/'source-config.yaml';config_path.write_text(yaml.safe_dump(config))
subprocess.run([sys.executable,str(ROOT/'vendor/pockettts/PocketTTS.cpp/export_onnx.py'),'--config',str(config_path),'--output-dir',str(out),'--no-quantize'],check=True)
def sha(file): return hashlib.sha256(file.read_bytes()).hexdigest()
if variant=='slow':
    # Which weights differ decides which graphs must ship; the byte comparison below is the final word.
    from huggingface_hub import hf_hub_download
    from safetensors import safe_open
    tensors={}
    for name in ('fast','slow'):
        with safe_open(hf_hub_download(REPO,f'model_{name}.safetensors',revision=revision),'pt') as f:
            tensors[name]={k:f.get_tensor(k) for k in f.keys()}
    changed=sorted({k.split('.')[0]+'.'+k.split('.')[1] for k in tensors['slow'] if k not in tensors['fast'] or not tensors['slow'][k].equal(tensors['fast'][k])})
    print('slow differs from fast in:',changed)
    fast={row['name']:row['sha256'] for row in json.loads((ROOT/'app/src/main/assets/shtorm_manifest.json').read_text())['files']}
    files=[]
    for file in sorted(out.glob('*.onnx')):
        digest=sha(file)
        if fast.get(file.name)==digest: file.unlink();continue
        files.append(dict(name=file.name,size=file.stat().st_size,sha256=digest))
    assert any(row['name'].startswith('flow_lm_main') for row in files),'slow export equals fast'
    (out/'LICENSE.txt').write_text('Shtorm PocketTTS RU v2 slow © ArtShtorm, CC BY 4.0. Base: Kyutai Pocket-TTS © Kyutai Labs.\nhttps://creativecommons.org/licenses/by/4.0/\nSource revision: '+revision+'\n')
    files.append(dict(name='LICENSE.txt',size=(out/'LICENSE.txt').stat().st_size,sha256=sha(out/'LICENSE.txt')))
    (out/'manifest.json').write_text(json.dumps(dict(version=1,variant='slow',source_revision=revision,sample_rate=24000,changed_weights=changed,files=files),indent=2)+'\n')
    print((out/'manifest.json').read_text())
    sys.exit(0)
# The official CC-BY reference is a starter voice; the Russian model determines language.
voice=out/'alba.wav'
urllib.request.urlretrieve('https://huggingface.co/kyutai/tts-voices/resolve/main/alba-mackenna/casual.wav',out/'reference.wav')
import math,numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly
rate,audio=wavfile.read(out/'reference.wav')
if np.issubdtype(audio.dtype,np.integer): audio=audio.astype(np.float32)/max(abs(np.iinfo(audio.dtype).min),np.iinfo(audio.dtype).max)
if audio.ndim>1: audio=audio.mean(axis=1)
common=math.gcd(rate,24000)
audio=resample_poly(audio[:rate*5],24000//common,rate//common)
wavfile.write(voice,24000,(np.clip(audio,-1,1)*32767).astype(np.int16))
(out/'reference.wav').unlink()
(out/'LICENSE.txt').write_text('Shtorm PocketTTS RU © ArtShtorm, CC BY 4.0. Base: Kyutai Pocket-TTS © Kyutai Labs.\nStarter reference voice: Alba MacKenna, casual.wav, kyutai/tts-voices, CC BY 4.0, trimmed to 5s and converted to 24kHz mono.\nhttps://creativecommons.org/licenses/by/4.0/\nSource revision: '+revision+'\n')
files=[]
for file in sorted(out.iterdir()):
 if file.suffix not in ('.onnx','.model','.wav','.txt'):continue
 files.append(dict(name=file.name,size=file.stat().st_size,sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
(out/'manifest.json').write_text(json.dumps(dict(version=1,source_revision=revision,sample_rate=24000,files=files),indent=2)+'\n')
