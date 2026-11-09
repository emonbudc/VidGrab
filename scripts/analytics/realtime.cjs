const fs = require("node:fs");
const path = require("node:path");
const { JWT } = require("google-auth-library");

function arg(name, fallback) {
  const i = process.argv.indexOf("--" + name);
  return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const SERVICE_ACCOUNT = arg("service-account", process.env.FIREBASE_SERVICE_ACCOUNT_FILE);
const PROJECT = arg("project", "vidgrab-app-prod");
const OUTPUT = arg("output", "realtime.json");

if (!SERVICE_ACCOUNT) {
  console.error("Missing --service-account or FIREBASE_SERVICE_ACCOUNT_FILE");
  process.exit(1);
}

const key = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT, "utf8"));
const auth = new JWT({
  email: key.client_email,
  key: key.private_key,
  scopes: [
    "https://www.googleapis.com/auth/firebase.readonly",
    "https://www.googleapis.com/auth/analytics.readonly",
  ],
});

async function accessToken() {
  const token = await auth.getAccessToken();
  if (!token || !token.token) throw new Error("Could not obtain Google access token");
  return token.token;
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
        if (appsResponse.ok) {
          const apps = await appsResponse.json();
          const app = (apps.apps || []).find((item) => item.packageName === "com.vidgrab.app");
          if (app && app.appId) {
            const mapping = (data.streamMappings || []).find(
              (item) => String(item.app || "").endsWith("/" + app.appId)
            );
            if (mapping && mapping.streamId) streamId = String(mapping.streamId);
          }
        }
        return { property, streamId };
      }
    }
  } catch (error) {
    console.warn("Firebase analytics lookup failed:", error.message);
  }
  return null;
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

async function realtimeReport(token, property, body) {
  const r = await fetch(
    "https://analyticsdata.googleapis.com/v1beta/" + property + ":runRealtimeReport",
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
    throw new Error("GA4 realtime " + r.status + ": " + String(message).slice(0, 240));
  }
  return r.json();
}

function metric(data) {
  const row = data.rows && data.rows[0];
  const value = row && row.metricValues && row.metricValues[0] && row.metricValues[0].value;
  const num = Number(value);
  return Number.isFinite(num) ? num : 0;
}

(async () => {
  const result = {
    generatedAt: new Date().toISOString(),
    propertyId: null,
    activeUsers30m: null,
    downloads30m: null,
    installs30m: null,
    pending: true,
  };
  try {
    const token = await accessToken();
    const found = await findProperty(token);
    if (!found) {
      result.reason = "No GA4 property linked yet.";
    } else {
      result.propertyId = found.property;
      const active = await realtimeReport(token, found.property, {
        metrics: [{ name: "activeUsers" }],
        dimensionFilter: streamFilter(found.streamId),
      });
      const downloads = await realtimeReport(token, found.property, {
        metrics: [{ name: "eventCount" }],
        dimensionFilter: eventFilter("download_completed", found.streamId),
      });
      const installs = await realtimeReport(token, found.property, {
        metrics: [{ name: "eventCount" }],
        dimensionFilter: eventFilter("first_open", found.streamId),
      });
      result.activeUsers30m = metric(active);
      result.downloads30m = metric(downloads);
      result.installs30m = metric(installs);
      result.pending = false;
      console.log(
        "Realtime " +
          found.property +
          ": " +
          result.activeUsers30m +
          " active, " +
          result.downloads30m +
          " downloads, " +
          result.installs30m +
          " installs"
      );
    }
  } catch (error) {
    result.reason = error.message.slice(0, 240);
    console.warn("Realtime snapshot skipped:", error.message);
  }
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, JSON.stringify(result, null, 2) + "\n");
  console.log("Wrote " + OUTPUT);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
