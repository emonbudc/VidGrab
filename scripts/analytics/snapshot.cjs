const fs = require("node:fs");
const path = require("node:path");
const { JWT } = require("google-auth-library");
const { crashlyticsSnapshot } = require("./crashlytics.cjs");

function arg(name, fallback) {
  const i = process.argv.indexOf("--" + name);
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const SERVICE_ACCOUNT = arg("service-account", process.env.FIREBASE_SERVICE_ACCOUNT_FILE);
const PROJECT = arg("project", "vidgrab-app-prod");
const PACKAGE_NAME = arg("package", "com.vidgrab.app");
const REPO = arg("repo", "emonbudc/VidGrab");
const OUTPUT = arg("output", "docs/analysis/data.json");
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || "";

if (!SERVICE_ACCOUNT) {
  console.error("Missing --service-account or FIREBASE_SERVICE_ACCOUNT_FILE");
  process.exit(1);
}

const key = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT, "utf8"));
const auth = new JWT({
  email: key.client_email,
  key: key.private_key,
  scopes: [
    "https://www.googleapis.com/auth/firebase",
    "https://www.googleapis.com/auth/analytics.readonly",
  ],
});

async function accessToken() {
  const token = await auth.getAccessToken();
  if (!token || !token.token) throw new Error("Could not obtain Google access token");
  return token.token;
}

async function gh(pathname) {
  const headers = { Accept: "application/vnd.github+json", "User-Agent": "vidgrab-analytics" };
  if (GITHUB_TOKEN) headers.Authorization = "Bearer " + GITHUB_TOKEN;
  const r = await fetch("https://api.github.com" + pathname, { headers });
  if (!r.ok) throw new Error("GitHub " + pathname + " -> " + r.status);
  return r.json();
}

async function findProperty(token) {
  try {
    const headers = { Authorization: "Bearer " + token, Accept: "application/json" };
    const [detailsResponse, appsResponse] = await Promise.all([
      fetch(
        "https://firebase.googleapis.com/v1beta1/projects/" + encodeURIComponent(PROJECT) + "/analyticsDetails",
        { headers }
      ),
      fetch(
        "https://firebase.googleapis.com/v1beta1/projects/" + encodeURIComponent(PROJECT) + "/androidApps",
        { headers }
      ),
    ]);
    if (detailsResponse.ok) {
      const data = await detailsResponse.json();
      const id = data.analyticsProperty && data.analyticsProperty.id;
      if (id) {
        const property = String(id).startsWith("properties/") ? String(id) : "properties/" + id;
        let streamId = null;
        let appId = null;
        if (appsResponse.ok) {
          const apps = await appsResponse.json();
          const app = (apps.apps || []).find((item) => item.packageName === PACKAGE_NAME);
          if (app && app.appId) {
            appId = app.appId;
            const mapping = (data.streamMappings || []).find(
              (item) => String(item.app || "").endsWith("/" + app.appId)
            );
            if (mapping && mapping.streamId) streamId = String(mapping.streamId);
          }
        }
        return { property, streamId, appId };
      }
    }
  } catch (error) {
    console.warn("Firebase analytics lookup failed:", error.message);
  }
  const r = await fetch(
    "https://analyticsadmin.googleapis.com/v1beta/accountSummaries?pageSize=200",
    { headers: { Authorization: "Bearer " + token, Accept: "application/json" } }
  );
  if (!r.ok) throw new Error("Analytics Admin " + r.status + ": " + (await r.text()).slice(0, 400));
  const data = await r.json();
  const properties = [];
  for (const account of data.accountSummaries || []) {
    for (const prop of account.propertySummaries || []) {
      if (prop.property) properties.push(prop);
    }
  }
  if (!properties.length) return null;
  const preferred = properties.find((p) => /vidgrab/i.test(p.displayName || "")) || properties[0];
  return { property: preferred.property, streamId: null, appId: null };
}

async function runReport(token, property, body) {
  const r = await fetch(
    "https://analyticsdata.googleapis.com/v1beta/" + property + ":runReport",
    {
      method: "POST",
      headers: {
        Authorization: "Bearer " + token,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(body),
    }
  );
  if (!r.ok) {
    const text = await r.text();
    let message = text;
    try {
      const parsed = JSON.parse(text);
      message = (parsed.error && parsed.error.message) || text;
    } catch (_) {
      message = text.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    }
    throw new Error("GA4 " + r.status + ": " + String(message).slice(0, 240));
  }
  return r.json();
}

function metric(data, index = 0) {
  const row = data.rows && data.rows[0];
  const value = row && row.metricValues && row.metricValues[index] && row.metricValues[index].value;
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

function eventFilter(eventName, streamId) {
  const expressions = [{ filter: { fieldName: "eventName", stringFilter: { value: eventName } } }];
  if (streamId) {
    expressions.push({ filter: { fieldName: "streamId", stringFilter: { value: streamId } } });
  }
  return { andGroup: { expressions } };
}

function streamFilter(streamId) {
  return streamId
    ? { filter: { fieldName: "streamId", stringFilter: { value: streamId } } }
    : undefined;
}

function dateKey(value) {
  if (!value || value.length !== 8) return value;
  return value.slice(0, 4) + "-" + value.slice(4, 6) + "-" + value.slice(6, 8);
}

function last14Days() {
  const days = [];
  const now = new Date();
  for (let i = 13; i >= 0; i--) {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - i));
    days.push(d.toISOString().slice(0, 10));
  }
  return days;
}

async function firebaseSnapshot(token, property, streamId) {
  const base = { dateRanges: [{ startDate: "2020-01-01", endDate: "today" }] };
  const installsReport = await runReport(token, property, {
    ...base,
    metrics: [{ name: "eventCount" }, { name: "totalUsers" }],
    dimensionFilter: eventFilter("first_open", streamId),
  });
  const videosReport = await runReport(token, property, {
    ...base,
    metrics: [{ name: "eventCount" }, { name: "totalUsers" }],
    dimensionFilter: eventFilter("download_completed", streamId),
  });
  const activeReport = await runReport(token, property, {
    dateRanges: [{ startDate: "28daysAgo", endDate: "today" }],
    dimensions: [],
    metrics: [{ name: "activeUsers" }, { name: "sessions" }, { name: "crashFreeUsersRate" }],
    dimensionFilter: streamFilter(streamId),
  });
  const dailyReport = await runReport(token, property, {
    dateRanges: [{ startDate: "13daysAgo", endDate: "today" }],
    dimensions: [{ name: "date" }],
    metrics: [{ name: "eventCount" }],
    dimensionFilter: eventFilter("download_completed", streamId),
  });
  const byDay = new Map();
  for (const row of dailyReport.rows || []) {
    const day = dateKey(row.dimensionValues && row.dimensionValues[0] && row.dimensionValues[0].value);
    const value = Number(row.metricValues && row.metricValues[0] && row.metricValues[0].value) || 0;
    if (day) byDay.set(day, value);
  }
  const activeUsers = metric(activeReport, 0);
  return {
    pending: false,
    installs: metric(installsReport, 0),
    installUsers: metric(installsReport, 1),
    videoDownloads: metric(videosReport, 0),
    videoDownloadUsers: metric(videosReport, 1),
    activeUsers28d: activeUsers,
    sessions28d: metric(activeReport, 1),
    crashFreeUsersRate: activeUsers && activeUsers > 0 ? metric(activeReport, 2) : null,
    bytes: null,
    videosByDay: last14Days().map((date) => ({ date, count: byDay.get(date) || 0 })),
  };
}

async function githubSnapshot() {
  const releases = await gh("/repos/" + REPO + "/releases?per_page=100");
  let apkDownloads = 0;
  let assetDownloads = 0;
  const normalized = [];
  for (const rel of Array.isArray(releases) ? releases : []) {
    let total = 0;
    for (const asset of rel.assets || []) {
      const count = Number(asset.download_count) || 0;
      assetDownloads += count;
      if (/\.apk$/i.test(asset.name || "")) {
        apkDownloads += count;
        total += count;
      }
    }
    normalized.push({ tag: rel.tag_name, publishedAt: rel.published_at, downloads: total });
  }
  normalized.sort((a, b) => new Date(b.publishedAt) - new Date(a.publishedAt));
  return {
    releaseCount: normalized.length,
    apkDownloads,
    assetDownloads,
    latestTag: normalized[0] ? normalized[0].tag : null,
    latestDownloads: normalized[0] ? normalized[0].downloads : 0,
  };
}

async function trafficSnapshot() {
  if (!GITHUB_TOKEN) return null;
  try {
    const [views, clones] = await Promise.all([
      gh("/repos/" + REPO + "/traffic/views"),
      gh("/repos/" + REPO + "/traffic/clones"),
    ]);
    return {
      views14d: Number(views.count) || 0,
      uniques14d: Number(views.uniques) || 0,
      clones14d: Number(clones.count) || 0,
      uniqueCloners14d: Number(clones.uniques) || 0,
    };
  } catch (error) {
    console.warn("GitHub traffic unavailable:", error.message);
    return null;
  }
}

(async () => {
  const result = {
    generatedAt: new Date().toISOString(),
    propertyId: null,
    firebase: { pending: true, reason: "Analytics property not available yet." },
    crashlytics: { pending: true, reason: "Crashlytics data not available yet." },
    traffic: null,
    github: null,
  };
  try {
    result.github = await githubSnapshot();
  } catch (error) {
    console.warn("GitHub release stats unavailable:", error.message);
  }
  result.traffic = await trafficSnapshot();
  try {
    const token = await accessToken();
    const found = await findProperty(token);
    if (found) {
      result.propertyId = found.property;
      result.firebase = await firebaseSnapshot(token, found.property, found.streamId);
      console.log("Firebase snapshot from " + found.property + (found.streamId ? " / stream " + found.streamId : ""));
      if (found.appId) {
        try {
          result.crashlytics = await crashlyticsSnapshot(token, PROJECT, found.appId, 7);
          console.log("Crashlytics: " + result.crashlytics.issueCount + " issues");
        } catch (error) {
          result.crashlytics = { pending: true, reason: error.message.slice(0, 240) };
          console.warn("Crashlytics snapshot skipped:", error.message);
        }
      } else {
        result.crashlytics.reason = "No Android app id found for " + PACKAGE_NAME + ".";
      }
    } else {
      result.firebase.reason = "No GA4 property is linked to " + PROJECT + " yet.";
      result.crashlytics.reason = "No GA4 property is linked to " + PROJECT + " yet.";
      console.warn(result.firebase.reason);
    }
  } catch (error) {
    result.firebase = { pending: true, reason: error.message.slice(0, 300) };
    console.warn("Firebase snapshot skipped:", error.message);
  }
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(result, null, 2) + "\n");
  console.log("Wrote " + OUTPUT);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
