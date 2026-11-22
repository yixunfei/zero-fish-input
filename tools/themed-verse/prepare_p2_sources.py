"""Prepare P2 candidates from local, publicly licensed source snapshots."""
import argparse
import csv
from hashlib import sha256
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit

from p2_corpus import canonical_json, clipped, normalized_key


GOV_LICENSE = "https://data.gov.tw/license"
PG_LICENSE = "https://www.gutenberg.org/policy/license.html"
CATALOG = "https://gutenberg.pglaf.org/cache/epub/feeds/pg_catalog.csv"
HAN = re.compile(r"[\u3400-\u9fff]")
BOOK_START = re.compile(r"^\*{3}\s*START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\*{3}", re.M | re.I)
BOOK_END = re.compile(r"^\*{3}\s*END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK", re.M | re.I)
CHAPTER = re.compile(r"^[\s　]*第[〇零一二三四五六七八九十百千兩两０-９0-9]+[回章節节]")
FOREWORD = re.compile(r"^(?:自序|小序|代序|譯序|译序|編者序|作者序|目[次錄录]|校[點勘]|序[言文]?)$")
STORY_ANCHORS = {
    "25259": "上海的狐步舞",
    "25260": "豐收",
    "27636": "一",
}
GOV_SOURCES = {
    "exam": {
        "name": "考試院資訊處",
        "dataset": "考試院委託研究報告",
        "catalog": "https://data.gov.tw/dataset/158138",
        "download": "https://www.exam.gov.tw/OpenData.aspx?SN=26A0B1DA6A0EBAA2",
        "snapshot": "exam.checked",
    },
    "abri": {
        "name": "內政部建築研究所",
        "dataset": "內政部建築研究所全球資訊網_研究成果",
        "catalog": "https://data.gov.tw/dataset/25125",
        "download": "https://www.abri.gov.tw/OpenData.aspx?SN=E35AF0F8413059E5",
        "snapshot": "gov-25125-0.raw",
    },
    "ndc": {
        "name": "國家發展委員會人力發展處",
        "dataset": "人力供需議題相關研究報告",
        "catalog": "https://data.gov.tw/dataset/55806",
        "download": "https://ws.ndc.gov.tw/001/administrator/10/relfile/0/10565/b4d1c5af-c36d-4b85-a74c-2f9d83d2d298.csv",
        "snapshot": "gov-55806-0.raw",
    },
}


class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.hidden += 1
        if tag == "br" and not self.hidden:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self.hidden:
            self.hidden -= 1
        if tag in ("p", "div", "li", "tr", "blockquote") and not self.hidden:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def plain(value):
    parser = TextExtractor()
    parser.feed(value or "")
    parser.close()
    text = "".join(parser.parts).replace("\r\n", "\n").replace("\r", "\n")
    return re.sub(r"\n{3,}", "\n\n", "\n".join(line.strip() for line in text.split("\n"))).strip()


def author_names(*fields):
    names = set()
    for field in fields:
        text = re.sub(r"[（(][^）)]*[）)]", "", plain(field))
        for piece in re.split(r"[、，,；;／/\n]|及|與", text):
            candidate = re.sub(r"\s+", "", piece).strip("。．.")
            candidate = re.sub(r"(?:副教授|教授|研究員|助理|博士|先生|女士)$", "", candidate)
            if candidate in ("", "-", "無", "同上"):
                continue
            if not re.fullmatch(r"[\u3400-\u9fff]{2,6}", candidate) or candidate.endswith("等"):
                return []
            names.add(candidate)
    return sorted(names)


def eligible_text(body):
    try:
        raw, _ = clipped(body)
    except ValueError:
        return False
    text = raw.decode()
    return len(HAN.findall(text)) >= 200 and not re.search(
        r"(?i)\bhttps?://|[\w.+-]+@[\w.-]+\.[a-z]{2,}|(?:電話|聯絡方式)\s*[:：]", text)


def government_record(kind, item):
    if kind == "exam":
        title, url = item["title"], item["網址"]
        authors = author_names(item["研究主持人"], item["撰稿人"])
        first, second = plain(item["中文摘要"]), plain(item["內容"])
        body, field = (first, "中文摘要") if eligible_text(first) else (second, "內容")
        year = item["研究年度（民國年）"]
    elif kind == "abri":
        title, url = item["title"], item["Source"]
        authors = author_names(item["計畫主持人"], item["協同主持人"])
        body, field = plain(item["中文摘要"]), "中文摘要"
        date = item["執行行程(結束)"]
        if date and re.fullmatch(r"\d{4}-\d{2}-\d{2}", date) and date > "2026-11-20":
            return None
        year = date
    else:
        title, url = item["研究報告名稱"], item["資料下載網址"]
        authors = author_names(item["作者"])
        body, field = plain(item["資料說明"]), "資料說明"
        year = item["年度"]
    if not authors or not url or not title or not eligible_text(body):
        return None
    url = url.strip()
    if not url.startswith("https://"):
        return None
    title = plain(title)
    slug = re.sub(r"^[0-9]{2,4}\s*年度\s*", "", title)
    slug = re.sub(r"[（(](?:[一二三四五六七八九十\d]+|[IiVvXx]+)[）)]$", "", slug)
    record = parse_qs(urlsplit(url).query).get("s", [""])[0]
    suffix = record if record.isdecimal() else sha256(url.encode()).hexdigest()[:16]
    return ({"id": f"{kind}-{suffix}", "genre": "work-record", "source": kind, "title": title,
             "source_url": url, "authors": authors, "work_group": f"report-{normalized_key(slug)}",
             "publication": year, "source_field": field}, body)


def narrative_lines(book_id, text):
    start = BOOK_START.search(text)
    end = BOOK_END.search(text)
    if not start or not end or start.end() >= end.start():
        return None, None
    if re.search(r"posted with (?:the )?permission of|copyrighted project gutenberg", text[:start.start()], re.I):
        return None, None
    body = text[start.end():end.start()]
    if re.search(r"<br[\s/>]", body[:16000], re.I):
        body = plain(body)
    lines = body.splitlines()
    anchor = STORY_ANCHORS.get(book_id)
    if anchor:
        hits = [n for n, line in enumerate(lines) if line.strip().strip("〈〉《》") == anchor]
        if hits:
            # Anthology tables of contents can repeat the story heading.
            return lines[hits[-1] + 1:], f"story:{anchor}"
    headings = [n for n, line in enumerate(lines[:450]) if CHAPTER.match(line)]
    for n in headings:
        if any(len(HAN.findall(line)) >= 23 and "。" in line
               for line in lines[n+1:n+8] if not CHAPTER.match(line)):
            return lines[n+1:], f"chapter-line:{n + 1}"
    if any(FOREWORD.fullmatch(line.strip()) for line in lines[:120]):
        return None, None
    return lines, "first-prose"


def historical_evidence(entry):
    author = entry["Authors"].split(";")[0].strip()
    if "[" in author or re.match(r"^(Anonymous|Unknown|Various)", author):
        return None
    years = [int(y) for y in re.findall(r"(?<!\d)\d{3,4}(?!\d)", author)]
    if (len(years) >= 2 and max(years) <= 1955) or (len(years) == 1 and years[0] < 1880):
        return "PGLAF author dates: " + author
    if re.search(r"\b(?:1[0-9]|[1-9])(?:st|nd|rd|th) century\b", author, re.I):
        return "PGLAF author active period: " + author
    era = re.search(r"Chinese fiction -- (?:Ming dynasty, 1368-1644|Qing dynasty, 1644-1912)",
                    entry["Subjects"], re.I)
    if era:
        return "PGLAF historical classification: " + era.group()
    return None


def narrative_record(inputs, entry):
    book = entry["Text#"]
    title, author = entry["Title"], entry["Authors"].split(";")[0].strip()
    evidence = historical_evidence(entry)
    # The first Yang edition contains a different text. The collected stories
    # share source works with other credited authors in this candidate pool.
    if (not evidence or "erotic" in entry["Subjects"].casefold() or book == "23838"
            or title in ("今古奇觀", "補紅樓夢", "水滸後傳", "殘唐五代史演義傳", "後西游記")):
        return None
    raw = (inputs / "pg-texts" / f"{book}.txt").read_bytes()
    lines, anchor = narrative_lines(book, raw.decode("utf-8-sig"))
    if lines is None:
        return None
    start = next((n for n, line in enumerate(lines[:150]) if not CHAPTER.match(line)
                  and len(HAN.findall(line)) >= 18 and re.search(r"[。．！？]", line)), None)
    if start is None:
        return None
    body = "\n".join(lines[start:]).strip()[:18000]
    if not eligible_text(body):
        return None
    return ({"id": f"pg-{book}", "genre": "free-narrative", "source": "gutenberg",
             "title": title.replace("\n", " "), "authors": [author], "work_group": f"fiction-{normalized_key(title)}",
             "source_url": f"https://www.gutenberg.org/ebooks/{book}", "download": entry["download"],
             "source_sha256": sha256(raw).hexdigest(), "historical_evidence": evidence,
             "source_field": anchor}, body)


def prepare(inputs, output):
    output.mkdir(parents=True, exist_ok=False)
    sources = {"gutenberg": {"name": "Project Gutenberg Literary Archive Foundation",
                             "catalog": CATALOG, "license": PG_LICENSE,
                             "catalog_sha256": sha256((inputs / "pg-cache.raw").read_bytes()).hexdigest(),
                             "dataset": "Chinese-language historically attributed fiction",
                             "notice": "Historical work text. E-book branding and front/back matter omitted."}}
    candidates = []
    for kind, spec in GOV_SOURCES.items():
        raw = (inputs / spec["snapshot"]).read_bytes()
        sources[kind] = dict(spec, sha256=sha256(raw).hexdigest(), license=GOV_LICENSE,
                             notice="Released under Taiwan Open Government Data License 1.0. Attribution to the source agency.")
        rows = csv.DictReader(raw.decode("utf-8-sig").splitlines(keepends=True)) if kind == "ndc" else json.loads(raw)
        for row in rows:
            parsed = government_record(kind, row)
            if parsed is not None:
                candidates.append(parsed)
    books = json.loads((inputs / "pg-texts/titles.json").read_bytes())
    for book in books:
        parsed = narrative_record(inputs, book)
        if parsed is not None:
            candidates.append(parsed)
    records, seen = [], set()
    for row, body in candidates:
        if row["id"] not in seen:
            (output / f"{row['id']}.txt").write_bytes(body.encode())
            records.append(row)
            seen.add(row["id"])
    (output / "index.json").write_bytes(canonical_json({"sources": sources, "records": records}))
    for source in sources:
        print(source, sum(row["source"] == source for row in records), "public candidates")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_snapshots", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.input_snapshots, args.output)


if __name__ == "__main__":
    main()
