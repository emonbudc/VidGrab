(() => {
  const header = document.querySelector(".site-header");
  const navToggle = document.querySelector(".nav-toggle");
  const nav = document.querySelector("#primary-nav");
  const year = document.querySelector("#year");

  if (year) year.textContent = String(new Date().getFullYear());

  const closeNav = () => {
    header.classList.remove("nav-open");
    navToggle.setAttribute("aria-expanded", "false");
    navToggle.setAttribute("aria-label", "Open menu");
  };

  if (navToggle && header && nav) {
    navToggle.addEventListener("click", () => {
      const open = header.classList.toggle("nav-open");
      navToggle.setAttribute("aria-expanded", String(open));
      navToggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    });

    nav.querySelectorAll("a").forEach((link) => {
      link.addEventListener("click", closeNav);
    });

    document.addEventListener("click", (event) => {
      if (header.classList.contains("nav-open") && !header.contains(event.target)) closeNav();
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") closeNav();
    });

    const desktop = window.matchMedia("(min-width: 921px)");
    desktop.addEventListener("change", (event) => {
      if (event.matches) closeNav();
    });
  }

  const formatSize = (bytes) => {
    if (!bytes || Number.isNaN(bytes)) return null;
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(mb >= 100 ? 0 : 1)} MB`;
  };

  const loadLatestRelease = async () => {
    const response = await fetch("https://api.github.com/repos/emonbudc/VidGrab/releases/latest", {
      headers: { Accept: "application/vnd.github+json" },
      cache: "no-store",
    });
    if (!response.ok) return;
    const release = await response.json();
    if (!release || !Array.isArray(release.assets) || !release.tag_name) return;

    const assets = release.assets;
    const universal = assets.find(
      (asset) =>
        asset.name.endsWith(".apk") &&
        !asset.name.endsWith("-arm64-v8a.apk") &&
        !asset.name.endsWith("-armeabi-v7a.apk")
    );
    const arm64 = assets.find((asset) => asset.name.endsWith("-arm64-v8a.apk"));
    const armv7 = assets.find((asset) => asset.name.endsWith("-armeabi-v7a.apk"));
    const builds = { universal, arm64, armv7 };

    document.querySelectorAll("[data-version]").forEach((element) => {
      element.textContent = release.tag_name;
    });

    document.querySelectorAll("[data-release-link]").forEach((element) => {
      element.href = release.html_url;
    });

    Object.entries(builds).forEach(([key, asset]) => {
      if (!asset) return;

      document.querySelectorAll(`[data-download="${key}"]`).forEach((element) => {
        element.href = asset.browser_download_url;
      });

      const checksum = assets.find((item) => item.name === `${asset.name}.sha256`);
      document.querySelectorAll(`[data-sha="${key}"]`).forEach((element) => {
        if (checksum) element.href = checksum.browser_download_url;
      });

      const size = formatSize(asset.size);
      if (size) {
        document.querySelectorAll(`[data-size="${key}"]`).forEach((element) => {
          element.textContent = size;
        });
      }
    });
  };

  loadLatestRelease().catch(() => {});
})();
