import numpy as np
from scipy.signal import lfilter, fftconvolve
hr = np.fromfile('hrir.bin', dtype='<f4').reshape(10, 2, 256)
AZ = [0, 30, 90, 110, 150, 180, 210, 250, 270, 330]
def k(x):
    x = lfilter([1.53512485958697, -2.69169618940638, 1.19839281085285], [1, -1.69065929318241, 0.73248077421585], x)
    return lfilter([1, -2, 1], [1, -1.99004745483398, 0.99007225036621], x)
rng = np.random.default_rng(1)
n = 48000 * 10
s = 0.7071
norm = 1 / (1 + s)
def lufs(l, r):
    return -0.691 + 10 * np.log10(np.mean(k(l) ** 2) + np.mean(k(r) ** 2))
# pink-ish: white noise through 1/f approx filter
def pink(n):
    w = rng.standard_normal(n)
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1, -2.494956002, 2.017265875, -0.522189400]
    return lfilter(b, a, w) * 0.1
for layout, az, mat in [
    ('5.1', [30, 330, 0, 110, 250], [(1, 0), (0, 1), (s, s), (s, 0), (0, s)]),
    ('7.1', [30, 330, 0, 150, 210, 90, 270], [(1, 0), (0, 1), (s, s), (s, 0), (0, s), (s, 0), (0, s)]),
]:
    ch = [pink(n) for _ in az]
    dl = sum(m[0] * c for m, c in zip(mat, ch)) * norm
    dr = sum(m[1] * c for m, c in zip(mat, ch)) * norm
    bl = sum(fftconvolve(c, hr[AZ.index(a), 0])[:n] for a, c in zip(az, ch))
    br = sum(fftconvolve(c, hr[AZ.index(a), 1])[:n] for a, c in zip(az, ch))
    d = lufs(dl, dr)
    b = lufs(bl, br)
    print(layout, 'downmix %.2f binaural(gain 1) %.2f -> gain %.4f' % (d, b, 10 ** ((d - b) / 20)))
    # centre only
    c = ch[2]
    print('  centre only: downmix %.2f binaural %.2f' % (lufs(c * s * norm, c * s * norm), lufs(fftconvolve(c, hr[0, 0])[:n], fftconvolve(c, hr[0, 1])[:n])))
