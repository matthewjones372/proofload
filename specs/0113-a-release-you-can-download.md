# 0113 — a release you can download

## Problem

`gh release list` on this repository is empty. There is a tag, `v0.1.0-rc1`, and
nothing else: no release, no notes, no downloadable anything. Someone who does not
want a launcher on their machine has only `installDist`, which means a clone, a
JDK and a Gradle build before one tool is callable.

The artefact for them already gets built and thrown away. `application` produces
`proofload-mcp-0.1.0-rc1.zip` on every release run — 20 MB, 27 files, carrying
`bin/proofload-mcp` and `bin/proofload-mcp.bat`, so it covers Windows, which no
launcher recipe here does. It is never uploaded anywhere.

## Not doing

- **No container.** That is 0114.
- **No Homebrew tap.** A second place versions go stale, for one platform.
- **Not every module.** `proofload-mcp` and `proofload-cli` are the two anyone
  runs; the rest are libraries and belong on Central only.
- **Not publishing the release.** It is created as a draft, for the same reason
  the Central bundle waits for a click.
- **Not widening the publish job.** It keeps `contents: read`.

## Shape

A second job that holds no credentials, after the one that does:

```yaml
  release:
    needs: publish
    runs-on: ubuntu-latest
    # Only this job can write, and it has none of the signing secrets.
    permissions:
      contents: write
    steps:
      - uses: actions/download-artifact@v4
        with: { name: distributions, path: dist }
      - name: Draft the release
        env:
          GH_TOKEN: ${{ github.token }}
        run: >
          gh release create "$GITHUB_REF_NAME" dist/*
          --draft --generate-notes
          $([[ "$GITHUB_REF_NAME" == *-rc* ]] && echo --prerelease)
```

with the publish job uploading what it already built:

```yaml
      - uses: actions/upload-artifact@v4
        with:
          name: distributions
          path: proofload-*/build/distributions/*.zip
```

## Why this shape

Two jobs rather than one step, because the publish job holds a GPG key and a
Central token and today cannot write to the repository. That is worth keeping: it
is the reason a failed release so far has never been able to do damage. Job-level
`permissions` and job-level `env` mean the job that can write holds no secrets and
the job with secrets cannot write.

`gh release create` over a third-party action: `gh` is on the runner already, this
is one command, and a release step is exactly where an unpinned third party would
be worst.

A draft, not a release, for symmetry with the Central bundle — nothing is public
until a person looks. The alternative is publishing straight away, which is one
fewer click and removes the only check between a bad build and a download link.

## Stack

- [ ] **`spec-0113-artifacts`** — the publish job builds and uploads the two
      distributions.
      Done when: a `workflow_dispatch` run leaves `distributions` on the run with
      both zips in it.
- [ ] **`spec-0113-release`** — the `release` job drafts a release with them
      attached, `--prerelease` on an `-rc` tag.
      Done when: a throwaway tag leaves a draft release carrying both zips, and
      the publish job still declares `contents: read`.
- [ ] **`spec-0113-docs`** — the README and `docs/mcp.md` name the download as
      the no-launcher route.
      Done when: the documented `curl` names a URL that resolves.

## Acceptance

```bash
gh workflow run publish.yml --repo matthewjones372/proofload
gh release view v0.1.0-rc2 --repo matthewjones372/proofload --json isDraft,assets
```

## Open questions

1. **Draft or published?** Recommended: draft, per Why this shape. It costs a
   click per release and it is the same click Central already asks for.
2. **`--generate-notes`, or notes from `CHANGELOG.md`?** The changelog is
   maintained here and is better than a commit list. Recommended: generate for
   now and revisit — reading a section out of the changelog is its own small job.
3. **Does the tar go up as well as the zip?** Recommended: zip only. The tar is
   the same 27 files and doubles the asset weight.
4. **Should this job also verify the download?** Unzipping the asset and running
   `bin/proofload-mcp` against `initialize` would catch a broken distribution
   before anyone downloads it. Recommended: yes, as a step in the release job —
   it is four lines and it is the only end-to-end check that exists.
