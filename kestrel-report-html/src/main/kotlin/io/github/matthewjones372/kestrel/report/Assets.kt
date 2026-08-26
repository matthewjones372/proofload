package io.github.matthewjones372.kestrel.report

/**
 * The stylesheet, inlined into every report.
 *
 * Hand-written, because the report has to open from a `file://` URL with
 * nothing fetched. `color-scheme` plus one media query is the whole theming
 * story: a report is read in a terminal-coloured editor as often as in a
 * browser on a projector.
 */
internal val REPORT_CSS: String = """
    :root {
      color-scheme: light dark;
      --bg: #fdfdfc;
      --fg: #1b1b19;
      --muted: #6b6b66;
      --line: #e2e2dd;
      --card: #ffffff;
      --ok: #1c7c4a;
      --failed: #b3261e;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #16171a;
        --fg: #e8e8e4;
        --muted: #9a9a94;
        --line: #2c2e33;
        --card: #1d1f23;
        --ok: #57c38a;
        --failed: #f2857c;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--fg);
      font: 15px/1.5 ui-sans-serif, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }
    main { max-width: 68rem; margin: 0 auto; padding: 2rem 1.25rem 4rem; }
    h1 { font-size: 1.5rem; margin: 0 0 0.25rem; }
    h2 { font-size: 1.05rem; margin: 0; }
    .when { color: var(--muted); margin: 0 0 1.5rem; }
    .totals {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9.5rem, 1fr));
      gap: 0.75rem;
      margin-bottom: 2rem;
    }
    .tile { background: var(--card); border: 1px solid var(--line); border-radius: 0.5rem; padding: 0.75rem 0.9rem; }
    .tile-label {
      display: block;
      color: var(--muted);
      font-size: 0.75rem;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }
    .tile-value { display: block; font-size: 1.5rem; font-variant-numeric: tabular-nums; margin-top: 0.15rem; }
    .tile.ok .tile-value { color: var(--ok); }
    .tile.failed .tile-value { color: var(--failed); }
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
    th, td { padding: 0.5rem 0.6rem; border-bottom: 1px solid var(--line); text-align: left; }
    thead th {
      color: var(--muted);
      font-size: 0.75rem;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      white-space: nowrap;
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
      background: color-mix(in srgb, var(--failed) 12%, var(--card));
      border: 1px solid var(--failed);
      border-radius: 0.5rem;
      padding: 0.7rem 0.9rem;
      margin: 0 0 1.5rem;
    }
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
    .plan { margin: 0 0 1.5rem; }
    .plan p { margin: 0 0 0.5rem; }
    .chart.shape svg { height: 4.5rem; }
    .shape-line { fill: none; stroke: var(--fg); stroke-width: 1.5; opacity: 0.7; }
    .shape-area { fill: var(--fg); opacity: 0.09; }
    .shape-end { text-anchor: end; }
    .reading { margin: 0 0 2rem; max-width: 46rem; }
    .reading p { margin: 0 0 0.6rem; }
    .reading strong { font-weight: 600; }
    .chart { margin: 1.5rem 0 0; }
    .chart figcaption { color: var(--muted); font-size: 0.78rem; margin-bottom: 0.35rem; }
    .chart svg { width: 100%; height: 9rem; overflow: visible; }
    .bar { fill: var(--ok); opacity: 0.75; }
    .mark { stroke: var(--failed); stroke-width: 1; stroke-dasharray: 3 2; }
    .mark-label { fill: var(--failed); font-size: 9px; }
    .tick { stroke: var(--line); stroke-width: 1; }
    .tick-label { fill: var(--muted); font-size: 9px; text-anchor: middle; }
    .note { color: var(--muted); font-size: 0.82rem; margin: 1rem 0 0; }
    .empty { color: var(--muted); }
""".trimIndent()

/**
 * The page's behaviour, inlined like everything else it needs.
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
