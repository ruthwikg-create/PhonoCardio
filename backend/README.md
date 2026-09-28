# PhonoCardio DSP Backend

The backend performs research-grade signal processing for uploaded acoustic cardiac recordings.

Pipeline:
1. Robust DC removal
2. 20–180 Hz Butterworth band-pass
3. 50/60 Hz configurable notch
4. Bounded adaptive LMS noise suppression
5. Symlet-8 wavelet denoising
6. Robust normalization
7. Shannon-energy envelope
8. Peak detection with physiological refractory period
9. Autocorrelation-based rate estimation
10. Independent rate-estimator agreement
11. S1/S2 candidate pairing
12. SNR, clipping/flatline artifact fraction and signal-quality index
13. Confidence and explicit measurement-quality status

This backend does not diagnose disease. Its outputs are measurement/quality features that require validation against reference phonocardiography, ECG and/or a validated heart-rate reference before clinical use.
