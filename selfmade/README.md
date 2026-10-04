# The Self Made Flesh: Slice 0 (Fabric, Minecraft 26.3)

Working title. "Eidolon" is already the name of an existing Minecraft mod, so the mod id here is `selfmade`
(change it in `fabric.mod.json`, `SelfMade.MOD_ID` and the package name whenever you pick a real title).

## What this is
Step 1 of the build order: the **identity engine**. It watches what each player does, keeps eight hidden trait
scores, remembers where you died, and quietly starts to haunt those places.

There is **no** Eidolon entity, mirror, Memory Fragment, Inner World or final boss yet. They all need to read from
this first.

## What you will see in game
- Soul particles at places you have died. Die 3+ times in one spot and the place gets heavier, with a whispered message.
- A one-time whispered omen the first time a trait settles in (the game never shows numbers).
- `/eidolon read`: a vague hint of what is starting to follow you.
- `/eidolon debug`: raw trait numbers. Dev tool only; anyone can run it for now.

## What it watches
| Trait | Signal |
|---|---|
| Violence | Kills (innocents like villagers count most, animals least). Repeating the same kill quickly is worth less each time, so mob farms barely move you. |
| Fear | Dying; running away after nearly dying to a creature. |
| Resolve | Holding your ground after a close call; killing the thing that nearly killed you; going back to where you died. |
| Will | Going back to where you died within 10 minutes; pressing on after a close call. |
| Curiosity | Entering a 64x64-block patch of the world you have never stood in. |
| Ego, Empathy, Attachment | **Not wired yet.** They need hooks Fabric API does not expose (mixins). Good next step. |

Old behaviour fades slowly (only while you are online), so people can change.

## Getting the jar

**No installs (GitHub builds it for you)**
1. Make a free GitHub repository and upload everything in this folder, including the hidden `.github` folder.
2. Open the repository's **Actions** tab, pick **build**, and press **Run workflow**.
3. When it finishes, open the run and download the **selfmade-jar** artifact. Unzip it to get the `.jar`.

**On your own computer**
Install JDK 25 and Gradle 9.5.1 or newer, then run `gradle build` in this folder.
The jar is created at `build/libs/selfmade-0.1.0.jar`.

## Playing it
You need Minecraft Java 26.3, Fabric Loader, and Fabric API for 26.3 (0.160.5+26.3 or newer).
Put Fabric API and `selfmade-0.1.0.jar` in your `mods` folder.

## If the build fails
This was written against Minecraft 26.3 (released 15 September 2026) without being compiled, so the most likely
causes are a changed method name in the game or a version number in `gradle.properties`.
Fabric's current recommended versions are listed at https://fabricmc.net/develop/.
Send the first error in the log to whoever helped you set this up and it should be a quick fix.
