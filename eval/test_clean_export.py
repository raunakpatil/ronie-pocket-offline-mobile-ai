import os
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
import numpy as np
import onnxruntime as ort
from diffusers import SanaSprintPipeline

print("[1/4] Loading transformer...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)
transformer = pipe.transformer
transformer.eval()

# Check eps on all modules
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

print("[2/4] Testing PyTorch forward...")
with torch.no_grad():
    pt_out = wrapper(dummy_latent, dummy_timestep, dummy_encoder_hidden_states, dummy_encoder_attention_mask, dummy_guidance)
    print("PyTorch forward out:", pt_out.min().item(), pt_out.max().item(), "has_nan:", torch.isnan(pt_out).any().item())

onnx_path = r"R:\Local Phone Ai Assistant\models-local\sana\sana_dit_clean.onnx"
print(f"[3/4] Exporting to {onnx_path} with legacy TorchScript exporter (opset=17)...")
t0 = time.perf_counter()
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
print(f"Exported in {time.perf_counter() - t0:.2f} s")

print("[4/4] Verifying ONNX model output in onnxruntime...")
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
diff = np.max(np.abs(pt_out.numpy() - ort_out))
print(f"Max absolute difference between PyTorch and ONNX: {diff:.6f}")
