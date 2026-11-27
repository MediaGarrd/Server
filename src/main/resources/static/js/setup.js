import { api } from './api.js';

function formatWait(seconds) {
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}

export async function unlock(dialog) {
  const status = await api.setupStatus();
  if (status.authorized) return;

  const form = dialog.querySelector('form');
  const error = dialog.querySelector('[data-error]');
  const unlockButton = dialog.querySelector('[data-unlock]');
  dialog.addEventListener('cancel', (event) => event.preventDefault());
  dialog.showModal();

  function lockFor(seconds) {
    let left = seconds;
    unlockButton.disabled = true;
    const tick = setInterval(() => {
      left -= 1;
      if (left <= 0) {
        clearInterval(tick);
        unlockButton.disabled = false;
        unlockButton.textContent = 'Unlock';
        return;
      }
      unlockButton.textContent = `Locked ${formatWait(left)}`;
    }, 1000);
    unlockButton.textContent = `Locked ${formatWait(left)}`;
  }

  await new Promise((resolve) => {
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      try {
        await api.startSetupSession(form.elements.pin.value);
        dialog.close();
        resolve();
      } catch (e) {
        error.textContent = e.message;
        form.reset();
        form.elements.pin.focus();
        if (e.retryAfter) lockFor(e.retryAfter);
      }
    });
  });
}

function showPairing(panel, pairingPin, notify) {
  const withPin = panel.querySelector('[data-with-pin]');
  const withoutPin = panel.querySelector('[data-without-pin]');
  withPin.hidden = !pairingPin;
  withoutPin.hidden = Boolean(pairingPin);
  if (pairingPin) {
    const code = panel.querySelector('[data-pin]');
    const button = panel.querySelector('[data-copy-pin]');
    code.textContent = pairingPin;
    button.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(pairingPin);
        button.textContent = 'Copied';
      } catch {
        window.getSelection().selectAllChildren(code);
        notify.ok('Copying is not available on this connection. The PIN is selected, press Ctrl+C or Cmd+C to copy it.');
      }
    });
  }
  panel.hidden = false;
}

export function initFinish(root, pairingPanel, notify, onComplete) {
  const button = root.querySelector('[data-finish]');
  button.addEventListener('click', async () => {
    if (button.disabled) return;
    if (!confirm('Finish setup? This page will be disabled and further changes must go through the API or the MediaGarrd Client.')) return;
    button.disabled = true;
    try {
      const result = await api.completeSetup();
      onComplete();
      notify.ok('Setup complete. This page is now disabled, further changes go through the Client.');
      showPairing(pairingPanel, result && result.pairingPin, notify);
    } catch (error) {
      button.disabled = false;
      notify.error(error);
    }
  });
}
