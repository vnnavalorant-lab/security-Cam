"""Reproduz o TerminalSoundFX do "Terminal de Antena - Puzzle USB" (HTML, Web Audio) em .wav -> .ogg.
Rode na raiz do projeto (precisa de ffmpeg com libvorbis). Mesma escala de volume do gen_door_sounds.py,
então o balanço entre os sons do painel da porta e os do pendrive hacker é o do HTML.
"""
import math, wave, struct, subprocess, os
SR = 44100

# pico dos sons da porta (gen_door_sounds.py): usa o mesmo ganho de normalização (0.6 / pico) para manter a escala
DOOR_PEAK = None  # calculado abaixo


def wave_val(kind, ph):           # ph em ciclos
    ph -= math.floor(ph)
    if kind == 'square':
        return 1.0 if ph < 0.5 else -1.0
    if kind == 'sawtooth':
        return 2.0 * ph - 1.0
    if kind == 'triangle':
        return 4 * ph - 1 if ph < 0.5 else 3 - 4 * ph
    raise ValueError(kind)


def render(total, voices):
    """voices: (kind, [(t, freq)...], start, stop, gain0, ramp_end): frequência em degraus (setValueAtTime);
    ganho setValueAtTime(gain0, start) + exponentialRampToValueAtTime(0.001, ramp_end)."""
    n = int(total * SR)
    out = [0.0] * n
    for kind, steps, start, stop, g0, ramp_end in voices:
        phase = 0.0
        i0, i1 = int(start * SR), min(n, int(stop * SR))
        for i in range(i0, i1):
            t = i / SR
            f = steps[0][1]
            for ts, fr in steps:
                if t >= ts:
                    f = fr
            phase += f / SR
            g = 0.001 if t >= ramp_end else g0 * (0.001 / g0) ** ((t - start) / (ramp_end - start))
            out[i] += wave_val(kind, phase) * g
    return out


def door_sounds():
    s = {}
    s['door_key'] = render(0.05, [('square', [(0, 620), (0.02, 460)], 0, 0.05, 0.08, 0.045)])
    s['door_clear'] = render(0.095, [('square', [(0, 280), (0.04, 190)], 0, 0.095, 0.08, 0.09)])
    v = []
    for i, fr in enumerate([155, 115]):
        st = i * 0.14
        v.append(('sawtooth', [(st, fr)], st, st + 0.14, 0.1, st + 0.13))
    s['door_error'] = render(0.28, v)
    v = []
    for i, fr in enumerate([587.33, 783.99, 1046.50, 1318.51]):
        st = i * 0.065
        v.append(('triangle', [(st, fr)], st, st + 0.23, 0.1, st + 0.22))
    s['door_unlock'] = render(0.5, v)
    return s


sounds = {}
# playUsbConnect
sounds['hack_usb_connect'] = render(0.13, [('square', [(0, 180), (0.035, 440), (0.07, 720)], 0, 0.13, 0.11, 0.12)])
# playUsbEject
sounds['hack_usb_eject'] = render(0.14, [('square', [(0, 600), (0.04, 320), (0.08, 160)], 0, 0.14, 0.1, 0.13)])
# playPulseTick(stage): 360 + stage * 110 Hz
for stage in (1, 2, 3):
    sounds[f'hack_tick_{stage}'] = render(0.04, [('square', [(0, 360 + stage * 110)], 0, 0.04, 0.03, 0.035)])
# playStageLock(stage): dois quadrados (base e base * 1.25), defasados de 55 ms
for stage in (1, 2):
    base = 523.25 if stage == 1 else 659.25
    v = []
    for i, fr in enumerate([base, base * 1.25]):
        st = i * 0.055
        v.append(('square', [(st, fr)], st, st + 0.1, 0.09, st + 0.09))
    sounds[f'hack_stage_lock_{stage}'] = render(0.2, v)
# playSyncFail: dois dentes de serra (165 Hz e 110 Hz)
v = []
for i, fr in enumerate([165, 110]):
    st = i * 0.13
    v.append(('sawtooth', [(st, fr)], st, st + 0.13, 0.11, st + 0.12))
sounds['hack_sync_fail'] = render(0.27, v)
# playVictoryFanfare: arpejo triangular de 5 notas
v = []
for i, fr in enumerate([523.25, 659.25, 783.99, 1046.50, 1318.51]):
    st = i * 0.065
    v.append(('triangle', [(st, fr)], st, st + 0.25, 0.12, st + 0.24))
sounds['hack_victory'] = render(0.6, v)

DOOR_PEAK = max(abs(x) for s in door_sounds().values() for x in s)
gain = 0.6 / DOOR_PEAK
outdir = 'src/main/resources/assets/vigicam/sounds'
os.makedirs(outdir, exist_ok=True)
for name, data in sounds.items():
    n = len(data); fade = int(0.001 * SR)       # microfade de 1 ms nas pontas evita estalo
    for i in range(fade):
        data[i] *= i / fade; data[n - 1 - i] *= i / fade
    wav = f'/tmp/{name}.wav'
    with wave.open(wav, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, x * gain)) * 32767)) for x in data))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5', f'{outdir}/{name}.ogg'], check=True)
    print(name, os.path.getsize(f'{outdir}/{name}.ogg'), 'bytes')
