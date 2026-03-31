PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS evals (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at     TEXT NOT NULL,
  resolved_at    TEXT,
  target         TEXT NOT NULL CHECK (target IN ('backend', 'frontend')),
  nrepl_port     INTEGER NOT NULL,
  ns             TEXT DEFAULT 'user',
  form           TEXT NOT NULL,
  form_original  TEXT,
  status         TEXT NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'evaluating', 'ok',
                                   'error', 'timeout', 'syntax-error')),
  value          TEXT,
  out            TEXT,
  err            TEXT,
  ex             TEXT,
  eval_ms        INTEGER,
  dump_path      TEXT,
  session_id     TEXT
);
