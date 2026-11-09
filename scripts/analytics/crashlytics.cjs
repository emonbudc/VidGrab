function isoAgo(days) {
  return new Date(Date.now() - days * 86400000).toISOString();
}

function toNumber(value) {
  const num = Number(value);
  return Number.isFinite(num) ? num : 0;
}

function normalizeIssue(group) {
  const issue = group.issue || {};
  const metrics = (group.metrics && group.metrics[0]) || {};
  const signals = (issue.signals || []).map((item) => item.signal).filter(Boolean);
  return {
    id: issue.id || null,
    title: issue.title || issue.subtitle || "Unknown issue",
    subtitle: issue.subtitle || "",
    errorType: issue.errorType || "FATAL",
    state: issue.state || "OPEN",
    events: toNumber(metrics.eventsCount),
    users: toNumber(metrics.impactedUsersCount),
    sessions: toNumber(metrics.impactedSessionsCount),
    firstSeen: issue.firstSeenTime || null,
    lastSeen: issue.lastSeenTime || null,
    version: issue.lastSeenVersion || null,
    firstVersion: issue.firstSeenVersion || null,
    signals,
    sampleEvent: issue.sampleEvent || null,
    uri: issue.uri || null,
  };
}

function reportUrl(project, appId, report, days) {
  const params = new URLSearchParams();
  params.set("filter.interval.startTime", isoAgo(days));
  params.set("filter.interval.endTime", new Date().toISOString());
  return (
    "https://firebasecrashlytics.googleapis.com/v1alpha/projects/" +
    encodeURIComponent(project) +
    "/apps/" +
    appId +
    "/reports/" +
    report +
    "?" +
    params.toString()
  );
}

async function readReport(token, url) {
  const r = await fetch(url, {
    headers: {
      Authorization: "Bearer " + token,
      Accept: "application/json",
    },
  });
  if (!r.ok) {
    const text = await r.text();
    let message = text;
    try {
      const parsed = JSON.parse(text);
      message = (parsed.error && parsed.error.message) || text;
    } catch (_) {}
    throw new Error("Crashlytics " + r.status + ": " + String(message).slice(0, 200));
  }
  return r.json();
}

function parseSummary(data) {
  const metrics = data.groups && data.groups[0] && data.groups[0].metrics && data.groups[0].metrics[0];
  if (!metrics) return null;
  return {
    totalUsers: toNumber(metrics.totalUsersCount),
    totalSessions: toNumber(metrics.totalSessionsCount),
    events: toNumber(metrics.eventsCount),
    impactedUsers: toNumber(metrics.impactedUsersCount),
    impactedSessions: toNumber(metrics.impactedSessionsCount),
    crashFreeUsersPercent:
      typeof metrics.crashFreeUsersPercentage === "number" ? metrics.crashFreeUsersPercentage : null,
    crashFreeSessionsPercent:
      typeof metrics.crashFreeSessionsPercentage === "number" ? metrics.crashFreeSessionsPercentage : null,
  };
}

async function crashlyticsSnapshot(token, project, appId, days) {
  const intervalDays = days || 7;
  const summaryDays = 90;
  const issuesUrl = reportUrl(project, appId, "topIssues", intervalDays);
  const issuesUrlWithPage = issuesUrl + "&pageSize=25";
  const summaryUrl = reportUrl(project, appId, "appSummary", summaryDays);
  const [issuesResult, summaryResult] = await Promise.all([
    readReport(token, issuesUrlWithPage).catch((error) => ({ error })),
    readReport(token, summaryUrl).catch((error) => ({ error })),
  ]);
  if (issuesResult.error) throw issuesResult.error;
  const data = issuesResult;
  const issues = (Array.isArray(data.groups) ? data.groups : [])
    .map(normalizeIssue)
    .filter((issue) => issue.events > 0 || issue.users > 0)
    .sort((a, b) => b.events - a.events);
  const count = (type) => issues.filter((issue) => issue.errorType === type).length;
  let summary = null;
  if (!summaryResult.error) summary = parseSummary(summaryResult);
  return {
    pending: false,
    intervalDays,
    summaryDays,
    summary,
    issueCount: issues.length,
    openIssues: issues.filter((issue) => issue.state === "OPEN").length,
    fatalIssues: count("FATAL"),
    nonFatalIssues: count("NON_FATAL"),
    anrIssues: count("ANR"),
    eventCount: issues.reduce((sum, issue) => sum + issue.events, 0),
    affectedUsers: issues.reduce((sum, issue) => sum + issue.users, 0),
    issues: issues.slice(0, 8),
  };
}

module.exports = { crashlyticsSnapshot };
