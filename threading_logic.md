# Supertonic TTS: Threading & Optimization Logic

This document outlines the threading strategy and battery optimizations implemented for the Supertonic Android application using ONNX Runtime (ORT) and the XNNPACK Execution Provider.

## 1. Threading Strategy

### Dual-Pool Configuration
To ensure high performance regardless of whether XNNPACK is supported on the device, we configure two distinct thread pools with the same user-defined thread count (`N`):

*   **ORT Session Pool (Intra-op):** Configured via `with_intra_threads(N)`. This pool handles all operators executed by the default CPU Execution Provider.
*   **XNNPACK Internal Pool:** Configured via `XNNPACKExecutionProvider::with_intra_op_num_threads(N)`. This pool handles math-intensive operators (Conv, MatMul, Gemm) optimized for ARM.

### Avoiding Contention
Because the TTS model is executed in **Sequential Mode** (`ORT_SEQUENTIAL`), only one operator runs at a time. This means the ORT pool and the XNNPACK pool never compete for CPU cycles simultaneously. 

*   When an XNNPACK-optimized operator runs, the XNNPACK pool is active while the ORT pool is idle.
*   When a fallback CPU operator runs, the ORT pool is active while the XNNPACK pool is idle.

## 2. Battery & Thermal Optimizations

### Disabling Thread Spinning
By default, ONNX Runtime threads "spin" (stay 100% active) for a short period while waiting for new work to reduce latency. On mobile devices, this leads to significant battery drain and heat generation even during short pauses between sentences.

*   **Implementation:** We explicitly set `session.intra_op.allow_spinning` and `session.inter_op.allow_spinning` to `"0"`.
*   **Result:** Idle threads are immediately parked by the OS kernel, preserving battery life and keeping the device cool during long synthesis tasks.

## 3. Implementation Details (Rust)

The logic is encapsulated in the `create_session` helper:

```rust
fn create_session(model_path: &str, use_xnnpack: bool, intra_threads: usize) -> Result<Session> {
    let mut builder = Session::builder()?
        .with_optimization_level(GraphOptimizationLevel::Level3)?
        .with_config_entry("session.intra_op.allow_spinning", "0")?
        .with_config_entry("session.inter_op.allow_spinning", "0")?
        .with_intra_threads(intra_threads)?;

    if use_xnnpack {
        #[cfg(feature = "xnnpack")]
        {
            let xnn_threads = std::num::NonZeroUsize::new(intra_threads)
                .unwrap_or(std::num::NonZeroUsize::new(1).unwrap());
            builder = builder
                .with_execution_providers([
                    XNNPACKExecutionProvider::default()
                        .with_intra_op_num_threads(xnn_threads)
                        .build(),
                    CPUExecutionProvider::default().build(),
                ])?;
        }
    }
    builder.commit_from_file(model_path)
}
```

## 4. User Control

*   **Default Behavior:** The app defaults to **4 threads** on first launch to target high-performance cores.
*   **Manual Override:** Users can select 1, 3, 4, 5, or 8 threads from the settings menu (3-dot menu in the top right).
*   **Targeting Performance Cores:** On typical Android 8-core CPUs (Big.LITTLE), selecting **4 or 5 threads** is recommended to target the high-performance cores without involving the slower efficiency cores.
*   **CPU Fallback:** When XNNPACK is active, the thread count for non-optimized CPU operators is left to the system's default management to ensure optimal resource utilization.
