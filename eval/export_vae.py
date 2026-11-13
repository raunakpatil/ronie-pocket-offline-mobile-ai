import os
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
from diffusers import SanaSprintPipeline

print("[1/3] Loading VAE from diffusers...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)
vae = pipe.vae
vae.eval()

# Set epsilon for numerical stability
for m in vae.modules():
    if hasattr(m, 'eps') and m.eps < 1e-6:
        m.eps = 1e-5

class VaeDecoderWrapper(torch.nn.Module):
    def __init__(self, vae):
        super().__init__()
        self.vae = vae

    def forward(self, z):
        return self.vae.decode(z, return_dict=False)[0]

wrapper = VaeDecoderWrapper(vae)
dummy_z = torch.randn(1, 32, 32, 32, dtype=torch.float32)

out_dir = r"R:\Local Phone Ai Assistant\models-local\sana"
os.makedirs(out_dir, exist_ok=True)
onnx_path = os.path.join(out_dir, "sana_vae.onnx")

print(f"[2/3] Exporting VAE decoder to {onnx_path}...")
t0 = time.perf_counter()
with torch.no_grad():
    torch.onnx.export(
        wrapper,
        dummy_z,
        onnx_path,
        input_names=["latent"],
        output_names=["image"],
        opset_version=18,
        do_constant_folding=True
    )

print(f"[3/3] VAE exported in {time.perf_counter() - t0:.2f} s")
size_mb = os.path.getsize(onnx_path) / (1024 * 1024)
print(f"File size: {size_mb:.2f} MB")
