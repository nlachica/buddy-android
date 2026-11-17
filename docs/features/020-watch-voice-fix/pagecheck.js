// Page check for 020: node docs/features/020-watch-voice-fix/pagecheck.js "$PWD"  (from the repo root; plain Node, not in CI)
// Part 1 parses both live pages' scripts; part 2 runs CodexLive's page against fakes (resume/pause recovery, log cap, hang-up).
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const expr = (java, name) => { const m = java.match(new RegExp('\\b' + name + '\\s*=\\s*([\\s\\S]*?");\\n')); if (!m) throw new Error('no ' + name); return m[1]; };
const dir = process.argv[2] + '/app/src/main/java/app/buddy/assistant/';
const sp = fs.readFileSync(dir + 'CodexSpeech.java', 'utf8'), lv = fs.readFileSync(dir + 'CodexLive.java', 'utf8');
const TAP_JS = eval(expr(sp, 'TAP_JS'));
const pages = {};
for (const [n, j] of [['CodexLive', lv], ['CodexSpeech', sp]]) {
  const page = eval(expr(j, 'PAGE').replace(/CodexSpeech\.TAP_JS|\bTAP_JS\b/g, 'TAP_JS'));
  pages[n] = page.match(/<script>([\s\S]*)<\/script>/)[1];
  new Function(pages[n]); // throws SyntaxError on a typo
  console.log(n + ' PAGE: syntax ok');
}

(async () => {
  const wait = async (n = 3) => { for (let i = 0; i < n; i++) await new Promise(r => setImmediate(r)); };
  const def = () => { const d = {}; d.p = new Promise((res, rej) => { d.res = res; d.rej = rej; }); return d; };
  const log = [], intervals = {}, resumes = [], plays = [];
  const count = s => log.filter(l => l.startsWith(s)).length;
  const ctx = {
    state: 'suspended', sampleRate: 48000,
    resume() { const d = def(); resumes.push(d); return d.p; },
    createAnalyser: () => ({ getFloatTimeDomainData() {} }),
    createMediaStreamSource: () => ({ connect() {} }),
    close() { ctx.state = 'closed'; },
  };
  const out = { paused: false, muted: false, volume: 0, srcObject: null, play() { const d = def(); plays.push(d); return d.p; } };
  let pc;
  const track = { label: 'fake', getSettings: () => ({}), enabled: true };
  const sandbox = {
    Buddy: { log: s => log.push(s), remote: () => false, toWatch: () => 0, level() {}, connected() {}, offer() {}, failed(m) { throw new Error('Buddy.failed: ' + m); } },
    AudioContext: function () { return ctx; },
    navigator: { mediaDevices: { getUserMedia: async () => ({ getAudioTracks: () => [track], getTracks: () => [track] }) } },
    RTCPeerConnection: function () {
      pc = this; this.signalingState = 'stable'; this.connectionState = 'new'; this.iceGatheringState = 'complete'; this.localDescription = { sdp: '' };
      this.addTrack = () => {}; this.createDataChannel = () => ({}); this.createOffer = async () => ({}); this.setLocalDescription = async () => {};
      this.getStats = async () => [];
      this.close = () => { this.connectionState = this.signalingState = 'closed'; };
    },
    document: { visibilityState: 'hidden', getElementById: () => out },
    setInterval: (f, ms) => { intervals[ms] = f; return ms; }, clearInterval() {}, setTimeout() {}, MediaStream: function () {},
  };
  vm.createContext(sandbox);
  vm.runInContext(pages.CodexLive, sandbox);
  const tick = async () => { intervals[70](); await wait(); };
  for (let i = 0; i < 20 && !intervals[10000]; i++) await wait();

  // 1. a first resume that stays pending must not stop the setup (F1); one resume in flight at a time
  assert(intervals[70] && intervals[10000], 'timers exist while the first resume is pending');
  assert(log[0].startsWith('audio context suspended'), 'first line: ' + log[0]);
  assert(/page hidden/.test(log[0]), 'first line has the page visibility: ' + log[0]);
  assert.strictEqual(typeof ctx.onstatechange, 'function');
  assert.strictEqual(resumes.length, 1);
  await tick(); await tick();
  assert.strictEqual(resumes.length, 1, 'no second resume while one is pending');
  resumes[0].rej(new Error('no')); await wait(); await tick();
  assert.strictEqual(resumes.length, 2, 'retry after a rejection');
  ctx.state = 'running'; resumes[1].res(); await wait(); await tick();
  assert.strictEqual(resumes.length, 2, 'no resume while running');
  ctx.state = 'suspended'; await tick();
  assert.strictEqual(resumes.length, 3, 'resume again when suspended later');
  ctx.state = 'running'; resumes[2].res(); await wait();

  // 2. the audio element is resumed whenever it pauses (F2)
  const stream = {};
  pc.ontrack({ track: { kind: 'audio', muted: false }, streams: [stream] });
  assert.strictEqual(plays.length, 1); plays[0].res(); await wait();
  out.paused = true; out.onpause(); await wait();
  assert.strictEqual(plays.length, 2, 'onpause resumes at once');
  assert.strictEqual(count('audio element paused'), 1);
  await tick(); await tick(); await tick();
  assert.strictEqual(plays.length, 2, 'a pending play() blocks another');
  plays[1].rej(new Error('x')); await wait(); await tick();
  assert.strictEqual(plays.length, 3, 'a rejected play() is retried');
  assert.strictEqual(count('audio element resume failed'), 1);
  plays[2].rej(new Error('x')); await wait(); await tick();
  assert.strictEqual(plays.length, 4, 'and again');
  assert.strictEqual(count('audio element resume failed'), 1, 'the same error is logged once');
  plays[3].res(); out.paused = false; await wait(); await tick();
  assert.strictEqual(plays.length, 4, 'playing: no new play()');

  // 3. pause logs are capped; the rtp line carries the states
  for (let i = 0; i < 6; i++) { out.paused = true; out.onpause(); await wait(); plays[plays.length - 1].res(); out.paused = false; await wait(); }
  assert.strictEqual(count('audio element paused'), 5, 'capped at 5 pause lines');
  intervals[10000](); await wait();
  const rtp = log[log.length - 1];
  for (const w of ['voice peak', 'audio playing', 'pauses', 'ctx running']) assert(rtp.includes(w), w + ' in: ' + rtp);

  // 4. a hung-up call resumes nothing
  sandbox.hangUp();
  const p = plays.length, r = resumes.length;
  out.paused = true; out.onpause(); await tick();
  assert.strictEqual(plays.length, p, 'no play() after hang-up');
  assert.strictEqual(resumes.length, r, 'no resume() after hang-up');
  console.log('behaviour ok');
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
