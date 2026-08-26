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
    .note { color: var(--muted); font-size: 0.82rem; margin: 1rem 0 0; }
    .empty { color: var(--muted); }
""".trimIndent()
