# Public assets in the Debug verse workbench

The five-character scenery pools derive from the locked Wanxiang shici table (RIME-LMDG v18.1.1, revision
26a9efeb18e604c091f4d3890d00bec723fe755f). The builder selects five-character BMP lines,
deduplicates and sorts them, and derives three scenery pools. See the APK license notices for the
upstream attribution and CC BY 4.0 terms.

The window observation profile joins ZeroInput's authored window corpus
with the 16 pinned Wanxiang public tables and freezes the resulting
grammar. ZeroInput's corpus is Apache-2.0. The Wanxiang vocabulary
retains its attribution and CC BY 4.0 terms in the APK license notices.
[Window corpus](../prose_window_scene_corpus.json) ·
[Paired observations](../prose_window_progression_corpus.json) ·
[Source revision and checksums](../../dictionaries/sources.lock.json)

The latest profile uses the public Tatoeba Mandarin export as evidence
for individual noun slots. The generated graph contains authored
templates and reviewed vocabulary. The compiler removes new sentences
that match a normalized Tatoeba source sentence in full. Source
preparation uses the pinned OpenCC dictionaries.
[Tatoeba Mandarin exports](https://tatoeba.org/en/downloads) ·
[Slot compilation and review](../prose_window_slot_probe.py)

The 15,021-byte GB18030 zlib dictionary is a byte suffix of 32 interleaved public excerpts.
The contributors to the retained suffix are listed in order below. Government abstracts
are from the named public sources under the Taiwan Open Government Data License v1.0.
The other excerpts are historical Chinese works from the Project Gutenberg public-domain mirror.
The byte suffix retains only the text, with no ebook packaging or trademark branding.
[Taiwan Open Government Data License](https://data.gov.tw/license) ·
[Project Gutenberg terms](https://www.gutenberg.org/policy/license.html)

- 強化低碳與循環經濟效益之再生綠建材推廣應用, 陳文卿, 陳文欽. [Source](https://www.abri.gov.tw/News_Content_Table.aspx?n=807&s=340224)
- 西湖二集, Zhou, Qingyuan, active 16th century-17th century. [Source](https://www.gutenberg.org/ebooks/25392)
- 公務人員報考與錄取類科及職涯發展，與其畢業科系相關性之研究, 陳敦源. [Source](https://www.exam.gov.tw/News_Content.aspx?n=3832&s=46751)
- 豆棚閒話, Ainajushi. [Source](https://www.gutenberg.org/ebooks/25328)
- 我國高階文官培訓之前瞻研究, 江明修. [Source](https://www.exam.gov.tw/News_Content.aspx?n=3832&s=28477)
- 老殘遊記, Liu, E, 1857-1909. [Source](https://www.gutenberg.org/ebooks/23850)
- 台灣及香港產業人力供需推估之比較－以觀光旅館業和物流業為例－, 趙偉慈. [Source](https://ws.ndc.gov.tw/001/administrator/10/relfile/0/10256/a21bcba5-c254-4b33-91a6-0f2047196f27.pdf)
- 杜騙新書, Zhang, Yingyu, active 16th century-17th century. [Source](https://www.gutenberg.org/ebooks/24021)
- 法國中央人事行政組織之研究（考試院委託國立台灣大學研究案）, 陳淳文. [Source](https://www.exam.gov.tw/News_Content.aspx?n=3832&s=28428)
- 牛郎織女傳, Zhu, Mingshi, 1260-1340. [Source](https://www.gutenberg.org/ebooks/27217)
- 綠建築基地保水設施維護管理指引研訂之研究, 廖朝軒, 欒中丕. [Source](https://www.abri.gov.tw/News_Content_Table.aspx?n=807&s=339786)
- 封神演義, Lu, Xixing, 1520-1606. [Source](https://www.gutenberg.org/ebooks/23910)

The demonstration body is a synthetic public fixture with mixed Unicode and CRLF.
The host vector was made with the public test key consisting of bytes 0 through 31.

## Word-context model v1

The model stores short vocabulary entries, Huffman codes, counts, and token
transitions derived from the 32 locked public training excerpts. The
Taiwan government data is released under Open Government Data License v1.0.
The remaining excerpts are historical Chinese works from Project Gutenberg
with ebook packaging omitted. ZeroInput's frozen public word associations
are Apache-2.0. Source identities, authors, and hashes are in the corpus lock
and the [per-work credits](word-context-sources.json) bundled with the APK.

- [Architecture and Building Research Institute, Ministry of the Interior](https://data.gov.tw/dataset/25125)
- [Examination Yuan Department of Information Management](https://data.gov.tw/dataset/158138)
- [National Development Council](https://data.gov.tw/dataset/55806)
- [Project Gutenberg terms](https://www.gutenberg.org/policy/license.html)
- [Taiwan Open Government Data License](https://data.gov.tw/license)
- [P2 corpus lock](../../../docs/themed-verse/p2-corpus-lock.json)
