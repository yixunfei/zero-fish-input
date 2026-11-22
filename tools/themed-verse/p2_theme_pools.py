"""Fixed, public scenery rules for the P2 five-character capacity comparison."""
from collections import Counter


FAMILIES = (
    "天日月星云霞风雨雪霜露雾雷晴夜朝暮晨晓春夏秋冬晖烟夕寒阴",
    "江河海湖溪川水泉潮波浪潭池浦渚岸渡舟船帆瀑沧沙津湾汀",
    "山峰岭岫崖岩壑石松林竹木树花草叶柳梅桃谷苔径坡丘庭",
)
FAMILY_INDEX = {char: i for i, family in enumerate(FAMILIES) for char in family}
RULES = ("same-image", "same-family", "family-or-next")


def last_image(line):
    for char in reversed(line):
        if char in FAMILY_INDEX:
            return char
    raise ValueError("line has no configured scenery marker")


class ThemePools:
    def __init__(self, theme, pools, rule):
        if rule not in RULES:
            raise ValueError("unknown scenery rule")
        self.theme = theme
        self.pools = pools
        self.groups = tuple(pools.values())
        self.rule = rule

    def pool(self, mode, previous):
        if mode != "linked":
            raise ValueError("unknown configuration")
        if not previous:
            return self.theme
        image = last_image(previous)
        key = image if self.rule == "same-image" else str(FAMILY_INDEX[image])
        return self.pools[key]

    def to_record(self):
        return {"rule": self.rule, "families": FAMILIES, "opening": self.theme, "pools": self.pools}


def build_pools(five):
    by_char = {char: [] for char in FAMILY_INDEX}
    by_family = [[] for _ in FAMILIES]
    opening = []
    for line in five:
        marks = set(line).intersection(FAMILY_INDEX)
        if not marks:
            continue
        opening.append(line)
        for char in marks:
            by_char[char].append(line)
        for group in {FAMILY_INDEX[char] for char in marks}:
            by_family[group].append(line)
    family = {str(i): pool for i, pool in enumerate(by_family)}
    next_family = {str(i): sorted(set(pool).union(by_family[(i + 1) % len(FAMILIES)]))
                   for i, pool in enumerate(by_family)}
    return [ThemePools(opening, pools, rule)
            for pools, rule in zip((by_char, family, next_family), RULES, strict=True)]


def line_budget(target_bytes):
    # P1 layout spends 18 UTF-8 bytes on each line and adds LF after every fourth.
    return (4 * target_bytes + 4) // 73


def fits_budget(row, head, transition):
    lines = line_budget(row["target_bytes"])
    return lines > 0 and head * transition ** (lines - 1) >= (1 << (8 * row["envelope_bytes"]))


def capacity_bounds(rows, opening, branches):
    sizes = list(map(len, branches))
    smallest, largest = min(sizes), max(sizes)
    return {"opening": len(opening), "min_branch": smallest, "max_branch": largest,
            "branches": sizes if len(sizes) <= len(FAMILIES) else None,
            "lower_passes": sum(fits_budget(row, len(opening), smallest) for row in rows),
            "upper_passes": sum(fits_budget(row, len(opening), largest) for row in rows)}


def carrier_lines(surface):
    return surface.decode("utf-8")[:-1].replace("\n", "").replace("，", "。").split("。")


def continuity(lines):
    marks = [set(line).intersection(FAMILY_INDEX) for line in lines]
    families = [{FAMILY_INDEX[char] for char in group} for group in marks]
    family_links = same_image_links = same_or_next_links = adjacent_repeats = 0
    first_missing_image = None
    for index, (previous, current) in enumerate(zip(lines, lines[1:])):
        pivot = last_image(previous)
        group = FAMILY_INDEX[pivot]
        same_image_links += pivot in marks[index + 1]
        family_links += group in families[index + 1]
        same_or_next_links += bool(families[index + 1].intersection((group, (group + 1) % len(FAMILIES))))
        adjacent_repeats += previous == current
        if first_missing_image is None and pivot not in marks[index + 1]:
            first_missing_image = [previous, current]
    counts = Counter(lines)
    return {"lines": len(lines), "pairs": len(lines) - 1, "same_image": same_image_links,
            "same_family": family_links, "same_or_next": same_or_next_links,
            "repeated_lines": len(lines) - len(counts), "adjacent_repeats": adjacent_repeats,
            "first_pair_without_pivot": first_missing_image}
