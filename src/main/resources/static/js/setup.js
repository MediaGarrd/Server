import { api } from './api.js';

export async function unlock(dialog) {
  const status = await api.setupStatus();
  if (status.authorized) return;

  const form = dialog.querySelector('form');
  const error = dialog.querySelector('[data-error]');
  dialog.addEventListener('cancel', (event) => event.preventDefault());
  dialog.showModal();

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
      }
    });
  });
}

export function initFinish(root, notify, onComplete) {
  root.querySelector('button').addEventListener('click', async () => {
    if (!confirm('Finish setup? This page will be disabled and further changes must go through the API or the MediaGarrd Client.')) return;
    try {
      await api.completeSetup();
      onComplete();
      notify.ok('Setup complete. This page is now disabled, further changes go through the API or the MediaGarrd Client.');
    } catch (error) {
      notify.error(error);
    }
  });
}
