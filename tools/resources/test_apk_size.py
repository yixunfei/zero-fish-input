"""Exercise delivery rejection, including optimized Python and native-symbol leakage."""
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import apk_size


def elf(wide, section):
    header_size, entry_size = (64, 64) if wide else (52, 40)
    section_offset = 64
    names = b'\0.shstrtab\0' + section.encode('ascii') + b'\0'
    names_offset = section_offset + 3 * entry_size
    data = bytearray(names_offset + len(names))
    data[:6] = b'\x7fELF' + bytes([2 if wide else 1, 1])
    struct.pack_into('<Q' if wide else '<I', data, 40 if wide else 32, section_offset)
    struct.pack_into('<HHH', data, 58 if wide else 46, entry_size, 3, 1)
    struct.pack_into('<I', data, section_offset + entry_size, 1)
    struct.pack_into('<QQ' if wide else '<II', data,
                     section_offset + entry_size + (24 if wide else 16), names_offset, len(names))
    struct.pack_into('<I', data, section_offset + 2 * entry_size, 11)
    data[names_offset:] = names
    return data


class ApkSizeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.apk = self.root / 'public.apk'

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self, section='.dynsym'):
        with zipfile.ZipFile(self.apk, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('lib/arm64-v8a/libpublic.so', elf(True, section))
            archive.writestr('assets/public.txt', b'fixed public package fixture')
        return self.apk

    def test_exact_physical_budget_passes_and_one_byte_over_fails(self):
        self.fixture()
        apk_size.enforce(apk_size.analyze(self.apk, self.apk.stat().st_size))
        with self.assertRaisesRegex(ValueError, 'exceeds'):
            apk_size.enforce(apk_size.analyze(self.apk, self.apk.stat().st_size - 1))

    def test_both_elf_widths_keep_dynamic_symbols_but_reject_debug_sections(self):
        for wide in (False, True):
            self.assertEqual([], apk_size.debug_sections(elf(wide, '.dynsym')))
            for section in ('.symtab', '.debug_info', '.zdebug_line'):
                self.assertEqual([section], apk_size.debug_sections(elf(wide, section)))

    def test_truncated_and_forged_elf_fail_closed(self):
        damaged = elf(True, '.dynsym')
        struct.pack_into('<Q', damaged, 40, len(damaged) + 1)
        for data in (b'not ELF', elf(True, '.dynsym')[:64], damaged):
            with self.assertRaises(ValueError):
                apk_size.debug_sections(data)

    def test_native_symbols_fail_even_when_budget_is_met(self):
        self.fixture('.debug_info')
        report = apk_size.analyze(self.apk)
        self.assertTrue(report['budgetMet'])
        with self.assertRaisesRegex(ValueError, 'native debug'):
            apk_size.enforce(report)

    def test_obsolete_zip_space_fails_even_when_budget_is_met(self):
        self.fixture()
        report = apk_size.analyze(self.apk)
        report['zipOverheadBytes'] = apk_size.MAX_ZIP_OVERHEAD
        with self.assertRaisesRegex(ValueError, 'ZIP space'):
            apk_size.enforce(report)

    def test_component_totals_and_byte_units_match_physical_file(self):
        self.fixture()
        report = apk_size.analyze(self.apk)
        self.assertEqual(self.apk.stat().st_size, report['bytes'])
        self.assertEqual(report['payloadBytes'], sum(report['components'].values()))
        self.assertEqual(report['bytes'], report['payloadBytes'] + report['zipOverheadBytes'])
        self.assertEqual(['arm64-v8a'], report['abis'])

    def test_custom_delivery_limit_does_not_relabel_the_project_target(self):
        self.fixture()
        with patch.object(apk_size, 'DEFAULT_BUDGET', self.apk.stat().st_size - 1):
            report = apk_size.analyze(self.apk, 90_000_000)
        self.assertTrue(report['budgetMet'])
        self.assertFalse(report['targetMet'])

    def test_cli_keeps_failure_report_and_cannot_skip_gate_with_python_optimization(self):
        self.fixture()
        for flags in ([], ['-O']):
            target = self.root / 'report.json'
            command = [sys.executable, *flags, str(Path(apk_size.__file__)), str(self.apk),
                       '--max-bytes', '1', '--report', str(target)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(json.loads(target.read_text())['budgetMet'])
            measured = subprocess.run(command + ['--report-only'], capture_output=True, text=True)
            self.assertEqual(0, measured.returncode, measured.stderr)
            self.assertFalse(json.loads(target.read_text())['budgetMet'])


if __name__ == '__main__':
    unittest.main()
