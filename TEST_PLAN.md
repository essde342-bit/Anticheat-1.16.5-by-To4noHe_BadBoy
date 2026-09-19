# EssGuard 0.3.0 — False-Positive-First Test Plan

Use this on a private Paper 1.16.5 test server. EssGuard only reports; it does not punish.

## Phase A — Vanilla must stay silent

1. Walk normally for 2-3 minutes.
2. Sprint in straight lines.
3. Sprint-jump repeatedly.
4. Strafe while sprinting.
5. Walk backwards and sideways.
6. Sneak-walk.
7. Shield while walking.
8. Fight a player at normal melee distance.
9. Fight while the target is moving.
10. Fight with attacker ping around 50-100 ms.
11. Fight with target ping around 50-150 ms.
12. Run across slabs.
13. Run up/down stairs.
14. Walk on carpet and pressure plates.
15. Walk over soul sand.
16. Walk on ice/packed ice/blue ice.
17. Jump near ledges.
18. Get pushed by another player.
19. Take normal knockback.
20. Get hit while sprinting.
21. Teleport with /tp.
22. Join and immediately move.
23. Go through water normally.
24. Swim normally.
25. Use a riptide trident.
26. Use elytra/gliding.
27. Enter/leave vehicles.
28. Stand still for a long time.
29. Build and move around custom-looking terrain.
30. Repeat with temporary server lag.

Expected result: no EssGuard alert during ordinary vanilla behavior.

## Phase B — Positive movement tests

Test one behavior at a time and leave each module active long enough to create a sustained pattern.

- Speed: sustained horizontal movement above the vanilla envelope.
- Fly: stable horizontal travel while airborne without normal support.
- Fly hover: remain at nearly constant Y while moving horizontally in open air.
- Fly rise: repeated vertical rises that do not match ordinary jump physics.
- Jesus: move continuously across a water surface without normal swimming/water contact.
- Hitbox/Reach: create clearly excessive attack distance and repeat the attack rather than relying on one borderline hit.

Expected result: the relevant check accumulates VL and eventually reports it in chat/console.

## Phase C — Edge cases

Repeat positive tests with:

- high ping;
- recent knockback;
- recent damage;
- teleport;
- ice/slime/honey;
- stairs/slabs/carpet;
- water/swimming;
- vehicles;
- creative/spectator;
- large server tick delay.

Expected result: EssGuard should suppress or decay ambiguous evidence instead of escalating it into an immediate alert.

## Reading an alert

Example:

    [EssGuard] Player -> Speed (VL 7.1) window=1.34x limit=0.332 severe=4/10

The VL is an evidence level, not a percentage probability of cheating.

## Important

A test that produces no alert does not automatically mean the check is broken. The project deliberately prefers false negatives over false positives. A packet-level engine would be the next major architectural upgrade for stronger detection without making Bukkit-side heuristics more aggressive.
