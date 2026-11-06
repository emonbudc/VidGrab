const DEFAULTS = { port: 37821, token: "", quality: 0, audio: false };

async function getSettings() {
  const stored = await chrome.storage.sync.get(DEFAULTS);
  return { ...DEFAULTS, ...stored };
}

async function api(path, options = {}) {
  const { port, token } = await getSettings();
  if (!token) throw new Error("Set the VidGrab token in the extension options.");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, {
      ...options,
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "X-VidGrab-Token": token,
        ...(options.headers || {}),
      },
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `VidGrab returned HTTP ${response.status}`);
    return data;
  } catch (error) {
    if (error.name === "AbortError") throw new Error("VidGrab did not answer (timed out).");
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function sendDownload(payload) {
  const settings = await getSettings();
  return api("/download", {
    method: "POST",
    body: JSON.stringify({
      ...payload,
      audio: settings.audio,
      quality: settings.quality,
    }),
  });
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || typeof message.type !== "string") return;

  if (message.type === "vidgrab:download") {
    sendDownload(message.payload || {})
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message.type === "vidgrab:status") {
    api("/status")
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (message.type === "vidgrab:job") {
    api(`/jobs/${encodeURIComponent(message.id)}`)
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: "vidgrab-page",
      title: "Download this page with VidGrab",
      contexts: ["page", "frame"],
    });
    chrome.contextMenus.create({
      id: "vidgrab-media",
      title: "Download this video with VidGrab",
      contexts: ["link", "video", "audio"],
    });
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  const url = info.pageUrl || tab?.url;
  if (!url) return;
  sendDownload({
    url,
    mediaUrl: info.linkUrl || info.srcUrl,
    title: tab?.title,
  }).catch((error) => {
    console.error("VidGrab:", error);
    chrome.action.setBadgeText({ text: "!" });
    chrome.action.setBadgeBackgroundColor({ color: "#b3261e" });
  });
});
