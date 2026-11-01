'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

function openDatabase(file) {
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS users (
      id            INTEGER PRIMARY KEY AUTOINCREMENT,
      name          TEXT NOT NULL,
      email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
      phone         TEXT,
      department    TEXT,
      job_title     TEXT,
      password_hash TEXT NOT NULL,
      role          TEXT NOT NULL DEFAULT 'employee' CHECK (role IN ('employee','admin')),
      status        TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','approved','rejected','suspended')),
      created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
      validated_at  TEXT,
      validated_by  INTEGER REFERENCES users(id) ON DELETE SET NULL,
      last_seen     TEXT
    );

    CREATE TABLE IF NOT EXISTS conversations (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      type        TEXT NOT NULL CHECK (type IN ('direct','group')),
      name        TEXT,
      direct_key  TEXT UNIQUE,
      created_by  INTEGER REFERENCES users(id) ON DELETE SET NULL,
      created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    CREATE TABLE IF NOT EXISTS conversation_members (
      conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
      user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      is_group_admin  INTEGER NOT NULL DEFAULT 0,
      last_read_id    INTEGER NOT NULL DEFAULT 0,
      joined_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
      PRIMARY KEY (conversation_id, user_id)
    );

    CREATE TABLE IF NOT EXISTS messages (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
      sender_id       INTEGER REFERENCES users(id) ON DELETE SET NULL,
      body            TEXT,
      file_path       TEXT,
      file_name       TEXT,
      file_type       TEXT,
      file_size       INTEGER,
      deleted         INTEGER NOT NULL DEFAULT 0,
      created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, id);
    CREATE INDEX IF NOT EXISTS idx_members_user ON conversation_members(user_id);
  `);
  return db;
}

function transaction(db, fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}

module.exports = { openDatabase, transaction };
