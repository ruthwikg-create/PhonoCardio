CREATE TABLE IF NOT EXISTS measurements (
  id BIGSERIAL PRIMARY KEY,
  heart_rate_bpm INTEGER,
  snr_db REAL,
  quality REAL NOT NULL,
  confidence REAL NOT NULL,
  duration_sec REAL,
  device_model TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
