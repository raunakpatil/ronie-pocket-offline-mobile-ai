import os
import numpy as np
from PIL import Image

sd_dir = r"eval/bakeoff/sd_turbo"
nitro_dir = r"eval/bakeoff/nitro_e"
sana_dir = r"eval/bakeoff/sana_sprint"

def get_stats(img_path):
    img = Image.open(img_path).convert("L")
    if img.size != (512, 512):
        img = img.resize((512, 512), Image.Resampling.LANCZOS)
    arr = np.array(img, dtype=np.float32)
    gy, gx = np.gradient(arr)
    gnorm = np.sqrt(gx**2 + gy**2)
    sharpness = float(np.var(gnorm))
    std = float(np.std(arr))
    return sharpness, std

def main():
    print(f"{'Prompt':<30} | {'SD-Turbo (Sharp/Std)':<22} | {'Nitro-E (Sharp/Std)':<22} | {'SANA-Sprint (Sharp/Std)':<24}")
    print("-" * 105)
    for f in sorted(os.listdir(sd_dir)):
        p_sd = os.path.join(sd_dir, f)
        p_ne = os.path.join(nitro_dir, f)
        p_sa = os.path.join(sana_dir, f)
        s_sd, std_sd = get_stats(p_sd)
        s_ne, std_ne = get_stats(p_ne)
        if os.path.exists(p_sa):
            s_sa, std_sa = get_stats(p_sa)
            sa_str = f"{s_sa:7.1f} / {std_sa:5.1f}"
        else:
            sa_str = "  Generating...    "
        print(f"{f:<30} | {s_sd:7.1f} / {std_sd:5.1f}        | {s_ne:7.1f} / {std_ne:5.1f}        | {sa_str}")

if __name__ == "__main__":
    main()
