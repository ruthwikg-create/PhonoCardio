from __future__ import annotations

from typing import List, Optional, Literal
import numpy as np
import pywt
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from scipy import signal

app = FastAPI(title="PhonoCardio Signal Processing API", version="0.2.0")


class SignalRequest(BaseModel):
    samples: List[float] = Field(min_length=256, max_length=2_000_000)
    sample_rate: int = Field(default=16000, ge=4000, le=96000)
    mains_hz: Literal[50, 60] = 50


class AnalysisResponse(BaseModel):
    sample_rate: int
    duration_sec: float
    heart_rate_bpm: Optional[float]
    beat_count: int
    s1_count: int
    s2_count: int
    snr_db: float
    signal_quality: float
    confidence: float
    dominant_band_hz: float
    artifact_fraction: float
    processing_chain: list[str]
    status: str


def sos_filter(x, sos):
    return signal.sosfiltfilt(sos, x) if len(x) > 30 else signal.sosfilt(sos, x)


def adaptive_lms(x: np.ndarray, order: int = 16, mu: float = 0.003) -> np.ndarray:
    # Self-referenced conservative LMS noise suppression. It is intentionally
    # bounded so the algorithm cannot amplify unstable microphone noise.
    if len(x) < order + 2:
        return x
    ref = np.concatenate(([0.0], np.diff(x)))
    w = np.zeros(order)
    y = np.zeros_like(x)
    for n in range(order, len(x)):
        u = ref[n-order:n][::-1]
        estimate = float(np.dot(w, u))
        e = x[n] - estimate
        norm = float(np.dot(u, u)) + 1e-8
        w += (mu / norm) * e * u
        y[n] = e
    return np.clip(y, np.percentile(x, 0.1), np.percentile(x, 99.9))


def wavelet_denoise(x: np.ndarray) -> np.ndarray:
    coeffs = pywt.wavedec(x, "sym8", mode="symmetric", level=min(6, pywt.dwt_max_level(len(x), pywt.Wavelet("sym8").dec_len)))
    sigma = np.median(np.abs(coeffs[-1] - np.median(coeffs[-1]))) / 0.6745 + 1e-9
    threshold = sigma * np.sqrt(2 * np.log(max(len(x), 2)))
    denoised = [coeffs[0]] + [pywt.threshold(c, threshold, mode="soft") for c in coeffs[1:]]
    return pywt.waverec(denoised, "sym8", mode="symmetric")[:len(x)]


def preprocess(x: np.ndarray, fs: int, mains: int) -> tuple[np.ndarray, list[str]]:
    x = x.astype(np.float64)
    x -= np.median(x)
    sos = signal.butter(4, [20, 180], btype="bandpass", fs=fs, output="sos")
    y = sos_filter(x, sos)
    b, a = signal.iirnotch(mains, Q=30, fs=fs)
    y = signal.filtfilt(b, a, y)
    y = adaptive_lms(y)
    y = wavelet_denoise(y)
    y = signal.detrend(y, type="constant")
    scale = np.percentile(np.abs(y), 99.5)
    if scale > 1e-9:
        y = y / scale
    return y, [
        "median DC removal",
        "4th-order 20-180 Hz Butterworth band-pass",
        f"{mains} Hz Q=30 notch",
        "bounded adaptive LMS suppression",
        "Symlet-8 wavelet soft-threshold denoising",
        "robust amplitude normalization",
    ]


def shannon_envelope(x: np.ndarray, fs: int) -> np.ndarray:
    energy = -(x * x) * np.log(np.maximum(x * x, 1e-12))
    smooth = max(3, int(0.025 * fs) | 1)
    return signal.savgol_filter(energy, smooth, 2, mode="interp")


def estimate_sqi(raw: np.ndarray, clean: np.ndarray, fs: int) -> tuple[float, float, float]:
    residual = raw - clean
    snr = 10 * np.log10((np.mean(clean * clean) + 1e-12) / (np.mean(residual * residual) + 1e-12))
    clipping = np.mean(np.abs(raw) >= np.percentile(np.abs(raw), 99.9) * 1.001)
    flat = np.mean(np.abs(np.diff(raw)) < 1e-8)
    artifact = np.clip(0.65 * clipping + 0.35 * flat, 0, 1)
    quality = np.clip((snr + 5) / 30, 0, 1) * (1 - artifact)
    return float(snr), float(quality), float(artifact)


def analyze(x: np.ndarray, fs: int, mains: int) -> AnalysisResponse:
    clean, chain = preprocess(x, fs, mains)
    env = shannon_envelope(clean, fs)
    env = np.maximum(env - np.percentile(env, 20), 0)

    min_distance = int(fs * 0.30)
    prominence = max(float(np.std(env) * 0.35), float(np.percentile(env, 70) * 0.08))
    peaks, props = signal.find_peaks(env, distance=min_distance, prominence=prominence)

    # Beat candidates are paired S1/S2 events. Use an autocorrelation period
    # estimate as a second independent rate estimator.
    ac = signal.fftconvolve(env - np.mean(env), (env - np.mean(env))[::-1], mode="full")
    ac = ac[len(ac)//2:]
    lo, hi = int(fs * 0.30), min(len(ac)-1, int(fs * 2.0))
    lag = lo + int(np.argmax(ac[lo:hi])) if hi > lo else 0
    autocorr_bpm = 60 * fs / lag if lag > 0 else None

    peak_intervals = np.diff(peaks) / fs
    valid_intervals = peak_intervals[(peak_intervals >= 0.30) & (peak_intervals <= 2.0)]
    peak_bpm = 60 / float(np.median(valid_intervals)) if len(valid_intervals) else None

    bpm = None
    if peak_bpm and autocorr_bpm:
        if abs(peak_bpm - autocorr_bpm) <= 12:
            bpm = float(np.median([peak_bpm, autocorr_bpm]))
        else:
            bpm = float(peak_bpm)
    elif peak_bpm:
        bpm = float(peak_bpm)
    elif autocorr_bpm:
        bpm = float(autocorr_bpm)

    # S1/S2 pairing: within each cycle, two dominant envelope events are
    # reported only when their temporal separation is physiologically plausible.
    s1 = []
    s2 = []
    for a, b in zip(peaks[:-1], peaks[1:]):
        gap = (b - a) / fs
        if 0.05 <= gap <= 0.45:
            if env[a] >= env[b]:
                s1.append(a); s2.append(b)
            else:
                s1.append(b); s2.append(a)

    snr, quality, artifact = estimate_sqi(x, clean, fs)
    agreement = 0.0 if not (peak_bpm and autocorr_bpm) else max(0.0, 1 - abs(peak_bpm-autocorr_bpm)/20)
    confidence = float(np.clip(0.55 * quality + 0.30 * agreement + 0.15 * min(len(peaks)/6, 1), 0, 1))

    freqs, psd = signal.welch(clean, fs=fs, nperseg=min(len(clean), fs*4))
    band = (freqs >= 20) & (freqs <= 180)
    dominant = float(freqs[band][np.argmax(psd[band])]) if np.any(band) else 0.0

    status = "VALID_RESEARCH_MEASUREMENT" if quality >= 0.55 and confidence >= 0.60 else "LOW_SIGNAL_QUALITY"

    return AnalysisResponse(
        sample_rate=fs,
        duration_sec=len(x)/fs,
        heart_rate_bpm=round(bpm, 1) if bpm else None,
        beat_count=len(peaks),
        s1_count=len(s1),
        s2_count=len(s2),
        snr_db=round(snr, 2),
        signal_quality=round(quality, 3),
        confidence=round(confidence, 3),
        dominant_band_hz=round(dominant, 2),
        artifact_fraction=round(artifact, 4),
        processing_chain=chain,
        status=status,
    )


@app.get("/health")
def health():
    return {"service": "PhonoCardio DSP", "status": "ok", "engine": "scipy-pywavelets"}


@app.post("/v1/analyze", response_model=AnalysisResponse)
def analyze_signal(req: SignalRequest):
    try:
        x = np.asarray(req.samples, dtype=np.float64)
        return analyze(x, req.sample_rate, req.mains_hz)
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"Signal processing failed: {exc}")
