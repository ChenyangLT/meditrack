#!/usr/bin/env python3
"""Synthesises the five built-in reminder tones into app/src/main/res/raw/.

Why generate instead of shipping downloaded audio: ringtones pulled from a device or the web carry
licences this project cannot grant. These are computed from scratch, so the app can ship them under its
own MIT licence, and they are tiny (OGG, ~20 KB each).

Why bundled tones at all: the previous channel used the phone's own alarm ringtone
(content://settings/system/alarm_alert). On MIUI/HyperOS that URI can be silently replaced or muted by
the per-app notification style, so the reminder arrived with vibration but no audible tone. A tone that
ships inside the APK cannot be taken away.

Run from the repo root:

    python tools/generate_tones.py

Writes app/src/main/res/raw/tone_<n>_<slug>.ogg for n = 1..5.
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44100
OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"


def envelope(length: int, attack: float, decay: float) -> np.ndarray:
    """Percussive envelope: fast attack, exponential decay."""
    t = np.arange(length) / RATE
    attack_part = np.clip(t / max(attack, 1e-4), 0.0, 1.0)
    return attack_part * np.exp(-t / max(decay, 1e-4))


def tone(freq: float, length: int, *, attack=0.004, decay=0.35, partials=((1.0, 1.0), (2.0, 0.35), (3.0, 0.15))) -> np.ndarray:
    """A struck-tone note: a few harmonics under a percussive envelope."""
    t = np.arange(length) / RATE
    wave_sum = np.zeros(length)
    for ratio, amplitude in partials:
        wave_sum += amplitude * np.sin(2 * np.pi * freq * ratio * t)
    return wave_sum * envelope(length, attack, decay)


def silence(seconds: float) -> np.ndarray:
    return np.zeros(int(RATE * seconds))


def pad(signal: np.ndarray, seconds: float, *, at_start=False) -> np.ndarray:
    block = silence(seconds)
    return np.concatenate([block, signal]) if at_start else np.concatenate([signal, block])


def bright_bell() -> np.ndarray:
    """Two bright strikes a fifth apart - cuts through a phone speaker."""
    strike = lambda f: tone(f, int(RATE * 1.1), attack=0.002, decay=0.42,
                            partials=((1.0, 1.0), (2.76, 0.5), (5.4, 0.22), (8.9, 0.1)))
    return np.concatenate([strike(1318.5), pad(strike(1975.5), 0.35)])


def rising_three() -> np.ndarray:
    """C5 - E5 - G5, marimba-ish: reads as 'something is due' without alarm panic."""
    notes = [tone(f, int(RATE * 0.42), decay=0.22, partials=((1.0, 1.0), (4.0, 0.25))) for f in (523.25, 659.25, 783.99)]
    return np.concatenate([notes[0], pad(notes[1], 0.02), pad(notes[2], 0.02), silence(0.25)])


def soft_marimba() -> np.ndarray:
    """Warm and low - for people who find bright tones harsh."""
    a = tone(392.0, int(RATE * 0.7), attack=0.008, decay=0.3, partials=((1.0, 1.0), (3.0, 0.3), (5.0, 0.1)))
    b = tone(523.25, int(RATE * 0.9), attack=0.008, decay=0.38, partials=((1.0, 1.0), (3.0, 0.3), (5.0, 0.1)))
    return np.concatenate([a, pad(b, 0.3)])


def double_beep() -> np.ndarray:
    """Two crisp beeps: the least missable option, still short."""
    beep = lambda: tone(880.0, int(RATE * 0.16), attack=0.003, decay=0.14,
                        partials=((1.0, 1.0), (3.0, 0.18)))
    return np.concatenate([beep(), silence(0.12), beep(), silence(0.3)])


def rising_chime() -> np.ndarray:
    """A slow swell with shimmer - gentle, but obviously a notification."""
    length = int(RATE * 2.0)
    t = np.arange(length) / RATE
    swell = np.minimum(t / 0.35, 1.0) * np.exp(-t / 0.9)
    base = np.sin(2 * np.pi * 659.25 * t) + 0.4 * np.sin(2 * np.pi * 987.77 * t)
    shimmer = 1.0 + 0.12 * np.sin(2 * np.pi * 6.0 * t)
    return base * shimmer * swell


TONES = [
    ("tone_1_bell", bright_bell),
    ("tone_2_rising", rising_three),
    ("tone_3_marimba", soft_marimba),
    ("tone_4_beep", double_beep),
    ("tone_5_chime", rising_chime),
]


def write_ogg(name: str, signal: np.ndarray, workdir: pathlib.Path) -> pathlib.Path:
    peak = float(np.max(np.abs(signal))) or 1.0
    normalised = signal / peak * 0.89            # ~ -1 dBFS, leaves headroom for the harmonics
    fade = int(RATE * 0.012)                     # 12 ms fades: no click at either end
    normalised[:fade] *= np.linspace(0, 1, fade)
    normalised[-fade:] *= np.linspace(1, 0, fade)

    wav_path = workdir / (name + ".wav")
    data = (normalised * 32767).astype("<i2")
    with wave.open(str(wav_path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(RATE)
        handle.writeframes(data.tobytes())

    ogg_path = OUT_DIR / (name + ".ogg")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path),
         "-c:a", "libvorbis", "-q:a", "3", "-ar", "44100", "-ac", "1", str(ogg_path)],
        check=True,
    )
    return ogg_path


def main() -> int:
    if shutil.which("ffmpeg") is None:
        print("ffmpeg not found on PATH", file=sys.stderr)
        return 2
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        workdir = pathlib.Path(tmp)
        for name, build in TONES:
            path = write_ogg(name, build(), workdir)
            seconds = 0.0
            with wave.open(str(workdir / (name + ".wav"))) as handle:
                seconds = handle.getnframes() / handle.getframerate()
            print("%-18s %5.2f s  %6.1f KB" % (name + ".ogg", seconds, path.stat().st_size / 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
