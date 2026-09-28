import express from "express";
import cors from "cors";
import pg from "pg";
const app = express();
app.use(cors());
app.use(express.json({ limit: "1mb" }));
const pool = process.env.DATABASE_URL ? new pg.Pool({ connectionString: process.env.DATABASE_URL, ssl: { rejectUnauthorized: false } }) : null;
app.get("/health", (_req, res) => res.json({ service: "PhonoCardio API", status: "ok" }));
app.post("/api/measurements", async (req, res) => {
  const { heartRateBpm, snrDb, quality, confidence, durationSec, deviceModel } = req.body;
  if (!pool) return res.status(503).json({ error: "DATABASE_URL is not configured" });
  const result = await pool.query(
    `INSERT INTO measurements (heart_rate_bpm, snr_db, quality, confidence, duration_sec, device_model)
     VALUES ($1,$2,$3,$4,$5,$6) RETURNING id, created_at`,
    [heartRateBpm, snrDb, quality, confidence, durationSec, deviceModel]
  );
  res.status(201).json(result.rows[0]);
});
app.listen(process.env.PORT || 3000, () => console.log("PhonoCardio API listening"));
