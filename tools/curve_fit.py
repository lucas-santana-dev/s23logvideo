"""Estima a curva efetiva do ISP: casa os quantis de luma do clipe padrão com os do clipe com curva.
Mesma cena e mesma exposição, então curva(q) ≈ Y_curva no quantil q versus Y_padrao no mesmo quantil."""
import subprocess, sys, math
import numpy as np

d = sys.argv[1]
tag = sys.argv[2]
W, H = 960, 540

def frame(name, ten):
    fmt = "gray16le" if ten else "gray"
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", "5", "-i", f"{d}/{name}_{tag}.mp4", "-frames:v", "1",
         "-vf", f"scale={W}:{H}:flags=area,format={fmt}", "-f", "rawvideo", "-"],
        capture_output=True, check=True).stdout
    a = np.frombuffer(raw, dtype=np.uint16 if ten else np.uint8).astype(np.float64)
    if ten:  # gray16 em escala cheia; volta para código de 10 bits e tira a faixa limitada (64..940)
        code = a / 65535.0 * 1023.0
        return np.clip((code - 64.0) / 876.0, 0, 1)
    return a / 255.0

# curva provisória (mesmas constantes de LogCurve.kt)
G_IN, G_OUT, B_OUT, W_OUT = 0.18, 0.41, 0.093, 0.94
target = (G_OUT - B_OUT) / (W_OUT - B_OUT)
lo, hi = -6.0, 1.0
for _ in range(100):
    mid = (lo + hi) / 2; c = 10 ** mid
    r = math.log10((G_IN + c) / c) / math.log10((1 + c) / c)
    lo, hi = (mid, hi) if r > target else (lo, mid)
c = 10 ** ((lo + hi) / 2); a = (W_OUT - B_OUT) / math.log10((1 + c) / c); dd = B_OUT - a * math.log10(c)
log_enc = lambda x: a * np.log10(np.maximum(x, 0) + c) + dd

def srgb_inv(v):
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)

def hlg_inv(v):
    a_, b_, c_ = 0.17883277, 0.28466892, 0.55991073
    return np.where(v <= 0.5, v * v / 3, (np.exp((v - c_) / a_) + b_) / 12)

for label, ten, std_inv in (("8 bits", False, srgb_inv), ("10 bits", True, hlg_inv)):
    p = frame(f"4K30_{'10bit' if ten else '8bit'}_padrao", ten)
    q = frame(f"4K30_{'10bit' if ten else '8bit'}_curva", ten)
    qs = np.linspace(0.02, 0.98, 13)
    yp, yq = np.quantile(p, qs), np.quantile(q, qs)
    print(f"\n== {label}: quantil | Y padrao | Y curva | linear estimado | esperado (LogCurve) | esperado se HAL usa entrada gama")
    for k, x, y in zip(qs, yp, yq):
        lin = float(std_inv(np.array(x)))
        print(f"  {k:4.2f} | {x:6.3f} | {y:6.3f} | {lin:6.3f} | {float(log_enc(np.array(lin))):6.3f} | {float(log_enc(np.array(x))):6.3f}")
