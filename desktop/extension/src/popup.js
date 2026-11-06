const statusEl = document.getElementById("status");
const hintEl = document.getElementById("hint");
const mediaEl = document.getElementById("media");
const versionEl = document.getElementById("version");

let pageUrl = "";
let pageTitle = "";

function setStatus(text, state) {
  statusEl.textContent = text;
  statusEl.className = "status" + (state ? " " + state : "");
}

function refreshStatus() {
  chrome.runtime.sendMessage({ type: "vidgrab:status" }, (response) => {
    if (response?.ok) setStatus("app connected", "ok");
    else setStatus("app offline", "bad");
  });
}

function render(scan) {
  pageUrl = scan.pageUrl || pageUrl;
  pageTitle = scan.title || pageTitle;
  mediaEl.textContent = "";

  const items = scan.media || [];
  if (!items.length) {
    hintEl.textContent = "No media detected on this page.";
    return;
  }
  hintEl.textContent = "";

  for (const item of items.slice(0, 12)) {
    const li = document.createElement("li");
    const meta = document.createElement("div");
    meta.className = "meta";

    const label = document.createElement("div");
    label.className = "label";
    label.textContent = item.kind === "page"
      ? "this page"
      : item.height
        ? item.height + "p"
        : item.kind || "media";

    const title = document.createElement("div");
    title.className = "title";
    title.textContent = item.title || item.url;

    meta.append(label, title);

    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Download";
    button.addEventListener("click", () => send(item, button));

    li.append(meta, button);
    mediaEl.append(li);
  }
}

function send(item, button) {
  button.disabled = true;
  button.textContent = "Sending...";
  chrome.runtime.sendMessage(
    {
      type: "vidgrab:download",
      payload: {
        url: pageUrl,
        mediaUrl: item.url === pageUrl ? undefined : item.url,
        title: item.title || pageTitle,
      },
    },
    (response) => {
      if (response?.ok) {
        button.textContent = "Queued";
        setStatus("job " + response.data.id + ": queued", "ok");
        poll(response.data.id);
      } else {
        button.disabled = false;
        button.textContent = "Retry";
        hintEl.textContent = response?.error || "Could not reach the VidGrab app.";
        setStatus("app offline", "bad");
      }
    },
  );
}

function poll(id) {
  if (!id) return;
  const started = Date.now();
  const timer = setInterval(() => {
    chrome.runtime.sendMessage({ type: "vidgrab:job", id }, (response) => {
      if (!response?.ok) {
        clearInterval(timer);
        return;
      }
      const job = response.data;
      const progress = job.total > 0 ? " " + Math.round((job.done * 100) / job.total) + "%" : "";
      setStatus(`job ${job.id}: ${job.state}${progress}`, job.state === "failed" ? "bad" : "ok");
      if (job.state === "done" || job.state === "failed") {
        clearInterval(timer);
        if (job.state === "failed") hintEl.textContent = job.error || "Download failed.";
        else hintEl.textContent = job.file ? "Saved to " + job.file : "Saved.";
      }
    });
    if (Date.now() - started > 60000) clearInterval(timer);
  }, 1000);
}

chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
  pageUrl = tab?.url || "";
  pageTitle = tab?.title || "";
  if (!tab?.id || !/^https?:/i.test(pageUrl)) {
    hintEl.textContent = "Open a regular web page to send media.";
    return;
  }
  chrome.tabs.sendMessage(tab.id, { type: "vidgrab:scan" }, (response) => {
    if (chrome.runtime.lastError || !response) {
      hintEl.textContent = "This page can't be scanned. Open a normal web page.";
      return;
    }
    render(response);
  });
});

document.getElementById("options").addEventListener("click", (event) => {
  event.preventDefault();
  chrome.runtime.openOptionsPage();
});

versionEl.textContent = "v" + chrome.runtime.getManifest().version;
refreshStatus();
