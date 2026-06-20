// ==UserScript==
// @name         Nezha Detail Enhancer: 1 小时 + 网速中线
// @namespace    https://ab.example.com/
// @version      0.1.0
// @description  为 Nezha 详情页增加“1 小时”伪历史视图，并补齐网速图中间刻度线。
// @match        https://ab.example.com/server/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(() => {
  "use strict";

  const HOUR_MS = 60 * 60 * 1000;
  const ENHANCE_ATTR = "data-nezha-detail-enhancer";
  const HOUR_TAB_ATTR = "data-nezha-hour-tab";
  const MIDLINE_ATTR = "data-nezha-net-midline";
  const MIDLABEL_ATTR = "data-nezha-net-midlabel";
  const NATIVE_PERIOD_LABELS = ["实时", "1 天", "7 天", "30 天"];
  const HOUR_LABEL = "1 小时";
  const DEBUG = false;

  const state = {
    hourMode: false,
    suppressNativeReset: false,
    enhanceFrame: 0,
    clickListenerInstalled: false,
    observerStarted: false,
    fetchPatched: false
  };

  const log = (...args) => {
    if (DEBUG) console.log("[Nezha Detail Enhancer]", ...args);
  };

  const warn = (...args) => {
    console.warn("[Nezha Detail Enhancer]", ...args);
  };

  const normalizeText = (value) => (value || "").replace(/\s+/g, " ").trim();
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const isFiniteNumber = (value) => Number.isFinite(Number(value));

  function patchFetch() {
    if (state.fetchPatched || window.__nezhaDetailEnhancerFetchPatched) return;
    if (typeof window.fetch !== "function") return;

    const originalFetch = window.fetch.bind(window);

    window.fetch = async function enhancedFetch(input, init) {
      const response = await originalFetch(input, init);

      try {
        if (!state.hourMode) return response;

        const requestUrl = getRequestUrl(input);
        if (!requestUrl || !shouldFilterMetricsResponse(requestUrl)) return response;

        return await buildFilteredHourResponse(response);
      } catch (error) {
        warn("过滤 1 小时 metrics 数据失败，已回退原始响应。", error);
        return response;
      }
    };

    Object.defineProperty(window, "__nezhaDetailEnhancerFetchPatched", {
      value: true,
      configurable: false,
      enumerable: false,
      writable: false
    });

    state.fetchPatched = true;
    log("fetch patched");
  }

  function getRequestUrl(input) {
    try {
      const rawUrl =
        typeof input === "string"
          ? input
          : input && typeof input === "object" && "url" in input
            ? input.url
            : String(input || "");

      if (!rawUrl) return null;
      return new URL(rawUrl, window.location.href);
    } catch {
      return null;
    }
  }

  function shouldFilterMetricsResponse(url) {
    return (
      url.origin === window.location.origin &&
      /^\/api\/v1\/server\/\d+\/metrics$/.test(url.pathname) &&
      url.searchParams.get("period") === "1d"
    );
  }

  async function buildFilteredHourResponse(response) {
    const data = await response.clone().json();
    const points = data && data.data && data.data.data_points;

    if (!Array.isArray(points) || points.length === 0) {
      return response;
    }

    const latestTs = points.reduce((latest, point) => {
      const ts = Number(point && point.ts);
      return Number.isFinite(ts) && ts > latest ? ts : latest;
    }, 0);

    if (!latestTs) return response;

    const cutoff = latestTs - HOUR_MS;
    data.data.data_points = points.filter((point) => Number(point && point.ts) >= cutoff);

    const headers = new Headers(response.headers);
    headers.set("content-type", "application/json; charset=utf-8");
    headers.delete("content-length");
    headers.delete("content-encoding");

    log("filtered metrics response", {
      before: points.length,
      after: data.data.data_points.length
    });

    return new Response(JSON.stringify(data), {
      status: response.status,
      statusText: response.statusText,
      headers: headers
    });
  }

  function ensureStyles() {
    if (document.getElementById("nezha-detail-enhancer-style")) return;

    const style = document.createElement("style");
    style.id = "nezha-detail-enhancer-style";
    style.textContent =
      "\n[" + HOUR_TAB_ATTR + "] {\n" +
      "  user-select: none;\n" +
      "}\n" +
      "\n[" + HOUR_TAB_ATTR + "][data-nezha-hour-active=\"true\"] {\n" +
      "  color: hsl(var(--foreground)) !important;\n" +
      "}\n" +
      "\n[" + HOUR_TAB_ATTR + "][data-nezha-hour-active=\"false\"],\n" +
      ".nezha-hour-native-muted {\n" +
      "  color: hsl(var(--muted-foreground)) !important;\n" +
      "}\n" +
      "\n[" + HOUR_TAB_ATTR + "][data-nezha-hour-active=\"false\"]:hover {\n" +
      "  color: hsl(var(--foreground)) !important;\n" +
      "}\n";

    (document.head || document.documentElement).appendChild(style);
  }

  function installClickListener() {
    if (state.clickListenerInstalled) return;

    document.addEventListener(
      "click",
      (event) => {
        const periodButton = event.target && event.target.closest
          ? event.target.closest("div[class*='rounded-full']")
          : null;

        if (!periodButton) return;

        if (periodButton.hasAttribute(HOUR_TAB_ATTR)) {
          event.preventDefault();
          event.stopPropagation();
          activateHourPeriod();
          return;
        }

        const label = normalizeText(periodButton.textContent);
        if (NATIVE_PERIOD_LABELS.includes(label) && !state.suppressNativeReset) {
          if (isDisabledPeriodButton(periodButton)) return;

          if (state.hourMode && label === "1 天") {
            event.preventDefault();
            event.stopPropagation();
            activateNativeDayPeriod();
            return;
          }

          state.hourMode = false;
          scheduleEnhance();
          scheduleDelayedEnhance();
        }
      },
      true
    );

    state.clickListenerInstalled = true;
  }

  async function activateNativeDayPeriod() {
    state.hourMode = false;
    clickNativePeriod("实时");
    await sleep(80);
    clickNativePeriod("1 天");
    scheduleEnhance();
    scheduleDelayedEnhance();
  }

  async function activateHourPeriod() {
    const group = findPeriodGroup();
    if (!group) return;

    const currentNativeLabel = findActiveNativeLabel(group);

    if (currentNativeLabel === "1 天" && !state.hourMode) {
      state.hourMode = false;
      clickNativePeriod("实时");
      await sleep(80);
    }

    state.hourMode = true;
    clickNativePeriod("1 天");
    scheduleEnhance();
    scheduleDelayedEnhance();
  }

  function clickNativePeriod(label) {
    const group = findPeriodGroup();
    const button = group && group.buttons && group.buttons.get(label);
    if (!button) return false;

    state.suppressNativeReset = true;
    button.dispatchEvent(
      new MouseEvent("click", {
        bubbles: true,
        cancelable: true,
        view: window
      })
    );

    window.setTimeout(() => {
      state.suppressNativeReset = false;
    }, 0);

    return true;
  }

  function isDisabledPeriodButton(button) {
    const className = String(button.getAttribute("class") || "");
    return className.includes("cursor-not-allowed") || className.includes("opacity-40");
  }

  function scheduleDelayedEnhance() {
    [60, 180, 450, 900, 1500].forEach((delay) => {
      window.setTimeout(scheduleEnhance, delay);
    });
  }

  function scheduleEnhance() {
    if (state.enhanceFrame) return;

    state.enhanceFrame = window.requestAnimationFrame(() => {
      state.enhanceFrame = 0;

      try {
        enhancePage();
      } catch (error) {
        warn("页面增强失败。", error);
      }
    });
  }

  function enhancePage() {
    ensureStyles();
    ensureHourTab();
    patchNetworkMidline();
  }

  function findPeriodGroup() {
    const containers = Array.from(document.querySelectorAll("div[class*='rounded-full']"))
      .filter((element) => {
        const text = normalizeText(element.textContent);
        const className = String(element.getAttribute("class") || "");
        return className.includes("bg-muted") && NATIVE_PERIOD_LABELS.every((label) => text.includes(label));
      })
      .sort((a, b) => normalizeText(a.textContent).length - normalizeText(b.textContent).length);

    for (const container of containers) {
      const buttons = new Map();
      const slots = new Map();

      NATIVE_PERIOD_LABELS.forEach((label) => {
        const matches = Array.from(container.querySelectorAll("div"))
          .filter((element) => !element.hasAttribute(HOUR_TAB_ATTR))
          .filter((element) => normalizeText(element.textContent) === label)
          .filter((element) => String(element.getAttribute("class") || "").includes("rounded-full"));

        const button =
          matches.find((element) => String(element.getAttribute("class") || "").includes("px-3")) ||
          matches[0];

        if (button) {
          buttons.set(label, button);
          slots.set(label, getDirectChildWithin(container, button));
        }
      });

      if (NATIVE_PERIOD_LABELS.every((label) => buttons.has(label))) {
        return {
          container: container,
          buttons: buttons,
          slots: slots
        };
      }
    }

    return null;
  }

  function getDirectChildWithin(container, element) {
    let node = element;

    while (node && node.parentElement !== container) {
      node = node.parentElement;
    }

    return node || element;
  }

  function ensureHourTab() {
    const group = findPeriodGroup();
    if (!group) return;

    const hourButton = getOrCreateHourButton(group);
    if (!hourButton) return;

    applyHourVisualState(group, hourButton);
  }

  function getOrCreateHourButton(group) {
    const existing = group.container.querySelector("[" + HOUR_TAB_ATTR + "]");
    if (existing) return existing;

    const dayButton = group.buttons.get("1 天");
    if (!dayButton) return null;

    const hourButton = dayButton.cloneNode(true);
    hourButton.setAttribute(HOUR_TAB_ATTR, "true");
    hourButton.setAttribute(ENHANCE_ATTR, "hour-tab");
    hourButton.setAttribute("data-nezha-hour-active", "false");
    replaceButtonLabel(hourButton, HOUR_LABEL);

    const daySlot = group.slots && group.slots.get("1 天");
    group.container.insertBefore(hourButton, daySlot || dayButton);
    return hourButton;
  }

  function replaceButtonLabel(button, label) {
    const candidates = Array.from(button.querySelectorAll("p, span, div")).reverse();
    const textNode = candidates.find((element) => normalizeText(element.textContent) === "1 天");

    if (textNode) {
      textNode.textContent = label;
    } else {
      button.textContent = label;
    }
  }

  function applyHourVisualState(group, hourButton) {
    const oneDayButton = group.buttons.get("1 天");
    const activeTarget = state.hourMode ? hourButton : findActiveNativeButton(group);

    hourButton.setAttribute("data-nezha-hour-active", state.hourMode ? "true" : "false");

    if (oneDayButton) {
      oneDayButton.classList.toggle("nezha-hour-native-muted", state.hourMode);
    }

    if (activeTarget) {
      moveActiveIndicator(group.container, activeTarget);
    }
  }

  function findActiveNativeButton(group) {
    const activeLabel = findActiveNativeLabel(group);
    return activeLabel ? group.buttons.get(activeLabel) : null;
  }

  function findActiveNativeLabel(group) {
    for (const [label, button] of group.buttons) {
      const className = String(button.getAttribute("class") || "");
      const isMuted = className.includes("text-muted-foreground");
      const isDisabled = className.includes("cursor-not-allowed") || className.includes("opacity-40");

      if (!isMuted && !isDisabled) return label;
    }

    return null;
  }

  function moveActiveIndicator(container, target) {
    const indicator = container.querySelector(".active-indicator-fade-in");
    if (!indicator || !target) return;

    const containerRect = container.getBoundingClientRect();
    const targetRect = target.getBoundingClientRect();

    if (!containerRect.width || !targetRect.width) return;

    const x = targetRect.left - containerRect.left + container.scrollLeft;
    const y = targetRect.top - containerRect.top + container.scrollTop;

    indicator.style.width = String(targetRect.width) + "px";
    indicator.style.height = String(targetRect.height) + "px";
    indicator.style.transform = "translate(" + x + "px, " + y + "px)";
  }

  function patchNetworkMidline() {
    const card = findNetworkSpeedCard();
    if (!card) return;

    const svg = card.querySelector("svg.recharts-surface");
    if (!svg) return;

    const nativeTickTexts = getNativeSpeedTickTexts(svg);
    if (nativeTickTexts.length >= 3) {
      removeNetworkMidline(card);
      return;
    }
    if (nativeTickTexts.length < 2) return;

    const tickInfos = nativeTickTexts
      .map((element) => ({
        element: element,
        y: readSvgNumber(element, "y"),
        value: parseSpeedTick(element.textContent)
      }))
      .filter((item) => isFiniteNumber(item.y) && isFiniteNumber(item.value))
      .sort((a, b) => a.y - b.y);

    if (tickInfos.length < 2) return;

    const topTick = tickInfos[0];
    const bottomTick = tickInfos[tickInfos.length - 1];
    const midLabelY = (topTick.y + bottomTick.y) / 2;
    const midValue = (topTick.value + bottomTick.value) / 2;

    const gridLines = getNativeHorizontalGridLines(svg);
    const lineInfo = getMidlineGeometry(svg, gridLines, topTick, bottomTick);
    if (!lineInfo) return;

    const existingLine = card.querySelector("[" + MIDLINE_ATTR + "]");
    const existingLabel = card.querySelector("[" + MIDLABEL_ATTR + "]");
    const line = existingLine || createMidline(gridLines[0]);
    const label = existingLabel || createMidLabel(topTick.element);

    const gridGroup =
      svg.querySelector(".recharts-cartesian-grid-horizontal") ||
      svg.querySelector(".recharts-cartesian-grid") ||
      svg;
    const labelGroup =
      svg.querySelector(".recharts-yAxis .recharts-cartesian-axis-tick-labels") ||
      svg.querySelector(".recharts-yAxis") ||
      svg;

    applyMidlineGeometry(line, lineInfo);
    applyMidLabel(label, midLabelY, midValue);

    if (!existingLine) gridGroup.appendChild(line);
    if (!existingLabel) labelGroup.appendChild(label);
  }

  function findNetworkSpeedCard() {
    return Array.from(document.querySelectorAll(".server-charts > *")).find((card) => {
      const text = normalizeText(card.textContent);
      return text.includes("上传") && text.includes("下载") && /M\/s/.test(text);
    });
  }

  function removeNetworkMidline(card) {
    card
      .querySelectorAll("[" + MIDLINE_ATTR + "], [" + MIDLABEL_ATTR + "]")
      .forEach((element) => element.remove());
  }

  function getNativeSpeedTickTexts(svg) {
    return Array.from(svg.querySelectorAll(".recharts-yAxis text, text.recharts-cartesian-axis-tick-value"))
      .filter((element) => !element.hasAttribute(MIDLABEL_ATTR))
      .filter((element) => /M\/s$/.test(normalizeText(element.textContent)));
  }

  function getNativeHorizontalGridLines(svg) {
    return Array.from(svg.querySelectorAll(".recharts-cartesian-grid-horizontal line"))
      .filter((line) => !line.hasAttribute(MIDLINE_ATTR))
      .filter((line) => isFiniteNumber(readSvgNumber(line, "y1")));
  }

  function getMidlineGeometry(svg, gridLines, topTick, bottomTick) {
    if (gridLines.length >= 2) {
      const sortedLines = gridLines
        .map((line) => ({
          line: line,
          y: readSvgNumber(line, "y1")
        }))
        .filter((item) => isFiniteNumber(item.y))
        .sort((a, b) => a.y - b.y);

      const topLine = sortedLines[0];
      const bottomLine = sortedLines[sortedLines.length - 1];

      if (topLine && bottomLine) {
        return {
          x1: readSvgNumber(topLine.line, "x1"),
          x2: readSvgNumber(topLine.line, "x2"),
          y: (topLine.y + bottomLine.y) / 2
        };
      }
    }

    const width = readSvgNumber(svg, "width") || (svg.viewBox && svg.viewBox.baseVal && svg.viewBox.baseVal.width);
    const left = readSvgNumber(topTick.element, "x") || 0;

    if (!width) return null;

    return {
      x1: left + 12,
      x2: width - 8,
      y: (topTick.y + bottomTick.y) / 2
    };
  }

  function createMidline(templateLine) {
    const line = templateLine
      ? templateLine.cloneNode(false)
      : document.createElementNS("http://www.w3.org/2000/svg", "line");

    line.setAttribute(MIDLINE_ATTR, "true");
    line.setAttribute(ENHANCE_ATTR, "network-midline");

    if (!line.getAttribute("stroke")) line.setAttribute("stroke", "#ccc");
    if (!line.getAttribute("fill")) line.setAttribute("fill", "none");

    return line;
  }

  function applyMidlineGeometry(line, geometry) {
    line.setAttribute("x1", String(geometry.x1));
    line.setAttribute("x2", String(geometry.x2));
    line.setAttribute("y1", String(geometry.y));
    line.setAttribute("y2", String(geometry.y));
  }

  function createMidLabel(templateText) {
    const label = templateText.cloneNode(true);
    label.setAttribute(MIDLABEL_ATTR, "true");
    label.setAttribute(ENHANCE_ATTR, "network-midlabel");
    return label;
  }

  function applyMidLabel(label, y, value) {
    label.setAttribute("y", String(y));
    label.textContent = formatSpeedTick(value);
  }

  function readSvgNumber(element, attr) {
    const value = Number(element && element.getAttribute && element.getAttribute(attr));
    return Number.isFinite(value) ? value : NaN;
  }

  function parseSpeedTick(text) {
    const match = normalizeText(text).match(/^(-?\d+(?:\.\d+)?)M\/s$/);
    return match ? Number(match[1]) : NaN;
  }

  function formatSpeedTick(value) {
    if (!Number.isFinite(value)) return "";
    if (Math.abs(value) < 0.0001) return "0M/s";

    const fixed = Math.abs(value) < 10 && Math.abs(value % 1) > 0.0001 ? value.toFixed(1) : value.toFixed(0);
    return fixed.replace(/\.0$/, "") + "M/s";
  }

  function startObserver() {
    if (state.observerStarted) return;

    const root = document.documentElement || document;
    const observer = new MutationObserver(scheduleEnhance);
    observer.observe(root, {
      childList: true,
      subtree: true,
      characterData: true
    });

    window.addEventListener("resize", scheduleDelayedEnhance, { passive: true });
    window.addEventListener("popstate", scheduleDelayedEnhance, { passive: true });
    document.addEventListener("visibilitychange", scheduleDelayedEnhance, { passive: true });

    state.observerStarted = true;
    scheduleEnhance();
    scheduleDelayedEnhance();
  }

  function boot() {
    patchFetch();
    installClickListener();
    ensureStyles();
    startObserver();
  }

  if (document.documentElement) {
    boot();
  } else {
    document.addEventListener("DOMContentLoaded", boot, { once: true });
  }
})();
