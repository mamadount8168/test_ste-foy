'use strict';

const { createApp } = require('./app');

const port = Number(process.env.PORT) || 3000;
const { server } = createApp({
  dataDir: process.env.DATA_DIR,
  secureCookies: process.env.SECURE_COOKIES === 'true',
});

server.listen(port, () => {
  console.log(`Agropama Messagerie disponible sur http://localhost:${port}`);
});
