import os
import time
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
import onnx
from diffusers import SanaSprintPipeline

print("[1/4] Loading SanaSprintPipeline text_encoder...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)
text_encoder = pipe.text_encoder
text_encoder.eval()

class TextEncoderWrapper(torch.nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder

    def forward(self, input_ids, attention_mask):
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        return out[0]

wrapper = TextEncoderWrapper(text_encoder)
wrapper.eval()

dummy_ids = torch.ones(1, 300, dtype=torch.int64)
dummy_mask = torch.ones(1, 300, dtype=torch.int64)

out_dir = r"R:\Local Phone Ai Assistant\models-local\sana"
os.makedirs(out_dir, exist_ok=True)
onnx_path = os.path.join(out_dir, "sana_text_encoder.onnx")
data_path = "sana_text_encoder.onnx.data"

print(f"[2/4] Testing PyTorch forward...")
with torch.no_grad():
    res = wrapper(dummy_ids, dummy_mask)
    print("PyTorch forward out:", res.shape, res.dtype)

print(f"[3/4] Exporting text encoder to ONNX...")
t0 = time.perf_counter()
with torch.no_grad():
    torch.onnx.export(
        wrapper,
        (dummy_ids, dummy_mask),
        onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["last_hidden_state"],
        opset_version=17,
        training=torch.onnx.TrainingMode.EVAL,
        do_constant_folding=True,
        dynamo=False
    )

print(f"[4/4] Consolidating external data...")
model = onnx.load(onnx_path, load_external_data=True)
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

# Clean up separate chunks
for f in os.listdir(out_dir):
    if f.startswith("onnx__") or f.startswith("_encoder_"):
        try:
            os.remove(os.path.join(out_dir, f))
        except Exception:
            pass

print(f"Exported text encoder in {time.perf_counter() - t0:.2f} s!")
print(f"Graph: {os.path.getsize(onnx_path)/1e6:.2f} MB, Data: {os.path.getsize(full_data_path)/1e6:.2f} MB")
