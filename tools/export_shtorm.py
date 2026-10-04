"""One-time Shtorm export, with pinned original weights and verified inference graphs."""
import hashlib,json,pathlib,subprocess,sys,urllib.request
import yaml
ROOT=pathlib.Path(__file__).resolve().parents[1]
out=ROOT/'build/shtorm-model';out.mkdir(parents=True,exist_ok=True)
meta=json.load(urllib.request.urlopen('https://huggingface.co/api/models/ArtShtorm/Shtorm_PocketTTS_RU'))
revision=meta['sha']
config_url=f'https://huggingface.co/ArtShtorm/Shtorm_PocketTTS_RU/resolve/{revision}/config_fast.yaml'
config=yaml.safe_load(urllib.request.urlopen(config_url))
config['weights_path']=f'hf://ArtShtorm/Shtorm_PocketTTS_RU/model_fast.safetensors@{revision}'
config['flow_lm']['lookup_table']['tokenizer_path']=f'hf://ArtShtorm/Shtorm_PocketTTS_RU/tokenizer.model@{revision}'
config_path=out/'source-config.yaml';config_path.write_text(yaml.safe_dump(config))
subprocess.run([sys.executable,str(ROOT/'vendor/pockettts/PocketTTS.cpp/export_onnx.py'),'--config',str(config_path),'--output-dir',str(out),'--no-quantize'],check=True)
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
