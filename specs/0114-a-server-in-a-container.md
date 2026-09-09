# 0114 — a server in a container

## Problem

The install still assumes a JVM. Once this repository is public, the shortest
honest answer for someone who has neither a JDK nor a launcher is an image:

```bash
claude mcp add proofload -- docker run -i --rm ghcr.io/matthewjones372/proofload-mcp:VERSION
```

0110 is what makes this worth doing. `report` answers with a path, and a path
inside a container is meaningless on the host — so before `summary` existed, a
containerised server's most useful output was unreachable without a mount.
`summary` returns the run itself, which travels down stdout like everything else.

## Not doing

- **No fat jar.** The image is built from the distribution `application` already
  produces, so nothing is shaded and nothing has to be kept in step.
- **No Docker Hub.** GHCR is the same account and the same token.
- **Not building it in the job that signs.** Same separation 0113 argues for.
- **No `latest` on a prerelease.** An `-rc` tag publishes its version and nothing
  else.
- **Not a container for the CLI.** A load test that is a test belongs in the
  build that runs it; the server is the thing people want to attach.

## Shape

```dockerfile
FROM eclipse-temurin:21-jre
# The distribution, not a jar: bin/proofload-mcp with the jars beside it.
COPY proofload-mcp-*/ /opt/proofload-mcp/
# `Allowance.fromFile()` reads `proofload.toml` from the working directory and
# needs no new setting, so the fence is a mount and nothing else. It returns
# `none` when the file is absent, which is what entry 2 is about.
WORKDIR /work
ENTRYPOINT ["/opt/proofload-mcp/bin/proofload-mcp"]
```

```bash
docker run -i --rm \
  -v "$PWD/proofload.toml:/work/proofload.toml:ro" \
  ghcr.io/matthewjones372/proofload-mcp:VERSION
```

## Why this shape

`-i` and no `-t`: the protocol is stdin and stdout, and a TTY would corrupt it.
`--rm` because a run's state is a file the server keeps, and a container that
outlives the client holds a registry nobody can reach.

Multi-arch — `linux/amd64` and `linux/arm64` — because half the people who would
use this are on Apple Silicon and an emulated JVM is slow enough to change a
measurement, which for this tool is the one thing that must not happen.

The allowance is the decision worth arguing about. `docs/allowance.md` is explicit
that no file means no limits, and that is defensible for someone who installed a
load generator on purpose. A public image an LLM can drive with no fence is a
different object: it points anywhere, at any rate, for as long as it likes.
Recommended that the image refuse to send load without an allowance, which is a
behaviour change to the server and not only to packaging — hence the open question
rather than a decision taken here.

## Stack

- [ ] **`spec-0114-dockerfile`** — a `Dockerfile` built from the distribution, and
      a script that builds and exercises it locally.
      Done when: `docker run -i` answers `initialize` and `tools/list`, and the
      image carries no build tooling.
- [ ] **`spec-0114-fail-closed`** — the server refuses to send load with no
      allowance when asked to, and says so in the sentence `preview` already uses.
      Done when: `run` on an unfenced server refuses, and a mounted allowance
      lifts it.
- [ ] **`spec-0114-ghcr`** — a job pushing multi-arch to GHCR, `packages: write`
      and no signing secrets.
      Done when: a tag leaves `ghcr.io/matthewjones372/proofload-mcp:<version>`
      pullable for both architectures, with no `latest` on an `-rc`.
- [ ] **`spec-0114-docs`** — the container as a third install route.
      Done when: the documented `docker run` is one a reader can paste, mount and
      all.

## Acceptance

```bash
docker build -t proofload-mcp:dev proofload-mcp
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  | docker run -i --rm proofload-mcp:dev
docker run --rm ghcr.io/matthewjones372/proofload-mcp:VERSION --help
```

## Open questions

1. **Should the image fail closed without an allowance?** Recommended: yes, and
   it is the entry that needs your judgement most — it changes the server, not
   the packaging, and it makes the container behave unlike a local install.
   The alternative is documenting the mount loudly and trusting the reader.
2. **How does the image know it is meant to fail closed?** A local install and
   the image run the same code, and only one of them should refuse an absent
   allowance. Options: an environment variable the image sets, or a flag on the
   entry point. Recommended: an environment variable — a flag is something a
   client's `command` has to carry and get right.
3. **Multi-arch on every tag, or amd64 on prereleases?** Emulated arm64 builds
   are slow. Recommended: both every time — an rc nobody can run on a Mac is an
   rc nobody tests.
4. **Where do reports go?** `report` writes into the container. Recommended: a
   documented `-v` for an output directory, and `summary` named first, because it
   needs no filesystem at all.
