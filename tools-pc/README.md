# tools-pc

PC-side tooling for Ronie Pocket: the model bake-off, failure analysis, weight editing (`.rpatch`), distillation and quantization.

## Setup (Windows)

```powershell
cd tools-pc
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

The heavy ML packages (PyTorch, Transformers) are listed in `requirements-ml.txt` and installed in Phase 2. PyTorch should be the CUDA build for the RTX 3060.

## Hardware notes
- PC GPU: RTX 3060 Laptop, 6 GB VRAM. That's enough to run and edit Qwen3.5-0.8B in fp16. For 2B, use bf16 with activation caching on CPU, or run 4-bit.
- Private data (your emails for the triage eval) goes in `data/private/`, which git ignores. It never leaves this PC.
