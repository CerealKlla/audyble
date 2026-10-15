# Audyble

Advanced positional/acoustic voice chat for the Kyngdoms suite, built on top of **Simple Voice Chat**
(SVC) as the underlying voice transport rather than a from-scratch VoIP system. The distinctive
contribution is a per-listener audio treatment layer: the same speaker can sound different to
different listeners depending on world geometry (walls, distance, rooms) and gameplay state
(equipment, radios, status effects) — `Speaker -> Listener = treatment`, not one global voice
distance.

Local speech recognition and automated voice moderation (the client-side STT + evidence/decay +
encrypted-reporting system from the original design pitch) are **explicitly out of scope for this
mod** — split out to a likely-future separate mod, per the user's own direction, 2026-10-14. Audyble
should expose clean extension points so that future mod can plug into the voice pipeline without
Audyble needing a redesign, but builds none of that itself.

## Context directory — read this first

`context/` is a **separate private repo** (https://github.com/CerealKlla/audyble-context), not part
of this one — listed in `.gitignore` here, cloned as a subdirectory for local convenience. If missing:

```
git clone https://github.com/CerealKlla/audyble-context.git context
```

- `context/design-document.md` — the user's original design pitch verbatim, plus the scope split
  decision and the phased development plan for Audyble specifically. Start here.
- `context/decisions.md` — dated log of decisions + pending/cross-mod-interface facts.
- `context/classes/` — one short markdown file per implemented class, once there is one.

Keep this current the same way every other mod in this suite does: update design-document.md/
decisions.md when a decision is made, update classes/ when a class's public surface changes,
commit+push context/ separately from this repo's own commits.

## Status

**Scaffolded 2026-10-14 — planning stage only.** Bare NeoForge mod shell (mirrors
TinyWyrlds'/Kyt's own project shape):

- Loader: **NeoForge**, Minecraft **26.1.2**, Java **25**
- Group ID: `com.github.cerealklla.audyble` / Mod ID: `audyble`
- Hard dependency declared on Simple Voice Chat (`voicechat`) in `neoforge.mods.toml` — the real
  Gradle dependency coordinate/version isn't wired into `build.gradle` yet, deliberately: confirming
  it is itself Phase 1 work (see `context/design-document.md`), not something to guess at scaffold
  time.
- `AudybleMod.java` is the minimal possible mod class — `@Mod` registration + a log line on common
  setup. No registries, no event listeners, no feature code. `./gradlew build` green.
- No implementation work has started. See `context/design-document.md` for the full phased plan
  before picking up Phase 1.
