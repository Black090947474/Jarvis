import json, subprocess, wave, numpy as np, onnxruntime as ort, os, glob, sys
A = "app/src/main/assets/"
mel = ort.InferenceSession(A+"melspectrogram.onnx"); emb = ort.InferenceSession(A+"embedding_model.onnx"); ww = ort.InferenceSession(A+"hey_jarvis_v0.1.onnx")
wwin = ww.get_inputs()[0].name

def feats_and_scores(audio):
    raw = np.zeros(1760, np.float32); melbuf = np.ones((76,32), np.float32); feats=[]; scores=[]; embs=[]
    audio = np.concatenate([np.zeros(16000*2, np.int16), audio, np.zeros(16000, np.int16)])
    for i in range(0, len(audio)-1280, 1280):
        c = audio[i:i+1280].astype(np.float32)
        raw = np.concatenate([raw, c])[-1760:]
        m = mel.run(None, {"input": raw[None]})[0].squeeze()/10+2
        melbuf = np.vstack([melbuf, m])[-76:]
        e = emb.run(None, {"input_1": melbuf[None,:,:,None]})[0].reshape(96)
        feats = (feats+[e])[-16:]; embs.append(e)
        if len(feats) == 16: scores.append(float(ww.run(None, {wwin: np.array(feats, np.float32)[None]})[0].reshape(-1)[0]))
    return np.array(embs), scores

def read(path):
    w = wave.open(path); sr = w.getframerate(); x = np.frombuffer(w.readframes(w.getnframes()), np.int16)
    if sr != 16000:
        idx = np.arange(0, len(x), sr/16000); x = np.interp(idx, np.arange(len(x)), x).astype(np.int16)
    return x

def synth(text, voice, out, scale=1.0):
    subprocess.run(["piper", "-m", voice, "-f", out, "--length_scale", str(scale)], input=text.encode(), check=True, capture_output=True)

voices = sorted(glob.glob("voices/*.onnx"))
print("Stimmen:", voices)
pos_jarvis = ["Jarvis", "Jarvis.", "Jarvis?", "Jarvis, wie spät ist es?", "Jarvis, mach das Licht an."]
pos_hey = ["Hey Jarvis", "Hey Jarvis, wie wird das Wetter?"]
neg = ["Ja, wir sehen uns morgen.", "Das ist Paris.", "Der Jaguar ist schnell.", "Hast du den Service angerufen?", "Charles kommt später.",
       "Ich war gestern im Kino.", "Jawohl, das mache ich.", "Wie viel Uhr ist es?", "Mach mal Musik an.", "Gaming ist cool."]
res = {}
rng = np.random.default_rng(0)
for v in voices:
    for group, texts in [("JARVIS", pos_jarvis), ("HEY", pos_hey), ("NEG", neg)]:
        for t in texts:
            for sc in [0.9, 1.0, 1.2]:
                synth(t, v, "o.wav", sc)
                x = read("o.wav")
                noise = (rng.normal(0, 150, len(x))).astype(np.int16)
                embs, s = feats_and_scores((x + noise).astype(np.int16))
                res.setdefault(group, []).append((os.path.basename(v), t, sc, max(s)))
for g, rows in res.items():
    vals = np.array([r[3] for r in rows])
    print(f"\n== {g}: min {vals.min():.3f} median {np.median(vals):.3f} max {vals.max():.3f}")
    for r in sorted(rows, key=lambda r: -r[3])[:40]: print(f"  {r[3]:.3f}  {r[0]}  x{r[2]}  {r[1]}")
