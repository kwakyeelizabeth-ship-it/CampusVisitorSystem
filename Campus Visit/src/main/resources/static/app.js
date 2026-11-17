const landingView = document.querySelector('#landing-view');
const visitorView = document.querySelector('#visitor-view');
const successCard = document.querySelector('#success-card');
const adminView = document.querySelector('#admin-view');
const checkinForm = document.querySelector('#checkin-form');
const checkinButton = document.querySelector('#checkin-submit');
const contactInput = checkinForm.elements.namedItem('contact');
const purposeSelect = document.querySelector('#purpose-select');
const otherPurposeField = document.querySelector('#other-purpose-field');
const otherPurposeInput = document.querySelector('#other-purpose');
const campusRadiusMeters = 6000;
const adminLogin = document.querySelector('#admin-login');
const dashboard = document.querySelector('#dashboard');
const loginForm = document.querySelector('#admin-login-form');
const searchInput = document.querySelector('#visitor-search');
const visitorRows = document.querySelector('#visitor-rows');
const emptyState = document.querySelector('#empty-state');
const recordCount = document.querySelector('#record-count');
const locationPanel = document.querySelector('#location-panel');
const locationTitle = document.querySelector('#location-title');
const locationDetail = document.querySelector('#location-detail');
let adminAuthorization = '';
let searchTimer;
let locationRefreshTimer;
let activeTracking = null;
let verificationVisitorId = '';

function showError(element, message) {
  element.textContent = message;
  element.hidden = false;
}

function clearError(element) {
  element.textContent = '';
  element.hidden = true;
}

function showScreen(screen) {
  landingView.hidden = screen !== 'landing';
  visitorView.hidden = screen !== 'visitor';
  successCard.hidden = screen !== 'success';
  adminView.hidden = screen !== 'admin';
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function formatTime(value) {
  if (!value) return '—';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

async function submitForm(url, form) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams(new FormData(form))
  });
  const result = await response.json();
  if (!response.ok) {
    const error = new Error(result.error || 'The request could not be completed.');
    error.status = response.status;
    throw error;
  }
  return result;
}

async function submitValues(url, values) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams(values)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'The request could not be completed.');
  return result;
}

function updateOtherPurposeVisibility() {
  const isOther = purposeSelect.value === 'Other';
  otherPurposeField.hidden = !isOther;
  otherPurposeInput.required = isOther;
  if (!isOther) otherPurposeInput.value = '';
}

function validateContactNumber() {
  const digitCount = contactInput.value.replace(/[^0-9]/g, '').length;
  contactInput.setCustomValidity(
    digitCount < 7 || digitCount > 15
      ? 'Enter a phone number containing 7 to 15 digits.'
      : ''
  );
}

function showLocationStatus(title, detail, sharing, canStopSharing = sharing) {
  locationPanel.hidden = false;
  locationTitle.textContent = title;
  locationDetail.textContent = detail;
  locationPanel.classList.toggle('is-sharing', sharing);
  document.querySelector('#stop-location').hidden = !canStopSharing;
}

function campusDistance(latitude, longitude) {
  const radians = (degrees) => degrees * Math.PI / 180;
  const lat = radians(latitude - 5.9204706);
  const lng = radians(longitude - 0.0313081);
  const haversine = Math.sin(lat / 2) ** 2
    + Math.cos(radians(5.9204706)) * Math.cos(radians(latitude)) * Math.sin(lng / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
}

async function stopLocationSharing(notifyServer = true) {
  if (!activeTracking) return;
  const tracking = activeTracking;
  activeTracking = null;
  if (tracking.watchId !== null) navigator.geolocation.clearWatch(tracking.watchId);
  if (notifyServer) {
    try {
      await submitValues('/api/location', {
        visitorId: tracking.visitorId,
        trackingToken: tracking.token,
        stop: 'true'
      });
    } catch (error) {
      showLocationStatus('Location sharing stopped on this device', error.message, false);
      return;
    }
  }
  showLocationStatus('Location sharing stopped', 'Your live location is no longer shared with campus security.', false);
}

function startLocationSharing(visitor) {
  locationPanel.hidden = false;
  if (!visitor.trackingToken) {
    showLocationStatus('Location sharing is off', 'You can check in without sharing your location.', false);
    return;
  }
  if (!navigator.geolocation) {
    showLocationStatus('Location is not available', 'This browser does not support location sharing.', false);
    return;
  }

  activeTracking = {
    visitorId: visitor.visitorId,
    token: visitor.trackingToken,
    watchId: null,
    lastSentAt: 0
  };
  showLocationStatus('Waiting for location permission', 'Allow location access in your browser to share your position with campus security.', true);
  const tracking = activeTracking;
  tracking.watchId = navigator.geolocation.watchPosition(async (position) => {
    if (activeTracking !== tracking) return;
    const { latitude, longitude } = position.coords;
    if (campusDistance(latitude, longitude) > campusRadiusMeters) {
      await stopLocationSharing();
      showLocationStatus('Location sharing stopped', 'Your device is more than 6 km from Palm University College. Sharing stops beyond this range.', false);
      return;
    }
    if (Date.now() - tracking.lastSentAt < 15000) return;
    tracking.lastSentAt = Date.now();
    try {
      await submitValues('/api/location', {
        visitorId: tracking.visitorId,
        trackingToken: tracking.token,
        latitude: String(latitude),
        longitude: String(longitude)
      });
      if (activeTracking === tracking) {
        showLocationStatus('Live location shared with campus security', `Last updated ${formatTime(new Date().toISOString())}. Sharing ends when you check out or move more than 6 km from campus.`, true);
      }
    } catch (error) {
      if (!error.status || error.status >= 500) {
        showLocationStatus('Location update delayed', `${error.message} Retrying automatically.`, false, true);
        return;
      }
      await stopLocationSharing(false);
      showLocationStatus('Location sharing paused', error.message, false);
    }
  }, async (error) => {
    if (activeTracking !== tracking) return;
    await stopLocationSharing();
    const message = error.code === error.PERMISSION_DENIED
      ? 'Location permission was not granted. You can continue your visit without sharing.'
      : error.code === error.POSITION_UNAVAILABLE
        ? 'Your device could not determine its location.'
        : 'Your device took too long to determine its location.';
    showLocationStatus('Location sharing unavailable', message, false);
  }, { enableHighAccuracy: true, maximumAge: 10000, timeout: 20000 });
}

purposeSelect.addEventListener('change', updateOtherPurposeVisibility);
contactInput.addEventListener('input', validateContactNumber);
checkinForm.addEventListener('reset', () => {
  contactInput.setCustomValidity('');
  window.setTimeout(updateOtherPurposeVisibility, 0);
});

document.querySelector('#choose-visitor').addEventListener('click', () => showScreen('visitor'));
document.querySelector('#back-from-visitor').addEventListener('click', () => {
  checkinForm.reset();
  showScreen('landing');
});
document.querySelector('#back-from-admin').addEventListener('click', returnToLanding);
document.querySelector('#brand-home').addEventListener('click', (event) => {
  event.preventDefault();
  if (!successCard.hidden) return;
  returnToLanding();
});

function returnToLanding() {
  if (window.location.pathname === '/admin' || window.location.pathname === '/admin/') {
    window.history.replaceState(null, '', '/');
  }
  showScreen('landing');
}

checkinForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  validateContactNumber();
  if (!checkinForm.reportValidity()) return;
  const error = document.querySelector('#checkin-error');
  const requestedLocation = checkinForm.elements.namedItem('shareLocation').checked;
  clearError(error);
  checkinButton.disabled = true;
  checkinButton.innerHTML = 'Registering…';
  try {
    const visitor = await submitForm('/api/visitors', checkinForm);
    document.querySelector('#success-name').textContent = visitor.name;
    document.querySelector('#success-id').textContent = visitor.visitorId;
    document.querySelector('#success-time').textContent = formatTime(visitor.checkedIn);
    document.querySelector('#new-visit').hidden = false;
    showScreen('success');
    if (requestedLocation) startLocationSharing(visitor);
    else showLocationStatus('Location sharing is off', 'You can check in without sharing your location.', false);
  } catch (requestError) {
    showError(error, requestError.message);
  } finally {
    checkinButton.disabled = false;
    checkinButton.innerHTML = 'Register &amp; check in <span aria-hidden="true">→</span>';
  }
});

document.querySelector('#stop-location').addEventListener('click', () => stopLocationSharing());
document.querySelector('#new-visit').addEventListener('click', () => {
  if (activeTracking) stopLocationSharing();
  checkinForm.reset();
  locationPanel.hidden = true;
  showScreen('visitor');
});

document.querySelectorAll('.dashboard-tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    const security = tab.dataset.adminPanel === 'security';
    document.querySelectorAll('.dashboard-tab').forEach((item) => item.classList.toggle('is-active', item === tab));
    document.querySelector('#records-panel').hidden = security;
    document.querySelector('#security-panel').hidden = !security;
    if (security) {
      refreshLocations();
      document.querySelector('#verify-id').focus();
    }
  });
});

document.querySelector('#admin-logout').addEventListener('click', () => {
  adminAuthorization = '';
  window.clearInterval(locationRefreshTimer);
  locationRefreshTimer = null;
  adminLogin.hidden = false;
  dashboard.hidden = true;
  document.querySelector('#admin-logout').hidden = true;
  loginForm.reset();
  showScreen('landing');
});

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = document.querySelector('#login-error');
  const button = document.querySelector('#login-submit');
  clearError(error);
  const values = new FormData(loginForm);
  adminAuthorization = `Basic ${btoa(unescape(encodeURIComponent(`${values.get('username')}:${values.get('password')}`)))}`;
  button.disabled = true;
  try {
    if (await verifyAdmin()) {
      adminLogin.hidden = true;
      dashboard.hidden = false;
      document.querySelector('#admin-logout').hidden = false;
      await loadVisitors();
      await refreshLocations();
      window.clearInterval(locationRefreshTimer);
      locationRefreshTimer = window.setInterval(refreshLocations, 20000);
    } else {
      adminAuthorization = '';
      showError(error, 'That username or password is not correct.');
    }
  } catch (requestError) {
    adminAuthorization = '';
    showError(error, requestError.message);
  } finally {
    button.disabled = false;
  }
});

async function verifyAdmin() {
  const response = await fetch('/api/admin/session', {
    headers: { Authorization: adminAuthorization }
  });
  if (response.status === 401) return false;
  if (!response.ok) throw new Error('Could not verify the administrator sign-in.');
  return true;
}

async function loadVisitors() {
  const error = document.querySelector('#admin-error');
  clearError(error);
  const query = encodeURIComponent(searchInput.value.trim());
  try {
    const response = await fetch(`/api/admin/visitors?q=${query}`, {
      headers: { Authorization: adminAuthorization }
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Visitor records could not be loaded.');
    renderVisitors(result.visitors);
    recordCount.textContent = result.visitors.length;
  } catch (requestError) {
    showError(error, requestError.message);
  }
}

function renderVisitors(visitors) {
  visitorRows.innerHTML = visitors.map((visitor) => {
    const checkedOut = Boolean(visitor.checkedOut);
    return `<tr>
      <td class="visitor-name">${escapeHtml(visitor.name)}<small>${escapeHtml(visitor.email)}</small></td>
      <td class="visitor-id">${escapeHtml(visitor.visitorId)}</td>
      <td>${escapeHtml(visitor.contact)}</td>
      <td>${escapeHtml(visitor.purpose)}</td>
      <td>${escapeHtml(visitor.residence)}</td>
      <td>${escapeHtml(formatTime(visitor.checkedIn))}</td>
      <td>${escapeHtml(formatTime(visitor.checkedOut))}</td>
      <td><span class="id-check ${visitor.verifiedAt ? 'id-check-done' : ''}">${visitor.verifiedAt ? 'Verified' : 'Not checked'}</span></td>
      <td><span class="status ${checkedOut ? 'status-out' : 'status-in'}">${checkedOut ? 'Checked out' : 'On campus'}</span></td>
      <td>${checkedOut ? '—' : `<button class="checkout-action" type="button" data-checkout-id="${escapeHtml(visitor.visitorId)}" aria-label="Check out ${escapeHtml(visitor.name)}">Check out</button>`}</td>
    </tr>`;
  }).join('');
  emptyState.hidden = visitors.length !== 0;
}

visitorRows.addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-checkout-id]');
  if (!button || !visitorRows.contains(button)) return;
  const error = document.querySelector('#admin-error');
  clearError(error);
  button.disabled = true;
  button.textContent = 'Checking out…';
  try {
    const response = await fetch('/api/admin/checkout', {
      method: 'POST',
      headers: {
        Authorization: adminAuthorization,
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
      },
      body: new URLSearchParams({ visitorId: button.dataset.checkoutId })
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'The visitor could not be checked out.');
    await loadVisitors();
    await refreshLocations();
  } catch (requestError) {
    showError(error, requestError.message);
    button.disabled = false;
    button.textContent = 'Check out';
  }
});

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character]);
}

searchInput.addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadVisitors, 220);
});

async function refreshLocations() {
  if (!adminAuthorization || dashboard.hidden) return;
  const error = document.querySelector('#security-error');
  clearError(error);
  try {
    const response = await fetch('/api/admin/locations', {
      headers: { Authorization: adminAuthorization }
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Live locations could not be loaded.');
    document.querySelector('#active-location-count').textContent = result.locations.length;
    document.querySelector('#location-list').innerHTML = result.locations.length
      ? result.locations.map((location) => {
        const mapUrl = `https://www.google.com/maps?q=${encodeURIComponent(`${location.latitude},${location.longitude}`)}`;
        return `<article class="location-row">
          <span class="live-pulse"></span>
          <span class="location-copy"><strong>${escapeHtml(location.name)} <small>${escapeHtml(location.visitorId)}</small></strong><small>Updated ${escapeHtml(formatTime(location.updatedAt))}</small></span>
          <a class="location-map-link" href="${mapUrl}" target="_blank" rel="noopener noreferrer">Open map ↗</a>
        </article>`;
      }).join('')
      : '<p class="empty-locations">No active location shares.</p>';
  } catch (requestError) {
    showError(error, requestError.message);
  }
}

const verifyForm = document.querySelector('#verify-form');
verifyForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = document.querySelector('#verify-error');
  clearError(error);
  document.querySelector('#verification-result').hidden = true;
  const requestedId = verifyForm.elements.namedItem('visitorId').value.trim();
  try {
    const response = await fetch(`/api/admin/visitors?q=${encodeURIComponent(requestedId)}`, {
      headers: { Authorization: adminAuthorization }
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Visitor records could not be searched.');
    const visitor = result.visitors.find((item) => item.visitorId.toLowerCase() === requestedId.toLowerCase());
    if (!visitor) throw new Error('No visitor record matches that ID.');
    verificationVisitorId = visitor.visitorId;
    renderVerification(visitor);
  } catch (requestError) {
    verificationVisitorId = '';
    showError(error, requestError.message);
  }
});

function renderVerification(visitor) {
  const active = !visitor.checkedOut;
  document.querySelector('#verification-result').hidden = false;
  document.querySelector('#verify-result-title').textContent = active ? 'Visitor record found' : 'This visit is checked out';
  document.querySelector('#verify-result-subtitle').textContent = active
    ? 'Match this record to the visitor’s photo ID before granting access.'
    : 'This visitor is no longer checked in.';
  document.querySelector('#verify-result-name').textContent = visitor.name;
  document.querySelector('#verify-result-id').textContent = visitor.visitorId;
  document.querySelector('#verify-result-purpose').textContent = visitor.purpose;
  document.querySelector('#verify-result-status').textContent = active ? 'On campus' : `Checked out ${formatTime(visitor.checkedOut)}`;
  document.querySelector('#verify-result-time').textContent = formatTime(visitor.checkedIn);
  document.querySelector('#verify-result-verified').textContent = visitor.verifiedAt
    ? `Verified by ${visitor.verifiedBy} · ${formatTime(visitor.verifiedAt)}`
    : 'Not yet verified';
  document.querySelector('#physical-id-field').hidden = !active;
  const matchCheckbox = document.querySelector('#physical-id-match');
  matchCheckbox.checked = false;
  matchCheckbox.disabled = !active || Boolean(visitor.verifiedAt);
  document.querySelector('#verify-confirm').disabled = !active || Boolean(visitor.verifiedAt);
  document.querySelector('#verify-confirm').textContent = visitor.verifiedAt ? 'ID check recorded' : 'Confirm ID checked';
}

document.querySelector('#physical-id-match').addEventListener('change', (event) => {
  document.querySelector('#verify-confirm').disabled = !event.currentTarget.checked;
});

document.querySelector('#verify-confirm').addEventListener('click', async () => {
  const error = document.querySelector('#verify-error');
  clearError(error);
  try {
    const result = await submitValues('/api/admin/verify', {
      visitorId: verificationVisitorId,
      physicalIdMatched: 'true'
    });
    renderVerification(result);
    await loadVisitors();
  } catch (requestError) {
    showError(error, requestError.message);
  }
});

document.querySelector('#export-button').addEventListener('click', async () => {
  const error = document.querySelector('#admin-error');
  clearError(error);
  try {
    const response = await fetch('/api/admin/export', {
      headers: { Authorization: adminAuthorization }
    });
    if (!response.ok) {
      const result = await response.json();
      throw new Error(result.error || 'Visitor records could not be exported.');
    }
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = 'palm-visitor-records.csv';
    link.click();
    URL.revokeObjectURL(url);
  } catch (requestError) {
    showError(error, requestError.message);
  }
});

if (window.location.pathname === '/admin' || window.location.pathname === '/admin/') {
  showScreen('admin');
}
