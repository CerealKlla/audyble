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

**Phases 1-4 implemented and boot-tested 2026-10-15; Phase 5 scaffolded, unverified.** See
`context/decisions.md`'s 2026-10-15 entry for the full account, including real SVC API research
(cloned and read the actual `henkelmax/simple-voice-chat` source rather than guessing) and what's
still open.

- Loader: **NeoForge**, Minecraft **26.1.2**, Java **25**
- Group ID: `com.github.cerealklla.audyble` / Mod ID: `audyble`
- SVC dependency fully wired: `de.maxhenkel.voicechat:voicechat-api:2.6.20` (compile), real runtime
  mod jar via `maven.modrinth:simple-voice-chat:neoforge-2.6.20+26.1.2` (dev/test only), hard
  `required` dependency on `voicechat_api` in `neoforge.mods.toml`.
- `voice.AudyblePlugin` — the real SVC `VoicechatPlugin`, intercepting `EntitySoundPacketEvent` for
  real player-to-player proximity voice. `voice.TreatmentEngine` resolves `Speaker -> Listener`
  treatment (occlusion via `voice.AcousticOcclusion`, gameplay hooks via `voice.GameplayHearing` —
  the Radio item, Deafened/Keen Hearing status effects); `voice.AudioProcessor` does the real opus
  decode/filter/encode per pair. `api.AudybleApi`/`TreatmentModifier` is the cross-mod extension
  point the design doc asked for.
- `npc.*` — Phase 5 scaffold (Ollama/Kokoro/Whisper HTTP clients, PCM/WAV utilities, the
  conversation-pipeline orchestrator, a `/audyble npc bind` command). Off by default
  (`AudybleConfig.NPC_CONVERSATION_ENABLED`), **entirely unverified** — no external LLM/TTS/STT
  server has ever actually run against this code.
- **Confirmed live via a real `./gradlew runServer` boot** with the actual SVC mod present: clean
  mod loading, SVC plugin registration, config/registries, server reaches `Done (...)!`. **Not yet
  confirmed**: any actual audible effect from two real people talking (see decisions.md's Pending
  work list for the full set of open items to hand back to the user).
- 17 unit tests (pure logic only — `VoiceTreatment`, `AcousticOcclusion`'s math, `PcmAudio`'s WAV/
  resample/chunking round-trips), `./gradlew build`/`test` green.
