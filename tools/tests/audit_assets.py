"""Read-only binary/manifest audit. Optional source ZIP comparison is structural,
not a claim that converted TRS reproduces upstream matrices or final JBRA poses.
Run from the repository root with Python 3; no third-party packages required.
"""
import argparse
import collections
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import zipfile

DATA = Path('src/main/resources/assets/epicfight1710/data')
UPSTREAM = 'a0a1027cdd210821c0b3bcc461f1ca170303d5fc'


class Reader:
    def __init__(self, path):
        self.stream = io.BytesIO(path.read_bytes())

    def values(self, fmt):
        return struct.unpack('>' + fmt, self.stream.read(struct.calcsize('>' + fmt)))

    def integer(self):
        return self.values('i')[0]

    def text(self):
        length = self.values('H')[0]
        # Asset names are ASCII, a subset of Java's modified UTF-8.
        return self.stream.read(length).decode('ascii')


def multiply(a, b):
    return [sum(a[r*4+k]*b[k*4+c] for k in range(4))
            for r in range(4) for c in range(4)]


def read_mesh(path):
    r = Reader(path)
    assert (r.integer(), r.integer()) == (0x45464231, 1)
    n = r.integer()
    assert n == 20
    joints, globals_ = [], []
    for i in range(n):
        name, parent = r.text(), r.integer()
        bind, inverse = r.values('16f'), r.values('16f')
        assert -1 <= parent < i, (name, parent)
        assert all(math.isfinite(v) for v in bind + inverse)
        global_ = list(bind) if parent == -1 else multiply(globals_[parent], bind)
        identity = multiply(global_, inverse)
        assert max(abs(v - (1 if j % 5 == 0 else 0))
                   for j, v in enumerate(identity)) < 1e-4, name
        globals_.append(global_)
        joints.append((name, parent, bind, inverse))
    assert len({j[0] for j in joints}) == n
    vertices = r.integer()
    for _ in range(vertices):
        assert all(math.isfinite(v) for v in r.values('3f'))
        weights = []
        for _ in range(4):
            joint, weight = r.values('if')
            assert math.isfinite(weight) and weight >= 0
            assert weight == 0 or 0 <= joint < n
            weights.append(weight)
        assert abs(sum(weights) - 1) < 1e-4
    uv_count = r.integer()
    assert all(math.isfinite(v) for v in r.values(str(uv_count*2)+'f'))
    normal_count = r.integer()
    assert all(math.isfinite(v) for v in r.values(str(normal_count*3)+'f'))
    for _ in range(r.integer()):
        r.text()
        for _ in range(r.integer()):
            vertex, uv, normal = r.values('3i')
            assert 0 <= vertex < vertices and 0 <= uv < uv_count and 0 <= normal < normal_count
    assert not r.stream.read(), 'Trailing mesh bytes'
    return joints, vertices


def read_clips():
    r = Reader(DATA / 'clips.dat')
    assert (r.integer(), r.integer()) == (0x45464131, 2)
    count, clips = r.integer(), {}
    assert count == 242
    for _ in range(count):
        name, duration, tracks = r.text(), r.values('f')[0], {}
        assert name not in clips and math.isfinite(duration) and duration > 0
        for _ in range(r.integer()):
            joint, keys = r.integer(), r.integer()
            assert joint not in tracks and 0 <= joint < 20 and keys > 0
            times = []
            for _ in range(keys):
                time, *trs = r.values('11f')
                assert all(math.isfinite(v) for v in [time] + trs), name
                assert 0 <= time <= duration + 1e-4, (name, time, duration)
                assert not times or time > times[-1], (name, joint, time)
                assert abs(sum(v*v for v in trs[3:7]) - 1) < 1e-4, name
                assert all(abs(v) > 1e-8 for v in trs[7:10]), name
                times.append(time)
            tracks[joint] = times
        clips[name] = (duration, tracks)
    assert not r.stream.read(), 'Trailing clip bytes'
    return clips


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', type=Path)
    args = parser.parse_args()
    joints, modern = read_mesh(DATA / 'biped.dat')
    old_joints, legacy = read_mesh(DATA / 'biped_old.dat')
    assert joints == old_joints, 'Different bind/inverse matrices in legacy mesh'
    clips = read_clips()
    manifest = {}
    for line in (DATA / 'clip_manifest.txt').read_text().splitlines():
        if line and not line.startswith('#'):
            name, duration, tracks, source = line.split('\t')
            assert name not in manifest
            manifest[name] = (float(duration), int(tracks), source)
    assert set(clips) == set(manifest)
    for name, (duration, tracks) in clips.items():
        assert abs(duration-manifest[name][0]) < 1e-4 and len(tracks) == manifest[name][1]
    weapon = json.loads((DATA / 'weapon_types.json').read_text())
    assert len(weapon['weaponTypes']) == 8 and len(weapon['itemRules']) == 7
    print('PASS binary audit: 242 clips, 20 identical joints, %d/%d vertices; manifest, '
          'key times, finite TRS, unit quaternions, bind inverses, mesh indices and weights' % (modern, legacy))
    for i, (name, parent, _, _) in enumerate(joints):
        print('JOINT %d %s parent=%s' % (i, name, '-' if parent < 0 else joints[parent][0]))
    for p in sorted(DATA.iterdir()):
        print('SHA256', p.name, hashlib.sha256(p.read_bytes()).hexdigest())
    if args.upstream:
        source_zip = zipfile.ZipFile(args.upstream)
        root = 'epicfight-' + UPSTREAM + '/src/main/resources/'
        print('| Clip | Duration | Tracks | Source track/time comparison |')
        print('|---|---:|---:|---|')
        differences = []
        for name, (duration, tracks) in sorted(clips.items()):
            source = json.loads(source_zip.read(root + manifest[name][2]))
            source_tracks = {t['name']: t['time'] for t in source['animation'] if t['name'] != 'Coord'}
            local_tracks = {joints[j][0]: times for j, times in tracks.items()}
            equal = set(source_tracks) == set(local_tracks)
            equal = equal and all(len(times) == len(source_tracks[j]) and
                                 all(abs(a-b) < 1e-5 for a, b in zip(times, source_tracks[j]))
                                 for j, times in local_tracks.items())
            if not equal:
                differences.append(name)
            print('| %s | %.4f | %d | %s |' % (name, duration, len(tracks), 'MATCH' if equal else 'DIFFERENT'))
        print('Source paths found: 242/242; track/time differences:', len(differences))
        print('DIFFERENCES', json.dumps(differences))
        print('Families by source directory:', dict(collections.Counter(v[2].split('/')[-2] for v in manifest.values())))
        assert not differences, 'Upstream track/time comparison failed'


if __name__ == '__main__':
    main()
