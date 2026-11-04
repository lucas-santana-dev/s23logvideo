#!/usr/bin/env python3
"""Gera a LUT técnica LogCam -> Rec.709 (.cube, 33^3).

A conversão é a MESMA do shader de monitoramento (gl/PreviewRenderer.kt, modo monitor709):
  1. inversa da LogCurve, por canal:   lin = 10^((y - d) / a) - c
  2. BT.2020 -> BT.709 em luz linear (matriz de linhas abaixo), max(0)
  3. rolloff suave acima de 0,8:       0.8 + 0.2 * (1 - exp(-(x - 0.8) / 0.2))
  4. OETF Rec.709:                     x < 0.018 ? 4.5x : 1.099 x^0.45 - 0.099

As constantes da curva são lidas de color/LogCurve.kt (fonte única), e `c` é resolvido por
bisseção exatamente como no `init` da LogCurve.

Fase 6: além da LUT técnica, gera uma LUT criativa opcional ("look"), que é a técnica seguida de um
contraste em S suave, split toning (sombras levemente frias, altas levemente quentes) e +8% de
saturação. A técnica é para corrigir em cima; a criativa é um ponto de partida de estilo.

Uso:  python tools/make_lut.py                 # gera as duas em luts/
      python tools/make_lut.py --look none     # só a técnica
      python tools/make_lut.py --size 65 --out luts/LogCam_to_Rec709_65.cube --look none
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


def luma709(rgb):
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]


def look_filme(rgb):
    """Look criativo sobre o Rec.709 já convertido (valores de tela, 0..1)."""
    # Contraste em S em torno do cinza 709 (0,41), com ombro suave para não estourar.
    pivot = 0.41
    out = []
    for v in rgb:
        x = pivot + (v - pivot) * 1.12
        if x > 0.85:
            x = 0.85 + 0.15 * (1.0 - math.exp(-(x - 0.85) / 0.15))
        out.append(max(0.0, x))
    # Split toning: sombras puxam para azul-esverdeado, altas para quente.
    lum = luma709(out)
    sh = (1.0 - lum) ** 2
    hi = lum ** 2
    tone = [sh * -0.015 + hi * 0.025, sh * 0.004 + hi * 0.008, sh * 0.025 + hi * -0.025]
    out = [v + t for v, t in zip(out, tone)]
    # Saturação +8% em torno da luma.
    lum = luma709(out)
    return [min(max(lum + (v - lum) * 1.08, 0.0), 1.0) for v in out]


LOOKS = {"filme": look_filme}


def write_cube(path: Path, size: int, a, c, d, k, look=None, title="LogCam to Rec709"):
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f'TITLE "{title}"',
        f"# Gerado por tools/make_lut.py a partir de color/LogCurve.kt",
        f"# GRAY_IN={k['GRAY_IN']} GRAY_OUT={k['GRAY_OUT']} BLACK_OUT={k['BLACK_OUT']} WHITE_OUT={k['WHITE_OUT']}",
        f"# y = {a:.6f}*log10(x + {c:.6f}) + {d:.6f}",
        "# Entrada: LogCam (BT.2020). Saída: Rec.709 (BT.709, gama 709) com rolloff acima de 0,8.",
        *([f"# Look criativo: {look.__name__}"] if look else []),
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
                if look:
                    out = look(out)
                lines.append(" ".join(f"{min(max(v, 0.0), 1.0):.6f}" for v in out))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", type=int, default=33)
    ap.add_argument("--out", type=Path, default=ROOT / "luts/LogCam_to_Rec709.cube")
    ap.add_argument("--look", choices=["filme", "none"], default="filme",
                    help="também gera a LUT criativa (luts/LogCam_Look_Filme.cube)")
    args = ap.parse_args()

    k = read_constants(LOGCURVE_KT)
    a, c, d = solve_curve(k)
    write_cube(args.out, args.size, a, c, d, k)
    if args.look != "none":
        look_out = args.out.with_name("LogCam_Look_Filme.cube")
        write_cube(look_out, args.size, a, c, d, k, look=LOOKS[args.look], title="LogCam Look Filme")
        print(f"escrito: {look_out} ({args.size}^3, look criativo)")
    gray = log_to_rec709([k["GRAY_OUT"]] * 3, a, c, d)[0]
    print(f"a={a:.6f} c={c:.6f} d={d:.6f}")
    print(f"cinza 18% (Log {k['GRAY_OUT']}) -> Rec.709 {gray:.3f}")
    print(f"escrito: {args.out} ({args.size}^3)")


if __name__ == "__main__":
    main()
