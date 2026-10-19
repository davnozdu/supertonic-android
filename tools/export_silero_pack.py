#!/usr/bin/env python3
"""Build the Android Silero RU voice pack (pack.json + tts_mel.ptl + backbone.pte + head.ptl).

Same file layout as the existing ruvoice-pack-ru.zip the app already reads:
  tts_mel.ptl  - the TorchScript model with the vocoder call cut out (returns mel, durations)
  backbone.pte - the vocoder ConvNeXt backbone, ExecuTorch/XNNPACK fp32, dynamic frame count
  head.ptl     - the vocoder without its backbone (head, iSTFT, PQMF)

Run with torch 2.14 + executorch 1.5.0 (the phone runtime is ExecuTorch 1.5.0):
  python -I tools/export_silero_pack.py v5_6_ru.pt out/ruvoice-pack-ru.zip
Every split is checked against the untouched model before the zip is written.
"""
import json
import os
import sys
import tempfile
import zipfile

import torch
from torch import nn
from torch.jit.mobile import _load_for_lite_interpreter

D = 512
TEXTS = ['прив+ет, м+ир.', 'а вы когд+а приед+ете?', 'как+ая красот+а!', 'он – вр+ач. к+ак-ниб+удь потом.']


def cut_forward_call(graph, child):
    """Replace `child.forward(x, ...)` in a TorchScript graph with its first argument."""
    for node in graph.nodes():
        if node.kind() == 'prim::CallMethod' and node.s('name') == 'forward' and child in str(node.inputsAt(0)):
            node.output().replaceAllUsesWith(node.inputsAt(1))
            node.destroy()
            return
    raise SystemExit(f'{child}.forward call not found')


class ConvNeXtBlock(nn.Module):
    def __init__(self):
        super().__init__()
        self.dwconv = nn.Conv1d(D, D, 7, padding=3, groups=D)
        self.norm = nn.LayerNorm(D, eps=1e-6)
        self.pwconv1 = nn.Linear(D, 3 * D)
        self.pwconv2 = nn.Linear(3 * D, D)
        self.gamma = nn.Parameter(torch.ones(D))

    def forward(self, x):
        y = self.dwconv(x).transpose(1, 2)
        y = self.pwconv2(nn.functional.gelu(self.pwconv1(self.norm(y))))
        return x + (self.gamma * y).transpose(1, 2)


class VocoderBackbone(nn.Module):
    """Eager copy of Silero's vocoder backbone so torch.export can lower it to XNNPACK."""
    def __init__(self):
        super().__init__()
        self.embed = nn.Conv1d(192, D, 7, padding=3)
        self.norm = nn.LayerNorm(D, eps=1e-6)
        self.convnext = nn.ModuleList([ConvNeXtBlock() for _ in range(8)])
        self.final_layer_norm = nn.LayerNorm(D, eps=1e-6)

    def forward(self, x):
        x = self.norm(self.embed(x).transpose(1, 2)).transpose(1, 2)
        for block in self.convnext:
            x = block(x)
        return self.final_layer_norm(x.transpose(1, 2))


def model_args(pk, text, speaker, n_args, sr=48000):
    """Positional forward arguments in the order the app passes them (no explicit durations)."""
    seq, _ = pk.preprocess_tacotron(text)
    seq = seq.unsqueeze(0)
    n = seq.shape[1]
    types = torch.zeros(1, n, dtype=torch.long)
    if '?' in text:
        types[:] = 2
    elif '!' in text:
        types[:] = 5
    args = [seq, torch.LongTensor([speaker]), sr, None, torch.ones(1, n), torch.ones(1, n),
            None, None, 'cpu', -1, False, types, torch.zeros(1, n, dtype=torch.long)]
    if n_args == 14:
        args.append(None)
    return args


def main(source, out_zip):
    big = torch.package.PackageImporter(source).load_pickle('tts_models', 'model')
    assert len(big.packages) == 1
    pk = big.packages[0]
    assert len(pk.models) == 1
    tts = pk.models[0]
    n_args = len(tts.forward.schema.arguments) - 1
    assert n_args in (13, 14), n_args
    speakers = dict(pk.speaker_to_ids[0])
    version = os.path.basename(source).removesuffix('.pt')
    pack = {
        'format': 2, 'id': 'ru', 'title': f'Silero {version}', 'license': 'CC BY-NC-SA 4.0',
        'source': f'https://models.silero.ai/models/tts/ru/{os.path.basename(source)}',
        'symbols': pk.symbols, 'symbol_to_id': pk.symbol_to_id, 'sos': pk.sos_token, 'eos': pk.eos_token,
        'alphabet': ''.join(pk.alphabet), 'types': True, 'args': n_args, 'speakers': speakers,
    }
    sid = speakers['xenia']
    with torch.no_grad():
        refs = [tts(*model_args(pk, t, sid, n_args)) for t in TEXTS]
        refs24 = [tts(*model_args(pk, t, sid, n_args, 24000))[0] for t in TEXTS]
    with tempfile.TemporaryDirectory() as tmp:
        mel_p, head_p, bb_p = (os.path.join(tmp, n) for n in ('tts_mel.ptl', 'head.ptl', 'backbone.pte'))
        backbone_state = {k: v.clone() for k, v in tts.vocoder.backbone.state_dict().items()}
        cut_forward_call(tts.vocoder.forward.graph, 'backbone')
        torch.jit.freeze(tts.vocoder.eval())._save_for_lite_interpreter(head_p)
        cut_forward_call(tts.forward.graph, 'vocoder')
        torch.jit.freeze(tts.eval())._save_for_lite_interpreter(mel_p)
        mel_m = _load_for_lite_interpreter(mel_p)
        head_m = _load_for_lite_interpreter(head_p)
        backbone = VocoderBackbone().eval()
        backbone.load_state_dict(backbone_state)
        mels = []
        with torch.no_grad():
            for text, (ref, ref_durs), ref24 in zip(TEXTS, refs, refs24):
                mel, durs = mel_m(*model_args(pk, text, sid, n_args))
                assert torch.equal(durs, ref_durs), f'durations differ: {text}'
                hidden = backbone(mel)
                for sr, expected in ((48000, ref), (24000, ref24)):
                    diff = (head_m(hidden, sr, 0., True) - expected).abs().max().item()
                    assert diff < 1e-4, f'eager split differs {diff}: {text}'
                mels.append((mel.contiguous(), ref))
        from torch.export import Dim, export
        from executorch.exir import to_edge_transform_and_lower
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        from executorch.runtime import Runtime
        program = export(backbone, (mels[0][0],), dynamic_shapes={'x': {2: Dim('T', min=4, max=6000)}})
        lowered = to_edge_transform_and_lower(program, partitioner=[XnnpackPartitioner()]).to_executorch()
        with open(bb_p, 'wb') as f:
            f.write(lowered.buffer)
        method = Runtime.get().load_program(bb_p).load_method('forward')
        with torch.no_grad():
            for mel, ref in mels:
                diff = (head_m(method.execute([mel])[0], 48000, 0., True) - ref).abs().max().item()
                assert diff < 1e-3, f'backbone.pte differs {diff}'
        os.makedirs(os.path.dirname(os.path.abspath(out_zip)), exist_ok=True)
        with zipfile.ZipFile(out_zip, 'w') as zf:
            zf.writestr('pack.json', json.dumps(pack, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
            for name, path in (('tts_mel.ptl', mel_p), ('backbone.pte', bb_p), ('head.ptl', head_p)):
                zf.write(path, name, compress_type=zipfile.ZIP_STORED)
            sizes = {i.filename: i.file_size for i in zf.infolist()}
    print(json.dumps({'zip': out_zip, 'bytes': os.path.getsize(out_zip), 'files': sizes, 'args': n_args}))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
