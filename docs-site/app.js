/* =========================================================================
   Pendulum — project site
   Three canvases, no dependencies:
     1. the hero pendulum   — decaying swing, evenly spaced ticks
     2. the harmonic mixture — what a miss rate does to the interval distribution
     3. the night trace      — a synthetic accelerometric envelope
   ========================================================================= */

(function () {
  'use strict';

  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)');

  /* ---- shared helpers -------------------------------------------------- */

  function tokens() {
    var s = getComputedStyle(document.documentElement);
    var t = {};
    ['brass', 'brass-dim', 'brass-wash', 'blue', 'blue-dim', 'blue-wash',
     'rose', 'rose-dim', 'line', 'line-soft', 'muted', 'faint', 'text',
     'panel', 'panel-2'].forEach(function (k) {
      t[k] = s.getPropertyValue('--' + k).trim();
    });
    return t;
  }

  function fitCanvas(cv) {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var r = cv.getBoundingClientRect();
    var w = Math.max(1, Math.round(r.width));
    var h = Math.max(1, Math.round(r.height));
    cv.width = Math.round(w * dpr);
    cv.height = Math.round(h * dpr);
    var ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { ctx: ctx, w: w, h: h };
  }

  function mono(px) {
    return px + 'px ui-monospace, "SF Mono", SFMono-Regular, "Cascadia Mono", Consolas, "Liberation Mono", monospace';
  }

  /* deterministic PRNG, so the synthetic trace is identical on every load */
  function rng(seed) {
    var s = seed >>> 0;
    return function () {
      s = (s * 1664525 + 1013904223) >>> 0;
      return s / 4294967296;
    };
  }

  var redrawHooks = [];
  function onRedraw(fn) { redrawHooks.push(fn); fn(); }
  function redrawAll() { redrawHooks.forEach(function (f) { f(); }); }

  window.addEventListener('resize', (function () {
    var t;
    return function () { clearTimeout(t); t = setTimeout(redrawAll, 150); };
  })());

  if (window.matchMedia) {
    var dm = window.matchMedia('(prefers-color-scheme: dark)');
    if (dm.addEventListener) dm.addEventListener('change', redrawAll);
  }
  new MutationObserver(redrawAll).observe(document.documentElement, {
    attributes: true, attributeFilter: ['data-theme']
  });

  /* =====================================================================
     1. THE HERO PENDULUM
     Amplitude decays exponentially; the ticks — one per half period —
     stay evenly spaced. That is the whole argument of the project.
     ===================================================================== */

  (function () {
    var cv = document.getElementById('swingCanvas');
    if (!cv) return;

    var PERIOD = 1.5;      // seconds, one full swing
    var TAU = 7.5;         // decay constant
    var CYCLE = 21;        // seconds before the demonstration restarts

    function frame(tSec) {
      var f = fitCanvas(cv), ctx = f.ctx, w = f.w, h = f.h;
      var t = tokens();

      ctx.clearRect(0, 0, w, h);

      var pivotX = Math.min(120, w * 0.16);
      var pivotY = 14;
      var rodLen = h - 62;
      var maxAng = 0.42;

      var amp = maxAng * Math.exp(-tSec / TAU);
      var ang = amp * Math.cos(2 * Math.PI * tSec / PERIOD);

      /* ghosted extremes, so the decay is legible even in a still frame */
      ctx.strokeStyle = t['line'];
      ctx.lineWidth = 1;
      [maxAng, maxAng * 0.55, maxAng * 0.3].forEach(function (a) {
        ctx.beginPath();
        ctx.arc(pivotX, pivotY, rodLen, Math.PI / 2 - a, Math.PI / 2 + a);
        ctx.stroke();
      });

      /* rod and bob */
      var bx = pivotX + rodLen * Math.sin(ang);
      var by = pivotY + rodLen * Math.cos(ang);
      ctx.strokeStyle = t['muted'];
      ctx.lineWidth = 1.25;
      ctx.beginPath();
      ctx.moveTo(pivotX, pivotY);
      ctx.lineTo(bx, by);
      ctx.stroke();

      ctx.fillStyle = t['brass'];
      ctx.beginPath();
      ctx.arc(bx, by, 6.5, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = t['muted'];
      ctx.beginPath();
      ctx.arc(pivotX, pivotY, 2.5, 0, Math.PI * 2);
      ctx.fill();

      /* the timeline of crossings */
      var axisY = h - 20;
      var x0 = pivotX + rodLen * Math.sin(maxAng) + 34;
      var x1 = w - 8;
      if (x1 - x0 < 80) return;

      ctx.strokeStyle = t['line'];
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x0, axisY);
      ctx.lineTo(x1, axisY);
      ctx.stroke();

      var nTicks = Math.floor(CYCLE / (PERIOD / 2));
      var step = (x1 - x0) / nTicks;
      var done = Math.floor(tSec / (PERIOD / 2));

      for (var i = 0; i <= nTicks; i++) {
        var x = x0 + i * step;
        var live = i <= done;
        ctx.strokeStyle = live ? t['brass'] : t['line'];
        ctx.lineWidth = live ? 1.6 : 1;
        ctx.beginPath();
        ctx.moveTo(x, axisY - (live ? 11 : 5));
        ctx.lineTo(x, axisY);
        ctx.stroke();
      }

      ctx.fillStyle = t['faint'];
      ctx.font = mono(10);
      ctx.textAlign = 'left';
      ctx.fillText('one tick per crossing — spacing constant', x0, axisY + 14);
      ctx.textAlign = 'right';
      ctx.fillText('amplitude ×' + (Math.exp(-tSec / TAU)).toFixed(2), x1, axisY - 18);
    }

    if (reduced.matches) {
      onRedraw(function () { frame(CYCLE * 0.72); });
      return;
    }

    var start = null;
    function loop(ts) {
      if (start === null) start = ts;
      var t = ((ts - start) / 1000) % CYCLE;
      frame(t);
      requestAnimationFrame(loop);
    }
    requestAnimationFrame(loop);
    redrawHooks.push(function () { /* the loop refits every frame */ });
  })();

  /* =====================================================================
     2. THE MISS-RATE DEMONSTRATION
     ===================================================================== */

  (function () {
    var slider = document.getElementById('missRate');
    var hist = document.getElementById('histCanvas');
    if (!slider || !hist) return;

    var MU = 21;            // fundamental interval, seconds
    var SIGMA = 0.3;        // log-normal spread, mid-range of the documented 0.2-0.4
    var TRUE_INDEX = 25.2;  // /h, the nominal synthetic night of the regression suite
    var SCALE_MAX = 45;     // seconds, the shared scale of the two interval bars
    var K = 5;              // harmonics carried by the mixture model

    /* E[ln N] = sum_k p^(k-1) (1-p) ln k  — the bias on the raw mean log */
    function rawLogBias(p) {
      if (p <= 0) return 0;
      var s = 0, w = (1 - p);
      for (var k = 1; k <= 4000; k++) {
        s += w * Math.log(k);
        w *= p;
        if (w < 1e-14) break;
      }
      return s;
    }

    function weights(p) {
      var w = [], sum = 0, i;
      for (i = 1; i <= K; i++) { var v = Math.pow(p, i - 1) * (1 - p); w.push(v); sum += v; }
      for (i = 0; i < K; i++) w[i] /= sum;
      return w;
    }

    function density(x, p) {
      if (x <= 0) return 0;
      var w = weights(p), d = 0;
      for (var k = 1; k <= K; k++) {
        var m = Math.log(MU) + Math.log(k);
        var z = (Math.log(x) - m) / SIGMA;
        d += w[k - 1] * Math.exp(-0.5 * z * z) / (x * SIGMA * Math.SQRT2 * Math.sqrt(Math.PI));
      }
      return d;
    }

    var peak0 = density(MU, 0);

    function drawHist(p) {
      var f = fitCanvas(hist), ctx = f.ctx, w = f.w, h = f.h;
      var t = tokens();
      ctx.clearRect(0, 0, w, h);

      var L = 42, R = 12, T = 16, B = 34;
      var pw = w - L - R, ph = h - T - B;
      var XMAX = 112;

      function px(sec) { return L + (sec / XMAX) * pw; }
      function py(d) { return T + ph - (d / (peak0 * 1.05)) * ph; }

      /* grid at the fundamental and its harmonics */
      ctx.font = mono(10);
      ctx.textAlign = 'center';
      for (var k = 1; k <= 5; k++) {
        var sec = MU * k;
        if (sec > XMAX) break;
        ctx.strokeStyle = t['line-soft'];
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px(sec), T);
        ctx.lineTo(px(sec), T + ph);
        ctx.stroke();
        ctx.fillStyle = t['faint'];
        ctx.fillText(sec + ' s', px(sec), h - 18);
        ctx.fillText(k === 1 ? 'fundamental' : '×' + k, px(sec), h - 6);
      }

      /* baseline */
      ctx.strokeStyle = t['line'];
      ctx.beginPath();
      ctx.moveTo(L, T + ph);
      ctx.lineTo(L + pw, T + ph);
      ctx.stroke();

      /* the observed distribution */
      var pts = [], i;
      for (i = 0; i <= pw; i++) {
        var sec2 = (i / pw) * XMAX;
        pts.push([L + i, py(density(sec2, p))]);
      }
      ctx.beginPath();
      ctx.moveTo(L, T + ph);
      for (i = 0; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
      ctx.lineTo(L + pw, T + ph);
      ctx.closePath();
      ctx.fillStyle = t['blue-wash'];
      ctx.fill();
      ctx.beginPath();
      for (i = 0; i < pts.length; i++) {
        if (i === 0) ctx.moveTo(pts[i][0], pts[i][1]); else ctx.lineTo(pts[i][0], pts[i][1]);
      }
      ctx.strokeStyle = t['blue'];
      ctx.lineWidth = 1.6;
      ctx.stroke();

      /* the raw mean of the log distribution — dashed, drifts right */
      var rawSec = MU * Math.exp(rawLogBias(p));
      ctx.save();
      ctx.setLineDash([5, 4]);
      ctx.strokeStyle = t['rose'];
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(px(rawSec), T - 2);
      ctx.lineTo(px(rawSec), T + ph);
      ctx.stroke();
      ctx.restore();

      /* the fundamental — solid, does not move */
      ctx.strokeStyle = t['brass'];
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(px(MU), T - 2);
      ctx.lineTo(px(MU), T + ph);
      ctx.stroke();

      /* labels, positioned so they cannot collide */
      ctx.font = mono(10);
      ctx.fillStyle = t['brass'];
      ctx.textAlign = 'right';
      ctx.fillText('μ 21.0 s', px(MU) - 5, T + 9);
      if (px(rawSec) - px(MU) > 34) {
        ctx.fillStyle = t['rose'];
        ctx.textAlign = 'left';
        ctx.fillText('raw ' + rawSec.toFixed(1) + ' s', px(rawSec) + 5, T + 9);
      }

      ctx.save();
      ctx.translate(11, T + ph / 2);
      ctx.rotate(-Math.PI / 2);
      ctx.fillStyle = t['faint'];
      ctx.textAlign = 'center';
      ctx.fillText('density of observed intervals', 0, 0);
      ctx.restore();
    }

    var els = {
      value: document.getElementById('missValue'),
      count: document.getElementById('countVal'),
      countBar: document.getElementById('countBar'),
      raw: document.getElementById('rawVal'),
      rawBar: document.getElementById('rawBar'),
      funBar: document.getElementById('funBar'),
      bias: document.getElementById('biasVal'),
      mult: document.getElementById('multVal'),
      fateCount: document.getElementById('fateCount'),
      fateRaw: document.getElementById('fateRaw'),
      readout: document.getElementById('readoutText')
    };

    function update() {
      var p = Number(slider.value) / 100;
      var bias = rawLogBias(p);
      var raw = MU * Math.exp(bias);
      var count = TRUE_INDEX * (1 - p);

      els.value.textContent = slider.value + ' % missed';
      els.count.textContent = count.toFixed(1);
      els.countBar.style.width = ((1 - p) * 100).toFixed(1) + '%';
      els.raw.textContent = raw.toFixed(1);
      els.rawBar.style.width = Math.min(100, (raw / SCALE_MAX) * 100).toFixed(1) + '%';
      els.funBar.style.width = ((MU / SCALE_MAX) * 100).toFixed(1) + '%';
      els.bias.textContent = (bias >= 0 ? '+' : '') + bias.toFixed(3);
      els.mult.textContent = '×' + Math.exp(bias).toFixed(2);
      els.fateCount.textContent = p === 0 ? 'unaffected' : 'falls in proportion';
      els.fateRaw.textContent = p === 0 ? 'unbiased' : 'biased upward';

      var msg;
      if (p === 0) {
        msg = 'With nothing missed, all three quantities agree. Raise the slider.';
      } else {
        msg = 'At ' + slider.value + ' % missed, the hourly count reads ' + count.toFixed(1) +
              '/h instead of 25.2 — a fall of ' + (p * 100).toFixed(0) +
              ' %. The raw mean log interval reads ' + raw.toFixed(1) +
              ' s instead of 21.0, because ' + (weights(p)[0] * 100).toFixed(0) +
              ' % of the observed intervals still belong to the fundamental and the rest have ' +
              'landed on its harmonics. The mixture model assigns each interval to its harmonic and ' +
              'returns the fundamental unchanged — together with an estimate of the miss rate itself.';
        if (slider.value === '39') {
          msg += ' This is the mechanically invisible fraction Terrill measured: bias +0.357 nats, ' +
                 'interval ×1.43, and 3.3 times the night-to-night variability the method relies on.';
        }
      }
      els.readout.textContent = msg;

      drawHist(p);
    }

    slider.addEventListener('input', update);
    onRedraw(update);
  })();

  /* =====================================================================
     3. THE NIGHT TRACE
     A synthetic envelope built from the parameters of the worked example
     in docs/03-algorithm.md §7.
     ===================================================================== */

  (function () {
    var cv = document.getElementById('traceCanvas');
    if (!cv) return;

    var FS = 50;              // Hz
    var DUR = 420;            // seconds shown
    var N = FS * DUR;
    var FLOOR = 1.0;          // envelope expressed in multiples of the noise floor
    var K_ON = 8, K_OFF = 2.5, K_GBM = 40;

    /* events: [onset s, amplitude in multiples of floor, plateau s, kind] */
    var EV = [];
    (function build() {
      var ampA = [22.5, 30, 11.25, 37.5, 20, 26.25, 15];   // 90..150 mg over a 4 mg floor
      var lenA = [2.0, 3.0, 1.6, 2.4, 1.8, 2.2, 2.6];
      var i;
      for (i = 0; i < 7; i++) EV.push([25 + 21 * i, ampA[i], lenA[i], 'clm']);
      EV.push([188, 52, 6.5, 'gbm']);                       // ≥ ×40 → gross body movement
      var ampB = [18.75, 28, 12.5, 33, 21];
      var lenB = [2.2, 2.8, 1.7, 3.3, 2.0];
      for (i = 0; i < 5; i++) EV.push([236 + 21 * i, ampB[i], lenB[i], 'clm']);
      EV.push([378, 20, 1.9, 'iso']);
    })();

    /* magnitude signal, then the 0.50 s coarse RMS envelope of stage 2 */
    var env = (function () {
      var rand = rng(20260731);
      var m = new Float32Array(N), i;
      for (i = 0; i < N; i++) {
        /* Maxwell-like background: the L2 magnitude has a pedestal, not zero mean */
        var a = rand() - 0.5, b = rand() - 0.5, c = rand() - 0.5;
        m[i] = Math.sqrt(a * a + b * b + c * c) * 2.0 * FLOOR + 0.25;
      }
      EV.forEach(function (e) {
        var s = Math.round(e[0] * FS), L = Math.round(e[2] * FS), rise = Math.round(0.18 * FS);
        for (var j = -rise; j < L + rise; j++) {
          var idx = s + j;
          if (idx < 0 || idx >= N) continue;
          var g = j < 0 ? (j + rise) / rise : (j > L ? 1 - (j - L) / rise : 1);
          g = Math.max(0, Math.min(1, g));
          g = g * g * (3 - 2 * g);
          var A = e[1] * g;
          m[idx] = Math.sqrt(m[idx] * m[idx] + A * A);
        }
      });
      /* coarse envelope: RMS over 0.50 s, centred — hence the leading onset */
      var half = Math.round(0.25 * FS);
      var cum = new Float64Array(N + 1);
      for (i = 0; i < N; i++) cum[i + 1] = cum[i] + m[i] * m[i];
      var out = new Float32Array(N);
      for (i = 0; i < N; i++) {
        var lo = Math.max(0, i - half), hi = Math.min(N - 1, i + half);
        out[i] = Math.sqrt((cum[hi + 1] - cum[lo]) / (hi - lo + 1));
      }
      return out;
    })();

    /* onsets, as the detector would find them: first crossing of ×8 */
    var marks = EV.map(function (e) {
      var s = Math.round((e[0] - 1) * FS), on = e[0];
      for (var i = s; i < Math.min(N, s + 3 * FS); i++) {
        if (env[i] >= K_ON * FLOOR) { on = i / FS; break; }
      }
      return { t: on, kind: e[3], end: e[0] + e[2] };
    });
    var seriesBands = [
      { a: marks[0].t, b: marks[6].end },
      { a: marks[8].t, b: marks[12].end }
    ];

    var progress = reduced.matches ? 1 : 0;

    function draw() {
      var f = fitCanvas(cv), ctx = f.ctx, w = f.w, h = f.h;
      var t = tokens();
      ctx.clearRect(0, 0, w, h);

      var L = 48, R = 14, T = 16;
      var axisH = 26, evH = 20, serH = 12;
      var ph = h - T - axisH - evH - serH - 8;
      var pw = w - L - R;
      if (pw < 40) return;

      function px(sec) { return L + (sec / DUR) * pw; }
      function py(mult) {
        var v = Math.log2(Math.max(0.5, mult));       // log2, as the application draws it
        return T + ph - ((v - (-1)) / (6 - (-1))) * ph;
      }

      /* gridlines and the ×n axis */
      ctx.font = mono(10);
      ctx.textAlign = 'right';
      [1, 2, 4, 8, 16, 32, 64].forEach(function (m2) {
        var y = py(m2);
        ctx.strokeStyle = t['line-soft'];
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(L, y);
        ctx.lineTo(L + pw, y);
        ctx.stroke();
        ctx.fillStyle = t['faint'];
        ctx.fillText('×' + m2, L - 7, y + 3.5);
      });

      /* the envelope, min-max per pixel column, never averaged */
      var cols = Math.floor(pw);
      var shown = Math.floor(cols * progress);
      ctx.strokeStyle = t['blue'];
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (var c = 0; c < shown; c++) {
        var i0 = Math.floor((c / cols) * N), i1 = Math.floor(((c + 1) / cols) * N);
        var mn = Infinity, mx = -Infinity;
        for (var i = i0; i < i1; i++) {
          if (env[i] < mn) mn = env[i];
          if (env[i] > mx) mx = env[i];
        }
        if (mn === Infinity) continue;
        var x = L + c + 0.5;
        ctx.moveTo(x, py(mn));
        ctx.lineTo(x, py(mx));
      }
      ctx.stroke();

      /* thresholds */
      ctx.strokeStyle = t['brass'];
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      ctx.moveTo(L, py(K_ON));
      ctx.lineTo(L + pw, py(K_ON));
      ctx.stroke();

      ctx.save();
      ctx.setLineDash([2, 3]);
      ctx.strokeStyle = t['blue-dim'];
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(L, py(K_OFF));
      ctx.lineTo(L + pw, py(K_OFF));
      ctx.stroke();
      ctx.restore();

      ctx.font = mono(10);
      ctx.textAlign = 'left';
      ctx.fillStyle = t['brass'];
      ctx.fillText('Θon ×8', L + 4, py(K_ON) - 5);
      ctx.fillStyle = t['blue-dim'];
      ctx.fillText('Θoff ×2.5', L + 4, py(K_OFF) - 5);
      ctx.fillStyle = t['muted'];
      ctx.textAlign = 'right';
      ctx.fillText('gross body ×40', L + pw - 4, py(K_GBM) - 5);
      ctx.save();
      ctx.setLineDash([1, 4]);
      ctx.strokeStyle = t['muted'];
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(L, py(K_GBM));
      ctx.lineTo(L + pw, py(K_GBM));
      ctx.stroke();
      ctx.restore();

      /* event band — shape, not colour, carries the class */
      var evY = T + ph + 8;
      ctx.strokeStyle = t['line'];
      ctx.beginPath();
      ctx.moveTo(L, evY + evH);
      ctx.lineTo(L + pw, evY + evH);
      ctx.stroke();

      marks.forEach(function (mk) {
        if (mk.t / DUR > progress) return;
        var x = px(mk.t);
        if (mk.kind === 'gbm') {
          ctx.strokeStyle = t['muted'];
          ctx.lineWidth = 1.3;
          ctx.beginPath();
          ctx.moveTo(x - 4, evY + 4); ctx.lineTo(x + 4, evY + 12);
          ctx.moveTo(x + 4, evY + 4); ctx.lineTo(x - 4, evY + 12);
          ctx.stroke();
        } else if (mk.kind === 'iso') {
          ctx.strokeStyle = t['brass'];
          ctx.lineWidth = 1.4;
          ctx.strokeRect(x - 2.5, evY + 3, 5, 11);
        } else {
          ctx.fillStyle = t['brass'];
          ctx.fillRect(x - 2, evY + 3, 4, 11);
        }
      });

      /* series bands */
      var serY = evY + evH + 4;
      seriesBands.forEach(function (b) {
        var end = Math.min(b.b, progress * DUR);
        if (end <= b.a) return;
        ctx.fillStyle = t['brass-dim'];
        ctx.fillRect(px(b.a), serY, px(end) - px(b.a), 5);
      });
      ctx.fillStyle = t['faint'];
      ctx.font = mono(9);
      ctx.textAlign = 'left';
      ctx.fillText('series ≥ 4', L, serY + 14);

      /* time axis */
      var axY = h - 8;
      ctx.textAlign = 'center';
      ctx.font = mono(10);
      for (var s2 = 0; s2 <= DUR; s2 += 60) {
        ctx.fillStyle = t['faint'];
        ctx.fillText(Math.floor(s2 / 60) + ' min', px(s2), axY);
      }
    }

    onRedraw(draw);

    if (!reduced.matches) {
      var started = false;
      var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (e) {
          if (!e.isIntersecting || started) return;
          started = true;
          var t0 = null;
          function step(ts) {
            if (t0 === null) t0 = ts;
            progress = Math.min(1, (ts - t0) / 1700);
            draw();
            if (progress < 1) requestAnimationFrame(step);
          }
          requestAnimationFrame(step);
        });
      }, { threshold: 0.25 });
      io.observe(cv);
    }
  })();

})();
