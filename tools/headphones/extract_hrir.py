"""Extracts Ordo's ten horizontal-plane HRIR pairs from SADIE II D1 (KU100), 48 kHz, 256 taps, Apache-2.0.

Output: little-endian float32, per azimuth (SOFA convention, counter-clockwise, 0 = front, 90 = left) the left ear's
256 taps then the right ear's 256 taps, in the order of AZIMUTHS.
"""
import sys
import h5py
import numpy as np

AZIMUTHS = [0, 30, 90, 110, 150, 180, 210, 250, 270, 330]
f = h5py.File(sys.argv[1], 'r')
assert f['Data.SamplingRate'][()][0] == 48000
pos = f['SourcePosition'][()]
ir = f['Data.IR'][()]
out = []
for az in AZIMUTHS:
    idx = np.where((np.abs(pos[:, 0] - az) < 0.01) & (np.abs(pos[:, 1]) < 0.01))[0]
    assert len(idx) == 1, az
    pair = ir[idx[0]].astype(np.float32)
    left, right = pair
    print(az, 'left %.2f dB right %.2f dB' % (10 * np.log10(np.sum(left ** 2)), 10 * np.log10(np.sum(right ** 2))))
    out.append(pair.reshape(-1))
np.concatenate(out).astype('<f4').tofile(sys.argv[2])
