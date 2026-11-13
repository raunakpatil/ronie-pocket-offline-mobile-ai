import os
import sys
import shutil
from PIL import Image, ImageDraw, ImageFont

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if CURRENT_DIR not in sys.path:
    sys.path.insert(0, CURRENT_DIR)

from bakeoff_prompts import BENCHMARK_PROMPTS

ARTIFACT_DIR = r"C:\Users\Raunak Patil\.gemini\antigravity\brain\ce545612-33d4-4f74-af8a-4cf5dd348c71"

def get_font(size, bold=False):
    # Windows standard fonts
    try:
        font_name = "arialbd.ttf" if bold else "arial.ttf"
        font_path = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", font_name)
        if os.path.exists(font_path):
            return ImageFont.truetype(font_path, size)
    except Exception:
        pass
    try:
        return ImageFont.load_default()
    except Exception:
        return None

def main():
    print("=" * 60)
    print("Stitching 10 Side-by-Side Model Comparison Panels")
    print("=" * 60)

    sd_dir = os.path.join(CURRENT_DIR, "bakeoff", "sd_turbo")
    nitro_dir = os.path.join(CURRENT_DIR, "bakeoff", "nitro_e")
    sana_dir = os.path.join(CURRENT_DIR, "bakeoff", "sana_sprint")
    out_dir = os.path.join(CURRENT_DIR, "bakeoff", "composites")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(ARTIFACT_DIR, exist_ok=True)

    img_w, img_h = 512, 512
    margin_x = 24
    gutter = 16
    header_h = 100
    col_label_h = 44
    footer_h = 24
    total_w = margin_x * 2 + img_w * 3 + gutter * 2
    total_h = header_h + col_label_h + img_h + footer_h

    title_font = get_font(22, bold=True)
    prompt_font = get_font(14, bold=False)
    col_title_font = get_font(16, bold=True)
    col_sub_font = get_font(12, bold=False)

    col_meta = [
        {
            "name": "SD-Turbo (Stability AI)",
            "specs": "512×512 | 2-step SDE | 8× Latent (64×64) | 0.87B UNet",
            "dir": sd_dir,
            "border_color": (70, 130, 180)  # Steel Blue
        },
        {
            "name": "Nitro-E 0.3B (AMD)",
            "specs": "512×512 | 4-step Dist | 32× Latent (16×16) | 304M DiT",
            "dir": nitro_dir,
            "border_color": (220, 60, 60)   # Crimson / AMD Red
        },
        {
            "name": "SANA-Sprint 0.6B (ELM)",
            "specs": "1024×1024 (downsampled) | 2-step SCM | 32× Latent (32×32) | 0.6B DiT",
            "dir": sana_dir,
            "border_color": (46, 204, 113)  # Emerald Green
        }
    ]

    for item in BENCHMARK_PROMPTS:
        idx = item["id"]
        tag = item["tag"]
        title = item["title"]
        prompt = item["prompt"]

        panel = Image.new("RGB", (total_w, total_h), color=(20, 22, 28))
        draw = ImageDraw.Draw(panel)

        # Header background
        draw.rectangle([(0, 0), (total_w, header_h)], fill=(28, 32, 42))
        draw.line([(0, header_h), (total_w, header_h)], fill=(45, 52, 68), width=2)

        # Main Title
        draw.text((margin_x, 14), f"Prompt #{idx:02d}: {title}", fill=(255, 255, 255), font=title_font)

        # Prompt text (wrapped or truncated)
        prompt_display = prompt if len(prompt) < 160 else prompt[:157] + "..."
        draw.text((margin_x, 46), f'"{prompt_display}"', fill=(180, 190, 205), font=prompt_font)

        # Render 3 columns
        for c_idx, col in enumerate(col_meta):
            x_offset = margin_x + c_idx * (img_w + gutter)
            y_col_start = header_h + 10

            # Column header title
            draw.text((x_offset + 4, y_col_start), col["name"], fill=col["border_color"], font=col_title_font)
            draw.text((x_offset + 4, y_col_start + 20), col["specs"], fill=(150, 160, 175), font=col_sub_font)

            img_y = header_h + col_label_h
            img_path = os.path.join(col["dir"], f"{idx:02d}_{tag}.png")

            if os.path.exists(img_path):
                img = Image.open(img_path).convert("RGB")
                if img.size != (img_w, img_h):
                    img = img.resize((img_w, img_h), Image.Resampling.LANCZOS)
                panel.paste(img, (x_offset, img_y))
            else:
                # Placeholder box if image missing
                draw.rectangle([(x_offset, img_y), (x_offset + img_w, img_y + img_h)], fill=(35, 38, 48))
                draw.text((x_offset + 180, img_y + 240), "Generating...", fill=(120, 120, 120), font=col_title_font)

            # Border around each image
            draw.rectangle(
                [(x_offset - 1, img_y - 1), (x_offset + img_w, img_y + img_h)],
                outline=col["border_color"],
                width=2
            )

        composite_filename = f"comparison_{idx:02d}_{tag}.png"
        out_file = os.path.join(out_dir, composite_filename)
        panel.save(out_file, quality=95)

        # Also copy to brain artifact dir
        artifact_path = os.path.join(ARTIFACT_DIR, composite_filename)
        shutil.copyfile(out_file, artifact_path)
        print(f"[{idx:02d}/10] Saved composite: {composite_filename}")

    print("\nAll composite panels created successfully!")

if __name__ == "__main__":
    main()
