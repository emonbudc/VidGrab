const DEFAULTS = { port: 37821, token: "", quality: 0, audio: false };
const portEl = document.getElementById("port");
const tokenEl = document.getElementById("token");
const qualityEl = document.getElementById("quality");
const audioEl = document.getElementById("audio");
const resultEl = document.getElementById("result");
const form = document.getElementById("settings");

async function load() {
  const settings = await chrome.storage.sync.get(DEFAULTS);
  portEl.value = settings.port;
  tokenEl.value = settings.token;
  qualityEl.value = String(settings.quality);
  audioEl.checked = Boolean(settings.audio);
}

function setResult(text) {
  resultEl.textContent = text;
  setTimeout(() => {
    if (resultEl.textContent === text) resultEl.textContent = "";
  }, 4000);
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const port = Number(portEl.value);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) {
    setResult("Port must be between 1024 and 65535.");
    return;
  }
  await chrome.storage.sync.set({
    port,
    token: tokenEl.value.trim(),
    quality: Number(qualityEl.value),
    audio: audioEl.checked,
  });
  setResult("Saved.");
});

document.getElementById("test").addEventListener("click", () => {
  setResult("Checking...");
  chrome.runtime.sendMessage({ type: "vidgrab:status" }, (response) => {
    if (response?.ok) {
      setResult(`Connected to VidGrab ${response.data.version || ""}`.trim());
    } else {
      setResult(response?.error || "Could not reach the app.");
    }
  });
});

load();
