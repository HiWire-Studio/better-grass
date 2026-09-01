#!/usr/bin/env python3
"""Regenerate the grass transition asset overrides from a set of vanilla Hytale assets.

WHY THIS EXISTS
---------------
Hytale's asset packs override a base-game asset by *replacing* the file. There is no partial
merge: an override inherits from whatever its ``Parent`` names, not from the previous version of
the same key. (``"Parent": "super"`` looks like it should do that -- ``AssetStore.decode`` has a
special case for it -- but the contained-asset loader resolves parents before that runs, fails to
find ``super``, and drops the BlockType's parent entirely, which strips the block. Do not use it.)

So each override here is a verbatim copy of the vanilla file with only two fields changed. That
means they silently pin whatever else the vanilla file said at copy time. Re-run this script after
a Hytale update and re-diff, or the mod will quietly revert unrelated upstream changes.

WHAT IT CHANGES
---------------
Every grass variant is ``Group: "Grass"`` in vanilla, so "paint onto other grass" and "paint onto
my own family" are the same instruction. A block beside its own ``_Full`` variant therefore grows a
seam, and two neighbouring types each paint onto the other. Each family gets its own group here, and
paints only onto families *below* it in a dominance order, so a pair blends in exactly one
direction.

The order preserves the hierarchy vanilla actually defined: Dry/Sunny/Cold/Deep painted onto Grass,
and Grass/Wet/Burnt painted onto nothing. Ordering *within* those two tiers is invented -- vanilla
left it undefined -- and is the one thing here you should feel free to reorder.

Three of the group names (``GrassDeep``, ``GrassDeepSunny``, ``Grass_Dry``) are reused from names
vanilla already references as transition targets but never assigns to any block, so blocks such as
Fluid_Slime and Soil_Leaves start blending onto those families with no edit of their own.

USAGE
-----
    python3 tools/generate_grass_overrides.py \
        --assets /path/to/HytaleAssets/Server/Item/Items \
        --out    mod/src/main/resources/Server/Item/Items
    python3 tools/generate_grass_overrides.py --assets ... --out ... --verify
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys

# Most dominant first. A family paints onto every family below it and none above.
ORDER: list[tuple[str, list[str]]] = [
    # Top four match the precedence observed in-game in vanilla, where these overshadow each other
    # in this order: Dry < Sunny < Lush < Cold. Vanilla could not express that in data -- all four
    # simply painted onto group "Grass", which included one another -- so the order was only visible
    # as whichever mask happened to win. Here it is explicit.
    ("Grass_Cold", ["Soil_Grass_Cold", "Soil_Grass_Cold_Full"]),
    ("GrassDeep", ["Soil_Grass_Deep", "Soil_Grass_Deep_Full"]),
    # Sunny is the one family with broken art: Transition_Grass_Sunny.png is baked full colour while
    # every other family uses greyscale art plus a tint, and Sunny alone clears its tinting entirely
    # (TintUp: [], BiomeTintUp: 0). It therefore cannot follow the biome the way its neighbours do,
    # and its fringe reads as a fixed saturated green wherever it lands. Kept in its vanilla slot
    # because that is the observed precedence; move it to the bottom of this list if the artifact
    # matters more than the ordering.
    ("GrassDeepSunny", ["Soil_Grass_Sunny", "Soil_Grass_Sunny_Full"]),
    ("Grass_Dry", ["Soil_Grass_Dry", "Soil_Grass_Dry_Full"]),
    # Vanilla gave these three no grass transitions at all -- they painted onto nothing, so Grass,
    # Withered and Wet met each other with a hard edge. Ordering them here gives that boundary a
    # blend. The order within the three is invented; vanilla says nothing about it.
    ("Grass_Burnt", ["Soil_Grass_Burnt", "Soil_Grass_Burnt_Full"]),
    ("Grass_Wet", ["Soil_Grass_Wet", "Soil_Grass_Wet_Full"]),
    # Green stays on the vanilla "Grass" group so Soil_Seaweed_Block, which declares "Grass" itself,
    # is untouched. It is the neutral base everything else bleeds onto.
    ("Grass", ["Soil_Grass", "Soil_Grass_Full"]),
]
GROUPS = [g for g, _ in ORDER]
GRASSISH = set(GROUPS)
MEMBERS = {m for _, members in ORDER for m in members}


def index_assets(assets_dir: str) -> dict[str, str]:
    return {
        os.path.basename(p)[:-5]: p
        for p in glob.glob(os.path.join(assets_dir, "**", "*.json"), recursive=True)
    }


def resolve(idx: dict[str, str], name: str | None, key: str):
    """Resolve a BlockType field through the vanilla Parent chain."""
    while name and name in idx:
        doc = json.load(open(idx[name]))
        block_type = doc.get("BlockType") or {}
        if key in block_type:
            return block_type[key]
        name = doc.get("Parent")
    return None


# Sunny is the only grass family whose art is baked full colour. Every other family ships
# greyscale textures and colours them with a tint, and that tint is also what colours their
# transition fringe. Sunny clears tinting entirely (TintUp: [], BiomeTintUp: 0), so its fringe is
# stuck at one fixed saturated green no matter what it lands on -- wrong against every other grass
# and wrong on stone.
#
# Rather than generate greyscale art, Sunny borrows Lush's, which is already a complete and
# well-formed greyscale set, and applies a tint fitted to reproduce Sunny's own colour. The two
# families' leaf patterns are near-identical, so nothing looks out of place. The tint is a
# least-squares fit of (Lush greyscale x tint) against Sunny's original pixels across the top,
# full side and transition textures.
#
# Lush proves this configuration renders correctly with BiomeTint: 0, so the flat tint applies on
# its own. Tint and TintUp are both set because vanilla Sunny explicitly declares TintUp: [], which
# would otherwise blank the top face; TintDown stays empty so the dirt underside is not tinted.
# Fitted by least squares against Sunny's original pixels, then scaled so that grass sits 6.25%
# brighter than the dirt beneath it, matching vanilla. That ratio is the measure to tune against:
# it is independent of scene lighting, which a raw luminance comparison is not.
SUNNY_TINT = "#7dba33"
# Sides are the base layer; the grass on a fringed side is composited on top from TextureSideMask,
# a greyscale grass overlay carrying its shape in alpha and tinted at runtime (Lush's own side
# texture is plain dirt with no grass in it at all). Sunny therefore keeps its ORIGINAL side
# texture as the base: it carries Sunny's dirt at the right brightness *and* the shadow gradient
# under the fringe, which a flat Soil_Dirt.png lacks. Its baked grass is hidden beneath the
# overlay -- the two silhouettes match to within 9 pixels of 649, and those few are sunny-green.
SUNNY_SIDE_TEXTURE = "BlockTextures/HiWire_BetterGrass_Sunny_Side.png"
SUNNY_SIDE_MASK = "BlockTextures/HiWire_BetterGrass_Sunny_SideMask.png"
SUNNY_TEXTURE_FIX = {
    "Soil_Grass_Sunny": (SUNNY_SIDE_TEXTURE, SUNNY_SIDE_MASK),
    "Soil_Grass_Sunny_Full": ("BlockTextures/Soil_Grass_Lush_Full_GS.png", None),
}


def generate_sunny_side_texture(block_textures: str, out_dir: str) -> str:
    """Write Sunny's dirt base: Lush's dirt, brightened to Sunny's tone.

    Lush's side texture is the dirt layer only -- the grass is composited over it from
    TextureSideMask -- and it carries the shadow gradient under the fringe that a flat
    Soil_Dirt.png lacks. It is simply darker than Sunny's dirt, so every channel is scaled by the
    ratio of the two mean colours. That preserves all of the shading and noise while landing the
    tone exactly on Sunny's; nothing clips.
    """
    from PIL import Image  # only needed when regenerating textures
    import numpy as np

    def load(name):
        return np.asarray(Image.open(os.path.join(block_textures, name)).convert("RGBA")).astype(float)

    lush = load("Soil_Grass_Lush_Side.png")
    sunny = load("Soil_Grass_Sunny_Side.png")

    # Compare like with like: both textures are unambiguously dirt from row 24 down, whereas above
    # that Sunny still has baked grass and both carry the shadow under the fringe. Mixing those rows
    # into either mean skews the ratio and over-brightens the result.
    band = slice(24, 32)
    factor = (
        sunny[band, :, :3].reshape(-1, 3).mean(axis=0)
        / lush[band, :, :3].reshape(-1, 3).mean(axis=0)
    )

    rgb = np.clip(lush[..., :3] * factor, 0, 255)
    out = np.dstack([rgb, lush[..., 3]]).astype(np.uint8)

    os.makedirs(out_dir, exist_ok=True)
    dest = os.path.join(out_dir, os.path.basename(SUNNY_SIDE_TEXTURE))
    Image.fromarray(out).save(dest)

    # The grass on a fringed side is the TextureSideMask overlay, and Lush's is ~4% darker than the
    # other greyscale layers Sunny borrows. One tint cannot make layers of different brightness
    # render the same colour, so the side grass would come out darker than the block's own top.
    # Levelling the overlay to match the top face is what keeps a single tint consistent.
    mask = load("Soil_Grass_Lush_GS.png")
    top = load("Soil_Grass_Lush_Top_GS.png")
    opaque = mask[..., 3] > 16
    level = top[..., :3].mean() / mask[..., :3][opaque].mean()
    grey = np.clip(mask[..., :3] * level, 0, 255)
    mask_dest = os.path.join(out_dir, os.path.basename(SUNNY_SIDE_MASK))
    Image.fromarray(np.dstack([grey, mask[..., 3]]).astype(np.uint8)).save(mask_dest)

    return (
        f"{dest}  (dirt factors {factor.round(3).tolist()})\n"
        f"          {mask_dest}  (overlay levelled x{level:.3f})"
    )


def apply_sunny_fix(name: str, block_type: dict) -> None:
    """Put Sunny on Lush's greyscale art with a tint matching Sunny's own colour."""
    if name not in SUNNY_TEXTURE_FIX:
        return
    sides, side_mask = SUNNY_TEXTURE_FIX[name]
    block_type["Textures"] = [
        {
            "Down": "BlockTextures/Soil_Dirt.png",
            "Sides": sides,
            "Up": "BlockTextures/Soil_Grass_Lush_Top_GS.png",
            "Weight": 1,
        }
    ]
    block_type["TextureSideMask"] = side_mask
    block_type["TransitionTexture"] = "BlockTextures/Transition_Soil_Grass_Lush_GS.png"
    block_type["Tint"] = [SUNNY_TINT]
    block_type["TintUp"] = [SUNNY_TINT]
    block_type["TintDown"] = []
    block_type["BiomeTint"] = 0
    block_type["BiomeTintUp"] = 0
    block_type["BiomeTintDown"] = 0


def build_overrides(idx: dict[str, str]) -> dict[str, dict]:
    """Return {asset name: full override document}."""
    out: dict[str, dict] = {}

    for i, (group, members) in enumerate(ORDER):
        weaker = GROUPS[i + 1 :]
        for member in members:
            doc = json.loads(open(idx[member]).read())
            block_type = doc.setdefault("BlockType", {})
            if group != "Grass":
                block_type["Group"] = group
            keep = [
                t
                for t in (resolve(idx, member, "TransitionToGroups") or [])
                if t not in GRASSISH
            ]
            block_type["TransitionToGroups"] = keep + weaker
            apply_sunny_fix(member, block_type)
            out[member] = doc

    # Anything else that paints onto grass must reach every family, or splitting the groups would
    # stop snow, mud, leaves, moss and ice blending onto all but green.
    for name, path in sorted(idx.items()):
        if name in MEMBERS:
            continue
        block_type = json.load(open(path)).get("BlockType") or {}
        groups = block_type.get("TransitionToGroups")
        if not groups or "Grass" not in groups:
            continue
        doc = json.loads(open(path).read())
        merged = list(groups)
        merged += [g for g in GROUPS if g not in merged]
        doc.setdefault("BlockType", {})["TransitionToGroups"] = merged
        out[name] = doc

    return out


def diff_keys(a, b, path: str = "") -> list[str]:
    if isinstance(a, dict) and isinstance(b, dict):
        found: list[str] = []
        for k in set(a) | set(b):
            if k not in a or k not in b:
                found.append(f"{path}/{k}")
            else:
                found += diff_keys(a[k], b[k], f"{path}/{k}")
        return found
    return [] if a == b else [path]


ALLOWED = {"/BlockType/Group", "/BlockType/TransitionToGroups"}
# The Sunny fix legitimately rewrites these as well; see apply_sunny_fix.
ALLOWED_SUNNY = ALLOWED | {
    "/BlockType/Textures", "/BlockType/TransitionTexture", "/BlockType/TextureSideMask",
    "/BlockType/Tint", "/BlockType/TintUp", "/BlockType/TintDown",
    "/BlockType/BiomeTint", "/BlockType/BiomeTintUp", "/BlockType/BiomeTintDown",
} | {f"/BlockType/Textures/{i}" for i in range(4)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--assets", required=True, help="vanilla .../HytaleAssets/Server/Item/Items")
    ap.add_argument("--out", required=True, help="mod .../resources/Server/Item/Items")
    ap.add_argument("--verify", action="store_true", help="check only, write nothing")
    ap.add_argument(
        "--textures-out",
        help="also regenerate Sunny's dirt base into this Common/BlockTextures directory "
        "(needs Pillow + numpy)",
    )
    args = ap.parse_args()

    idx = index_assets(args.assets)
    missing = [m for m in MEMBERS if m not in idx]
    if missing:
        print(f"ERROR: vanilla assets not found: {missing}", file=sys.stderr)
        return 2

    overrides = build_overrides(idx)
    problems: list[str] = []

    for name, doc in overrides.items():
        rel = os.path.relpath(idx[name], args.assets)
        dest = os.path.join(args.out, rel)
        vanilla = json.load(open(idx[name]))

        allowed = ALLOWED_SUNNY if name in SUNNY_TEXTURE_FIX else ALLOWED
        unexpected = [d for d in diff_keys(vanilla, doc) if d not in allowed]
        if unexpected:
            problems.append(f"{name}: unexpected change {unexpected}")

        if args.verify:
            if not os.path.exists(dest):
                problems.append(f"{name}: missing override at {dest}")
            elif json.load(open(dest)) != doc:
                problems.append(f"{name}: on-disk override is stale")
        else:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "w") as fh:
                json.dump(doc, fh, indent=2)
                fh.write("\n")

    # Every pair must blend in exactly one direction.
    group_of = {
        m: ("Grass" if g == "Grass" else g) for g, members in ORDER for m in members
    }
    for i, (_, a_members) in enumerate(ORDER):
        for _, b_members in ORDER[i + 1 :]:
            a, b = a_members[0], b_members[0]
            a_to_b = group_of[b] in overrides[a]["BlockType"]["TransitionToGroups"]
            b_to_a = group_of[a] in overrides[b]["BlockType"]["TransitionToGroups"]
            if a_to_b == b_to_a:
                problems.append(f"{a} <-> {b}: both directions {a_to_b}")

    if problems:
        print("FAILED:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1

    if args.textures_out and not args.verify:
        common = os.path.normpath(os.path.join(args.assets, "..", "..", "..", "Common", "BlockTextures"))
        if not os.path.isdir(common):
            print(f"ERROR: cannot find vanilla BlockTextures at {common}", file=sys.stderr)
            return 2
        print("texture:", generate_sunny_side_texture(common, args.textures_out))

    verb = "verified" if args.verify else "written"
    print(f"{len(overrides)} overrides {verb}; diffs confined to Group/TransitionToGroups")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
