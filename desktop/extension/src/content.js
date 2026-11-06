(() => {
  if (window.__vidgrabReady) return;
  window.__vidgrabReady = true;

  const NATIVE_HOSTS = [
    "youtube.com", "youtu.be", "soundcloud.com", "bandcamp.com", "media.ccc.de",
    "instagram.com", "facebook.com", "fb.watch", "tiktok.com", "twitter.com", "x.com",
  ];
  const MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|3gp|m3u8|m4a|mp3|ogg|wav|flac|aac|jpg|jpeg|png|gif|webp)(?:[?#]|$)/i;

  function isNativeSite() {
    const host = location.hostname.replace(/^www\./, "");
    return NATIVE_HOSTS.some((name) => host === name || host.endsWith("." + name));
  }

  function collect() {
    const media = [];
    const seen = new Set();

    function add(url, extra = {}) {
      if (!url || !/^https?:/i.test(url)) return;
      const key = url.split("#")[0];
      if (seen.has(key)) return;
      seen.add(key);
      media.push({ url: key, ...extra });
    }

    add(location.href, { kind: "page", title: document.title, height: 0, page: true });

    for (const video of document.querySelectorAll("video")) {
      if (isNativeSite()) break;
      const url = video.currentSrc || video.src || video.querySelector("source[src]")?.src;
      add(url, {
        kind: "video",
        title: video.getAttribute("title") || document.title,
        height: video.videoHeight || 0,
      });
    }

    if (!isNativeSite()) {
      for (const node of document.querySelectorAll("video source[src], audio source[src], audio[src]")) {
        add(node.src || node.getAttribute("src"), {
          kind: node.tagName === "AUDIO" ? "audio" : "video",
          title: document.title,
          height: 0,
        });
      }
    }

    const metas = [
      'meta[property="og:video"]',
      'meta[property="og:video:url"]',
      'meta[property="og:video:secure_url"]',
      'meta[property="og:audio"]',
      'meta[property="og:audio:url"]',
      'meta[name="twitter:player:stream"]',
    ];
    for (const meta of document.querySelectorAll(metas.join(","))) {
      add(meta.content, { kind: "video", title: document.title, height: 0 });
    }

    for (const anchor of document.querySelectorAll("a[href]")) {
      if (!MEDIA_EXT.test(anchor.href)) continue;
      add(anchor.href, {
        kind: "file",
        title: anchor.textContent.trim() || anchor.href.split("/").pop(),
        height: 0,
      });
    }

    for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
      try {
        walk(JSON.parse(script.textContent));
      } catch (_) {
        /* not JSON */
      }
    }

    function walk(node) {
      if (!node || typeof node !== "object") return;
      if (Array.isArray(node)) {
        node.forEach(walk);
        return;
      }
      if (typeof node.contentUrl === "string") {
        add(node.contentUrl, { kind: "video", title: node.name || document.title, height: 0 });
      }
      Object.values(node).forEach(walk);
    }

    return { pageUrl: location.href, title: document.title, media };
  }

  const host = document.createElement("div");
  host.id = "vidgrab-overlay-host";
  host.style.cssText = "position:fixed;inset:0;pointer-events:none;z-index:2147483647;";
  const shadow = host.attachShadow({ mode: "open" });
  shadow.innerHTML = `
    <style>
      .vg-btn {
        position: fixed; display: none; pointer-events: auto;
        padding: 8px 12px; border: 0; border-radius: 8px;
        background: #d0bcff; color: #1c1b1f; cursor: pointer;
        font: 600 13px/1.2 system-ui, -apple-system, Segoe UI, sans-serif;
        box-shadow: 0 4px 16px rgba(0, 0, 0, .35);
      }
      .vg-btn:hover { background: #e9ddff; }
      .vg-toast {
        position: fixed; right: 16px; bottom: 16px; display: none;
        max-width: 320px; padding: 10px 14px; border-radius: 8px;
        pointer-events: none; background: #1c1b1f; color: #e6e1e5;
        font: 500 13px/1.35 system-ui, -apple-system, Segoe UI, sans-serif;
        box-shadow: 0 6px 24px rgba(0, 0, 0, .45);
      }
      .vg-toast.error { background: #8c1d18; color: #fff; }
    </style>
    <button class="vg-btn" type="button">Download with VidGrab</button>
    <div class="vg-toast"></div>
  `;
  document.documentElement.appendChild(host);

  const button = shadow.querySelector(".vg-btn");
  const toastEl = shadow.querySelector(".vg-toast");
  let hovered = null;
  let hideTimer = 0;
  let toastTimer = 0;

  function showFor(element) {
    const rect = element.getBoundingClientRect();
    if (rect.width < 60 || rect.height < 40) return;
    clearTimeout(hideTimer);
    hovered = element;
    const top = Math.max(8, rect.top + 8);
    const left = Math.max(8, Math.min(window.innerWidth - 190, rect.right - 180));
    button.style.top = `${top}px`;
    button.style.left = `${left}px`;
    button.style.display = "block";
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      button.style.display = "none";
      hovered = null;
    }, 350);
  }

  function mediaLink(node) {
    const anchor = node.closest?.("a[href]");
    return anchor && MEDIA_EXT.test(anchor.href) ? anchor : null;
  }

  function toast(message, isError = false) {
    toastEl.textContent = message;
    toastEl.classList.toggle("error", isError);
    toastEl.style.display = "block";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toastEl.style.display = "none";
    }, 3500);
  }

  function send(payload) {
    chrome.runtime.sendMessage({ type: "vidgrab:download", payload }, (response) => {
      if (chrome.runtime.lastError) {
        toast("VidGrab: " + chrome.runtime.lastError.message, true);
        return;
      }
      if (!response) return;
      if (response.ok) {
        toast("Sent to VidGrab" + (response.data?.id ? " · " + response.data.id : ""));
      } else {
        toast("VidGrab: " + (response.error || "could not send"), true);
      }
    });
  }

  document.addEventListener(
    "mouseover",
    (event) => {
      if (!(event.target instanceof Element)) return;
      const element = event.target.closest("video, audio") || mediaLink(event.target);
      if (element) showFor(element);
    },
    true,
  );

  document.addEventListener(
    "mouseout",
    (event) => {
      if (!hovered || !(event.target instanceof Element)) return;
      if (event.target === hovered || hovered.contains(event.target)) scheduleHide();
    },
    true,
  );

  button.addEventListener("mouseenter", () => clearTimeout(hideTimer));
  button.addEventListener("mouseleave", scheduleHide);
  button.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    const element = hovered;
    if (!element) return;
    const direct = element.tagName === "A"
      ? element.href
      : element.currentSrc || element.src || element.querySelector("source[src]")?.src;
    send({
      url: location.href,
      mediaUrl: direct && direct !== location.href ? direct : undefined,
      title: document.title,
    });
    scheduleHide();
  });

  window.addEventListener("scroll", () => {
    if (hovered) showFor(hovered);
  }, true);
  window.addEventListener("resize", () => {
    if (hovered) showFor(hovered);
  });

  document.addEventListener("fullscreenchange", () => {
    const parent = document.fullscreenElement || document.documentElement;
    if (host.parentNode !== parent) parent.appendChild(host);
  });

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "vidgrab:scan") sendResponse(collect());
  });
})();
