"""Reproduz os beep() do "Controle Remoto" (HTML, Web Audio) em .wav -> .ogg.
Cada beep([f1, f2, ...]): ondas quadradas, ganho 0,04 que corta a 0 depois de 60 ms, uma nota a cada 80 ms, cada oscilador para em 70 ms.
Rode na raiz do projeto (precisa de ffmpeg com libvorbis). Usa a mesma escala de volume dos sons da porta e do pendrive.
"""
import wave, struct, subprocess, os
SR = 44100
DOOR_PEAK = 0.11887314670225838      # pico dos 4 sons do teclado (gen_door_sounds.py) -> ganho de normalização 0.6 / pico

BEEPS = {
    'remote_tab': [520, 780],                       # trocar de aba
    'remote_arm': [660],                            # primeiro clique de apagar (arma a confirmação)
    'remote_remove': [392, 262, 196],               # removido do controle
    'remote_remove_all': [392, 262, 196, 130],      # apagar todos
    'remote_ping': [880, 1320, 880, 1320],          # PING (também toca no mundo, no dispositivo)
    'remote_menu': [660, 880],                      # abrir o menu do dispositivo (duplo clique)
    'remote_door_lock': [260, 196],                 # porta trancada
    'remote_door_open': [196, 330, 520],            # porta aberta
    'remote_watch': [660, 990],                     # ver câmera
    'remote_pilot': [880, 1320],                    # pilotar drone
}


def beep(freqs):
    total = 0.08 * (len(freqs) - 1) + 0.07
    out = [0.0] * int(total * SR + 1)
    for i, f in enumerate(freqs):
        t0 = i * 0.08
        phase = 0.0
        for n in range(int(t0 * SR), min(len(out), int((t0 + 0.07) * SR))):
            t = n / SR
            phase += f / SR
            if t < t0 + 0.06:                        # setValueAtTime(.04, t0) ... setValueAtTime(0, t0 + .06)
                out[n] += (0.04 if (phase % 1.0) < 0.5 else -0.04)
    return out


gain = 0.6 / DOOR_PEAK
outdir = 'src/main/resources/assets/vigicam/sounds'
os.makedirs(outdir, exist_ok=True)
for name, freqs in BEEPS.items():
    data = beep(freqs)
    n = len(data); fade = int(0.001 * SR)
    for i in range(fade):
        data[i] *= i / fade; data[n - 1 - i] *= i / fade
    wav = f'/tmp/{name}.wav'
    with wave.open(wav, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, x * gain)) * 32767)) for x in data))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5', f'{outdir}/{name}.ogg'], check=True)
    print(name, os.path.getsize(f'{outdir}/{name}.ogg'), 'bytes')
