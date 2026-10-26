# Tera int8 sampler

`sampler_loop_int8.onnx` (release `russian-resources-v1`, 65 447 786 bytes,
sha256 `50101128535530a307ae414ee21b4b8c6e76d51b2da1a36cd683d38592a1980f`) is the distilled TeraTTSv2 sampler
(`sampler_distilled_cfg3_8step.onnx`, TeraSpace/TeraTTSv2 @ f05ea799) with MatMul quantized to int8 inside its Loop body.

    python -m venv venv && venv/bin/pip install onnx==1.23.2 onnxruntime==1.31.0
    venv/bin/python loop_int8.py . sampler_distilled_cfg3_8step.onnx /tmp sampler_loop_int8.onnx

`tera_graph_tools.py` comes from the TeraTTS fork (github.com/9208499-ship-it/teratts-android, GPL-3.0): its
quantization of the unrolled graph gives the same output bit for bit, but 20 350 nodes take ~7 s (Mac) / ~12 s (phone)
just to open a session. Keeping the Loop gives 2 541 nodes: the session opens in ~0.3 s, a run is as fast
(Mac, 2 threads, 120 frames: fp32 839 ms, int8 303 ms), latent SNR vs fp32 12.9 dB — compare by ear.
