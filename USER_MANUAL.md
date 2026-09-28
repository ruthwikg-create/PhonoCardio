# PhonoCardio User Manual

PhonoCardio is an Android phonocardiography research application. It captures cardiac acoustic signals through a microphone, processes them continuously, displays a live waveform, detects candidate beats, and estimates heart rate and signal quality.

This is a research/prototyping system, not a certified diagnostic device.

## The headset speaker is NOT the sensor
The earphone speaker produces sound. The inline headset microphone is the sensor.

Use a wired headset with an inline microphone, or a suitable contact/auscultation microphone connected as an Android audio input. Do not press the earbud speaker against the chest expecting it to measure heart sounds.

## Microphone placement
Place the microphone capsule gently against the chest over a precordial area where cardiac sounds are audible. Start near the left lower sternal/precordial region. If your hardware permits, experiments can also use standard auscultation areas: aortic, pulmonic, tricuspid and mitral/apical.

The optimal position depends on the microphone, coupling, clothing, anatomy and environment. Do not insert anything into the body or apply excessive pressure.

## Before starting
1. Connect the headset with its microphone.
2. Keep the phone stationary.
3. Avoid touching or rubbing the microphone cable.
4. Use a quiet room.
5. Give the microphone gentle, stable chest contact.
6. Open PhonoCardio.
7. Grant microphone permission.
8. Confirm MICROPHONE INPUT CONNECTED.

## Running a measurement
1. Press START LIVE MEASUREMENT.
2. The app enters ACQUIRING.
3. Keep the microphone still for several seconds.
4. Watch the moving acoustic waveform.
5. When repeated events have adequate quality, the status can become LIVE_MEASUREMENT.
6. Heart rate appears only after enough beat intervals are accumulated.
7. R-R is the latest accepted beat interval.
8. QUALITY is the current signal-quality estimate.
9. CONFIDENCE reflects recent beat consistency and signal quality.
10. Press STOP LIVE MEASUREMENT to finish.

If the signal is weak or unstable, the app can remain in ACQUIRING, LOW_CONFIDENCE or POOR_SIGNAL. A poor signal is not evidence of a cardiac abnormality.

## Signal-processing path
Android performs continuous DC/baseline tracking, cardiac-band filtering, adaptive noise-floor estimation, Shannon-energy-style envelope extraction, adaptive event thresholding, physiological refractory gating, beat-to-beat interval tracking, robust median heart-rate estimation, clipping/low-signal checks and confidence/status gating.

The Python backend provides a separate research analysis path with Butterworth filtering, mains rejection, adaptive suppression, wavelet denoising, Shannon energy, autocorrelation and estimator agreement.

## Validation datasets
Public heart-sound datasets are engineering/validation assets only. They are not bundled into the APK, shown in the application, or made available through user download controls. The validation workflow stores them only in CI storage and does not upload them as workflow artifacts.

Planned sources include CirCor DigiScope, PhysioNet/CinC Challenge 2016, and PASCAL CHSC 2011 resources where licensing permits.

## Safety and intended use
Do not use PhonoCardio results to diagnose disease, change medication, or make emergency decisions.

Clinical/commercial claims require documented hardware characterization, algorithm verification, validation against suitable reference equipment, usability testing, risk management, cybersecurity controls and the applicable regulatory assessment.

## Troubleshooting
### Headset not detected
Reconnect the headset and verify that the phone recognizes its microphone input. Some phones route audio through the built-in microphone depending on the connector or adapter.

### Waveform is flat
Check microphone contact, input routing, microphone gain and whether the headset actually has a microphone.

### Waveform is noisy
Move to a quieter environment, immobilize the cable and gently reposition the microphone.

### Heart rate stays at --
Wait for several cardiac events. The application deliberately withholds a value until enough intervals are available.

## Research validation
Use repeated measurements, standardized microphone placement, a simultaneous reference HR source when available, noise/movement labels, acquisition metadata, subject-independent train/tune/test splits, and report both errors and indeterminate/failure rates.
