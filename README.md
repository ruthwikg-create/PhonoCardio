# PhonoCardio

Android-based digital phonocardiography research prototype.

## Features
- Headset/inline-microphone acoustic capture.
- Real-time on-device PCM processing.
- DC removal, high-pass/low-pass filtering, adaptive noise estimation and peak detection.
- Live waveform, heart-rate estimate, signal quality, SNR and confidence.
- Node.js/PostgreSQL measurement metadata backend.

## Hardware note
The **headset microphone** is the sensor. A normal earphone speaker cannot detect heart sounds. For meaningful acoustic acquisition, use a wired headset/inline microphone or dedicated contact microphone over the precordium.

## Research roadmap
Next modules can add 50/60 Hz notch selection, adaptive noise cancellation, wavelet denoising, Shannon-energy envelope, autocorrelation/FFT rate estimation, S1/S2 segmentation, artifact rejection and signal-quality indices.

## Validation
This is a research/educational prototype. Heart-rate accuracy must be validated against a reference device before making clinical claims.

## Build
Java 17:
`gradle :app:assembleDebug`
