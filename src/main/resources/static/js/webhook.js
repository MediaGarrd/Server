import { api } from './api.js';

function describe(status) {
  return status.configured ? `Configured: ${status.target}` : 'Not configured';
}

export function initWebhook(form, notify) {
  const { url } = form.elements;
  const label = form.querySelector('[data-webhook-status]');
  const buttons = form.querySelectorAll('button');

  function show(status) {
    label.textContent = describe(status);
    url.value = '';
  }

  async function busy(task) {
    buttons.forEach((button) => { button.disabled = true; });
    try {
      await task();
    } catch (error) {
      notify.error(error);
    } finally {
      buttons.forEach((button) => { button.disabled = false; });
    }
  }

  async function save() {
    const value = url.value.trim();
    if (value) {
      show(await api.updateWebhook(value));
    }
    return value;
  }

  api.getWebhook().then((status) => { label.textContent = describe(status); }).catch(notify.error);

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    busy(async () => {
      if (await save()) {
        notify.ok('Webhook saved');
      } else {
        notify.error(new Error('Enter a webhook URL, or use Clear to turn notifications off'));
      }
    });
  });

  form.querySelector('[data-webhook-clear]').addEventListener('click', () => busy(async () => {
    show(await api.updateWebhook(''));
    notify.ok('Webhook cleared');
  }));

  form.querySelector('[data-webhook-test]').addEventListener('click', () => busy(async () => {
    await save();
    const result = await api.testWebhook();
    if (result.delivered) {
      notify.ok('Test message delivered');
    } else {
      notify.error(new Error(`Test message was not delivered: ${result.error}`));
    }
  }));
}
