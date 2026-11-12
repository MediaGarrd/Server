import { api } from './api.js';

const ISO_DURATION = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/;

function toHours(duration) {
  const [, hours = 0, minutes = 0, seconds = 0] = ISO_DURATION.exec(duration) || [];
  return Number(hours) + Number(minutes) / 60 + Number(seconds) / 3600;
}

const toDuration = (hours) => `PT${Math.round(hours * 60)}M`;

export function initSettings(form, notify) {
  const { backupLocation, intervalHours, retentionCount } = form.elements;

  function show(config) {
    backupLocation.value = config.backupLocation;
    intervalHours.value = toHours(config.backupInterval);
    retentionCount.value = config.retentionCount;
  }

  api.getConfig().then(show).catch(notify.error);

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const config = await api.updateConfig({ backupLocation: backupLocation.value, retentionCount: Number(retentionCount.value) });
      const { backupInterval } = await api.updateSchedule(toDuration(Number(intervalHours.value)));
      show({ ...config, backupInterval });
      notify.ok('Backup settings saved');
    } catch (error) {
      notify.error(error);
    }
  });
}
