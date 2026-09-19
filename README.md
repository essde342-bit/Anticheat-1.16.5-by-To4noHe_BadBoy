# EssGuard 0.3.0 — Paper 1.16.5

Observation-only anti-cheat for Paper 1.16.5.

## Goal

The main design goal is conservative detection: a single weird tick is not treated as proof. Evidence is accumulated over time, with grace periods for server lag, teleportation, velocity, damage and special movement media.

## Checks

- Speed: rolling 10-tick window, potion-aware movement envelope, repeated overspeed evidence, separate severe/sustained paths.
- Fly: hover evidence + vertical prediction residual + impossible-rise evidence. Normal Fly alerts require two independent signals.
- Jesus: persistent movement on a water surface while not actually swimming/in water, with two independent surface signals.
- Hitbox: server AABB distance, ray/AABB geometry, facing consistency, attacker/target ping allowance and target-motion allowance.

## Anti-false-positive architecture

- Server tick gap / lag invalidates the sample instead of feeding it into checks.
- Teleport, velocity and recent damage create temporary grace windows.
- Creative/spectator, flight permission, vehicles and movement mediums are exempted.
- Violation levels decay over time.
- Clean windows reduce streaks.
- Per-check alert cooldown prevents chat spam.
- `essguard.bypass` can exempt staff/test accounts.

## Important limitation

A Bukkit/Paper plugin without packet-level access cannot reproduce the complete Minecraft client physics simulation or directly observe a modified client hitbox. That means the plugin intentionally uses conservative server-observable consequences rather than claiming impossible certainty.

## Build

Java 8 source/target. Run:

    mvn clean package

The GitHub Actions workflow builds `EssGuard-1.16.5.jar` as an artifact.

## Admin

    /essguard reload

Alerts are sent only to players with `essguard.alerts` (default: OP) and to the console. The plugin does not automatically punish anyone.

## Open-source references used for design study

The architecture was informed by public/open-source anti-cheat projects and documentation, especially Grim AntiCheat and NoCheatPlus: layered movement checks, reach checks, prediction-oriented movement, exemptions, lag/velocity handling and explicit false-positive tracking.

No proprietary source code is copied.
