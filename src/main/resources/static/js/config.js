import { initServices } from './services.js';
import { initSettings } from './settings.js';
import { initWebhook } from './webhook.js';
import { initFinish, unlock } from './setup.js';

const workspace = document.querySelector('.workspace');
const banner = document.getElementById('banner');

function show(message, kind) {
  banner.textContent = message;
  banner.className = `banner visible ${kind}`;
}

const notify = {
  ok: (message) => show(message, 'ok'),
  error: (error) => show(error.message || String(error), 'error'),
};

function start() {
  workspace.hidden = false;
  initSettings(document.getElementById('settingsForm'), notify);
  initWebhook(document.getElementById('webhookForm'), notify);
  initServices(document.getElementById('services'), notify);
  initFinish(document.getElementById('finish'), document.getElementById('pairingResult'), notify, () => {
    workspace.querySelectorAll('[data-setup]').forEach((element) => element.remove());
  });
}

const fatal = document.getElementById('fatal');
fatal.querySelector('[data-reload]').addEventListener('click', () => location.reload());

unlock(document.getElementById('pinDialog')).then(start).catch((error) => {
  workspace.hidden = true;
  fatal.querySelector('[data-message]').textContent = `Setup could not be unlocked: ${error.message || error}`;
  fatal.hidden = false;
});
