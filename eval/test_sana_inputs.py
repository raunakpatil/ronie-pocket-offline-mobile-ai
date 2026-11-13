import os
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
from diffusers import SanaSprintPipeline

print("Loading pipeline...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)

# Intercept transformer forward call
orig_transformer_forward = pipe.transformer.forward

def intercepted_transformer_forward(*args, **kwargs):
    print("=== Transformer Input Details ===")
    print("args len:", len(args))
    for k, v in kwargs.items():
        if isinstance(v, torch.Tensor):
            print(f"  kwarg '{k}': shape={list(v.shape)}, dtype={v.dtype}")
        else:
            print(f"  kwarg '{k}': {type(v)}")
    out = orig_transformer_forward(*args, **kwargs)
    if hasattr(out, 'sample'):
        print(f"Transformer output sample: shape={list(out.sample.shape)}, dtype={out.sample.dtype}")
    return out

pipe.transformer.forward = intercepted_transformer_forward

# Intercept VAE decode call
orig_vae_decode = pipe.vae.decode

def intercepted_vae_decode(*args, **kwargs):
    print("=== VAE Decode Input Details ===")
    for i, a in enumerate(args):
        if isinstance(a, torch.Tensor):
            print(f"  arg {i}: shape={list(a.shape)}, dtype={a.dtype}")
    for k, v in kwargs.items():
        if isinstance(v, torch.Tensor):
            print(f"  kwarg '{k}': shape={list(v.shape)}, dtype={v.dtype}")
    out = orig_vae_decode(*args, **kwargs)
    if hasattr(out, 'sample'):
        print(f"VAE output sample: shape={list(out.sample.shape)}, dtype={out.sample.dtype}")
    return out

pipe.vae.decode = intercepted_vae_decode

# Run 2 steps
print("Running 2 step generation...")
img = pipe(
    prompt="a cat astronaut",
    num_inference_steps=2,
    guidance_scale=4.5,
    width=1024,
    height=1024,
    clean_caption=False
).images[0]
print("Done!")
