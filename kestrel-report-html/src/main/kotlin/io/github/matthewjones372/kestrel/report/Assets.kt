package io.github.matthewjones372.kestrel.report

/**
 * The stylesheet, inlined into every report.
 *
 * Hand-written, because the report has to open from a `file://` URL with
 * nothing fetched — which rules out a web font, so the hierarchy here is built
 * from scale, weight and colour rather than from a typeface.
 *
 * Three theme states rather than two: the media query is what an untouched
 * report follows, and `data-theme` on the root is a reader overriding it. A CI
 * artifact is opened on whatever machine happened to have the link.
 */
internal val REPORT_CSS: String = """
    :root {
      color-scheme: light dark;
      --bg: #faf9f6;
      --fg: #191815;
      --muted: #6f6b62;
      --line: #e6e2d9;
      --rule: #d6d1c5;
      --card: #ffffff;
      --accent: oklch(0.55 0.13 265);
      --ok: oklch(0.50 0.13 150);
      --failed: oklch(0.53 0.15 25);
      --failed-tint: oklch(0.96 0.03 25);
      --sans: ui-sans-serif, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      --mono: ui-monospace, "SF Mono", "Cascadia Mono", "JetBrains Mono", Menlo, Consolas, monospace;
    }
    /* An untouched report follows the machine; `data-theme` is a reader who said otherwise. */
    @media (prefers-color-scheme: dark) {
      :root:not([data-theme="light"]) {
        --bg: #131316;
        --fg: #eceae4;
        --muted: #9b968c;
        --line: #2a2a30;
        --rule: #3a3a42;
        --card: #1a1a1e;
        --accent: oklch(0.75 0.13 265);
        --ok: oklch(0.75 0.13 150);
        --failed: oklch(0.72 0.15 25);
        --failed-tint: oklch(0.28 0.06 25);
      }
    }
    :root[data-theme="dark"] {
      color-scheme: dark;
      --bg: #131316;
      --fg: #eceae4;
      --muted: #9b968c;
      --line: #2a2a30;
      --rule: #3a3a42;
      --card: #1a1a1e;
      --accent: oklch(0.75 0.13 265);
      --ok: oklch(0.75 0.13 150);
      --failed: oklch(0.72 0.15 25);
      --failed-tint: oklch(0.28 0.06 25);
    }
    :root[data-theme="light"] { color-scheme: light; }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--fg);
      font: 15px/1.55 var(--sans);
    }
    main { max-width: 72rem; margin: 0 auto; padding: 3.5rem 2rem 5rem; }
    h1 { font-size: 2.125rem; font-weight: 600; letter-spacing: -0.02em; margin: 0; }
    h2 { font-size: 1.25rem; font-weight: 600; letter-spacing: -0.01em; margin: 0; }
    /* One numeral treatment, so a figure reads the same wherever it appears. */
    .num, .tile-value, .measured, .goodput-value, .tail-value, .failures-value,
    td.num, .operating-rate, .reason-count {
      font-family: var(--mono); font-variant-numeric: tabular-nums; letter-spacing: -0.02em;
    }
    .run {
      display: flex; align-items: flex-end; justify-content: space-between;
      gap: 1.5rem; flex-wrap: wrap;
      padding-bottom: 1.25rem; margin-bottom: 2.5rem; border-bottom: 2px solid var(--fg);
    }
    .run-name { display: flex; flex-direction: column; gap: 0.35rem; }
    .wordmark {
      font-size: 0.6875rem; font-weight: 600; letter-spacing: 0.3em;
      text-transform: uppercase; color: var(--accent);
    }
    .run-meta { display: flex; align-items: center; gap: 1rem; }
    .when {
      color: var(--muted); margin: 0; font-family: var(--mono);
      font-size: 0.8125rem; text-align: right;
    }
    .theme-toggle {
      font: inherit; color: var(--muted); background: none;
      border: 1px solid var(--line); border-radius: 0.4rem;
      padding: 0.35rem 0.4rem; cursor: pointer; line-height: 0;
    }
    .theme-toggle:hover { color: var(--fg); border-color: var(--rule); }
    .theme-toggle svg { display: block; }
    .totals {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
      gap: 1.75rem;
      margin: 0 0 3rem;
    }
    /* A rule rather than a card: six bordered boxes give a headline number the
       same weight as a footnote, which is most of why the page read flat. */
    .tile { padding-left: 1.25rem; border-left: 2px solid var(--rule); }
    .tile-value { display: block; font-size: 2.375rem; line-height: 1.05; margin-top: 0.5rem; }
    .tile-label {
      display: block;
      color: var(--muted);
      font-size: 0.75rem;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }
    .tile.ok .tile-value { color: var(--ok); }
    .tile.failed .tile-value { color: var(--failed); }
    .steps { margin-top: 3rem; }
    .steps-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 1rem;
      margin-bottom: 0.75rem;
    }
    .table-scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 0.8rem 0.6rem; border-bottom: 1px solid var(--line); text-align: left; }
    tbody td { font-size: 0.9375rem; }
    thead th {
      color: var(--muted);
      font-size: 0.6875rem;
      font-weight: 600;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      white-space: nowrap;
      border-bottom-color: var(--rule);
    }
    td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
    td.ok { color: var(--ok); }
    td.failed { color: var(--failed); }
    tr.reasons > td { background: var(--card); }
    .reason-list { list-style: none; margin: 0; padding: 0; }
    .reason-list li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.2rem 0; }
    .reason { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.85rem; }
    .reason { overflow-wrap: anywhere; }
    .reason-count { font-variant-numeric: tabular-nums; color: var(--muted); }
    .behind {
      background: var(--failed-tint);
      border: 0; border-left: 3px solid var(--failed);
      padding: 1.05rem 1.35rem;
      margin: 0 0 2rem;
      line-height: 1.55;
    }
    .behind strong { font-weight: 650; }
    thead th button {
      font: inherit;
      color: inherit;
      letter-spacing: inherit;
      text-transform: inherit;
      background: none;
      border: 0;
      padding: 0;
      cursor: pointer;
    }
    thead th[aria-sort="ascending"] button::after { content: " \2191"; }
    thead th[aria-sort="descending"] button::after { content: " \2193"; }
    tr.step[aria-expanded] { cursor: pointer; }
    tr.step[aria-expanded="true"] > th::before { content: "\25BE "; }
    tr.step[aria-expanded="false"] > th::before { content: "\25B8 "; }
    #mode-toggle { font: inherit; background: none; border: 1px solid var(--line); border-radius: 0.3rem; }
    #mode-toggle { padding: 0.1rem 0.45rem; color: inherit; cursor: pointer; }
    .verdicts { display: flex; gap: 3rem; align-items: flex-start; margin: 0 0 3rem; flex-wrap: wrap; }
    .verdict-headline {
      margin: 0; flex-shrink: 0; display: flex; flex-direction: column; gap: 0.3rem;
      padding-right: 3rem; border-right: 1px solid var(--line);
    }
    .verdict-score {
      font-family: var(--mono); font-variant-numeric: tabular-nums;
      font-size: 4.5rem; line-height: 0.9; font-weight: 600; letter-spacing: -0.05em;
    }
    .verdicts.met .verdict-score { color: var(--ok); }
    .verdicts.missed .verdict-score { color: var(--failed); }
    .verdict-caption {
      font-size: 0.6875rem; font-weight: 600; letter-spacing: 0.14em;
      text-transform: uppercase; color: var(--muted);
    }
    .verdict-list { list-style: none; margin: 0; padding: 0; flex: 1; min-width: 20rem; }
    .verdict-list li {
      display: flex; justify-content: space-between; gap: 1.25rem;
      padding: 0.7rem 0; border-top: 1px solid var(--line);
    }
    .verdict-list li::before {
      content: "met"; font-size: 0.6875rem; font-weight: 600; letter-spacing: 0.14em;
      text-transform: uppercase; color: var(--ok);
    }
    .verdict-list li.missed::before { content: "missed"; color: var(--failed); }
    .verdict-list .goal { flex: 1; margin-left: -3.9rem; padding-left: 4.3rem; font-size: 1rem; }
    .verdict-list .measured { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .changes { margin: 0 0 1.5rem; }
    .caveat { border: 1px solid var(--muted); border-radius: 0.5rem; padding: 0.6rem 0.8rem; margin: 0 0 0.6rem; }
    .change-headline { margin: 0 0 0.5rem; }
    .change-list { list-style: none; margin: 0; padding: 0; }
    .change-list li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.15rem 0; }
    .change-list li::before { content: "same"; font-size: 0.7rem; letter-spacing: 0.05em; color: var(--muted); }
    .change-list li.worse::before { content: "worse"; color: var(--failed); }
    .change-list li.better::before { content: "better"; color: var(--ok); }
    .change-list li.new::before { content: "new"; }
    .change-list li.gone::before { content: "gone"; color: var(--failed); }
    .change-list .goal { flex: 1; margin-left: -3.2rem; padding-left: 3.6rem; }
    .change-list .measured { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .plan { margin: 0 0 1.5rem; }
    .plan p { margin: 0 0 0.5rem; }
    .plan p.arrivals { color: var(--muted); font-size: 0.82rem; }
    .mix-list { list-style: none; margin: 0 0 0.5rem; padding: 0; }
    .mix-list li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.15rem 0; }
    .mix-arm { font-weight: 600; }
    .mix-shape { flex: 1; color: var(--muted); }
    .mix-share { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .chart.shape svg { height: 4.5rem; }
    .shape-line { fill: none; stroke: var(--fg); stroke-width: 1.5; opacity: 0.7; }
    .shape-area { fill: var(--fg); opacity: 0.09; }
    .shape-end, .curve-end { text-anchor: end; }
    .goodput { margin: 0 0 2rem; max-width: 46rem; }
    .goodput-headline { margin: 0.4rem 0 0.6rem; font-size: 1.05rem; }
    .goodput-list { list-style: none; margin: 0; padding: 0; }
    .goodput-list li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.15rem 0; }
    .goodput-step { flex: 1; }
    .goodput-value { font-variant-numeric: tabular-nums; }
    .goodput-value.none, .goodput-headline.none { color: var(--muted); }
    .failures { margin: 0 0 2rem; max-width: 46rem; }
    .failures-list { list-style: none; margin: 0.5rem 0 0; padding: 0; }
    .failures-list li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.15rem 0; }
    .failures-step { flex: 1; }
    .failures-value { color: var(--failed); font-variant-numeric: tabular-nums; }
    .tail { margin: 2rem 0 0; max-width: 46rem; }
    .tail-list { list-style: none; margin: 0.5rem 0 0; padding: 0; }
    .tail-list li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.15rem 0; }
    .tail-step { flex: 1; }
    .tail-value { font-variant-numeric: tabular-nums; }
    .tail-value.none, .tail-interval { color: var(--muted); }
    .reading { margin: 0 0 2rem; max-width: 46rem; }
    .reading p { margin: 0 0 0.6rem; }
    .reading strong { font-weight: 600; }
    .chart { margin: 2rem 0 0; }
    .chart figcaption {
      color: var(--muted); font-size: 0.6875rem; font-weight: 600;
      letter-spacing: 0.14em; text-transform: uppercase; margin-bottom: 0.7rem;
    }
    .chart svg { width: 100%; height: 14rem; overflow: visible; }
    .bar { fill: var(--accent); opacity: 0.62; }
    .mark { stroke: var(--failed); stroke-width: 1.5; stroke-dasharray: 4 3; }
    .mark-label { fill: var(--failed); font-size: 9px; }
    .tick { stroke: var(--line); stroke-width: 1; }
    .tick-label { fill: var(--muted); font-size: 9px; text-anchor: middle; }
    .timeline { margin: 2rem 0 0; }
    .chart.over-time svg { height: 4.5rem; }
    .series { fill: none; stroke-width: 1.5; }
    .series.count { stroke: var(--fg); opacity: 0.7; }
    .series.p50 { stroke: var(--muted); }
    .series.p99 { stroke: var(--fg); }
    .series.failed { stroke: var(--failed); }
    .key { font-weight: 600; }
    .key.p50 { color: var(--muted); }
    .key.p99 { color: var(--fg); }
    .series-start { text-anchor: start; }
    .series-end { text-anchor: end; }
    .note {
      color: var(--muted); font-size: 0.875rem; line-height: 1.6;
      margin: 1.5rem 0 0; padding: 1rem 0 1rem 1.35rem;
      border-left: 3px solid var(--accent); background: var(--card);
    }
    .note strong { color: var(--fg); font-weight: 600; }
    .operating { margin: 0 0 1.5rem; }
    .operating-rate { font-size: 1.6rem; margin: 0; font-variant-numeric: tabular-nums; }
    .operating-limit { color: var(--muted); margin: 0.25rem 0 0; }
    .chart.curve svg { height: 11rem; }
    .curve-line { fill: none; stroke: var(--fg); stroke-width: 1.5; opacity: 0.45; }
    .dot { fill: var(--ok); }
    .dot.failed { fill: var(--failed); }
    .dot.void { fill: var(--muted); }
    .chosen-mark { stroke: var(--ok); stroke-width: 1; stroke-dasharray: 3 2; }
    .chosen-label { fill: var(--ok); font-size: 9px; }
    .chosen-tag { color: var(--ok); font-size: 0.75rem; letter-spacing: 0.05em; margin-left: 0.4rem; }
    tr.rung.chosen > th { font-weight: 700; }
    tr.rung.failed > td.outcome { color: var(--failed); }
    tr.rung.void > td.outcome { color: var(--muted); }
    td.goals { color: var(--muted); font-size: 0.85rem; }
    .chart.series-over-points svg { height: 11rem; }
    .band { stroke: var(--muted); stroke-width: 2; opacity: 0.5; }
    tr.point.moved > th { font-weight: 700; }
    .empty { color: var(--muted); }
""".trimIndent()

/**
 * The theme control, on every page this module writes.
 *
 * Split from [REPORT_JS] because the capacity page carries no steps table and
 * so runs none of the rest of it, and a reader there wants the same control.
 */
internal val THEME_JS: String = """
    (function () {
      var toggle = document.getElementById('theme-toggle');
      if (!toggle) return;
      var root = document.documentElement;
      toggle.hidden = false;

      // Absent until a reader says otherwise, so an untouched report keeps
      // following the machine's own setting rather than one this page chose.
      var kept = null;
      try { kept = localStorage.getItem('kestrel-theme'); } catch (ignored) { kept = null; }
      if (kept === 'light' || kept === 'dark') root.setAttribute('data-theme', kept);

      function dark() {
        var set = root.getAttribute('data-theme');
        if (set) return set === 'dark';
        return window.matchMedia('(prefers-color-scheme: dark)').matches;
      }

      function label() {
        toggle.setAttribute('aria-label', dark() ? 'Switch to the light theme' : 'Switch to the dark theme');
      }
      label();

      toggle.addEventListener('click', function () {
        var next = dark() ? 'light' : 'dark';
        root.setAttribute('data-theme', next);
        try { localStorage.setItem('kestrel-theme', next); } catch (ignored) { /* a file:// URL may refuse */ }
        label();
      });
    })();
""".trimIndent()

/**
 * The steps table's behaviour, inlined like everything else it needs.
 *
 * Plain DOM against the hooks the markup carries. Both timings already ride on
 * each cell as text the server formatted, so the toggle swaps an attribute
 * rather than reformatting nanoseconds — there is one implementation of "three
 * significant figures" and it is the one under test.
 */
internal val REPORT_JS: String = """
    (function () {
      var table = document.querySelector('.steps table');
      if (!table) return;
      var body = table.tBodies[0];

      function rowsOf(step) {
        return Array.prototype.filter.call(body.rows, function (row) {
          return row.dataset.step === step || row.dataset.for === step;
        });
      }

      Array.prototype.forEach.call(body.querySelectorAll('tr.reasons'), function (row) {
        row.hidden = true;
      });

      body.addEventListener('click', function (event) {
        var row = event.target.closest('tr.step[aria-expanded]');
        if (!row) return;
        var open = row.getAttribute('aria-expanded') === 'true';
        row.setAttribute('aria-expanded', open ? 'false' : 'true');
        rowsOf(row.dataset.step).forEach(function (each) {
          if (each.classList.contains('reasons')) each.hidden = open;
        });
      });

      var toggle = document.getElementById('mode-toggle');
      var name = document.getElementById('mode-name');
      toggle.addEventListener('click', function () {
        var showing = table.dataset.mode === 'response' ? 'service' : 'response';
        table.dataset.mode = showing;
        name.textContent = showing === 'response' ? 'response time' : 'service time';
        toggle.textContent = showing === 'response' ? 'Show service time' : 'Show response time';
        Array.prototype.forEach.call(table.querySelectorAll('td.time'), function (cell) {
          cell.textContent = showing === 'response' ? cell.dataset.response : cell.dataset.service;
        });
      });

      function keyOf(row, column, mode) {
        var cell = row.cells[column];
        if (cell.classList.contains('time')) {
          return Number(mode === 'response' ? cell.dataset.responseNs : cell.dataset.serviceNs);
        }
        var text = cell.textContent.replace(/[^0-9.]/g, '');
        return text === '' ? cell.textContent.toLowerCase() : Number(text);
      }

      Array.prototype.forEach.call(table.tHead.rows[0].cells, function (header, column) {
        header.querySelector('button').addEventListener('click', function () {
          var descending = header.getAttribute('aria-sort') !== 'descending';
          Array.prototype.forEach.call(table.tHead.rows[0].cells, function (each) {
            each.setAttribute('aria-sort', 'none');
          });
          header.setAttribute('aria-sort', descending ? 'descending' : 'ascending');

          var mode = table.dataset.mode === 'response' ? 'response' : 'service';
          var steps = Array.prototype.filter.call(body.rows, function (row) {
            return row.classList.contains('step');
          });
          steps.sort(function (left, right) {
            var a = keyOf(left, column, mode);
            var b = keyOf(right, column, mode);
            if (a === b) return 0;
            return (a < b ? -1 : 1) * (descending ? -1 : 1);
          });
          steps.forEach(function (row) {
            rowsOf(row.dataset.step).forEach(function (each) { body.appendChild(each); });
          });
        });
      });
    })();
""".trimIndent()
