const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const [, , source, target] = process.argv;
if (!source || !target) throw new Error('Usage: node tools/pack-pool-scene.cjs <scene.blend> <scene.glb>');
const input = fs.readFileSync(source);
const b = input.subarray(0, 7).toString() === 'BLENDER' ? input : zlib.zstdDecompressSync(input);
if (b.subarray(0, 13).toString() !== 'BLENDER17-01v') throw new Error('Expected a Blender 5 scene');
const string = (offset, length) => b.subarray(offset, offset + length).toString().split('\0')[0];
const blocks = [], addresses = new Map();
for (let start = 17; start + 32 <= b.length; ) {
    const block = {
        code: string(start, 4), addr: b.readBigUInt64LE(start + 8),
        size: Number(b.readBigUInt64LE(start + 16)),
        count: b.readUInt32LE(start + 24), at: start + 32
    };
    if (block.at + block.size > b.length) throw new Error('Invalid Blender block size');
    if (block.code === 'ENDB') break;
    blocks.push(block);
    if (block.addr) addresses.set(block.addr, block);
    start = block.at + block.size;
}
const pointed = offset => addresses.get(b.readBigUInt64LE(offset));
const dna = blocks.find(block => block.code === 'DNA1');
let cursor = dna.at + 4;
function tag(value) {
    if (string(cursor, 4) !== value) throw new Error('Invalid Blender DNA: expected ' + value);
    cursor += 4;
}
function alignDNA() {
    cursor = dna.at + ((cursor - dna.at + 3) & ~3);
}
function strings() {
    const count = b.readUInt32LE(cursor);
    cursor += 4;
    const values = [];
    for (let i = 0; i < count; i++) {
        const end = b.indexOf(0, cursor);
        values.push(b.toString('ascii', cursor, end));
        cursor = end + 1;
    }
    alignDNA();
    return values;
}
tag('NAME');
const names = strings();
tag('TYPE');
const types = strings();
tag('TLEN');
const sizes = types.map(() => {
    const size = b.readUInt16LE(cursor);
    cursor += 2;
    return size;
});
alignDNA();
tag('STRC');
const structureCount = b.readUInt32LE(cursor);
cursor += 4;
const schema = new Map();
for (let i = 0; i < structureCount; i++) {
    const type = types[b.readUInt16LE(cursor)];
    const count = b.readUInt16LE(cursor + 2);
    cursor += 4;
    let offset = 0;
    const fields = new Map();
    for (let j = 0; j < count; j++) {
        const fieldType = b.readUInt16LE(cursor);
        const name = names[b.readUInt16LE(cursor + 2)];
        cursor += 4;
        fields.set(name, offset);
        const length = [...name.matchAll(/\[(\d+)\]/g)].reduce((n, dimension) => n * Number(dimension[1]), 1);
        offset += length * (name.includes('*') ? 8 : sizes[fieldType]);
    }
    schema.set(type, {fields, size: sizes[types.indexOf(type)]});
}
function field(start, type, name) {
    const offset = schema.get(type)?.fields.get(name);
    if (offset === undefined) throw new Error('Missing field ' + type + '.' + name);
    return start + offset;
}
const vector = (offset, length = 3) => Array.from({length}, (_, i) => b.readFloatLE(offset + i * 4));
const nameOf = block => string(field(block.at, 'ID', 'name[258]'), 258).slice(2);
const int = offset => b.readInt32LE(offset);
const short = offset => b.readInt16LE(offset);
const byCode = code => blocks.filter(block => block.code === code);
const wall = byCode('OB').find(block => nameOf(block) === 'CGPD_003_WALL');
if (!wall) throw new Error('Pool structural mesh was not found');
const mesh = pointed(field(wall.at, 'Object', '*data'));
const roomOrigin = vector(field(wall.at, 'Object', 'loc[3]'));
const offsetArray = pointed(field(mesh.at, 'Mesh', '*poly_offset_indices')).at;
const storage = field(mesh.at, 'Mesh', 'attribute_storage');
const attributes = pointed(field(storage, 'AttributeStorage', '*dna_attributes'));
const readAttributes = new Map();
for (let i = 0; i < attributes.count; i++) {
    const attr = attributes.at + i * schema.get('Attribute').size;
    const name = pointed(field(attr, 'Attribute', '*name'));
    const array = pointed(field(attr, 'Attribute', '*data'));
    const raw = pointed(field(array.at, 'AttributeArray', '*data'));
    readAttributes.set(string(name.at, name.size), raw.at);
}
const positions = readAttributes.get('position');
const cornerVertices = readAttributes.get('.corner_vert');
const faceMaterial = readAttributes.get('material_index');
const tileUv = readAttributes.get('Aligned Pool Tiles');
const blendToGltf = ([x, y, z]) => [x, z, -y];
const pointAt = corner => blendToGltf(vector(positions + 12 * int(cornerVertices + corner * 4)));
const cross = (u, v) => [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
const subtract = (a, c) => a.map((value, i) => value - c[i]);
const normalize = v => {
    const length = Math.hypot(...v);
    return length < 1e-10 ? null : v.map(value => value / length);
};

const groups = new Map();
function triangle(material, points, uvs) {
    const normal = normalize(cross(subtract(points[1], points[0]), subtract(points[2], points[0])));
    if (!normal) return;
    if (!groups.has(material)) groups.set(material, {p: [], n: [], uv: []});
    const group = groups.get(material);
    for (let i = 0; i < 3; i++) {
        group.p.push(...points[i]);
        group.n.push(...normal);
        group.uv.push(...uvs[i]);
    }
}

function triangulate(points) {
    if (points.length === 3) return [[0, 1, 2]];
    // Project the face onto its dominant plane so concave pool walls and floors triangulate correctly.
    let normal = [0, 0, 0];
    for (let i = 0; i < points.length; i++) {
        const a = cross(points[i], points[(i + 1) % points.length]);
        normal = normal.map((value, axis) => value + a[axis]);
    }
    const dominant = [0, 1, 2].reduce((a, c) => Math.abs(normal[c]) > Math.abs(normal[a]) ? c : a);
    const projected = points.map(vertex => [vertex[(dominant + 1) % 3], vertex[(dominant + 2) % 3]]);
    const winding = Math.sign(normal[dominant]);
    const turn = (a, c, d) => ((c[0] - a[0]) * (d[1] - a[1]) - (c[1] - a[1]) * (d[0] - a[0])) * winding;
    const ring = points.map((_, i) => i), triangles = [];
    while (ring.length > 3) {
        const ear = ring.findIndex((middle, i) => {
            const a = ring[(i + ring.length - 1) % ring.length], c = ring[(i + 1) % ring.length];
            const p = projected[a], q = projected[middle], r = projected[c];
            return turn(p, q, r) > 1e-10 && !ring.some(index => {
                if (index === a || index === middle || index === c) return false;
                const point = projected[index];
                return turn(p, q, point) > 1e-10 && turn(q, r, point) > 1e-10 && turn(r, p, point) > 1e-10;
            });
        });
        if (ear < 0) break;
        triangles.push([ring[(ear + ring.length - 1) % ring.length], ring[ear], ring[(ear + 1) % ring.length]]);
        ring.splice(ear, 1);
    }
    for (let i = 1; i + 1 < ring.length; i++) triangles.push([ring[0], ring[i], ring[i + 1]]);
    return triangles;
}

const tiled = new Set([0, 28, 29, 30]);
for (let face = 0; face < int(field(mesh.at, 'Mesh', 'totpoly')); face++) {
    const material = int(faceMaterial + face * 4);
    // Volumetric fog and water cages are Blender effects, not visible surfaces.
    if (material === 26 || material === 31) continue;
    const start = int(offsetArray + face * 4), end = int(offsetArray + (face + 1) * 4);
    if (end - start < 3) continue;
    const corners = Array.from({length: end - start}, (_, i) => start + i);
    const points = corners.map(pointAt);
    const uv = corners.map(corner => tiled.has(material) ? vector(tileUv + corner * 8, 2) : [0, 0]);
    for (const tri of triangulate(points)) {
        triangle(material, tri.map(i => points[i]), tri.map(i => [uv[i][0], 1 - uv[i][1]]));
    }
}

function addRail(points, radius) {
    if (points.length < 2) return;
    const rings = points.map((p, i) => {
        const forward = normalize(subtract(points[Math.min(points.length - 1, i + 1)], points[Math.max(0, i - 1)]));
        if (!forward) return null;
        const side = normalize(cross(forward, Math.abs(forward[1]) > 0.95 ? [1, 0, 0] : [0, 1, 0]));
        const up = cross(side, forward);
        return Array.from({length: 10}, (_, k) => {
            const angle = k * Math.PI / 5;
            return p.map((value, axis) => value + radius * (side[axis] * Math.cos(angle) + up[axis] * Math.sin(angle)));
        });
    });
    for (let i = 0; i + 1 < rings.length; i++) {
        if (!rings[i] || !rings[i + 1]) continue;
        for (let side = 0; side < 10; side++) {
            const next = (side + 1) % 10;
            triangle(25, [rings[i][side], rings[i + 1][side], rings[i + 1][next]], [[0, 0], [0, 0], [0, 0]]);
            triangle(25, [rings[i][side], rings[i + 1][next], rings[i][next]], [[0, 0], [0, 0], [0, 0]]);
        }
    }
}
for (const object of byCode('OB').filter(block => /^CGPD_003_(CHROME_RAIL|STAIR_)/.test(nameOf(block)))) {
    const curve = pointed(field(object.at, 'Object', '*data'));
    const radius = b.readFloatLE(field(curve.at, 'Curve', 'ext2'));
    const location = subtract(vector(field(object.at, 'Object', 'loc[3]')), roomOrigin);
    const scale = vector(field(object.at, 'Object', 'size[3]'));
    let spline = pointed(field(curve.at, 'Curve', 'nurb'));
    while (spline) {
        const points = pointed(field(spline.at, 'Nurb', '*bp'));
        const length = int(field(spline.at, 'Nurb', 'pntsu'));
        if (points && radius > 0) {
            const coordinates = Array.from({length}, (_, i) =>
                blendToGltf(vector(points.at + i * schema.get('BPoint').size).map((x, axis) => x * scale[axis] + location[axis])));
            addRail(coordinates, radius);
        }
        spline = pointed(field(spline.at, 'Nurb', '*next'));
    }
}

const waterCenter = blendToGltf([16, 1.3, -0.30]);
for (let i = 0; i < 96; i++) {
    const rim = k => {
        const angle = 2 * Math.PI * k / 96;
        return blendToGltf([16 + 5.36 * Math.cos(angle), 1.3 + 3.07 * Math.sin(angle), -0.30]);
    };
    triangle(26, [waterCenter, rim(i), rim(i + 1)], [[0, 0], [0, 0], [0, 0]]);
}

const doc = {
    asset: {version: '2.0', generator: 'MaterialLightingSimulator pool scene converter'},
    scene: 0, scenes: [{nodes: [0]}], nodes: [{mesh: 0, name: 'Pool Level 1'}],
    meshes: [{primitives: []}], materials: [], accessors: [], bufferViews: [], buffers: []
};
const chunks = [];
let offset = 0;
function addBuffer(buffer, usage) {
    const id = doc.bufferViews.length;
    doc.bufferViews.push({buffer: 0, byteOffset: offset, byteLength: buffer.length, target: usage});
    chunks.push(buffer);
    const padding = (4 - buffer.length % 4) % 4;
    if (padding) chunks.push(Buffer.alloc(padding));
    offset += buffer.length + padding;
    return id;
}
function accessor(values, width, bounds = false) {
    const data = Buffer.alloc(values.length * 4);
    const min = Array(width).fill(Infinity), max = Array(width).fill(-Infinity);
    values.forEach((value, i) => {
        const f = Math.fround(value);
        data.writeFloatLE(f, i * 4);
        if (bounds) {
            min[i % width] = Math.min(min[i % width], f);
            max[i % width] = Math.max(max[i % width], f);
        }
    });
    const index = doc.accessors.length;
    doc.accessors.push({
        bufferView: addBuffer(data, 34962), componentType: 5126, count: values.length / width,
        type: 'VEC' + width, ...(bounds ? {min, max} : {})
    });
    return index;
}

const tileImage = byCode('IM').find(block => nameOf(block) === 'pool_tiles_albedo.png');
if (tileImage) {
    const item = pointed(field(tileImage.at, 'Image', 'packedfiles'));
    const pack = pointed(field(item.at, 'ImagePackedFile', '*packedfile'));
    const bytes = pointed(field(pack.at, 'PackedFile', '*data'));
    doc.images = [{bufferView: addBuffer(b.subarray(bytes.at, bytes.at + bytes.size)), mimeType: 'image/png'}];
    doc.textures = [{source: 0}];
}
const slots = pointed(field(mesh.at, 'Mesh', '**mat'));
for (const [index, group] of groups) {
    const sourceMaterial = pointed(slots.at + index * 8);
    const name = nameOf(sourceMaterial);
    const color = vector(field(sourceMaterial.at, 'Material', 'r'), 4);
    const material = doc.materials.length;
    doc.materials.push({
        name, pbrMetallicRoughness: {
            baseColorFactor: color,
            ...(doc.textures && tiled.has(index) ? {baseColorTexture: {index: 0}} : {})
        }
    });
    doc.meshes[0].primitives.push({
        attributes: {POSITION: accessor(group.p, 3, true), NORMAL: accessor(group.n, 3), TEXCOORD_0: accessor(group.uv, 2)},
        material
    });
}
doc.buffers.push({byteLength: offset});
const json = Buffer.from(JSON.stringify(doc));
const padded = Buffer.alloc((json.length + 3) & ~3, 32);
json.copy(padded);
const header = Buffer.alloc(20);
header.write('glTF');
header.writeUInt32LE(2, 4);
header.writeUInt32LE(28 + padded.length + offset, 8);
header.writeUInt32LE(padded.length, 12);
header.write('JSON', 16);
const bin = Buffer.alloc(8);
bin.writeUInt32LE(offset);
bin.write('BIN', 4);
fs.mkdirSync(path.dirname(target), {recursive: true});
fs.writeFileSync(target, Buffer.concat([header, padded, bin, ...chunks]));
console.log(`${doc.materials.length} materials, ${(fs.statSync(target).size / 1024 / 1024).toFixed(1)} MiB`);
