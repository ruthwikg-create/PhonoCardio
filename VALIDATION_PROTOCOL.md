# PhonoCardio Reference Validation Protocol

This document defines the research validation workflow for PhonoCardio heart-rate estimation. It is not a clinical validation or regulatory certification.

## 1. Reference setup

Use a simultaneous reference source with a documented measurement method, such as ECG, PPG, or a validated patient monitor. Record the reference BPM and PhonoCardio BPM from the same acquisition interval.

For each paired record capture:
- timestamp
- subject/session identifier
- microphone type and placement
- reference source
- PhonoCardio BPM
- reference BPM
- signal quality and confidence
- duration
- artifact/motion notes

## 2. Required study structure

Use repeated measurements and keep tuning/development data separate from the final evaluation set. Do not tune the algorithm on the same records used for final performance reporting.

Include quiet, normal-use, and controlled motion/noise conditions. Record indeterminate measurements rather than silently removing them.

## 3. Metrics

For N paired observations:
- Absolute error = |PhonoCardio BPM - reference BPM|
- Signed error = PhonoCardio BPM - reference BPM
- MAE = mean absolute error
- RMSE = sqrt(mean(signed error^2))
- Bias = mean(signed error)
- Percent error = absolute error / reference BPM * 100
- Within-5-BPM rate = proportion with absolute error <= 5 BPM

Also report failure/indeterminate rate separately.

## 4. App implementation

The Validate screen stores paired observations locally and calculates MAE, RMSE, bias, and within-5-BPM rate. These values describe agreement with the entered reference data.

The app does not automatically label a dataset or device as clinically accurate. A clinical claim requires an appropriately designed validation study, suitable reference equipment, predefined acceptance criteria, representative data, risk analysis, and the applicable regulatory assessment.

## 5. Recommended acceptance-plan workflow

Define acceptance criteria before collecting the final evaluation set. Lock the algorithm version before final evaluation. Report confidence intervals where appropriate and retain failed/indeterminate measurements in the denominator for relevant analyses.

Public heart-sound datasets may be used for offline algorithm development and testing, subject to their licenses and intended annotations. They should not be bundled into the APK.
