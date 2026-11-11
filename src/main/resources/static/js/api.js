async function request(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || `${method} ${url} failed (${response.status})`);
  }
  return response.status === 204 ? null : response.json();
}

const servicePath = (name) => `/api/v1/services/${encodeURIComponent(name)}`;

export const api = {
  getConfig: () => request('GET', '/api/v1/config'),
  updateConfig: (settings) => request('PUT', '/api/v1/config', settings),
  updateSchedule: (backupInterval) => request('PUT', '/api/v1/schedule', { backupInterval }),
  listServices: () => request('GET', '/api/v1/services'),
  addService: (service) => request('POST', '/api/v1/services', service),
  updateService: (name, service) => request('PUT', servicePath(name), service),
  removeService: (name) => request('DELETE', servicePath(name)),
  setupStatus: () => request('GET', '/api/v1/setup'),
  startSetupSession: (pin) => request('POST', '/api/v1/setup/session', { pin }),
  completeSetup: () => request('POST', '/api/v1/setup/complete'),
};
