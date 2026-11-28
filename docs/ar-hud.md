# The AR HUD and the speed limit under Android Auto (and CarPlay's turns)

How the Wireless mod gets the AR HUD to animate Android Auto's turns, and what it will
take to fill the cluster's distance bar without losing the car's speed-limit sign (the
sections marked "Planned" aren't in the mod yet). Worked out
on a US Ioniq 5 with the AR HUD (NEEV23, head unit 260507, November 2026) from
`dumpsys com.hkmc.micom.cluster`, logcat and the cluster service's logs on drives with
the car's navigation and with Android Auto. Nothing here is the car's code; it describes
what the car does. [cluster-turns.md](cluster-turns.md) has the full turn format.

## What triggers the AR HUD

The AR HUD draws its animated arrow from the cluster's **main turn display**
(`NavigationCluster.updateTBTInfo`) alone:

- **The turn type has to be one of the car's own `Navigation.DisplayType` values**
  (`TURN` 13 with its angle, `U_TURN` 31, `FORK_2` 25, `EXIT_RIGHT`/`EXIT_LEFT` 17/18,
  roundabouts 32/33). Android Auto's turns used to go out as the projection icons (64 and
  up). The cluster and the HUD's turn list draw those fine, but the AR HUD doesn't
  animate them. The car's navigation sends its own types only near a maneuver (about
  457 m, 1,500 ft) and the icons farther out, so the mod does the same.
- **The direction is the maneuver's angle** in 45 degree steps (0, 45/315, 90/270,
  135/225).
- **Side streets have to be 0.** The projection icons always carried 65535; with a `TURN`
  type that draws a road in every direction around the arrow. `dumpsys` shows it the
  other way round (0 as every index, 65535 as "None").
- **Nothing else is needed.** In AR mode the car's navigation sent no ADASIS or other
  map data and no `updateCluDataNew_14` pattern, so the turn type, angle, distance and
  bar are all the AR HUD gets.

This is the `tbt_standard` setting ("Android Auto turns in the car's own format", off by
default), `AaTurns.standard()`.

### CarPlay

The same setting puts CarPlay's turns in the car's own format too (`CpTurns.standard()`).

**The window:** within 457 m of a maneuver, by the phone's distance to it (RouteGuidanceUpdate item 10, meters).

**The mapping:** from the iAP2 maneuver (ManeuverType, driving side, junction, exit angle) and its icon.

| ManeuverType | Sent near the maneuver |
|---|---|
| 3 straight | `TURN` 0 |
| 1 / 2 left / right, 20 / 21 left / right at the end of the road | `TURN` 270 / 90, or the exit angle's 45 degree step |
| 49 / 50 slight left / right | `TURN` 315 / 45 |
| 47 / 48 sharp left / right | `TURN` 225 / 135 |
| 4 U-turn, 26 U-turn when possible | `U_TURN` 270 (90 for left-hand traffic) |
| 52 / 53 change highway left / right, 13 / 14 keep left / right | `FORK_2` 270 / 90 |
| 22 / 23 highway off-ramp left / right, with or without an exit angle | `EXIT_LEFT` / `EXIT_RIGHT` |
| 8 off-ramp with no side | `EXIT_RIGHT` / `EXIT_LEFT` by the exit angle's side, else the side traffic keeps to (right in the US) |
| roundabout exit icons (96-107, 192-203) | `EXIT_ROUNDABOUT_TRS_RIGHT` 32 (33 for left-hand traffic) with the icon's direction |
| arrival icons 112/113/114 | the icon, no bar; `ARRIVED` within 20 m |

On-ramps and ferries keep their icon, with the bar. CarPlay has no reroute status in its maneuvers, so there is no "Recalculating..." for it.

Farther out, and with the setting off, 8, 13 / 14, 20 / 21 and 26 get the icons of the
maneuvers they're like (exit 81 / 82, fork 174 / 173, turn, U-turn). The turn-by-turn
build's table (ported from DiPO) gave them none, nor 22 / 23 with an exit angle, so nothing
was sent for them. [Evergreen](https://github.com/KSubedi/gen5w-Evergreen)'s CarPlay AR
builds (1.2.0.104 to 1.2.0.112, notes/carplay-ar.md) found this on a US EV6 (260507) with
Apple Maps: its highway exits came as 8 with no exit angle, and keep left / right as 13 / 14.

Two more of its findings, in both formats:

- **The previous turn is cleared** when the phone's current maneuver changes to one with no
  icon. Before, the old turn and HUD list stayed up (Apple Maps guiding to an exit a mile on
  while the cluster still showed "straight, 10 ft"). The same maneuver losing its icon (an
  update without its optional exit angle) keeps what it showed.
- **A turn goes out when its details arrive.** If the phone's route update names a current
  maneuver whose details haven't come yet, the turn is sent when they do, not at the next
  route update. While the car stands the phone sends none, so a route started parked
  stayed blank until the car moved.

**Side streets** (setting `tbt_side_streets`, off by default, needs `tbt_standard`): near a
turn, the `TURN` frame carries the iPhone's junction as side streets, so the cluster draws
the intersection around the arrow ([cluster-turns.md](cluster-turns.md#near-a-maneuver)).
**Not yet checked on a car.** The AR HUD was only ever seen with side streets 0 (and 65535,
which draws a road every way); whether it still animates with a real junction is the thing
to check first. With the setting off, nothing changes.

**Logs:** HMG-API's log gets one line per maneuver: `CarPlay turn: type … side … junction … exit … angles […] -> icon …, near: type … dir … sides 0x…`. `exit none` and `angles none` mean the iPhone didn't send them, and `(off)` after the side streets means the setting is off and 0 went out. So a drive with any app shows how many of its turns have a junction.

**Checked on a car:** a Korean EV6 (CVEV.KOR, 260902) with the AR HUD, November 2026, by hwjang1, with Naver Map and T map on wireless CarPlay.
- Right, left, slight left and U-turns animated as the car's own navigation's do.
- The Korean firmware's `Navigation.DisplayType` values are the same.
- The owner's build also gives icons to the exit-angle-less slight turn (49) these apps send. Here it only goes out when an icon does.
- Not yet seen on a drive: forks, highway exits, roundabouts and arrival.

The maneuver line is logged with the setting off too, without `near:`; icon 0 means nothing
went to the cluster for that maneuver. Alongside it: one line per route change
(`CarPlay route: component … state …, maneuver … of …, distance …`), the app's name
(`CarPlay: route guidance source …`) and every time DiPO stops route guidance (issue #24).

### Planned: the arrow gliding with the exact distance

> **Not in the mod yet.** The mod still sends Android Auto's display distance. This part
> has only been in test builds (2.2.1-test.2) on one car so far.

The arrow moves toward the turn as the distance drops. Android Auto's turn-by-turn
reports the **display** distance once a second (50 ft or 0.1 mi steps), so the arrow
steps and lags. Near a maneuver the standard format will send:

- **Maps' exact distance** (meters, converted to the cluster's unit) instead of the
  rounded one;
- **a countdown between Maps' reports**: every 250 ms the distance will drop at Maps' own
  pace (its meters and seconds to the turn), for at most 1.5 s after its last report.
  If Maps goes quiet, the distance holds instead of running past the turn.

## The distance bar and the speed limit

### The problem

The bar that fills as the turn gets close isn't drawn from the turn frame. The cluster
draws it from the **trap frame** (`NavigationCluster.updateTBTTrapInfo`, field
`barGraphLevel`). The cluster service packs that call into one frame to the cluster, and
the same frame carries the speed limit:

| Byte | What |
|---|---|
| 8 | `barGraphLevel`: the distance bar |
| 9 | `speedLimit` |
| 10 | bus speed, speed-limit type and unit |
| 11 | speed info type |

**Whoever sends the trap frame last sets both the bar and the speed limit.**

While a phone guides, the car's navigation keeps running with no route. It sends the
trap frame **only when something changes** (the map's speed limit going 25 → 35 → 45,
mostly), with the bar 0 and its own map speed limit, unit (mph) and type. That's why
the bar stayed empty under Android Auto. The car's speed-limit sign comes from that map
data: in every capture, the speed-limit echo from the cluster (`NaviSLIFG3Info`, byte 14)
matched the car navigation's limit within 1 to 2 s. No camera (TSR) speed limits showed
up in any capture.

### What doesn't work

- **Sending the trap frame from Android Auto's side (GPM).** GPM has no idea what the
  speed limit is. Its own built-in trap call sends "no limit" (255) with the bar 100, and
  a frame with anything but the car navigation's limit replaces the sign until the car's
  navigation sends again, which can be minutes on one road.
- **Reading the limit from the cluster's echo and sending it back.** It arrives 1 to 2 s
  late, so the sign would be wrong at every change. It also only comes in through a
  listener class, and the mod would have to hard-code the US unit and type values, which
  Canada's cars don't use.

### Planned: the bar in the car navigation's own frame

> **Not in the mod yet.** On today's mod the bar stays empty under Android Auto, and the
> speed limit is the car navigation's. This part has only been in test builds
> (2.2.1-test.2) on one car, with a hand-patched hook map. It needs four HMG-API hook
> points that haven't been proposed to HMG-API yet (below).

The bar will go the other way: from Android Auto's side to the car's navigation, which
already has the right speed limit.

1. **In GPM**, with standard turns on, `AaTurns` will work out the bar the way the car
   does, `(100 - progress) / 10`, at least 1, over about 457 m, sent ×10 (90 down to 10),
   and broadcast it (`hmgapi.mods.wireless.TBT_BAR`, extra `bar`) when it changes, every
   2 s while it's on, and 0 when guidance stops.
2. **In the car's navigation** (`com.mnsoft.navi`), a Wireless hook on
   `updateTBTTrapInfo` (both forms: 17 ints, or `NaviTbtTrapInfo`) will put the bar into
   each trap frame the navigation sends. Everything else in the frame stays the car's:
   the speed limit, its unit and type, cameras and warnings.
3. **When the bar changes**, the hook will send the car navigation's last trap frame
   again with the new bar, since the navigation itself only sends on its own changes.
4. **It fails safe.** If the bar is more than 5 s old, or no phone is guiding, the frame
   goes through as the car's navigation sent it.

**What it needs from HMG-API first:** four framework hook points,
`framework.navigation_cluster.update_tbt_trap_info`,
`framework.navigation_cluster.update_tbt_trap_info_new`, `framework.navi_tbt_trap_info`
and `framework.navi_tbt_trap_info.bar_graph_level`. HMG-API turns off all of a mod's
hooks on a car whose map lacks a point the mod declares, so they have to be in HMG-API's
catalogue and mapped on every car before the bar can ship in Wireless: a HMG-API pull
request (not opened yet), then `map-car.py` on the stock trees, or "Map this car from its
files" on cars mapped from Car Reports.

## Checking on a car

- `adb shell dumpsys com.hkmc.micom.cluster`: the last turn frame (type by name,
  direction, distance) and trap frame (bar, speed limit) the cluster service got.
- logcat, tag `ClusterService`: each `NaviCluster.updateTBTTrapInfo()` with its
  `barGraphLevel` and `speedLimit`. Today, under Android Auto the bar stays 0 and the
  speed limit is the car navigation's. With the planned bar, the bar would count down 90
  to 10 toward each turn and the speed limit stay the car navigation's.
- With the planned bar only, HMG-API's log (`/data/hmgapi/logs/hmgapi/log.log`):
  "navigation: Android Auto's distance bar goes in its trap frame (com.mnsoft.navi)" once
  the hook is in, or "...is off on this car (... not mapped)" when the car's map doesn't
  have the points.
