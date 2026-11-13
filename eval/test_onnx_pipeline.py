import os
import time
import math
os.environ["HF_HOME"] = r"R:\huggingface_cache"
import torch
import numpy as np
import onnxruntime as ort
from PIL import Image
from diffusers import SanaSprintPipeline

print("[1/5] Loading SanaSprintPipeline for prompt encoding and scheduler...")
pipe = SanaSprintPipeline.from_pretrained(
    "Efficient-Large-Model/Sana_Sprint_0.6B_1024px_diffusers",
    torch_dtype=torch.float32
)

prompt = "a photorealistic cat astronaut floating in nebula space, 8k resolution, cinematic lighting"
print(f"Encoding prompt: '{prompt}'...")
prompt_embeds, prompt_attention_mask = pipe.encode_prompt(prompt)
prompt_embeds_np = prompt_embeds.detach().to(torch.float32).cpu().numpy()
prompt_mask_np = prompt_attention_mask.detach().to(torch.int64).cpu().numpy()

guidance_val = 4.5 * pipe.transformer.config.guidance_embeds_scale # 4.5 * 0.1 = 0.45
guidance_np = np.array([guidance_val], dtype=np.float32)

print("[2/5] Creating ONNX Runtime sessions...")
sess_opts = ort.SessionOptions()
sess_opts.intra_op_num_threads = 8

dit_path = r"R:\Local Phone Ai Assistant\models-local\sana\sana_dit.onnx"
vae_path = r"R:\Local Phone Ai Assistant\models-local\sana\sana_vae.onnx"

dit_sess = ort.InferenceSession(dit_path, sess_opts)
vae_sess = ort.InferenceSession(vae_path, sess_opts)

print("[3/5] Setting up 2-step SCM scheduler timesteps...")
pipe.scheduler.set_timesteps(2)
timesteps = pipe.scheduler.timesteps[:-1] # drop final 0.0 step
print("Denoising timesteps:", timesteps)

sigma_data = pipe.scheduler.config.sigma_data # 0.5
scaling_factor = pipe.vae.config.scaling_factor # 0.41407

# Initial random noise latent [1, 32, 32, 32]
torch.manual_seed(42)
latents = torch.randn(1, 32, 32, 32, dtype=torch.float32) * sigma_data

print("[4/5] Running 2-step DiT denoising...")
t0 = time.perf_counter()
for i, t in enumerate(timesteps):
    timestep = t.expand(latents.shape[0])
    latents_model_input = latents / sigma_data

    scm_timestep = torch.sin(timestep) / (torch.cos(timestep) + torch.sin(timestep))
    scm_timestep_expanded = scm_timestep.view(-1, 1, 1, 1)
    latent_model_input = latents_model_input * torch.sqrt(
        scm_timestep_expanded**2 + (1 - scm_timestep_expanded) ** 2
    )

    inputs = {
        "latent": latent_model_input.numpy(),
        "timestep": scm_timestep.numpy(),
        "encoder_hidden_states": prompt_embeds_np,
        "encoder_attention_mask": prompt_mask_np,
        "guidance": guidance_np
    }
    
    t_step_start = time.perf_counter()
    noise_pred_np = dit_sess.run(None, inputs)[0]
    print(f"Step {i+1}/2 (t={float(t):.4f}) finished in {time.perf_counter() - t_step_start:.2f} s")
    
    noise_pred = torch.from_numpy(noise_pred_np)
    noise_pred = (
        (1 - 2 * scm_timestep_expanded) * latent_model_input
        + (1 - 2 * scm_timestep_expanded + 2 * scm_timestep_expanded**2) * noise_pred
    ) / torch.sqrt(scm_timestep_expanded**2 + (1 - scm_timestep_expanded) ** 2)
    noise_pred = noise_pred.float() * sigma_data

    # Scheduler step
    latents, denoised = pipe.scheduler.step(
        noise_pred, timestep, latents, return_dict=False
    )

print(f"DiT 2 steps finished in {time.perf_counter() - t0:.2f} s")

print("[5/5] Decoding latent with VAE...")
t_vae = time.perf_counter()
final_latents = (denoised / sigma_data) / scaling_factor
image_tensor = vae_sess.run(None, {"latent": final_latents.numpy()})[0]
print(f"VAE decode finished in {time.perf_counter() - t_vae:.2f} s")

# Denormalize [1, 3, 1024, 1024] from [-1, 1] to [0, 255]
image_np = image_tensor[0]
image_np = np.transpose(image_np, (1, 2, 0))
image_np = np.clip((image_np + 1.0) * 127.5, 0, 255).astype(np.uint8)

out_img_path = r"R:\Local Phone Ai Assistant\eval\test_onnx_sana.png"
Image.fromarray(image_np).save(out_img_path)
print(f"SUCCESS! Saved generated image to {out_img_path}!")
