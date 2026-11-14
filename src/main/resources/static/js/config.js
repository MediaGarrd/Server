import { initServices } from './services.js';
import { initSettings } from './settings.js';
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
  initServices(document.getElementById('services'), notify);
  initFinish(document.getElementById('finish'), notify, () => {
    workspace.querySelectorAll('[data-setup]').forEach((element) => element.remove());
  });
}

unlock(document.getElementById('pinDialog')).then(start).catch((error) => {
  workspace.hidden = false;
  notify.error(error);
});
