from huggingface_hub import HfApi

api = HfApi()
info = api.model_info('brad-agi/sana-0.6b-onnx-webgpu', files_metadata=True)
for f in info.siblings:
    name = f.rfilename
    if any(k in name for k in ['1024', 'text_encoder', 'config', 'tokenizer.json']):
        size_mb = (f.size or 0) / (1024 * 1024)
        print(f"{name}: {size_mb:.2f} MB")
