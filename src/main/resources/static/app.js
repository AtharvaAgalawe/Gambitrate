(() => {
    "use strict";

    const els = {
        apiStatus: document.getElementById("apiStatus"),
        apiStatusText: document.getElementById("apiStatusText"),
        syncForm: document.getElementById("syncForm"),
        username: document.getElementById("username"),
        syncButton: document.getElementById("syncButton"),
        syncButtonLabel: document.querySelector("#syncButton .button__label"),
        demoButton: document.getElementById("demoButton"),
        formMessage: document.getElementById("formMessage"),
        syncPanel: document.getElementById("syncPanel"),
        syncIndicator: document.getElementById("syncIndicator"),
        syncStatusLabel: document.getElementById("syncStatusLabel"),
        syncStatusMeta: document.getElementById("syncStatusMeta"),
        syncProgressWrap: document.getElementById("syncProgressWrap"),
        syncProgress: document.getElementById("syncProgress"),
        syncProgressFill: document.getElementById("syncProgressFill"),
        workspace: document.getElementById("workspace"),
        playerTitle: document.getElementById("playerTitle"),
        playerMeta: document.getElementById("playerMeta"),
        refreshButton: document.getElementById("refreshButton"),
        errorBanner: document.getElementById("errorBanner"),
        errorMessage: document.getElementById("errorMessage"),
        dismissError: document.getElementById("dismissError"),
        analysisLoader: document.getElementById("analysisLoader"),
        analysisContent: document.getElementById("analysisContent"),
        scoreboard: document.getElementById("scoreboard"),
        statBiggestCost: document.getElementById("statBiggestCost"),
        statBiggestDetail: document.getElementById("statBiggestDetail"),
        statGroups: document.getElementById("statGroups"),
        statLeaks: document.getElementById("statLeaks"),
        statGames: document.getElementById("statGames"),
        filterForm: document.getElementById("filterForm"),
        timeClassInputs: [...document.querySelectorAll('input[name="timeClass"]')],
        minGames: document.getElementById("minGames"),
        fromDate: document.getElementById("fromDate"),
        toDate: document.getElementById("toDate"),
        applyFilters: document.getElementById("applyFilters"),
        resetFilters: document.getElementById("resetFilters"),
        analysisGrid: document.querySelector(".analysis-grid"),
        chart: document.getElementById("costChart"),
        chartSummary: document.getElementById("chartSummary"),
        verdictContent: document.getElementById("verdictContent"),
        tablePanel: document.getElementById("tablePanel"),
        tableCount: document.getElementById("tableCount"),
        tableVisibility: document.getElementById("tableVisibility"),
        toggleRows: document.getElementById("toggleRows"),
        openingsTable: document.getElementById("openingsTable"),
        openingsBody: document.getElementById("openingsBody"),
        emptyState: document.getElementById("emptyState"),
        emptyTitle: document.getElementById("emptyTitle"),
        emptyMessage: document.getElementById("emptyMessage"),
        emptyAction: document.getElementById("emptyAction")
    };

    const state = {
        player: null,
        data: null,
        openings: [],
        filters: null,
        operationId: 0,
        syncing: false,
        lastSync: null,
        showAllRows: false,
        sortKey: "estimatedRatingCost",
        sortDir: "desc"
    };

    const USERNAME_PATTERN = /^[A-Za-z0-9_-]{1,50}$/;
    const VERDICTS = new Set(["leak", "fine", "strength"]);
    const MAX_SYNC_WAIT_MS = 5 * 60 * 1000;
    const intFormat = new Intl.NumberFormat("en-US");

    const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[character]);

    const formatPercent = (value, digits = 1) => {
        if (!Number.isFinite(value)) {
            return "—";
        }
        return `${(value * 100).toFixed(digits)}%`;
    };

    const formatSigned = (value, digits = 1) => {
        if (!Number.isFinite(value)) {
            return "—";
        }
        if (Math.abs(value) < Math.pow(10, -digits) / 2) {
            return (0).toFixed(digits);
        }
        return value > 0
            ? `+${value.toFixed(digits)}`
            : `−${Math.abs(value).toFixed(digits)}`;
    };

    const formatInteger = (value) => intFormat.format(Number(value) || 0);

    // Chess.com opening names often end with the move sequence, for example
    // "Ruy Lopez Opening Berlin Defense 4.d3 d6 5.O-O". Keep only the name.
    const trimmedOpeningName = (value) => {
        const cleaned = String(value || "").replace(/\s+/g, " ").trim();
        const moveSequence = cleaned.search(/\s*\d+\.(?:\.\.)?\S*/);
        const name = moveSequence >= 0 ? cleaned.slice(0, moveSequence) : cleaned;
        return name.replace(/[\s.,;:+-]+$/, "").trim();
    };

    const openingName = (group) => trimmedOpeningName(group.openingName) || group.ecoCode || "Unknown opening";

    const side = (color) => String(color || "").toLowerCase() === "black" ? "black" : "white";

    const sideLabel = (color) => side(color) === "black" ? "Black" : "White";

    const sideMark = (color) => `<span class="side-mark side-mark--${side(color)}" aria-hidden="true"></span>`;

    const verdictLabel = (verdict) => {
        if (verdict === "leak") return "Weak spot";
        if (verdict === "strength") return "Strong spot";
        return "No pattern";
    };

    const verdictBadge = (verdict) => {
        const safe = VERDICTS.has(verdict) ? verdict : "fine";
        return `<span class="verdict-badge verdict-badge--${safe}">${verdictLabel(safe)}</span>`;
    };

    const displayError = (error) => {
        if (error && error.status === 404) {
            return error.message || "That player has not been analyzed yet.";
        }
        return error && error.message ? error.message : "Something went wrong. Try again.";
    };

    class ApiError extends Error {
        constructor(message, status, data) {
            super(message);
            this.name = "ApiError";
            this.status = status;
            this.data = data;
        }
    }

    async function api(path, options = {}) {
        let response;
        try {
            response = await fetch(path, {
                ...options,
                headers: {
                    Accept: "application/json",
                    ...(options.headers || {})
                }
            });
        } catch (error) {
            throw new ApiError("Could not reach the GambitRate API.", 0, null);
        }

        const text = await response.text();
        let data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (error) {
                data = null;
            }
        }

        if (!response.ok) {
            const message = data && data.message ? data.message : `Request failed (${response.status}).`;
            throw new ApiError(message, response.status, data);
        }
        return data;
    }

    function setApiStatus(status) {
        const labels = {
            checking: "Checking API",
            live: "API live",
            down: "API unavailable"
        };
        els.apiStatusText.textContent = labels[status];
        els.apiStatus.classList.toggle("is-live", status === "live");
        els.apiStatus.classList.toggle("is-down", status === "down");
    }

    async function checkHealth() {
        setApiStatus("checking");
        try {
            const response = await fetch("/actuator/health", { cache: "no-store" });
            const data = await response.json();
            setApiStatus(response.ok && data.status === "UP" ? "live" : "down");
        } catch (error) {
            setApiStatus("down");
        }
    }

    function setFormMessage(message) {
        if (!message) {
            els.formMessage.hidden = true;
            els.formMessage.textContent = "";
            return;
        }
        els.formMessage.hidden = false;
        els.formMessage.textContent = message;
    }

    function showError(message) {
        els.errorMessage.textContent = message;
        els.errorBanner.hidden = false;
    }

    function hideError() {
        els.errorBanner.hidden = true;
        els.errorMessage.textContent = "";
    }

    function setBusy(busy) {
        state.syncing = busy;
        els.syncButton.disabled = busy;
        els.refreshButton.disabled = busy;
        els.applyFilters.disabled = busy;
        els.demoButton.disabled = busy;
        els.syncButtonLabel.textContent = busy ? "Analyzing…" : "Analyze my games";
    }

    function setPlayerInUrl(player) {
        const url = new URL(window.location.href);
        if (player) {
            url.searchParams.set("player", player);
        } else {
            url.searchParams.delete("player");
        }
        window.history.replaceState({}, "", url);
        document.title = player ? `${player} · GambitRate` : "GambitRate — Opening analysis";
        if (player) {
            try {
                localStorage.setItem("gambitrate.player", player);
            } catch (error) {
                // localStorage can be disabled; the page still works.
            }
        }
    }

    function readFilters() {
        const formData = new FormData(els.filterForm);
        return {
            timeClass: formData.get("timeClass") || "",
            minGames: Number(formData.get("minGames")) || 5,
            from: formData.get("from") || "",
            to: formData.get("to") || ""
        };
    }

    function setFilters(filters) {
        els.timeClassInputs.forEach((input) => {
            input.checked = input.value === (filters.timeClass || "");
        });
        els.minGames.value = String(filters.minGames || 5);
        els.fromDate.value = filters.from || "";
        els.toDate.value = filters.to || "";
    }

    function buildQuery(filters) {
        const params = new URLSearchParams();
        if (filters.timeClass) params.set("timeClass", filters.timeClass);
        params.set("minGames", String(filters.minGames));
        if (filters.from) params.set("from", filters.from);
        if (filters.to) params.set("to", filters.to);
        return params.toString();
    }

    function renderSyncPanel(job) {
        const status = job && job.status ? job.status : "QUEUED";
        const labels = {
            QUEUED: "Getting ready",
            RUNNING: "Fetching your games",
            DONE: "Games updated",
            FAILED: "Could not update"
        };
        const stateName = status === "DONE" ? "done" : status === "FAILED" ? "failed" : "running";
        const total = Number(job?.monthsTotal) || 0;
        const done = Number(job?.monthsDone) || 0;

        els.syncPanel.hidden = false;
        els.syncPanel.dataset.state = stateName;
        els.syncStatusLabel.textContent = labels[status] || "Fetching your games";
        if (status === "RUNNING") {
            els.syncStatusMeta.textContent = total > 0
                ? `${done} of ${total} ${total === 1 ? "month" : "months"} done`
                : "Checking which months you played";
        } else if (status === "DONE") {
            els.syncStatusMeta.textContent = `${total} ${total === 1 ? "month" : "months"} of games saved.`;
        } else if (status === "FAILED") {
            els.syncStatusMeta.textContent = job?.error || "The update did not finish.";
        } else {
            els.syncStatusMeta.textContent = "Waiting to start.";
        }

        const showProgress = status === "RUNNING" || status === "DONE";
        els.syncProgressWrap.hidden = !showProgress;
        if (showProgress) {
            const percent = total > 0 ? Math.round((done / total) * 100) : 0;
            els.syncProgress.setAttribute("aria-valuemax", String(Math.max(total, 1)));
            els.syncProgress.setAttribute("aria-valuenow", String(total > 0 ? done : 0));
            els.syncProgressFill.style.width = status === "DONE" ? "100%" : `${percent}%`;
        }
    }

    function renderSyncError(error) {
        els.syncPanel.hidden = false;
        els.syncPanel.dataset.state = "failed";
        els.syncStatusLabel.textContent = "Could not update";
        els.syncStatusMeta.textContent = displayError(error);
        els.syncProgressWrap.hidden = true;
    }

    function showLoading() {
        els.workspace.hidden = false;
        els.workspace.setAttribute("aria-busy", "true");
        els.analysisLoader.hidden = false;
        els.analysisContent.hidden = true;
        els.playerTitle.textContent = state.player || "Player";
        els.playerMeta.textContent = "Loading analysis…";
    }

    function hideLoading() {
        els.workspace.removeAttribute("aria-busy");
        els.analysisLoader.hidden = true;
        els.analysisContent.hidden = false;
    }

    function renderPlayerMeta(data) {
        const filters = data.filters || {};
        const pieces = [];
        pieces.push(filters.timeClass ? capitalize(filters.timeClass) : "All game speeds");
        pieces.push(`at least ${filters.minGames || 5} games`);
        pieces.push("grouped by opening");
        if (filters.from || filters.to) {
            const from = filters.from || "the start";
            const to = filters.to || "today";
            pieces.push(`${from} to ${to}`);
        }
        if (state.lastSync?.finishedAt) {
            const months = Number(state.lastSync.monthsTotal) || 0;
            pieces.push(`${months} ${months === 1 ? "month" : "months"} checked`);
        }
        els.playerMeta.textContent = pieces.join(" · ");
    }

    function renderStats(groups) {
        const leaks = groups.filter((group) => group.verdict === "leak");
        const totalGames = groups.reduce((sum, group) => sum + (Number(group.games) || 0), 0);
        const biggest = groups
            .filter((group) => Number(group.estimatedRatingCost) > 0)
            .sort((a, b) => Number(b.estimatedRatingCost) - Number(a.estimatedRatingCost))[0];

        els.statGroups.textContent = formatInteger(groups.length);
        els.statLeaks.textContent = formatInteger(leaks.length);
        els.statGames.textContent = formatInteger(totalGames);
        els.statBiggestCost.textContent = biggest ? formatSigned(-Number(biggest.estimatedRatingCost)) : "0.0";
        els.statBiggestCost.classList.toggle("is-neutral", !biggest);
        els.statBiggestDetail.textContent = biggest
            ? `${openingName(biggest)} · ${formatInteger(biggest.games)} games`
            : "No opening is costing you rating in this view.";
    }

    function renderChart(groups) {
        els.chart.replaceChildren();
        if (!groups.length) {
            const empty = document.createElement("p");
            empty.className = "chart-empty";
            empty.textContent = "No openings to show.";
            els.chart.append(empty);
            els.chartSummary.textContent = "No openings to chart.";
            return;
        }

        const rows = [...groups]
            .sort((a, b) => Number(b.estimatedRatingCost) - Number(a.estimatedRatingCost))
            .slice(0, 8);
        const maxAbs = Math.max(...rows.map((group) => Math.abs(Number(group.estimatedRatingCost) || 0)), 0);

        if (maxAbs === 0) {
            const empty = document.createElement("p");
            empty.className = "chart-empty";
            empty.textContent = "Every opening is close to your usual results.";
            els.chart.append(empty);
            els.chartSummary.textContent = "Every opening is close to your usual results.";
            return;
        }

        for (const group of rows) {
            const impact = -(Number(group.estimatedRatingCost) || 0);
            const isLoss = impact < 0;
            const width = Math.max(impact === 0 ? 0 : 2, Math.abs(impact) / maxAbs * 48);
            const valueClass = isLoss ? "is-loss" : impact > 0 ? "is-gain" : "";
            const row = document.createElement("div");
            row.className = "cost-row";
            row.innerHTML = `
                <div class="cost-row__label">
                    <div class="cost-row__name">
                        ${sideMark(group.color)}
                        <span title="${escapeHtml(openingName(group))}">${escapeHtml(openingName(group))}</span>
                    </div>
                    <span class="cost-row__meta">${escapeHtml(sideLabel(group.color))} · ${formatInteger(group.games)} games · won ${formatPercent(group.winRate)}</span>
                </div>
                <div class="cost-track" aria-hidden="true">
                    ${impact === 0 ? "" : `<span class="cost-bar cost-bar--${isLoss ? "loss" : "gain"}" style="--bar-width: ${width.toFixed(2)}%;"></span>`}
                </div>
                <div class="cost-value ${valueClass}">${formatSigned(impact)}</div>
            `;
            els.chart.append(row);
        }

        const lead = rows[0];
        const leadImpact = -(Number(lead.estimatedRatingCost) || 0);
        els.chartSummary.textContent = `Estimated rating impact by opening. ${openingName(lead)} has the largest impact at ${formatSigned(leadImpact)} rating points.`;
    }

    function renderVerdict(groups) {
        if (!groups.length) {
            els.verdictContent.innerHTML = `
                <div class="verdict-empty">
                    <h4>Not enough data yet.</h4>
                    <p>Add a player or loosen the filters to see a finding.</p>
                </div>
            `;
            return;
        }

        const leaks = groups
            .filter((group) => group.verdict === "leak")
            .sort((a, b) => Number(b.estimatedRatingCost) - Number(a.estimatedRatingCost));
        const strengths = groups
            .filter((group) => group.verdict === "strength")
            .sort((a, b) => Number(a.estimatedRatingCost) - Number(b.estimatedRatingCost));
        const strongest = strengths[0];

        if (leaks.length) {
            const group = leaks[0];
            const impact = -(Number(group.estimatedRatingCost) || 0);
            els.verdictContent.innerHTML = `
                <article class="verdict-card">
                    <div class="verdict-card__top">
                        ${verdictBadge("leak")}
                        <span class="verdict-card__eco">As ${escapeHtml(sideLabel(group.color))}</span>
                    </div>
                    <h4>${escapeHtml(openingName(group))}</h4>
                    <p class="verdict-reason">${escapeHtml(group.verdictReason)}</p>
                    <div class="verdict-stats">
                        <div class="verdict-stat">
                            <strong>${formatPercent(group.winRate)}</strong>
                            <span>wins · usually ${formatPercent(group.baselineWinRate)}</span>
                        </div>
                        <div class="verdict-stat">
                            <strong>${formatInteger(group.games)}</strong>
                            <span>games played</span>
                        </div>
                        <div class="verdict-stat">
                            <strong>${formatSigned(impact)}</strong>
                            <span>rating points lost (est.)</span>
                        </div>
                    </div>
                    <p class="verdict-caveat">This is an estimate. We only flag an opening when there are enough games and the gap is very unlikely to be luck.</p>
                </article>
            `;
            return;
        }

        const closest = groups
            .filter((group) => group.verdict === "fine")
            .sort((a, b) => Number(b.estimatedRatingCost) - Number(a.estimatedRatingCost))[0];

        els.verdictContent.innerHTML = `
            <div class="verdict-empty">
                <div class="verdict-card__top">
                    ${verdictBadge("fine")}
                    <span class="verdict-card__eco">${groups.length} ${groups.length === 1 ? "opening" : "openings"}</span>
                </div>
                <h4>Nothing to fix here.</h4>
                <p>
                    No opening is clearly worse than your usual results once we allow for how many games you have played there.
                    ${closest ? `The closest is <strong>${escapeHtml(openingName(closest))}</strong> at ${formatPercent(closest.winRate)} wins over ${formatInteger(closest.games)} games.` : ""}
                </p>
                ${strongest ? `<p class="verdict-best"><span>Best opening</span> ${escapeHtml(openingName(strongest))} · ${formatPercent(strongest.winRate)} wins · ${formatInteger(strongest.games)} games</p>` : ""}
                <p class="verdict-caveat">That is good news: there is no obvious opening to fix in this view.</p>
            </div>
        `;
    }

    function sortGroups(groups) {
        const key = state.sortKey;
        const direction = state.sortDir === "asc" ? 1 : -1;
        const value = (group) => {
            if (key === "opening") return openingName(group).toLowerCase();
            if (key === "games") return Number(group.games) || 0;
            if (key === "winRate") return Number(group.winRate) || 0;
            return Number(group.estimatedRatingCost) || 0;
        };
        return [...groups].sort((a, b) => {
            const left = value(a);
            const right = value(b);
            if (typeof left === "string") {
                return left.localeCompare(right) * direction;
            }
            return (left - right) * direction;
        });
    }

    function updateSortHeaders() {
        els.openingsTable.querySelectorAll("th[data-key]").forEach((header) => {
            if (header.dataset.key === state.sortKey) {
                header.setAttribute("aria-sort", state.sortDir === "asc" ? "ascending" : "descending");
            } else {
                header.removeAttribute("aria-sort");
            }
        });
    }

    function renderTable(groups) {
        const sorted = sortGroups(groups);
        const visible = state.showAllRows ? sorted : sorted.slice(0, 20);
        const totalGames = groups.reduce((sum, group) => sum + (Number(group.games) || 0), 0);
        els.tableCount.textContent = `${formatInteger(groups.length)} openings · ${formatInteger(totalGames)} games`;
        els.tableVisibility.textContent = sorted.length > 20
            ? `Showing ${formatInteger(visible.length)} of ${formatInteger(sorted.length)} openings.`
            : `Showing all ${formatInteger(sorted.length)} openings.`;
        els.toggleRows.hidden = sorted.length <= 20;
        els.toggleRows.textContent = state.showAllRows ? "Show top 20" : `Show all ${formatInteger(sorted.length)} openings`;
        els.openingsBody.innerHTML = visible.map((group) => {
            const delta = group.avgRatingDelta == null ? "—" : formatSigned(Number(group.avgRatingDelta));
            const deltaClass = group.avgRatingDelta == null ? "muted" : Number(group.avgRatingDelta) >= 0 ? "is-gain" : "is-cost";
            const impact = -(Number(group.estimatedRatingCost) || 0);
            const impactClass = impact < 0 ? "is-loss" : impact > 0 ? "is-gain" : "muted";
            const baseline = Math.max(0, Math.min(100, Number(group.baselineWinRate) * 100));
            return `
                <tr>
                    <td data-label="Opening">
                        <div class="opening-cell">
                            <div class="opening-cell__name">
                                <span>${escapeHtml(openingName(group))}</span>
                            </div>
                        </div>
                    </td>
                    <td data-label="Color"><span class="side-label">${sideMark(group.color)}${escapeHtml(sideLabel(group.color))}</span></td>
                    <td data-label="Games" class="numeric">${formatInteger(group.games)}</td>
                    <td data-label="Won">
                        <span class="rate-cell">
                            <span class="numeric">${formatPercent(group.winRate)}</span>
                            <span class="rate-track" aria-hidden="true">
                                <span class="rate-fill" style="width: ${Math.max(0, Math.min(100, Number(group.winRate) * 100))}%;"></span>
                                <span class="rate-baseline" style="left: ${baseline}%;"></span>
                            </span>
                        </span>
                    </td>
                    <td data-label="Your usual" class="numeric muted">${formatPercent(group.baselineWinRate)}</td>
                    <td data-label="Drawn" class="numeric muted">${formatPercent(group.drawRate)}</td>
                    <td data-label="Rating change" class="numeric ${deltaClass}">${delta}</td>
                    <td data-label="Rating impact" class="numeric cost-text ${impactClass}">${formatSigned(impact)}</td>
                    <td data-label="Finding">${verdictBadge(group.verdict)}</td>
                </tr>
            `;
        }).join("");
        updateSortHeaders();
    }

    function renderWorkspace(data) {
        state.data = data;
        state.openings = Array.isArray(data.openings) ? data.openings : [];
        state.player = data.username || state.player;
        state.showAllRows = false;
        els.workspace.hidden = false;
        els.playerTitle.textContent = state.player;
        renderPlayerMeta(data);
        renderStats(state.openings);
        renderChart(state.openings);
        renderVerdict(state.openings);
        renderTable(state.openings);
        updateSortHeaders();

        const noGroups = state.openings.length === 0;
        els.scoreboard.hidden = noGroups;
        els.analysisGrid.hidden = noGroups;
        els.tablePanel.hidden = noGroups;
        els.emptyState.hidden = !noGroups;

        if (noGroups) {
            els.emptyTitle.textContent = "No openings meet these filters.";
            els.emptyMessage.textContent = data.message || "Try lowering the games-per-opening filter or widening the date range.";
            els.emptyAction.hidden = false;
            els.emptyAction.textContent = "Reset filters";
            els.emptyAction.onclick = resetFilters;
        } else {
            els.emptyAction.hidden = true;
            els.emptyAction.onclick = null;
        }

        hideLoading();
    }

    async function loadOpenings(player, operationId) {
        showLoading();
        hideError();
        const filters = readFilters();
        state.filters = filters;
        const query = buildQuery(filters);

        try {
            const data = await api(`/api/players/${encodeURIComponent(player)}/openings?${query}`);
            if (operationId !== state.operationId) {
                return;
            }
            state.lastSync = state.lastSync || null;
            state.player = data.username || player;
            setPlayerInUrl(state.player);
            renderWorkspace(data);
        } catch (error) {
            if (operationId !== state.operationId) {
                return;
            }
            hideLoading();
            if (error.status === 404) {
                els.workspace.hidden = true;
                setFormMessage(`No games saved for "${player}" yet. Use "Analyze my games" to fetch them.`);
                els.username.focus();
                return;
            }
            if (state.data) {
                els.workspace.hidden = false;
                els.analysisContent.hidden = false;
                showError(displayError(error));
            } else {
                els.workspace.hidden = true;
                setFormMessage(displayError(error));
            }
        }
    }

    async function pollJob(username, operationId) {
        const startedAt = Date.now();
        while (Date.now() - startedAt < MAX_SYNC_WAIT_MS) {
            await new Promise((resolve) => window.setTimeout(resolve, 1200));
            if (operationId !== state.operationId) {
                return null;
            }
            const job = await api(`/api/players/${encodeURIComponent(username)}/sync/status`);
            renderSyncPanel(job);
            if (job.status === "DONE") {
                return job;
            }
            if (job.status === "FAILED") {
                throw new ApiError(job.error || "The update failed.", 0, job);
            }
        }
        throw new ApiError("The update is taking longer than five minutes. Check the status again shortly.", 0, null);
    }

    async function startSync(rawUsername) {
        const username = String(rawUsername || "").trim().toLowerCase();
        if (!USERNAME_PATTERN.test(username)) {
            setFormMessage("Use 1–50 letters, digits, underscores, or hyphens.");
            els.username.focus();
            return;
        }

        const operationId = ++state.operationId;
        state.player = username;
        state.data = null;
        state.openings = [];
        state.lastSync = null;
        state.sortKey = "estimatedRatingCost";
        state.sortDir = "desc";
        els.username.value = username;
        setPlayerInUrl(username);
        setFormMessage("");
        hideError();
        els.workspace.hidden = true;
        setBusy(true);
        renderSyncPanel({ status: "QUEUED", username, monthsTotal: 0, monthsDone: 0 });

        try {
            const job = await api(`/api/players/${encodeURIComponent(username)}/sync`, { method: "POST" });
            if (operationId !== state.operationId) return;
            renderSyncPanel(job);

            const finished = await pollJob(username, operationId);
            if (!finished || operationId !== state.operationId) return;
            state.lastSync = finished;
            await loadOpenings(username, operationId);
        } catch (error) {
            if (operationId !== state.operationId) return;
            renderSyncError(error);
            setFormMessage(displayError(error));
            els.workspace.hidden = true;
        } finally {
            if (operationId === state.operationId) {
                setBusy(false);
            }
        }
    }

    function resetFilters() {
        setFilters({
            timeClass: "",
            minGames: 5,
            from: "",
            to: ""
        });
        if (state.player && !state.syncing) {
            const operationId = ++state.operationId;
            loadOpenings(state.player, operationId);
        }
    }

    function applyFilters(event) {
        event.preventDefault();
        if (!state.player || state.syncing) return;
        const filters = readFilters();
        if (filters.from && filters.to && filters.from > filters.to) {
            setFormMessage("The start date must be on or before the end date.");
            return;
        }
        setFormMessage("");
        const operationId = ++state.operationId;
        loadOpenings(state.player, operationId);
    }

    function capitalize(value) {
        return value ? value.charAt(0).toUpperCase() + value.slice(1) : value;
    }

    function bindEvents() {
        els.syncForm.addEventListener("submit", (event) => {
            event.preventDefault();
            startSync(els.username.value);
        });

        els.demoButton.addEventListener("click", () => {
            els.username.value = "gambitrate";
            startSync("gambitrate");
        });

        els.refreshButton.addEventListener("click", () => {
            if (state.player) startSync(state.player);
        });

        els.filterForm.addEventListener("submit", applyFilters);
        els.resetFilters.addEventListener("click", resetFilters);
        els.emptyAction.addEventListener("click", resetFilters);
        els.dismissError.addEventListener("click", hideError);

        els.openingsTable.querySelector("thead").addEventListener("click", (event) => {
            const button = event.target.closest("button[data-key]");
            if (!button) return;
            const key = button.dataset.key;
            if (!key) return;
            if (state.sortKey === key) {
                state.sortDir = state.sortDir === "asc" ? "desc" : "asc";
            } else {
                state.sortKey = key;
                state.sortDir = key === "opening" ? "asc" : "desc";
            }
            state.showAllRows = false;
            renderTable(state.openings);
        });

        els.toggleRows.addEventListener("click", () => {
            state.showAllRows = !state.showAllRows;
            renderTable(state.openings);
        });
    }

    function initialize() {
        bindEvents();
        checkHealth();

        let player = new URLSearchParams(window.location.search).get("player");
        if (!player) {
            try {
                player = localStorage.getItem("gambitrate.player");
            } catch (error) {
                player = null;
            }
        }

        if (player && USERNAME_PATTERN.test(player)) {
            const normalized = player.trim().toLowerCase();
            els.username.value = normalized;
            state.player = normalized;
            const operationId = ++state.operationId;
            loadOpenings(normalized, operationId);
        }
    }

    initialize();
})();
