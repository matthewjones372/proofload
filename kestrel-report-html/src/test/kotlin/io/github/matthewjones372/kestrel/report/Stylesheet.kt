package io.github.matthewjones372.kestrel.report

/**
 * The page with its style block emptied, for a golden that is about the page.
 *
 * Every page inlines the same stylesheet — the report is one self-contained
 * file, which is the point of it — so a golden of the whole page is a golden of
 * the stylesheet with a page attached. Eight of them were, and one rule added
 * to `Assets.kt` moved all eight: three specs sprang that trap in a single
 * evening, each green on its own branch and red hours later at integration,
 * with nothing in the failure naming the cause.
 *
 * Here rather than beside the renderer: this exists to make an assertion
 * readable, and a production API for stripping the styles out of a report is a
 * thing nobody should have.
 */
internal fun String.withoutStylesheet(): String {
    val lines = lines()
    val opens = lines.indexOf(STYLE_OPENS)
    val closes = lines.indexOf(STYLE_CLOSES)
    require(opens >= 0 && closes > opens) {
        "this page has no style block to take out, which is itself the bug: $STYLE_OPENS at $opens, " +
            "$STYLE_CLOSES at $closes"
    }
    return (lines.take(opens + 1) + lines.drop(closes)).joinToString(separator = "\n")
}

/**
 * Whether the page still carries the stylesheet it is meant to be self-contained by.
 *
 * The assertion that has to exist because of the split above: with the styles
 * out of every page golden, a page that stopped inlining them would move
 * nothing and pass.
 */
internal fun String.carriesTheStylesheet(): Boolean = contains(REPORT_CSS)

private const val STYLE_OPENS = "<style>"
private const val STYLE_CLOSES = "</style>"
