import os
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
from diffusers import SanaSprintPipeline

print("[1/4] Loading SanaSprintPipeline...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)
transformer = pipe.transformer
transformer.eval()

# Let's inspect the forward method and wrapper
class SanaDitWrapper(torch.nn.Module):
    def __init__(self, transformer):
        super().__init__()
        self.transformer = transformer

    def forward(self, hidden_states, timestep, encoder_hidden_states, encoder_attention_mask, guidance):
        out = self.transformer(
            hidden_states=hidden_states,
            timestep=timestep,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            guidance=guidance,
            return_dict=False
        )
        return out[0]

wrapper = SanaDitWrapper(transformer)

dummy_latent = torch.randn(1, 32, 32, 32, dtype=torch.float32)
dummy_timestep = torch.tensor([1.0], dtype=torch.float32)
dummy_encoder_hidden_states = torch.randn(1, 300, 2304, dtype=torch.float32)
dummy_encoder_attention_mask = torch.ones(1, 300, dtype=torch.int64)
dummy_guidance = torch.tensor([4.5], dtype=torch.float32)

print("[2/4] Testing forward call of wrapper...")
with torch.no_grad():
    res = wrapper(dummy_latent, dummy_timestep, dummy_encoder_hidden_states, dummy_encoder_attention_mask, dummy_guidance)
    print("Wrapper forward output shape:", res.shape, res.dtype)

out_dir = r"R:\Local Phone Ai Assistant\models-local\sana"
os.makedirs(out_dir, exist_ok=True)
onnx_path = os.path.join(out_dir, "sana_dit.onnx")

print(f"[3/4] Exporting DiT to {onnx_path}...")
t0 = time.perf_counter()
with torch.no_grad():
    torch.onnx.export(
        wrapper,
        (dummy_latent, dummy_timestep, dummy_encoder_hidden_states, dummy_encoder_attention_mask, dummy_guidance),
        onnx_path,
        input_names=["latent", "timestep", "encoder_hidden_states", "encoder_attention_mask", "guidance"],
        output_names=["sample"],
        opset_version=18,
        do_constant_folding=True
    )

print(f"[4/4] DiT exported successfully in {time.perf_counter() - t0:.2f} s!")
size_mb = os.path.getsize(onnx_path) / (1024 * 1024)
print(f"File size: {size_mb:.2f} MB")
if os.path.exists(onnx_path + ".data"):
    print(f"Data file size: {os.path.getsize(onnx_path + '.data') / (1024 * 1024):.2f} MB")
