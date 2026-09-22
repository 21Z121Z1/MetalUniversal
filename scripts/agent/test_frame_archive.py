"""Synthetic integrity fixtures for bounded archives. No native/physical claim."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from verify_frame_evidence import load_report, verify


class FrameArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / 'frames.json'
        self.directory = self.root / 'frames.json.segments'
        self.directory.mkdir()
        self.identity = {'build': {'sourceSha': 'a' * 40}}
        self.segments = []
        self.index = {'schemaVersion': 2, 'identity': self.identity, 'scope': 'fixture', 'frames': [], 'droppedFrames': 0,
                      'archive': {'formatVersion': 1, 'directory': self.directory.name, 'committedSegments': 2,
                                  'committedFrames': 4, 'lastSegmentSha256': '', 'complete': True},
                      'retention': {'frameCapacityPerBuffer': 2, 'pendingSegmentLimit': 2, 'segmentFrameTarget': 2,
                                    'receiptRetentionNs': 1000, 'retainedFrames': 0, 'queuedSegments': 0,
                                    'censoredSegments': 0, 'exportedSegments': 2, 'exportedFrames': 4,
                                    'lateCompletionUpdates': 0, 'lateReceiptUpdates': 0}}
        for number in (1, 2):
            first = number * 2 - 1
            self.segments.append({'schemaVersion': 1, 'identity': self.identity, 'scope': 'fixture', 'droppedFrames': 0,
                                  'segment': {'id': number, 'firstFrameId': first, 'lastFrameId': first + 1,
                                              'frameCount': 2, 'previousSha256': '', 'censoringReason': ''},
                                  'frames': [{'frameId': i, 'exportCensoringReason': ''} for i in (first, first + 1)]})
        self.write()

    def write(self):
        previous = ''
        for number, segment in enumerate(self.segments, 1):
            segment['segment']['previousSha256'] = previous
            data = json.dumps(segment).encode()
            previous = hashlib.sha256(data).hexdigest()
            (self.directory / f'{number:08d}.json').write_bytes(data)
        self.index['archive']['lastSegmentSha256'] = previous
        self.path.write_text(json.dumps(self.index))

    def test_reassembles_one_global_identity_sequence(self):
        result = load_report(self.path)
        self.assertEqual([x['frameId'] for x in result['frames']], [1, 2, 3, 4])
        self.assertEqual(result['archiveSchemaVersion'], 2)
        self.assertEqual(result['schemaVersion'], 1)

    def test_legacy_schema_is_not_automatically_promoted(self):
        original = {'schemaVersion': 1, 'frames': []}
        self.path.write_text(json.dumps(original))
        self.assertEqual(load_report(self.path), original)

    def test_tampered_segment_cannot_preserve_the_chain(self):
        self.segments[0]['frames'][0]['context'] = 'tampered'
        (self.directory / '00000001.json').write_text(json.dumps(self.segments[0]))
        with self.assertRaisesRegex(ValueError, 'hash chain'): load_report(self.path)

    def test_missing_segment_is_not_treated_as_no_stutter(self):
        (self.directory / '00000001.json').unlink()
        with self.assertRaisesRegex(ValueError, 'missing'): load_report(self.path)

    def test_source_binary_mismatch_rejects_even_when_hash_chain_matches(self):
        self.segments[1]['identity'] = {'build': {'sourceSha': 'f' * 40}}
        self.write()
        with self.assertRaisesRegex(ValueError, 'identity mismatch'): load_report(self.path)

    def test_global_ids_cannot_wrap_with_the_recorder_buffer(self):
        self.segments[1]['frames'][0]['frameId'] = 1
        self.segments[1]['segment']['firstFrameId'] = 1
        self.write()
        with self.assertRaisesRegex(ValueError, 'reused/reordered'): load_report(self.path)

    def test_orphan_or_partial_output_rejects_a_complete_claim(self):
        (self.directory / '00000003.json.partial').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'orphan or partial'): load_report(self.path)

    def test_incomplete_checkpoint_remains_explicitly_incomplete(self):
        self.index['archive']['complete'] = False
        self.write()
        (self.directory / '00000003.json.partial').write_text('{')
        result = load_report(self.path)
        self.assertFalse(result['archive']['complete'])
        # The loader preserves the state, never invents a shutdown or successful workload.
        self.assertNotIn('shutdownDrained', result)
        self.assertNotIn('validationStatus', result)

    def test_archive_directory_cannot_escape_the_report(self):
        for invalid in ('../other', '/tmp/other', 'other.segments'):
            self.index['archive']['directory'] = invalid
            self.write()
            with self.assertRaisesRegex(ValueError, 'directory'): load_report(self.path)

    def test_symlinked_segment_is_rejected(self):
        victim = self.directory / '00000001.json'
        victim.rename(self.root / 'source.json')
        victim.symlink_to(self.root / 'source.json')
        with self.assertRaisesRegex(ValueError, 'symlinked'): load_report(self.path)

    def test_censoring_accounting_cannot_be_removed_from_a_segment(self):
        self.segments[0]['segment']['censoringReason'] = 'callback-retention-expired'
        self.write()
        with self.assertRaisesRegex(ValueError, 'censoring mismatch'): load_report(self.path)

    def test_detached_but_uncommitted_segment_rejects_completion(self):
        self.index['retention']['exportedSegments'] = 3
        self.write()
        with self.assertRaisesRegex(ValueError, 'lost a detached'): load_report(self.path)

    def test_duplicate_json_keys_and_non_finite_numbers_are_rejected(self):
        for data in ('{"schemaVersion":1,"schemaVersion":2}', '{"schemaVersion":1,"x":NaN}'):
            self.path.write_text(data)
            with self.assertRaises(ValueError): load_report(self.path)


if __name__ == '__main__':
    unittest.main()
