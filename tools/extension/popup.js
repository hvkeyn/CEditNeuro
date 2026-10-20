// CEditNeuro handoff. It calls the same loopback endpoints the VidBee extension uses:
// /status, /token, /video-info, plus this app's /download.

const PORT_START = 27100;
const PORT_END = 27120;
const STATUS_TIMEOUT = 900;
const INFO_TIMEOUT = 45000;

const $ = (id) => document.getElementById(id);

const dot = $("dot");
const phone = $("phone");
const status = $("status");
const video = $("video");
const title = $("title");
const meta = $("meta");
const formats = $("formats");
const quality = $("quality");
const send = $("send");
const retry = $("retry");

const state = { port: 0, token: "", url: "", info: null, busy: false };

const setDot = (kind) => {
  dot.className = `dot ${kind}`;
};

const setStatus = (text, bad = false) => {
  status.textContent = text;
  status.className = bad ? "status bad" : "status";
};

const fetchJson = async (url, timeoutMs) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal, cache: "no-store" });
    const data = await response.json().catch(() => null);
    if (!response.ok) {
      const message = data && data.error ? data.error : `Request failed: ${response.status}`;
      throw new Error(message);
    }
    return data;
  } finally {
    clearTimeout(timer);
  }
};

const findPort = async () => {
  for (let port = PORT_START; port <= PORT_END; port += 1) {
    try {
      const statusJson = await fetchJson(`http://127.0.0.1:${port}/status`, STATUS_TIMEOUT);
      if (statusJson && statusJson.ok && statusJson.app === "CEditNeuro") {
        state.port = port;
        return true;
      }
    } catch {
      // Try the next port.
    }
  }
  return false;
};

const formatDuration = (seconds) => {
  if (!seconds || seconds <= 0) return "";
  const total = Math.round(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
};

const formatBytes = (bytes) => {
  if (!bytes || bytes <= 0) return "";
  const units = ["B", "KB", "MB", "GB"];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size.toFixed(size >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`;
};

const render = () => {
  if (!state.info) {
    video.classList.add("hidden");
    return;
  }
  video.classList.remove("hidden");
  title.textContent = state.info.title || "Video";
  const parts = [];
  const duration = formatDuration(state.info.duration);
  if (duration) parts.push(duration);
  if (state.info.engine) parts.push(state.info.engine);
  const count = Array.isArray(state.info.formats) ? state.info.formats.length : 0;
  if (count > 0) parts.push(`${count} formats`);
  meta.textContent = parts.join(" · ") || "Ready";

  const rows = (state.info.formats || [])
    .slice()
    .sort((a, b) => (b.height || 0) - (a.height || 0))
    .slice(0, 10)
    .map((format) => {
      const height = format.height ? `${format.height}p` : format.resolution || "";
      const size = format.filesize ? formatBytes(format.filesize) : "";
      const kind = format.format_note || "";
      return [height, format.ext, size, kind].filter(Boolean).join("  ");
    });
  formats.textContent = rows.length ? rows.join("\n") : "No format list. The phone will pick the best.";
};

const sendDownload = async () => {
  if (state.busy || !state.url || !state.port) return;
  state.busy = true;
  send.disabled = true;
  setStatus("Sending the link to the phone…");
  try {
    const chosen = encodeURIComponent(quality.value || "best");
    const url = `http://127.0.0.1:${state.port}/download?url=${encodeURIComponent(state.url)}&quality=${chosen}&token=${encodeURIComponent(state.token)}`;
    const result = await fetchJson(url, 20000);
    setStatus(result.message || "The phone agent is downloading this link.");
    setDot("ok");
  } catch (error) {
    setStatus(error.message || "The phone did not accept the link.", true);
    setDot("bad");
  } finally {
    state.busy = false;
    send.disabled = false;
  }
};

const load = async () => {
  setDot("wait");
  phone.textContent = "Looking for the phone…";
  setStatus("Start CEditNeuro on the phone and keep it open.");
  video.classList.add("hidden");
  state.info = null;

  // `?url=` is for a manual or automated check of this popup: a popup page opened as a
  // normal tab has no active web tab of its own.
  const params = new URLSearchParams(location.search);
  const forcedUrl = params.get("url");
  const [tab] = forcedUrl ? [{ url: forcedUrl }] : await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.url || !/^https?:/i.test(tab.url)) {
    setDot("bad");
    phone.textContent = "No web page";
    setStatus("Open a video page in this tab, then open this popup.", true);
    return;
  }
  state.url = tab.url;

  const found = await findPort();
  if (!found) {
    setDot("bad");
    phone.textContent = "Phone not found";
    setStatus("CEditNeuro is not answering on 127.0.0.1. Start the app, keep it in front, and on a computer run adb reverse tcp:27100 tcp:27100.", true);
    return;
  }

  setDot("wait");
  phone.textContent = `Connected on port ${state.port}`;
  setStatus("Reading the video on the phone…");

  try {
    const token = await fetchJson(`http://127.0.0.1:${state.port}/token`, STATUS_TIMEOUT);
    state.token = token.token || "";
    if (!state.token) throw new Error("The phone printed no token.");
    const info = await fetchJson(
      `http://127.0.0.1:${state.port}/video-info?url=${encodeURIComponent(state.url)}&token=${encodeURIComponent(state.token)}`,
      INFO_TIMEOUT
    );
    state.info = info;
    setDot("ok");
    setStatus("Ready. The phone agent downloads it into the open project.");
    phone.textContent = `Connected on port ${state.port}`;
    render();
  } catch (error) {
    setDot("bad");
    setStatus(error.message || "That page could not be read.", true);
  }
};

send.addEventListener("click", () => void sendDownload());
retry.addEventListener("click", () => void load());
void load();
