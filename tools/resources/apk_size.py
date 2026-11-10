"""Measure physical APK bytes and fail delivery on size, ZIP slack or native symbols."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

DEFAULT_BUDGET = 80_000_000
MAX_ZIP_OVERHEAD = 8 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def debug_sections(data):
    """Read ELF section names without invoking a host-specific binary tool."""
    require(len(data) >= 64 and data[:4] == b'\x7fELF', 'Invalid native ELF header')
    require(data[4] in (1, 2) and data[5] == 1, 'Unsupported native ELF format')
    wide = data[4] == 2
    section_offset = struct.unpack_from('<Q' if wide else '<I', data, 40 if wide else 32)[0]
    entry_size, count, names_index = struct.unpack_from('<HHH', data, 58 if wide else 46)
    minimum = 64 if wide else 40
    require(entry_size >= minimum and 0 < count <= 65535 and names_index < count,
            'Invalid native ELF section table')
    require(section_offset >= 64 and section_offset + entry_size * count <= len(data),
            'Truncated native ELF section table')
    names_header = section_offset + names_index * entry_size
    names_offset, names_size = struct.unpack_from('<QQ' if wide else '<II', data,
                                                 names_header + (24 if wide else 16))
    require(names_size > 0 and names_offset + names_size <= len(data), 'Invalid native ELF names')
    names = data[names_offset:names_offset + names_size]
    found = []
    for index in range(count):
        name_offset = struct.unpack_from('<I', data, section_offset + index * entry_size)[0]
        require(name_offset < len(names), 'Invalid native ELF section name')
        end = names.find(b'\0', name_offset)
        require(end >= 0, 'Unterminated native ELF section name')
        name = names[name_offset:end].decode('ascii', errors='strict')
        if name == '.symtab' or name.startswith(('.debug_', '.zdebug_')):
            found.append(name)
    return sorted(found)


def category(name):
    if name.startswith('lib/'):
        return '/'.join(name.split('/')[:2])
    if name.startswith('assets/rime/'):
        return '/'.join(name.split('/')[:3])
    if name.startswith('assets/'):
        return '/'.join(name.split('/')[:2])
    return 'code-and-resources'


def analyze(path, budget=DEFAULT_BUDGET):
    require(budget > 0, 'APK budget must be positive')
    physical = path.stat().st_size
    components, libraries = {}, []
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len({entry.filename for entry in entries}) == len(entries), 'Duplicate APK entries')
        for entry in entries:
            group = category(entry.filename)
            components[group] = components.get(group, 0) + entry.compress_size
            if entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                require(0 < entry.file_size <= 256 * 1024 * 1024, 'Invalid native library size')
                libraries.append({'name': entry.filename, 'bytes': entry.file_size,
                                  'compressedBytes': entry.compress_size,
                                  'debugSections': debug_sections(archive.read(entry))})
        payload = sum(entry.compress_size for entry in entries)
        largest = [{'name': entry.filename, 'bytes': entry.file_size, 'compressedBytes': entry.compress_size}
                   for entry in sorted(entries, key=lambda item: item.compress_size, reverse=True)[:15]]
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'format': 1, 'apk': str(path.resolve()), 'sha256': digest,
            'bytes': physical, 'decimalMB': round(physical / 1_000_000, 3),
            'MiB': round(physical / 1024**2, 3), 'budgetBytes': budget,
            'budgetMet': physical <= budget, 'overBudgetBytes': max(0, physical - budget),
            'targetBytes': DEFAULT_BUDGET, 'targetMet': physical <= DEFAULT_BUDGET,
            'payloadBytes': payload, 'zipOverheadBytes': physical - payload,
            'abis': sorted({item['name'].split('/')[1] for item in libraries}),
            'components': dict(sorted(components.items(), key=lambda item: -item[1])),
            'nativeLibraries': libraries, 'largestEntries': largest}


def enforce(report):
    require(0 <= report['zipOverheadBytes'] < MAX_ZIP_OVERHEAD,
            'APK retains excessive ZIP space; rebuild packaging')
    require(not any(item['debugSections'] for item in report['nativeLibraries']),
            'APK retains native debug/symbol sections; check the app NDK toolchain')
    require(report['budgetMet'],
            f"APK exceeds {report['budgetBytes']:,}-byte budget by {report['overBudgetBytes']:,} bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--max-bytes', type=int, default=DEFAULT_BUDGET)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--report-only', action='store_true', help='Measure without accepting a deliverable')
    args = parser.parse_args()
    try:
        report = analyze(args.apk, args.max_bytes)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(f"{args.apk.name}: {report['bytes']:,} bytes ({report['decimalMB']} MB / {report['MiB']} MiB); "
              f"limit {report['budgetBytes']:,} bytes {'met' if report['budgetMet'] else 'exceeded'}; "
              f"80 MB target {'met' if report['targetMet'] else 'exceeded'}; report: {args.report}")
        if not args.report_only:
            enforce(report)
    except (ValueError, OSError, zipfile.BadZipFile, UnicodeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
