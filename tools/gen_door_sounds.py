"""Reproduz o MinecraftSoundFX dos painéis HTML (Web Audio) em .wav -> .ogg. Rode na raiz do projeto.

Painel de senha (teclado): door_key, door_clear, door_error, door_unlock.
Painel de biometria (leitor de digital): bio_scan_0..11 (pulso de leitura, sobe de tom com o progresso), bio_fail, bio_success.
"""
import math, wave, struct, subprocess, os
SR = 44100

def wave_val(kind, ph):           # ph em ciclos (0..1)
    ph -= math.floor(ph)
    if kind == 'square':   return 1.0 if ph < 0.5 else -1.0
    if kind == 'sawtooth': return 2.0 * ph - 1.0
    if kind == 'triangle': return 4.0 * abs(ph - 0.5) - 1.0 if False else (1 - 4*abs(ph-0.5))*-1 if False else (4*ph-1 if ph < 0.5 else 3-4*ph)
    raise ValueError

def render(total, voices):
    """voices: (kind, [(t, freq)...], start, stop, gain0, ramp_end). Frequência em degraus (setValueAtTime);
    ganho: setValueAtTime(gain0, start) + exponentialRampToValueAtTime(0.001, ramp_end)."""
    n = int(total * SR)
    out = [0.0] * n
    for kind, steps, start, stop, g0, ramp_end in voices:
        phase = 0.0
        i0, i1 = int(start * SR), min(n, int(stop * SR))
        for i in range(i0, i1):
            t = i / SR
            f = steps[0][1]
            for ts, fr in steps:
                if t >= ts: f = fr
            phase += f / SR
            if t >= ramp_end: g = 0.001
            else: g = g0 * (0.001 / g0) ** ((t - start) / (ramp_end - start))
            out[i] += wave_val(kind, phase) * g
    return out

sounds = {}
# playClick
sounds['door_key'] = render(0.05, [('square', [(0, 620), (0.02, 460)], 0, 0.05, 0.08, 0.045)])
# playClear
sounds['door_clear'] = render(0.095, [('square', [(0, 280), (0.04, 190)], 0, 0.095, 0.08, 0.09)])
# playError (dois dentes de serra, 155 Hz e 115 Hz)
v = []
for i, fr in enumerate([155, 115]):
    s = i * 0.14
    v.append(('sawtooth', [(s, fr)], s, s + 0.14, 0.1, s + 0.13))
sounds['door_error'] = render(0.28, v)
# playUnlock (arpejo triangular estilo orbe de XP)
v = []
for i, fr in enumerate([587.33, 783.99, 1046.50, 1318.51]):
    s = i * 0.065
    v.append(('triangle', [(s, fr)], s, s + 0.23, 0.1, s + 0.22))
sounds['door_unlock'] = render(0.5, v)

# a escala de volume vem só dos 4 sons do teclado: assim os sons novos entram na mesma escala sem mexer nos antigos
peak = max(abs(x) for s in sounds.values() for x in s)
gain = 0.6 / peak          # mesma escala para todos: o balanço entre eles fica igual ao do painel

# ---- painel de biometria (BiometricSoundFX do HTML) ----
# playScanPulse(progress): quadrada, 340 + 520*p Hz e +90 Hz aos 25 ms; ganho 0.055 -> 0.001 em 55 ms; dura 60 ms.
# O HTML dispara um pulso a cada 135 ms (o primeiro em p = 0) durante a leitura de 1,5 s: 12 pulsos, cada um com o seu tom.
BIO_PULSES, BIO_PULSE_MS, BIO_SCAN_MS = 12, 135, 1500
for i in range(BIO_PULSES):
    p = min(1.0, i * BIO_PULSE_MS / BIO_SCAN_MS)
    base = 340 + p * 520
    sounds[f'bio_scan_{i}'] = render(0.06, [('square', [(0, base), (0.025, base + 90)], 0, 0.06, 0.055, 0.055)])
# playCancelError: dois dentes de serra (165 Hz e 115 Hz), 110 ms cada
v = []
for i, fr in enumerate([165, 115]):
    s = i * 0.11
    v.append(('sawtooth', [(s, fr)], s, s + 0.11, 0.09, s + 0.10))
sounds['bio_fail'] = render(0.22, v)
# playSuccess: arpejo triangular (ganho 0.11), mais cheio que o do teclado
v = []
for i, fr in enumerate([587.33, 783.99, 1046.50, 1318.51]):
    s = i * 0.065
    v.append(('triangle', [(s, fr)], s, s + 0.23, 0.11, s + 0.22))
sounds['bio_success'] = render(0.425, v)
outdir = 'src/main/resources/assets/vigicam/sounds'
os.makedirs(outdir, exist_ok=True)
for name, data in sounds.items():
    # microfade de 1 ms nas pontas evita estalo
    n = len(data); fade = int(0.001 * SR)
    for i in range(fade):
        data[i] *= i / fade; data[n - 1 - i] *= i / fade
    wav = f'/tmp/{name}.wav'
    with wave.open(wav, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, x * gain)) * 32767)) for x in data))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5', f'{outdir}/{name}.ogg'], check=True)
    print(name, os.path.getsize(f'{outdir}/{name}.ogg'), 'bytes')
