#!/usr/bin/env python3
"""Valida o Log com um cartão cinza 18% (Fase 6).

Pega um quadro do clipe, mede a média RGB de uma região (por padrão, o quadrado central de 10% da
imagem, onde deve estar o cartão), e mostra:
  - o valor gravado (Log), que deve ficar perto de GRAY_OUT (0,41) se a exposição estiver certa;
  - quanto isso dá em stops em relação ao cinza 18% (pela inversa da LogCurve);
  - o valor depois da LUT técnica, que deve ficar perto de 0,409 (o cinza 18% em Rec.709),
    ou seja, no meio do waveform;
  - o desvio entre R, G e B (cartão neutro + WB certo = canais iguais).

O quadro é convertido como o editor faz: YUV de faixa limitada, matriz BT.2020, para RGB 0..1.

Uso:
  python tools/validar_cinza.py clipe.mp4 [--t 2.0] [--roi x,y,w,h]   (roi em fração: 0.45,0.45,0.1,0.1)
Requer ffmpeg no PATH e numpy.
"""
import argparse
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_lut  # noqa: E402  (mesma conta da LUT e do shader)


def probe_size(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True,
    ).stdout.strip().split(",")
    return int(out[0]), int(out[1])


def grab_frame(path, t, w, h):
    """Um quadro como RGB float 0..1 (rgb48le), interpretando o YUV como BT.2020 de faixa limitada."""
    vf = f"scale=in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=accurate_rnd+full_chroma_int,format=rgb48le"
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", str(t), "-i", str(path), "-frames:v", "1", "-vf", vf,
         "-f", "rawvideo", "-pix_fmt", "rgb48le", "-"],
        capture_output=True, check=True,
    ).stdout
    arr = np.frombuffer(raw, dtype="<u2")
    if arr.size != w * h * 3:
        raise SystemExit(f"quadro com tamanho inesperado ({arr.size} valores para {w}x{h})")
    return arr.reshape(h, w, 3).astype(np.float64) / 65535.0


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clip", type=Path)
    ap.add_argument("--t", type=float, default=1.0, help="instante do quadro, em segundos")
    ap.add_argument("--roi", default="0.45,0.45,0.1,0.1", help="x,y,w,h em fração da imagem")
    args = ap.parse_args()

    k = make_lut.read_constants(make_lut.LOGCURVE_KT)
    a, c, d = make_lut.solve_curve(k)
    w, h = probe_size(args.clip)
    img = grab_frame(args.clip, args.t, w, h)
    rx, ry, rw, rh = (float(v) for v in args.roi.split(","))
    x0, y0 = int(rx * w), int(ry * h)
    x1, y1 = max(x0 + 1, int((rx + rw) * w)), max(y0 + 1, int((ry + rh) * h))
    patch = img[y0:y1, x0:x1].reshape(-1, 3)
    rgb = patch.mean(axis=0)
    spread = patch.std(axis=0).mean()

    y_log = float(0.2627 * rgb[0] + 0.6780 * rgb[1] + 0.0593 * rgb[2])
    lin = 10.0 ** ((y_log - d) / a) - c
    stops = math.log2(max(lin, 1e-6) / k["GRAY_IN"])
    out709 = make_lut.log_to_rec709(list(rgb), a, c, d)
    y709 = make_lut.luma709(out709)
    chroma = (max(rgb) - min(rgb)) / max(y_log, 1e-6)

    print(f"clipe: {args.clip.name}  {w}x{h}  t={args.t}s  região {x1 - x0}x{y1 - y0} px")
    print(f"Log gravado   R {rgb[0]:.3f}  G {rgb[1]:.3f}  B {rgb[2]:.3f}   luma {y_log:.3f}  "
          f"(alvo {k['GRAY_OUT']:.3f})   ruído na região {spread:.4f}")
    print(f"exposição     {stops:+.2f} stops em relação ao cinza 18%")
    print(f"depois da LUT R {out709[0]:.3f}  G {out709[1]:.3f}  B {out709[2]:.3f}   luma {y709:.3f}  (alvo 0.409)")
    print(f"neutralidade  diferença entre canais = {chroma * 100:.1f}% da luma")
    ok_exp = abs(stops) <= 1 / 3
    ok_wb = chroma <= 0.05
    print("veredito      " + ("exposição OK" if ok_exp else "exposição fora de ±1/3 stop")
          + " · " + ("WB neutro" if ok_wb else "WB puxando cor (ou o cartão não é neutro)"))
    sys.exit(0 if ok_exp and ok_wb else 1)


if __name__ == "__main__":
    main()
