#!/usr/bin/env python3
"""Gera a LUT técnica LogCam -> Rec.709 (.cube, 33^3).

A conversão é a MESMA do shader de monitoramento (gl/PreviewRenderer.kt, modo monitor709):
  1. inversa da LogCurve, por canal:   lin = 10^((y - d) / a) - c
  2. BT.2020 -> BT.709 em luz linear (matriz de linhas abaixo), max(0)
  3. rolloff suave acima de 0,8:       0.8 + 0.2 * (1 - exp(-(x - 0.8) / 0.2))
  4. OETF Rec.709:                     x < 0.018 ? 4.5x : 1.099 x^0.45 - 0.099

As constantes da curva são lidas de color/LogCurve.kt (fonte única), e `c` é resolvido por
bisseção exatamente como no `init` da LogCurve.

Uso:  python tools/make_lut.py [--size 33] [--out luts/LogCam_to_Rec709.cube]
"""
import argparse
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOGCURVE_KT = ROOT / "app/src/main/java/br/com/plusapps/s23logvideo/color/LogCurve.kt"

BT2020_TO_709 = (
    (1.6605, -0.5876, -0.0728),
    (-0.1246, 1.1329, -0.0083),
    (-0.0182, -0.1006, 1.1187),
)


def read_constants(path: Path) -> dict:
    src = path.read_text(encoding="utf-8")
    out = {}
    for name in ("GRAY_IN", "GRAY_OUT", "BLACK_OUT", "WHITE_OUT"):
        m = re.search(rf"const\s+val\s+{name}\s*=\s*([0-9.eE+-]+)", src)
        if not m:
            raise SystemExit(f"{name} não encontrado em {path}")
        out[name] = float(m.group(1))
    return out


def solve_curve(k: dict):
    """Mesma bisseção do init da LogCurve: devolve (a, c, d)."""
    g, gout, bout, wout = k["GRAY_IN"], k["GRAY_OUT"], k["BLACK_OUT"], k["WHITE_OUT"]
    target = (gout - bout) / (wout - bout)

    def ratio(cc):
        return math.log10((g + cc) / cc) / math.log10((1 + cc) / cc)

    lo, hi = -6.0, 1.0
    for _ in range(100):
        mid = (lo + hi) / 2
        if ratio(10.0 ** mid) > target:
            lo = mid
        else:
            hi = mid
    c = 10.0 ** ((lo + hi) / 2)
    a = (wout - bout) / math.log10((1 + c) / c)
    d = bout - a * math.log10(c)
    return a, c, d


def rolloff(x):
    return 0.8 + 0.2 * (1.0 - math.exp(-(x - 0.8) / 0.2)) if x > 0.8 else x


def oetf709(x):
    return 4.5 * x if x < 0.018 else 1.099 * x ** 0.45 - 0.099


def log_to_rec709(rgb, a, c, d):
    lin = [10.0 ** ((v - d) / a) - c for v in rgb]
    lin709 = [max(0.0, sum(m * v for m, v in zip(row, lin))) for row in BT2020_TO_709]
    return [oetf709(rolloff(v)) for v in lin709]


def write_cube(path: Path, size: int, a, c, d, k):
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        'TITLE "LogCam to Rec709"',
        f"# Gerado por tools/make_lut.py a partir de color/LogCurve.kt",
        f"# GRAY_IN={k['GRAY_IN']} GRAY_OUT={k['GRAY_OUT']} BLACK_OUT={k['BLACK_OUT']} WHITE_OUT={k['WHITE_OUT']}",
        f"# y = {a:.6f}*log10(x + {c:.6f}) + {d:.6f}",
        "# Entrada: LogCam (BT.2020). Saída: Rec.709 (BT.709, gama 709) com rolloff acima de 0,8.",
        f"LUT_3D_SIZE {size}",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
    ]
    n = size - 1
    # Ordem do .cube: R varia mais rápido, depois G, depois B.
    for bi in range(size):
        for gi in range(size):
            for ri in range(size):
                out = log_to_rec709((ri / n, gi / n, bi / n), a, c, d)
                lines.append(" ".join(f"{min(max(v, 0.0), 1.0):.6f}" for v in out))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", type=int, default=33)
    ap.add_argument("--out", type=Path, default=ROOT / "luts/LogCam_to_Rec709.cube")
    args = ap.parse_args()

    k = read_constants(LOGCURVE_KT)
    a, c, d = solve_curve(k)
    write_cube(args.out, args.size, a, c, d, k)
    gray = log_to_rec709([k["GRAY_OUT"]] * 3, a, c, d)[0]
    print(f"a={a:.6f} c={c:.6f} d={d:.6f}")
    print(f"cinza 18% (Log {k['GRAY_OUT']}) -> Rec.709 {gray:.3f}")
    print(f"escrito: {args.out} ({args.size}^3)")


if __name__ == "__main__":
    main()
