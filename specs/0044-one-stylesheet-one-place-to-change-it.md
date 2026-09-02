# 0044 — One stylesheet, one place to change it

## Problem

The HTML report is one self-contained file, so its stylesheet is inlined into
every page it renders. Each golden is therefore a full copy of that stylesheet,
and a change to one CSS rule has to be made in every golden by hand.

There are two goldens today, `report-behind.html` and `capacity.html`, and the
trap has already sprung three times in a single evening. Spec 0033 added seven
`.goodput*` rules, spec 0030 added one `.caveat` rule and spec 0031 extended a
selector; each updated the golden that existed when it was written and each
broke the other golden's test at integration, hours later, in a branch that had
been green when it was pushed. Nothing in the failure names the cause: the
assertion prints a wall of stylesheet and leaves the reader to spot which of a
hundred and twenty lines moved.

This is a maintenance trap rather than a bug — the reports are correct — but it
scales with the number of pages, and 0025's timeline page and 0040's pipeline
panel both add rules. The next spec to add a page makes it worse for everybody
after it.

## Not doing

- No external stylesheet. A report that fetches anything renders blank from a CI
  artifact on a locked-down network, and a test already asserts that.
- No golden-updating flag. "Run it with `-Pupdate`" is how a golden stops being
  read, and `AGENTS.md` is explicit that a moved golden is the test working.
- No templating engine, no CSS build step, no dependency in a report module.
- No reduction in what the goldens cover. The whole point of a golden here is
  that it is the file a user opens.

## Shape

The stylesheet is asserted once, and each page golden asserts the page:

```kotlin
Assets.stylesheet shouldBe Golden.text("stylesheet.css")

Fixtures.fellBehind.toHtmlReport().withoutStylesheet() shouldBe Golden.text("report-behind.html")
Fixtures.capacity.toHtmlReport().withoutStylesheet() shouldBe Golden.text("capacity.html")
```

- One `stylesheet.css` golden. A new rule moves exactly one file, and its diff is
  the rule rather than a page.
- Page goldens keep everything a reader sees except the style block, so a change
  to the markup still moves the page that changed and only that page.
- A test that the rendered page still *contains* the stylesheet, so removing it
  is caught rather than made invisible by the split.

## Why this shape

Three options. Leaving it alone costs an integration failure per page per rule
and gets worse as pages are added. Asserting only that pages contain the same
stylesheet as each other tests consistency but stops testing the content, so a
rule could rot in every page at once and pass. Splitting the assertion tests the
stylesheet once, tests each page once, and makes the diff of a CSS change one
line in one file.

Recommend the third. The cost is that a golden is no longer byte-for-byte the
file a user opens, which is a real loss and is why the third assertion — that
the page still carries the stylesheet — has to exist rather than being obvious.

## Stack

- [x] **`spec-0044-split`** — the stylesheet as its own golden, page goldens
      without it, and a test that the page still carries it.
      Done when: adding one CSS rule moves exactly one golden file, the page
      goldens are unchanged by it, and deleting the style block from the
      rendered page fails a test.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does the GitHub markdown report have the same problem?** It has three
    goldens and no stylesheet. Recommend leaving it alone; the duplication there
    is content, which is what a golden is for.
2. **Should `withoutStylesheet()` live in the test source or beside the
    renderer?** Recommend the test source: it exists to make an assertion
    readable, and a production API for removing the styles from a report is a
    thing nobody should have.
3. **Is one stylesheet golden enough as pages diverge?** Today every page
    inlines the same rules whether it uses them or not. If a page ever ships a
    subset, this splits again — recommend noting that here rather than designing
    for it now.
4. **The scripts have the same problem, smaller.** Found when building this:
    eight goldens carried 2,208 lines of duplicated stylesheet and they also
    carry 660 lines of duplicated script — `THEME_JS` alone on the capacity and
    trend pages, `REPORT_JS` and `THEME_JS` on the six run reports. A change to
    either moves every page that inlines it, which is the same trap. Not built
    here: the split is the same three lines of test code, but two scripts and a
    rule about which page gets which is design this spec did not argue, and the
    stylesheet was 77% of the duplication. Recommend its own entry.
