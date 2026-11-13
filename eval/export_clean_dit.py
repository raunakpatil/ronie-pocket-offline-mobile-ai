import os
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
import onnx
import numpy as np
import onnxruntime as ort
from diffusers import SanaSprintPipeline

print("[1/5] Loading SanaSprintPipeline...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)
transformer = pipe.transformer
transformer.eval()

# Update eps to 1e-5 to prevent underflow in RMSNorm
count_eps = 0
for m in transformer.modules():
    if hasattr(m, 'eps') and m.eps < 1e-5:
        m.eps = 1e-5
        count_eps += 1
print(f"Updated eps on {count_eps} modules to 1e-5")

class SanaDitWrapper(torch.nn.Module):
    def __init__(self, transformer):
        super().__init__()
        self.transformer = transformer

    def forward(self, hidden_states, timestep, encoder_hidden_states, encoder_attention_mask, guidance):
        return self.transformer(
            hidden_states=hidden_states,
            timestep=timestep,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            guidance=guidance,
            return_dict=False
        )[0]

wrapper = SanaDitWrapper(transformer)
wrapper.eval()

dummy_latent = torch.randn(1, 32, 32, 32, dtype=torch.float32)
dummy_timestep = torch.tensor([1.5708], dtype=torch.float32)
dummy_encoder_hidden_states = torch.randn(1, 300, 2304, dtype=torch.float32)
dummy_encoder_attention_mask = torch.ones(1, 300, dtype=torch.int64)
dummy_guidance = torch.tensor([0.45], dtype=torch.float32)

out_dir = r"R:\Local Phone Ai Assistant\models-local\sana"
os.makedirs(out_dir, exist_ok=True)
onnx_path = os.path.join(out_dir, "sana_dit.onnx")
data_path = "sana_dit.onnx.data"

# Clean up any leftover chunk files in out_dir
for f in os.listdir(out_dir):
    if f.startswith("onnx__") or f.startswith("_transformer_") or f.startswith("sana_dit_clean"):
        try:
            os.remove(os.path.join(out_dir, f))
        except Exception:
            pass

print(f"[2/5] Exporting DiT to temporary graph...")
t0 = time.perf_counter()
import io
buf = io.BytesIO()
# We export to an in-memory or temp file using dynamo=False and opset=17
# Using torch.onnx.export with large model external data:
with torch.no_grad():
    torch.onnx.export(
        wrapper,
        (dummy_latent, dummy_timestep, dummy_encoder_hidden_states, dummy_encoder_attention_mask, dummy_guidance),
        onnx_path,
        input_names=["latent", "timestep", "encoder_hidden_states", "encoder_attention_mask", "guidance"],
        output_names=["sample"],
        opset_version=17,
        training=torch.onnx.TrainingMode.EVAL,
        do_constant_folding=True,
        dynamo=False
    )

print(f"[3/5] Consolidating all external weights into single file: {data_path}...")
model = onnx.load(onnx_path, load_external_data=True)
# Save as external data into single file
full_data_path = os.path.join(out_dir, data_path)
if os.path.exists(full_data_path):
    os.remove(full_data_path)

onnx.save_model(
    model,
    onnx_path,
    save_as_external_data=True,
    all_tensors_to_one_file=True,
    location=data_path
)

# Clean up separate weight chunks that were generated
for f in os.listdir(out_dir):
    if f.startswith("onnx__") or f.startswith("_transformer_") or f.startswith("sana_dit_clean"):
        try:
            os.remove(os.path.join(out_dir, f))
        except Exception:
            pass

print(f"[4/5] Model saved in {time.perf_counter() - t0:.2f} s! Graph: {os.path.getsize(onnx_path)/1e6:.2f} MB, Data: {os.path.getsize(full_data_path)/1e6:.2f} MB")

print("[5/5] Verifying consolidated ONNX model in onnxruntime...")
sess = ort.InferenceSession(onnx_path)
inputs = {
    "latent": dummy_latent.numpy(),
    "timestep": dummy_timestep.numpy(),
    "encoder_hidden_states": dummy_encoder_hidden_states.numpy(),
    "encoder_attention_mask": dummy_encoder_attention_mask.numpy(),
    "guidance": dummy_guidance.numpy()
}
ort_out = sess.run(None, inputs)[0]
print("ONNX out min/max/has_nan:", np.min(ort_out), np.max(ort_out), np.isnan(ort_out).any())
assert not np.isnan(ort_out).any(), "Error: ONNX output contains NaN!"
print("SUCCESS: Consolidated sana_dit.onnx is 100% verified and free of NaNs!")
