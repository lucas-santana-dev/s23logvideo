"""Mede a sincronia áudio-vídeo com palmas.

Grave batendo palmas na frente da câmera logo depois de começar e logo antes de parar.
O script acha as palmas no áudio (picos de energia) e, ao redor de cada uma, o quadro com
a maior mudança de imagem. Atraso = tempo do quadro − tempo do som.

O valor absoluto tem a imprecisão do método (o pico visual não é exatamente o contato
das mãos); o que importa é ficar < ~1 quadro e ser igual no início e no fim (sem deriva).

Uso: python tools/av_sync.py clip.mp4
"""
import json
import subprocess
import sys

import numpy as np


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,start_time,r_frame_rate", "-of", "json", path],
        capture_output=True, check=True, text=True).stdout
    streams = json.loads(out)["streams"]
    v = next(s for s in streams if s["codec_type"] == "video")
    a = next(s for s in streams if s["codec_type"] == "audio")
    num, den = map(int, v["r_frame_rate"].split("/"))
    return float(v["start_time"]), float(a["start_time"]), num / den


def audio_env(path, rate=8000):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-map", "0:a:0", "-ac", "1", "-ar", str(rate), "-f", "s16le", "-"],
        capture_output=True, check=True).stdout
    x = np.frombuffer(raw, dtype=np.int16).astype(np.float64)
    win = rate // 200  # janelas de 5 ms
    n = len(x) // win
    return np.sqrt((x[: n * win].reshape(n, win) ** 2).mean(axis=1)), win / rate


def video_motion(path, w=160, h=90):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-map", "0:v:0", "-vf", f"scale={w}:{h},format=gray", "-f", "rawvideo", "-"],
        capture_output=True, check=True).stdout
    f = np.frombuffer(raw, dtype=np.uint8).reshape(-1, h, w).astype(np.float64)
    d = np.abs(np.diff(f, axis=0)).mean(axis=(1, 2))
    return np.concatenate([[0.0], d])


def main(path):
    v0, a0, fps = probe(path)
    env, step = audio_env(path)
    motion = video_motion(path)
    total = len(env) * step
    print(f"fps {fps:.3f} · início vídeo {v0:.4f}s · início áudio {a0:.4f}s (A−V {1000 * (a0 - v0):+.1f} ms) · {total:.1f}s")

    results = []
    for label, lo, hi in (("início", 0.0, total / 3), ("fim", 2 * total / 3, total)):
        i0, i1 = int(lo / step), int(hi / step)
        k = i0 + int(np.argmax(env[i0:i1]))
        t_audio = a0 + k * step
        # quadro de maior mudança num raio de 0,5 s ao redor do som
        f0 = max(0, int((t_audio - v0 - 0.5) * fps))
        f1 = min(len(motion), int((t_audio - v0 + 0.5) * fps) + 1)
        j = f0 + int(np.argmax(motion[f0:f1]))
        t_video = v0 + j / fps
        delay = 1000 * (t_video - t_audio)
        results.append(delay)
        print(f"  palma {label}: som {t_audio:7.3f}s · quadro {j} ({t_video:7.3f}s) · vídeo − áudio {delay:+6.1f} ms")
    print(f"deriva (fim − início): {results[1] - results[0]:+.1f} ms · 1 quadro = {1000 / fps:.1f} ms")


if __name__ == "__main__":
    main(sys.argv[1])
