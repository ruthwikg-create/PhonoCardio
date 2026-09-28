import numpy as np
from main import analyze

def test_synthetic_heart_like_signal():
    fs = 4000
    t = np.arange(0, 12, 1/fs)
    x = 0.05*np.random.default_rng(4).normal(size=len(t))
    for beat in np.arange(0.5, 12, 1.0):
        i = int(beat*fs)
        width = int(0.035*fs)
        k = np.arange(max(0,i-width), min(len(t),i+width))
        x[k] += 0.8*np.exp(-((k-i)/(0.012*fs))**2)
        j = i + int(0.12*fs)
        k = np.arange(max(0,j-width), min(len(t),j+width))
        x[k] += 0.55*np.exp(-((k-j)/(0.015*fs))**2)
    result = analyze(x, fs, 50)
    assert result.beat_count > 4
    assert result.heart_rate_bpm is not None
    assert 45 <= result.heart_rate_bpm <= 75
