#!/usr/bin/env python3
"""Validate process-lifetime numeric dispatch facts; these counts are not timings."""
import argparse
import copy
import json
from pathlib import Path
import re

FIELDS = ('numericStorageBufferCalls', 'numericUniformCalls', 'resourceScanSteps',
          'descriptorNameParseCalls', 'numericToNameDispatches')

def evaluate(raw):
    errors = []
    if not isinstance(raw, dict):
        raw = {}
    for key, value in {'schemaVersion': 1, 'evidenceClass': 'diagnostic', 'performanceEligible': False,
                       'scope': 'process-observation-including-warmup', 'status': 'passed'}.items():
        if type(raw.get(key)) is not type(value) or raw[key] != value:
            errors.append(f'invalid {key}')
    if not isinstance(raw.get('sourceSha'), str) or not re.fullmatch('[0-9a-f]{40}', raw['sourceSha']):
        errors.append('invalid sourceSha')
    if not isinstance(raw.get('trialId'), str) or not raw['trialId'].strip() or len(raw['trialId']) > 160:
        errors.append('invalid trialId')
    counters = raw.get('counters')
    if not isinstance(counters, dict):
        counters = {}
    if counters.get('enabled') is not True:
        errors.append('diagnostic gate not enabled')
    for key in FIELDS:
        value = counters.get(key)
        if type(value) is not int or not 0 <= value <= (1 << 63) - 1:
            errors.append(f'invalid {key}')
    # Independent adders are not an atomic multi-counter transaction. Do not
    # assert cross-counter conservation without a quiescence contract.
    return {'complete': not errors, 'errors': errors, 'performanceEligible': False,
            'scope': 'process-observation-including-warmup', 'counters': counters}

def self_test():
    fixture = {'schemaVersion': 1, 'evidenceClass': 'diagnostic', 'performanceEligible': False,
               'scope': 'process-observation-including-warmup', 'status': 'passed',
               'sourceSha': 'a' * 40, 'trialId': 'fixture', 'counters': {'enabled': True, **dict.fromkeys(FIELDS, 0)}}
    assert evaluate(fixture)['complete']  # Zero is a valid observation, not activation proof.
    count = 0
    for field in FIELDS:
        for value in (-1, True, None, '0', 1 << 63):
            bad = copy.deepcopy(fixture); bad['counters'][field] = value
            assert not evaluate(bad)['complete']; count += 1
    for field, value in (('status', 'failed'), ('sourceSha', 'unknown'), ('performanceEligible', True)):
        bad = copy.deepcopy(fixture); bad[field] = value
        assert not evaluate(bad)['complete']; count += 1
    print(f'Numeric binding diagnostic self-test: PASS ({count} rejected mutations)')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', nargs='?', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test(); return 0
    if args.report is None:
        parser.error('report required')
    try:
        result = evaluate(json.loads(args.report.read_text()))
    except (OSError, ValueError) as error:
        result = {'complete': False, 'errors': [str(error)], 'performanceEligible': False}
    output = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(output)
    else:
        print(output, end='')
    return 0 if result['complete'] else 2

if __name__ == '__main__':
    raise SystemExit(main())
