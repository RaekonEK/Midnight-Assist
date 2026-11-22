/*
 * Static verifier for the Midnight Assist Fabric jars.
 *
 * For every build tree it links the built jar against the actual Minecraft
 * classes it was compiled for (intermediary jars cached by Loom / the
 * deobfuscated jars used by 26.x), then checks every profile:
 *   - fabric.mod.json dependencies resolve against the installed mods
 *   - entrypoint classes exist in the jar
 *   - mixin configs / mixin classes exist in the jar
 *   - mixin refmap targets exist in the Minecraft version
 *   - all net/minecraft references resolve (class + member, with inheritance)
 *   - all non-Minecraft references exist in the profile's mods / libraries
 *
 * Usage:  node tools/verify_fabric_profiles.js [--verbose] [--tree=<substring>]
 *         node tools/verify_fabric_profiles.js --exact [--profile=<substring>]
 *           (--exact links the installed jar in every profile against that
 *            profile's own client jar in .fabric/remappedJars)
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const HOME = process.env.USERPROFILE || process.env.HOME;
const GRADLE = path.join(HOME, '.gradle', 'caches', 'fabric-loom');
const APP = path.join(process.env.APPDATA || path.join(HOME, 'AppData', 'Roaming'), 'AstralRinthApp');
const PROFILES = path.join(APP, 'profiles');
const LIBRARIES = path.join(APP, 'meta', 'libraries');
const REPO = path.resolve(__dirname, '..');
const TREES = path.join(REPO, 'Fabric & Quilt');
const VERBOSE = process.argv.includes('--verbose');
const TREE_FILTER = ((process.argv.find((a) => a.startsWith('--tree=')) || '').slice(7));
const EXACT = process.argv.includes('--exact');
const PROFILE_FILTER = ((process.argv.find((a) => a.startsWith('--profile=')) || '').slice(10));

// ---------------------------------------------------------------- zip reader
function zipEntries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65558); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = new Map();
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory');
    const method = buf.readUInt16LE(p + 10);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOff = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    out.set(name, { method, compSize, localOff });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

function zipRead(buf, entry) {
  const p = entry.localOff;
  const nameLen = buf.readUInt16LE(p + 26);
  const extraLen = buf.readUInt16LE(p + 28);
  const start = p + 30 + nameLen + extraLen;
  const data = buf.subarray(start, start + entry.compSize);
  return entry.method === 0 ? data : zlib.inflateRawSync(data);
}

function loadJar(file) {
  const buf = fs.readFileSync(file);
  const entries = zipEntries(buf);
  return {
    file,
    classes() {
      const names = [];
      for (const name of entries.keys()) if (name.endsWith('.class') && !name.startsWith('META-INF/')) names.push(name.slice(0, -6));
      return names;
    },
    read(name) {
      const e = entries.get(name);
      return e ? zipRead(buf, e) : null;
    },
    has(name) { return entries.has(name); },
    nestedJars() {
      const list = [];
      for (const name of entries.keys()) {
        if (/^META-INF\/(jars|jarjar)\/.+\.jar$/.test(name)) list.push({ name, data: zipRead(buf, entries.get(name)) });
      }
      return list;
    },
  };
}

// ------------------------------------------------------------- class parser
const CP_UTF8 = 1, CP_INT = 3, CP_FLOAT = 4, CP_LONG = 5, CP_DOUBLE = 6,
  CP_CLASS = 7, CP_STRING = 8, CP_FIELD = 9, CP_METHOD = 10, CP_IMETHOD = 11,
  CP_NAME_TYPE = 12, CP_MH = 15, CP_MT = 16, CP_DYN = 17, CP_INDY = 18,
  CP_MODULE = 19, CP_PACKAGE = 20;

function parseClass(buf, opts = {}) {
  if (buf.readUInt32BE(0) !== 0xCAFEBABE) throw new Error('bad class');
  const cpCount = buf.readUInt16BE(8);
  const cp = new Array(cpCount);
  let p = 10;
  for (let i = 1; i < cpCount; i++) {
    const tag = buf[p++];
    switch (tag) {
      case CP_UTF8: { const len = buf.readUInt16BE(p); p += 2; cp[i] = { tag, v: buf.toString('utf8', p, p + len) }; p += len; break; }
      case CP_INT: case CP_FLOAT: p += 4; break;
      case CP_LONG: case CP_DOUBLE: p += 8; i++; break;
      case CP_CLASS: cp[i] = { tag, a: buf.readUInt16BE(p) }; p += 2; break;
      case CP_STRING: case CP_MT: case CP_MODULE: case CP_PACKAGE: p += 2; break;
      case CP_FIELD: case CP_METHOD: case CP_IMETHOD: case CP_NAME_TYPE: case CP_DYN: case CP_INDY:
        cp[i] = { tag, a: buf.readUInt16BE(p), b: buf.readUInt16BE(p + 2) }; p += 4; break;
      case CP_MH: cp[i] = { tag, a: buf[p], b: buf.readUInt16BE(p + 1) }; p += 3; break;
      default: throw new Error('bad constant tag ' + tag);
    }
  }
  const utf = (i) => (cp[i] && cp[i].tag === CP_UTF8 ? cp[i].v : null);
  const className = (i) => { const c = cp[i]; return c && c.tag === CP_CLASS ? utf(c.a) : null; };
  const utfStrings = [];
  for (let i = 1; i < cpCount; i++) if (cp[i] && cp[i].tag === CP_UTF8) utfStrings.push(cp[i].v);

  const access = buf.readUInt16BE(p); p += 2;
  const thisName = className(buf.readUInt16BE(p)); p += 2;
  const superIdx = buf.readUInt16BE(p); p += 2;
  const superName = superIdx ? className(superIdx) : null;
  const ifCount = buf.readUInt16BE(p); p += 2;
  const interfaces = [];
  for (let i = 0; i < ifCount; i++) { interfaces.push(className(buf.readUInt16BE(p))); p += 2; }

  const methods = new Set();
  const fields = new Set();
  const descs = new Set();
  function readMembers(isMethod) {
    const count = buf.readUInt16BE(p); p += 2;
    for (let i = 0; i < count; i++) {
      p += 2; // access
      const name = utf(buf.readUInt16BE(p)); p += 2;
      const desc = utf(buf.readUInt16BE(p)); p += 2;
      const attrCount = buf.readUInt16BE(p); p += 2;
      if (desc) descs.add(desc);
      if (name && desc) (isMethod ? methods : fields).add(name + (isMethod ? desc : ':' + desc));
      for (let a = 0; a < attrCount; a++) {
        p += 2;
        const len = buf.readUInt32BE(p); p += 4 + len;
      }
    }
  }
  readMembers(false);
  readMembers(true);

  const result = { name: thisName, superName, interfaces, methods, fields, access };

  if (!opts.membersOnly) {
    const classRefs = new Set();
    const methodRefs = new Map();
    const fieldRefs = new Map();
    for (let i = 1; i < cpCount; i++) {
      const e = cp[i];
      if (!e) continue;
      if (e.tag === CP_CLASS) {
        const n = utf(e.a);
        if (n) { const c = normClass(n); if (c) classRefs.add(c); }
      } else if (e.tag === CP_FIELD || e.tag === CP_METHOD || e.tag === CP_IMETHOD) {
        const owner = className(e.a);
        const nat = cp[e.b];
        if (!owner || !nat || nat.tag !== CP_NAME_TYPE) continue;
        const name = utf(nat.a), desc = utf(nat.b);
        if (!name || !desc) continue;
        descs.add(desc);
        const key = owner + '\u0000' + name + '\u0000' + desc;
        (e.tag === CP_FIELD ? fieldRefs : methodRefs).set(key, { owner, name, desc });
      }
    }
    // add classes named inside descriptors of declared / referenced members
    const descRe = /L([A-Za-z0-9_$/]+);/g;
    for (const d of descs) {
      let m;
      while ((m = descRe.exec(d)) !== null) { const c = normClass(m[1]); if (c) classRefs.add(c); }
    }
    result.classRefs = classRefs;
    result.methodRefs = [...methodRefs.values()];
    result.fieldRefs = [...fieldRefs.values()];
    result.utfStrings = utfStrings;
  }
  return result;
}

// ------------------------------------------------------------- mc indexing
function indexJar(file, opts = {}) {
  const jar = loadJar(file);
  const index = new Map();
  for (const name of jar.classes()) {
    let cls;
    try { cls = parseClass(jar.read(name + '.class'), { membersOnly: true }); } catch (e) { continue; }
    if (opts.prefix && cls.name && !cls.name.startsWith(opts.prefix)) continue;
    index.set(cls.name, {
      superName: cls.superName,
      interfaces: cls.interfaces,
      methods: cls.methods,
      fields: cls.fields,
    });
  }
  return index;
}

// true = found, false = definitely absent, null = may be inherited from a JDK type (unknown).
// java/lang/Object is treated as "has no members" so missing members are still detected.
function unknownOwner(owner) {
  return owner !== 'java/lang/Object' && jdkClass(owner);
}

function hasMethod(index, owner, name, desc, seen = new Set()) {
  if (!owner) return false;
  if (seen.has(owner)) return false;
  seen.add(owner);
  const c = index.get(owner);
  if (!c) return unknownOwner(owner) ? null : false;
  if (c.methods.has(name + desc)) return true;
  let unknown = false;
  if (c.superName) { const r = hasMethod(index, c.superName, name, desc, seen); if (r === true) return true; if (r === null) unknown = true; }
  for (const i of c.interfaces) { const r = hasMethod(index, i, name, desc, seen); if (r === true) return true; if (r === null) unknown = true; }
  return unknown ? null : false;
}

function hasField(index, owner, name, desc, seen = new Set()) {
  if (!owner) return false;
  if (seen.has(owner)) return false;
  seen.add(owner);
  const c = index.get(owner);
  if (!c) return unknownOwner(owner) ? null : false;
  if (c.fields.has(name + ':' + desc)) return true;
  let unknown = false;
  if (c.superName) { const r = hasField(index, c.superName, name, desc, seen); if (r === true) return true; if (r === null) unknown = true; }
  for (const i of c.interfaces) { const r = hasField(index, i, name, desc, seen); if (r === true) return true; if (r === null) unknown = true; }
  return unknown ? null : false;
}

// ------------------------------------------------------------- tiny mappings
function parseTiny(text) {
  const lines = text.split(/\r?\n/);
  const header = lines[0].split('\t');
  const classes = new Set();
  const members = new Map();
  const obfToInter = new Map();
  const mode = header[0];

  if (mode === 'tiny') {
    const rows = [];
    let current = null;
    for (const line of lines) {
      if (!line || line.startsWith('tiny')) continue;
      const depth = line.match(/^\t*/)[0].length;
      const parts = line.slice(depth).split('\t');
      const kind = parts[0];
      if (kind === 'c' && depth === 0) {
        const obf = parts[1], inter = parts[2];
        current = inter || null;
        if (!inter) continue;
        obfToInter.set(obf, inter);
        classes.add(inter);
        if (!members.has(inter)) members.set(inter, { m: new Set(), f: new Set() });
      } else if ((kind === 'm' || kind === 'f') && depth === 1 && current) {
        rows.push({ kind, desc: parts[1], interName: parts[3], owner: current });
      }
    }
    const mapDesc = (d) => d.replace(/L([^;]+);/g, (m, n) => obfToInter.has(n) ? 'L' + obfToInter.get(n) + ';' : m);
    for (const r of rows) {
      const bucket = members.get(r.owner);
      if (!bucket) continue;
      (r.kind === 'm' ? bucket.m : bucket.f).add(r.interName + mapDesc(r.desc));
    }
  } else if (mode === 'v1') {
    for (const line of lines) {
      const parts = line.split('\t');
      if (parts[0] === 'CLASS') {
        const obf = parts[1], inter = parts[2];
        if (!inter) continue;
        obfToInter.set(obf, inter);
        classes.add(inter);
        if (!members.has(inter)) members.set(inter, { m: new Set(), f: new Set() });
      } else if (parts[0] === 'METHOD' || parts[0] === 'FIELD') {
        const owner = obfToInter.get(parts[1]);
        if (!owner) continue;
        const bucket = members.get(owner);
        if (!bucket) continue;
        const desc = parts[2].replace(/L([^;]+);/g, (m, n) => obfToInter.has(n) ? 'L' + obfToInter.get(n) + ';' : m);
        (parts[0] === 'METHOD' ? bucket.m : bucket.f).add(parts[4] + desc);
      }
    }
  } else {
    throw new Error('unknown mapping format: ' + header.join(' '));
  }
  return { classes, members, format: mode };
}

function tinyFromJar(jarFile) {
  const jar = loadJar(jarFile);
  if (!jar.has('mappings/mappings.tiny')) return null;
  return jar.read('mappings/mappings.tiny').toString('utf8');
}

// ------------------------------------------------------------- misc helpers
function jdkClass(name) {
  return /^(java|javax|jdk|sun|com\/sun)\//.test(name);
}

function normClass(n) {
  if (!n) return n;
  while (n.startsWith('[')) n = n.slice(1);
  if (n.startsWith('L') && n.endsWith(';')) n = n.slice(1, -1);
  if (/^[BCDFIJSZV]$/.test(n)) return null; // primitive array element
  return n;
}

function versionParts(v) {
  return String(v).split('+')[0].split('-')[0].split('.').map((x) => (/^\d+$/.test(x) ? +x : x));
}

function cmpVer(a, b) {
  const pa = versionParts(a), pb = versionParts(b);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] === undefined ? 0 : pa[i];
    const y = pb[i] === undefined ? 0 : pb[i];
    if (x === y) continue;
    if (typeof x === 'number' && typeof y === 'number') return x < y ? -1 : 1;
    return String(x) < String(y) ? -1 : 1;
  }
  return 0;
}

function satisfies(version, range) {
  if (!range || range === '*') return true;
  return String(range).trim().split(/\s+/).every((part) => {
    if (part === '*') return true;
    let m;
    if ((m = /^>=(.+)$/.exec(part))) return cmpVer(version, m[1]) >= 0;
    if ((m = /^<=(.+)$/.exec(part))) return cmpVer(version, m[1]) <= 0;
    if ((m = /^>(.+)$/.exec(part))) return cmpVer(version, m[1]) > 0;
    if ((m = /^<(.+)$/.exec(part))) return cmpVer(version, m[1]) < 0;
    if ((m = /^=(.+)$/.exec(part))) return cmpVer(version, m[1]) === 0;
    if ((m = /^~(.+)$/.exec(part))) {
      const base = m[1].split('.');
      const upper = [base[0], base[1] === undefined ? 0 : +base[1] + 1].join('.');
      return cmpVer(version, m[1]) >= 0 && cmpVer(version, upper) < 0;
    }
    if ((m = /^\^(.+)$/.exec(part))) {
      const base = m[1].split('.');
      const upper = [base[0] === '0' ? base[1] : +base[0] + 1].join('.');
      return cmpVer(version, m[1]) >= 0 && cmpVer(version, upper) < 0;
    }
    return cmpVer(version, part) === 0;
  });
}

function readJsonFromJar(jar, entry) {
  const data = jar.read(entry);
  if (!data) return null;
  try { return JSON.parse(data.toString('utf8')); } catch (e) { return null; }
}

// -------------------------------------------------- external class index
const externalClasses = new Map();
const externalJars = [];
const jarCache = new Map();

function openJar(file) {
  if (!jarCache.has(file)) jarCache.set(file, loadJar(file));
  return jarCache.get(file);
}

function indexJarClasses(file) {
  if (!fs.existsSync(file)) return;
  let jar;
  try { jar = openJar(file); } catch (e) { return; }
  externalJars.push(file);
  for (const c of jar.classes()) if (!externalClasses.has(c)) externalClasses.set(c, file);
  for (const nested of jar.nestedJars()) {
    try {
      const entries = zipEntries(nested.data);
      for (const name of entries.keys()) {
        if (!name.endsWith('.class') || name.startsWith('META-INF/')) continue;
        const c = name.slice(0, -6);
        if (!externalClasses.has(c)) externalClasses.set(c, file + '!' + nested.name);
      }
    } catch (e) { /* ignore */ }
  }
}

function indexDirectory(dir) {
  if (!fs.existsSync(dir)) return;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) indexDirectory(p);
    else if (e.name.endsWith('.jar')) indexJarClasses(p);
  }
}

const LIB_DIRS = [
  'net/fabricmc/fabric-loader', 'net/fabricmc/sponge-mixin', 'net/fabricmc/access-widener',
  'org/ow2/asm', 'org/slf4j', 'org/jetbrains', 'it/unimi', 'org/lwjgl', 'io/netty',
  'com/mojang', 'com/google', 'org/apache', 'org/joml', 'net/java/dev/jna', 'org/yaml',
  'blue/endless', 'net/kyori', 'org/quiltmc', 'net/minecraft',
];

// ------------------------------------------------------------- tree definitions
const TREE_META = {
  // The mod avoids the two incompatible Fabric API generations on this line
  // (own MinecraftClient tick mixin + reflective keybind registration), so a
  // single jar covers 1.14.0 - 1.14.4. Each profile gets the Fabric API build
  // that actually boots on it (0.2.7 / 0.3.0 / 0.28.5).
  'Fabric-Quilt 1.14.x 1.4.0': { mc: '1.14.4', profiles: ['1.14', '1.14.1', '1.14.2', '1.14.3', '1.14.4'] },
  'Fabric-Quilt 1.15.2 1.4.0': { mc: '1.15.2', profiles: ['1.15', '1.15.1', '1.15.2'] },
  'Fabric-Quilt 1.16.5 1.4.0': { mc: '1.16.5', profiles: ['1.16', '1.16.1', '1.16.2', '1.16.3', '1.16.4', '1.16.5'] },
  'Fabric-Quilt 1.17.x 1.4.0': { mc: '1.17.1', profiles: ['1.17', '1.17.1'] },
  'Fabric-Quilt 1.18 1.4.0': { mc: '1.18.2', profiles: ['1.18', '1.18.1', '1.18.2'] },
  'Fabric-Quilt 1.19-1.19.2 1.4.0': { mc: '1.19.2', profiles: ['1.19', '1.19.1', '1.19.2'] },
  'Fabric-Quilt 1.19.3 1.4.0': { mc: '1.19.3', profiles: ['1.19.3'] },
  'Fabric-Quilt 1.19.4 1.4.0': { mc: '1.19.4', profiles: ['1.19.4'] },
  'Fabric-Quilt 1.20-1.20.1 1.4.0': { mc: '1.20.1', profiles: ['1.20', '1.20.1'] },
  'Fabric-Quilt 1.20.3-1.20.4 1.4.0': { mc: '1.20.4', profiles: ['1.20.3', '1.20.4'] },
  'Fabric-Quilt 1.20.5-1.20.6 1.4.0': { mc: '1.20.6', profiles: ['1.20.5', '1.20.6'] },
  'Fabric-Quilt 1.21-1.21.1 1.4.0': { mc: '1.21.1', profiles: ['1.21', '1.21.1'] },
  'Fabric-Quilt 1.21.2-1.21.3 1.4.0': { mc: '1.21.3', profiles: ['1.21.2', '1.21.3'] },
  'Fabric-Quilt 1.21.4-1.21.8 1.4.0': { mc: '1.21.8', profiles: ['1.21.4', '1.21.5', '1.21.6', '1.21.7', '1.21.8'] },
  'Fabric-Quilt 1.21.9-1.21.11 1.4.0': { mc: '1.21.11', profiles: ['1.21.9', '1.21.10', '1.21.11'] },
  'Fabric-Quilt 26.1.x 1.4.0': { mc: '26.1', profiles: ['26.1', '26.1.1', '26.1.2'] },
  'Fabric-Quilt 26.2 1.4.0': { mc: '26.2', profiles: ['26.2'] },
  'Fabric-Quilt 26.3 1.4.0': { mc: '26.3', profiles: ['26.3'] },
};

// ---------------------------------------------------- locate minecraft classes
function mcJarFor(mc) {
  const merged = path.join(GRADLE, mc, 'minecraft-merged-intermediary.jar');
  if (fs.existsSync(merged)) return { files: [merged], kind: 'intermediary' };

  const root = path.join(GRADLE, 'minecraftMaven', 'net', 'minecraft');
  const found = [];
  for (const artifact of ['minecraft-merged-intermediary', 'minecraft-common-intermediary', 'minecraft-clientonly-intermediary']) {
    const base = path.join(root, artifact);
    if (!fs.existsSync(base)) continue;
    for (const dir of fs.readdirSync(base)) {
      if (!dir.startsWith(mc + '-')) continue;
      const full = path.join(base, dir);
      for (const f of fs.readdirSync(full)) {
        if (f.endsWith('.jar') && !f.includes('sources') && !f.includes('backup')) found.push(path.join(full, f));
      }
    }
  }
  if (found.length) return { files: found, kind: 'intermediary' };

  if (mc.startsWith('26.')) {
    const deobf = path.join(GRADLE, mc, 'minecraft-client.jar');
    if (fs.existsSync(deobf)) return { files: [deobf], kind: 'official' };
  }
  return null;
}

function findProfileMcJar(profileName) {
  const base = path.join(PROFILES, profileName, '.fabric', 'remappedJars');
  if (!fs.existsSync(base)) return null;
  for (const d of fs.readdirSync(base)) {
    const dir = path.join(base, d);
    let entries;
    try { entries = fs.readdirSync(dir); } catch (e) { continue; }
    for (const f of entries) {
      if (f === 'client-intermediary.jar') return path.join(dir, f);
    }
  }
  return null;
}

function tinyFor(mc) {
  const direct = path.join(GRADLE, mc, 'intermediary-v2.tiny');
  if (fs.existsSync(direct)) return fs.readFileSync(direct, 'utf8');
  const jar = path.join(LIBRARIES, 'net', 'fabricmc', 'intermediary', mc, 'intermediary-' + mc + '.jar');
  if (fs.existsSync(jar)) {
    try {
      const text = tinyFromJar(jar);
      if (text && text.split('\n').length > 3) return text;
    } catch (e) { /* ignore */ }
  }
  return null;
}

// ------------------------------------------------------------- report helpers
const problems = [];
function problem(tree, profile, kind, message) {
  problems.push({ tree, profile, kind, message });
}

// ------------------------------------------------------------- main
function main() {
  if (!EXACT) {
    console.log('Indexing external classpath (profile mods + libraries)...');
    for (const dir of LIB_DIRS) indexDirectory(path.join(LIBRARIES, dir));
    for (const name of fs.readdirSync(PROFILES)) {
      if (!name.startsWith('Fabric ')) continue;
      indexDirectory(path.join(PROFILES, name, 'mods'));
    }
    console.log(`  indexed ${externalClasses.size} classes from ${externalJars.length} jars`);
  }

  const trees = EXACT ? [] : Object.keys(TREE_META).filter((t) => !TREE_FILTER || t.includes(TREE_FILTER));

  for (const tree of trees) {
    const meta = TREE_META[tree];
    const dir = path.join(TREES, tree);
    const libs = path.join(dir, 'build', 'libs');
    if (!fs.existsSync(libs)) { console.log(`${tree}: NO BUILD`); continue; }
    const jarFile = fs.readdirSync(libs).find((f) => f.endsWith('.jar') && !f.includes('sources') && !f.includes('dev'));
    if (!jarFile) { console.log(`${tree}: NO JAR`); continue; }
    const modJar = openJar(path.join(libs, jarFile));
    const fmj = readJsonFromJar(modJar, 'fabric.mod.json');
    if (!fmj) { console.log(`${tree}: INVALID fabric.mod.json`); continue; }

    // ---- mixin configs / entrypoints ----
    const mixinClasses = new Set();
    for (const m of (fmj.mixins || [])) {
      const cfgName = typeof m === 'string' ? m : m.config;
      const cfg = readJsonFromJar(modJar, cfgName);
      if (!cfg) { problem(tree, '-', 'mixin', `config ${cfgName} missing`); continue; }
      const pkg = cfg.package || '';
      for (const k of ['mixins', 'client', 'server']) {
        if (!Array.isArray(cfg[k])) continue;
        for (const c of cfg[k]) {
          const cls = (pkg ? pkg.replace(/\./g, '/') + '/' : '') + c.replace(/\./g, '/');
          mixinClasses.add(cls);
          if (!modJar.has(cls + '.class')) problem(tree, '-', 'mixin', `class ${cls} not in jar (${cfgName})`);
        }
      }
    }
    for (const [ep, entries] of Object.entries(fmj.entrypoints || {})) {
      for (const e of entries) {
        const value = typeof e === 'string' ? e : e.value;
        if (!value) continue;
        if (!modJar.has(value.replace(/\./g, '/') + '.class')) problem(tree, '-', 'entrypoint', `${ep} -> ${value} not in jar`);
      }
    }

    // ---- minecraft link check ----
    const mcJar = mcJarFor(meta.mc);
    let mcIndex = null;
    let mcMode = 'none';
    if (mcJar) {
      mcIndex = new Map();
      for (const file of mcJar.files) for (const [k, v] of indexJar(file, { prefix: 'net/' })) mcIndex.set(k, v);
      mcMode = mcJar.kind;
    } else {
      const tiny = tinyFor(meta.mc);
      if (tiny) { mcIndex = parseTiny(tiny); mcMode = 'mappings'; }
    }

    let mcClassErrors = 0, mcMemberErrors = 0, checkedClasses = 0, checkedRefs = 0;
    const externalMissing = new Set();
    const mcIssues = new Map();
    const mcIssue = (kind, message) => {
      if (kind === 'mc-class') mcClassErrors++; else mcMemberErrors++;
      const key = kind + ' ' + message;
      mcIssues.set(key, kind);
      if (VERBOSE) console.log(`  [${kind}] ${tree} ${message}`);
    };
    if (mcIndex) {
      for (const cn of modJar.classes()) {
        let cls;
        try { cls = parseClass(modJar.read(cn + '.class')); } catch (e) { continue; }
        checkedClasses++;

        for (const ref of cls.classRefs) {
          if (ref.startsWith('net/minecraft/')) {
            checkedRefs++;
            const exists = mcMode === 'mappings'
              ? (mcIndex.classes.has(ref) || mcIndex.members.has(ref))
              : mcIndex.has(ref);
            if (!exists) mcIssue('mc-class', ref);
          } else if (!externalClasses.has(ref) && !jdkClass(ref)) {
            externalMissing.add(ref);
          }
        }

        for (const r of cls.methodRefs) {
          if (!r.owner.startsWith('net/minecraft/')) continue;
          checkedRefs++;
          const ok = mcMode === 'mappings'
            ? !!(mcIndex.members.get(r.owner) && mcIndex.members.get(r.owner).m.has(r.name + r.desc))
            : hasMethod(mcIndex, r.owner, r.name, r.desc) !== false;
          if (!ok) mcIssue('mc-member', `${r.owner}.${r.name}${r.desc}`);
        }
        for (const r of cls.fieldRefs) {
          if (!r.owner.startsWith('net/minecraft/')) continue;
          checkedRefs++;
          const ok = mcMode === 'mappings'
            ? !!(mcIndex.members.get(r.owner) && mcIndex.members.get(r.owner).f.has(r.name + ':' + r.desc))
            : hasField(mcIndex, r.owner, r.name, r.desc) !== false;
          if (!ok) mcIssue('mc-field', `${r.owner}.${r.name}:${r.desc}`);
        }

        // @At(target = "Lowner;name(desc)") / field targets inside mixin classes
        if (mixinClasses.has(cn) && cls.utfStrings) {
          const methodTarget = /L(net\/minecraft\/[A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+)(\([^)]*\)[A-Za-z0-9_$/;\[]*)/g;
          const fieldTarget = /L(net\/minecraft\/[A-Za-z0-9_$/]+);([A-Za-z0-9_$]+):(\[*[A-Za-z0-9_$/;]+)/g;
          for (const s of cls.utfStrings) {
            if (!s.includes('net/minecraft/')) continue;
            let mt;
            while ((mt = methodTarget.exec(s)) !== null) {
              const owner = mt[1], name = mt[2], desc = mt[3];
              checkedRefs++;
              const ok = mcMode === 'mappings'
                ? !!(mcIndex.members.get(owner) && mcIndex.members.get(owner).m.has(name + desc))
                : hasMethod(mcIndex, owner, name, desc) !== false;
              if (!ok) mcIssue('mixin-target', `${owner}.${name}${desc}`);
            }
            let ft;
            while ((ft = fieldTarget.exec(s)) !== null) {
              const owner = ft[1], name = ft[2], desc = ft[3];
              checkedRefs++;
              const ok = mcMode === 'mappings'
                ? !!(mcIndex.members.get(owner) && mcIndex.members.get(owner).f.has(name + ':' + desc))
                : hasField(mcIndex, owner, name, desc) !== false;
              if (!ok) mcIssue('mixin-target', `${owner}.${name}:${desc}`);
            }
          }
        }
      }
    }
    for (const [key, kind] of mcIssues) problem(tree, '-', kind, key.slice(kind.length + 1));

    // ---- refmap targets ----
    let mixinTargetErrors = 0;
    const refmap = readJsonFromJar(modJar, 'midnight-assist-refmap.json');
    if (refmap && refmap.mappings) {
      for (const [owner, entries] of Object.entries(refmap.mappings)) {
        for (const target of Object.values(entries)) {
          const m = /^L([^;]+);([^(]+)(\(.*)$/.exec(target);
          if (!m) continue;
          const cls = m[1], name = m[2], desc = m[3];
          const ok = mcMode === 'mappings'
            ? !!(mcIndex && mcIndex.members.get(cls) && mcIndex.members.get(cls).m.has(name + desc))
            : (mcIndex ? hasMethod(mcIndex, cls, name, desc) !== false : false);
          if (!ok) { mixinTargetErrors++; problem(tree, '-', 'mixin-target', `${owner}: ${target}`); }
        }
      }
    }

    for (const cls of [...externalMissing].sort()) {
      const hard = /^(me\/shedaniel|com\/terraformersmc)\//.test(cls);
      problem(tree, '-', hard ? 'external-class' : 'external-class?', cls);
    }

    console.log(
      `${tree.padEnd(34)} mc=${meta.mc.padEnd(7)} ${mcMode.padEnd(12)} ` +
      `classes=${String(checkedClasses).padStart(3)} refs=${String(checkedRefs).padStart(4)} mcClassErr=${mcClassErrors} mcMemberErr=${mcMemberErrors} ` +
      `mixinTargetErr=${mixinTargetErrors} extMissing=${externalMissing.size}`
    );
  }

  // ---- per-profile metadata checks ----
  console.log('\nProfile dependency / entrypoint checks:');
  for (const tree of trees) {
    const dir = path.join(TREES, tree);
    const libs = path.join(dir, 'build', 'libs');
    if (!fs.existsSync(libs)) continue;
    const jarFile = fs.readdirSync(libs).find((f) => f.endsWith('.jar') && !f.includes('sources') && !f.includes('dev'));
    if (!jarFile) continue;
    const fmj = readJsonFromJar(openJar(path.join(libs, jarFile)), 'fabric.mod.json');
    for (const pv of TREE_META[tree].profiles) {
      const modsDir = path.join(PROFILES, 'Fabric ' + pv, 'mods');
      const installed = new Map();
      if (fs.existsSync(modsDir)) {
        for (const f of fs.readdirSync(modsDir)) {
          if (!f.endsWith('.jar')) continue;
          let jar;
          try { jar = openJar(path.join(modsDir, f)); } catch (e) { continue; }
          const meta2 = readJsonFromJar(jar, 'fabric.mod.json') || readJsonFromJar(jar, 'quilt.mod.json');
          if (!meta2) continue;
          const id = meta2.id || (meta2.quilt_loader && meta2.quilt_loader.id);
          const version = meta2.version || (meta2.quilt_loader && meta2.quilt_loader.version) || '0';
          if (id) installed.set(id, version);
          for (const p of (meta2.provides || [])) installed.set(typeof p === 'string' ? p : (p.id || ''), version);
        }
      }
      for (const [dep, range] of Object.entries(fmj.depends || {})) {
        if (dep === 'java') continue;
        if (dep === 'minecraft') {
          if (!satisfies(pv, range)) problem(tree, pv, 'depends', `minecraft ${range} vs profile ${pv}`);
          continue;
        }
        if (dep === 'fabricloader') {
          if (!satisfies('0.19.5', range)) problem(tree, pv, 'depends', `fabricloader ${range} vs 0.19.5`);
          continue;
        }
        if (dep === 'fabric') {
          if (!installed.has('fabric-api') && !installed.has('fabric')) problem(tree, pv, 'depends', 'fabric-api missing');
          continue;
        }
        if (!installed.has(dep)) { problem(tree, pv, 'depends', `${dep} missing (range ${range})`); continue; }
        if (range && range !== '*' && !satisfies(installed.get(dep), range)) {
          problem(tree, pv, 'depends', `${dep} ${installed.get(dep)} not in ${range}`);
        }
      }
    }
    console.log(`  ${tree.padEnd(34)} ${TREE_META[tree].profiles.length} profiles checked`);
  }

  // ---- exact per-profile link check ----
  if (EXACT) {
    console.log('\nExact check: installed jar vs each profile\'s own client jar');
    for (const name of fs.readdirSync(PROFILES)) {
      if (!name.startsWith('Fabric ')) continue;
      const pv = name.slice('Fabric '.length);
      if (PROFILE_FILTER && !pv.includes(PROFILE_FILTER)) continue;
      const modsDir = path.join(PROFILES, name, 'mods');
      const mid = fs.existsSync(modsDir)
        ? fs.readdirSync(modsDir).find((f) => f.startsWith('midnight-assist') && f.endsWith('.jar'))
        : null;
      if (!mid) { console.log(`  ${pv.padEnd(10)} no installed jar`); continue; }
      const mcFile = findProfileMcJar(name);
      if (!mcFile) { console.log(`  ${pv.padEnd(10)} skipped (no client jar)`); continue; }
      const jar = openJar(path.join(modsDir, mid));
      const fmj = readJsonFromJar(jar, 'fabric.mod.json');
      if (!fmj) { console.log(`  ${pv.padEnd(10)} INVALID fabric.mod.json`); continue; }
      const index = indexJar(mcFile, { prefix: 'net/' });

      // mixin configs + entrypoints
      const mixins = [];
      for (const m of (fmj.mixins || [])) {
        const cfgName = typeof m === 'string' ? m : m.config;
        const cfg = readJsonFromJar(jar, cfgName);
        if (!cfg) { problem('-', pv, 'mixin', `config ${cfgName} missing`); continue; }
        const pkg = cfg.package || '';
        for (const k of ['mixins', 'client', 'server']) {
          if (!Array.isArray(cfg[k])) continue;
          for (const c of cfg[k]) mixins.push((pkg ? pkg.replace(/\./g, '/') + '/' : '') + c.replace(/\./g, '/'));
        }
      }
      for (const cls of mixins) if (!jar.has(cls + '.class')) problem('-', pv, 'mixin', `class ${cls} missing`);
      for (const [ep, entries] of Object.entries(fmj.entrypoints || {})) {
        for (const e of entries) {
          const value = typeof e === 'string' ? e : e.value;
          if (value && !jar.has(value.replace(/\./g, '/') + '.class')) problem('-', pv, 'entrypoint', `${ep} -> ${value}`);
        }
      }

      let clsErr = 0, memErr = 0, refs = 0;
      for (const cn of jar.classes()) {
        let cls;
        try { cls = parseClass(jar.read(cn + '.class')); } catch (e) { continue; }
        for (const ref of cls.classRefs) {
          if (!ref.startsWith('net/minecraft/')) continue;
          refs++;
          if (!index.has(ref)) { clsErr++; if (VERBOSE) console.log(`  [mc-class] ${pv} ${ref}`); }
        }
        for (const r of cls.methodRefs) {
          if (!r.owner.startsWith('net/minecraft/')) continue;
          refs++;
          if (hasMethod(index, r.owner, r.name, r.desc) === false) { memErr++; if (VERBOSE) console.log(`  [mc-member] ${pv} ${r.owner}.${r.name}${r.desc}`); }
        }
        for (const r of cls.fieldRefs) {
          if (!r.owner.startsWith('net/minecraft/')) continue;
          refs++;
          if (hasField(index, r.owner, r.name, r.desc) === false) { memErr++; if (VERBOSE) console.log(`  [mc-field] ${pv} ${r.owner}.${r.name}:${r.desc}`); }
        }
      }
      console.log(`  ${pv.padEnd(10)} jar=${mid.padEnd(30)} refs=${String(refs).padStart(4)} mcClassErr=${clsErr} mcMemberErr=${memErr}`);
    }
  }

  // ---- summary ----
  console.log('\n================ SUMMARY ================');
  if (!problems.length) {
    console.log('No problems found.');
  } else {
    const grouped = new Map();
    for (const p of problems) {
      if (!grouped.has(p.kind)) grouped.set(p.kind, []);
      grouped.get(p.kind).push(p);
    }
    for (const [kind, list] of grouped) {
      console.log(`\n[${kind}] ${list.length} problem(s)`);
      for (const p of list.slice(0, VERBOSE ? 200 : 40)) {
        console.log(`  ${p.tree} / ${p.profile}: ${p.message}`);
      }
      if (list.length > 40 && !VERBOSE) console.log(`  ... ${list.length - 40} more (use --verbose)`);
    }
  }
}

main();
