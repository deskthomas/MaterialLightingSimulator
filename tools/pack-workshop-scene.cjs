const fs = require('node:fs');
const {crc32, deflateSync} = require('node:zlib');
const {writeScene} = require('./write-room-glb.cjs');

const [, , source, target] = process.argv;
if (!source || !target) throw new Error('Usage: node tools/pack-workshop-scene.cjs <scene.blend> <scene.glb>');
const b = fs.readFileSync(source);
if (b.subarray(0, 9).toString() !== 'BLENDER-v') throw new Error('Expected an uncompressed 64-bit little-endian Blender 4 scene');
const string = (offset, length) => b.subarray(offset, offset + length).toString().split('\0')[0];
const blocks = [], addresses = new Map();
for (let start = 12; start + 24 <= b.length; ) {
    const block = {
        code: string(start, 4), addr: b.readBigUInt64LE(start + 8),
        size: b.readUInt32LE(start + 4),
        count: b.readUInt32LE(start + 20), at: start + 24
    };
    if (block.at + block.size > b.length) throw new Error('Invalid Blender block size');
    if (block.code === 'ENDB') break;
    blocks.push(block);
    if (block.addr) {
        if (!addresses.has(block.addr)) addresses.set(block.addr, []);
        addresses.get(block.addr).push(block);
    }
    start = block.at + block.size;
}
for (let i = 0; i < blocks.length; ) {
    const owner = blocks[i++];
    while (blocks[i]?.code === 'DATA') i++;
    owner.end = blocks[i]?.at ?? b.length;
}
function pointed(offset, owner) {
    const matches = addresses.get(b.readBigUInt64LE(offset));
    if (!matches) return undefined;
    // Blender reuses synthetic addresses for attribute storage across different datablocks.
    return matches.length === 1 ? matches[0] : matches.find(block => block.at >= owner.at && block.at < owner.end);
}
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
const nameOf = block => string(field(block.at, 'ID', 'name[66]'), 66).slice(2);
const int = offset => b.readInt32LE(offset);
const short = offset => b.readInt16LE(offset);
const byCode = code => blocks.filter(block => block.code === code);

const blendToGltf = ([x, y, z]) => [x, z, -y];
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



function meshAttributes(mesh) {
    const values = new Map();
    for (const domain of ['vdata', 'pdata', 'ldata']) {
        const storage = field(mesh.at, 'Mesh', domain);
        const layers = pointed(field(storage, 'CustomData', '*layers'), mesh);
        const count = int(field(storage, 'CustomData', 'totlayer'));
        for (let i = 0; layers && i < count; i++) {
            const entry = layers.at + i * schema.get('CustomDataLayer').size;
            const name = string(field(entry, 'CustomDataLayer', 'name[68]'), 68);
            const data = pointed(field(entry, 'CustomDataLayer', '*data'), mesh);
            if (data) values.set(name, data.at);
        }
    }
    return values;
}

function packedImage(image) {
    const list = field(image.at, 'Image', 'packedfiles');
    const entry = pointed(list, image);
    const packed = entry && pointed(field(entry.at, 'ImagePackedFile', '*packedfile'), image);
    const data = packed && pointed(field(packed.at, 'PackedFile', '*data'), image);
    if (!data) return undefined;
    const bytes = b.subarray(data.at, data.at + int(packed.at));
    if (bytes[0] === 0x89 && bytes.subarray(1, 4).toString() === 'PNG') return {bytes, mime: 'image/png'};
    if (bytes[0] === 0xff && bytes[1] === 0xd8) return {bytes, mime: 'image/jpeg'};
    if (bytes.subarray(0, 2).toString() === 'BM') {
        const png = bitmapToPng(bytes);
        if (png) return {bytes: png, mime: 'image/png'};
    }
    return undefined;
}

function bitmapToPng(bitmap) {
    if (bitmap.length < 54 || bitmap.readUInt32LE(30) !== 0) return undefined;
    const width = bitmap.readInt32LE(18), signedHeight = bitmap.readInt32LE(22);
    const height = Math.abs(signedHeight), channels = bitmap.readUInt16LE(28) / 8;
    const pixels = bitmap.readUInt32LE(10);
    const stride = (width * channels + 3) & ~3;
    if (width <= 0 || height <= 0 || ![3, 4].includes(channels) || pixels + stride * height > bitmap.length) {
        return undefined;
    }
    const rows = Buffer.alloc((1 + width * 3) * height);
    for (let y = 0; y < height; y++) {
        let source = pixels + (signedHeight > 0 ? height - 1 - y : y) * stride;
        let destination = y * (1 + width * 3) + 1;
        for (let x = 0; x < width; x++, source += channels) {
            rows[destination++] = bitmap[source + 2];
            rows[destination++] = bitmap[source + 1];
            rows[destination++] = bitmap[source];
        }
    }
    const header = Buffer.alloc(13);
    header.writeUInt32BE(width, 0);
    header.writeUInt32BE(height, 4);
    header[8] = 8;
    header[9] = 2;
    function chunk(tag, data) {
        const output = Buffer.alloc(data.length + 12);
        output.writeUInt32BE(data.length);
        output.write(tag, 4);
        data.copy(output, 8);
        output.writeUInt32BE(crc32(output.subarray(4, -4)), output.length - 4);
        return output;
    }
    return Buffer.concat([
        Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header),
        chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))
    ]);
}

const materialsByName = new Map();
for (const material of byCode('MA')) {
    const name = nameOf(material);
    const color = [...vector(field(material.at, 'Material', 'r')), 1];
    const tree = pointed(field(material.at, 'Material', '*nodetree'), material);
    const images = [];
    if (tree) {
        let node = pointed(field(tree.at, 'bNodeTree', 'nodes'), material);
        for (let i = 0; node && i < 250; i++) {
            const image = pointed(field(node.at, 'bNode', '*id'), material);
            if (image?.code === 'IM') images.push(image);
            node = pointed(field(node.at, 'bNode', '*next'), material);
        }
    }
    const base = images.find(image => /base.?colou?r|diffuse|albedo|_color/i.test(nameOf(image)));
    const normal = images.find(image => /normal/i.test(nameOf(image)));
    const texture = base && packedImage(base);
    materialsByName.set(name, {color: texture ? [1, 1, 1, 1] : color, texture, normal: normal && packedImage(normal)});
}

const product = (a, b) => a.map((_, i) => {
    const row = i % 4, column = i - row;
    return a[row] * b[column] + a[row + 4] * b[column + 1]
        + a[row + 8] * b[column + 2] + a[row + 12] * b[column + 3];
});
const worldTransforms = new Map();
function worldTransform(object) {
    if (worldTransforms.has(object.addr)) return worldTransforms.get(object.addr);
    const [x, y, z] = vector(field(object.at, 'Object', 'rot[3]'));
    const [sx, sy, sz] = vector(field(object.at, 'Object', 'size[3]'));
    const [tx, ty, tz] = vector(field(object.at, 'Object', 'loc[3]'));
    const a = Math.cos(x), d = Math.sin(x), e = Math.cos(y), f = Math.sin(y);
    const g = Math.cos(z), h = Math.sin(z);
    let matrix = [
        e * g * sx, e * h * sx, -f * sx, 0,
        (d * f * g - a * h) * sy, (d * f * h + a * g) * sy, d * e * sy, 0,
        (a * f * g + d * h) * sz, (a * f * h - d * g) * sz, a * e * sz, 0,
        tx, ty, tz, 1
    ];
    const parent = pointed(field(object.at, 'Object', '*parent'), object);
    if (parent) {
        const inverse = vector(field(object.at, 'Object', 'parentinv[4][4]'), 16);
        matrix = product(product(worldTransform(parent), inverse), matrix);
    }
    worldTransforms.set(object.addr, matrix);
    return matrix;
}

function transform(m, p) {
    return blendToGltf([0, 1, 2].map(row => m[row] * p[0] + m[row + 4] * p[1]
            + m[row + 8] * p[2] + m[row + 12]));
}

for (const object of byCode('OB')) {
    const mesh = pointed(field(object.at, 'Object', '*data'), object);
    if (mesh?.code !== 'ME' ) continue;
    const faceCount = int(field(mesh.at, 'Mesh', 'totpoly'));
    if (!faceCount) continue;
    const attributes = meshAttributes(mesh);
    const positions = attributes.get('position'), corners = attributes.get('.corner_vert');
    if (positions == null || corners == null) continue;
    const uv = attributes.get('UVMap') ?? attributes.get('UVMap.001');
    const materialIndex = attributes.get('material_index');
    const faceOffsets = pointed(field(mesh.at, 'Mesh', '*poly_offset_indices'), mesh).at;
    const slots = pointed(field(mesh.at, 'Mesh', '**mat'), mesh);
    const transformMatrix = worldTransform(object);
    const points = Array.from({length: int(field(mesh.at, 'Mesh', 'totvert'))}, (_, i) =>
        transform(transformMatrix, vector(positions + 12 * i)));
    const materials = slots ? Array.from({length: short(field(mesh.at, 'Mesh', 'totcol'))}, (_, i) => {
        const material = pointed(slots.at + 8 * i, mesh);
        return material ? nameOf(material) : 'concrete_procedural';
    }) : [];
    for (let face = 0; face < faceCount; face++) {
        const start = int(faceOffsets + face * 4), end = int(faceOffsets + (face + 1) * 4);
        if (end - start < 3) continue;
        const polygon = Array.from({length: end - start}, (_, i) => points[int(corners + 4 * (start + i))]);
        const material = materials[materialIndex == null ? 0 : int(materialIndex + 4 * face)] || 'concrete_procedural';
        const uvs = polygon.map((_, i) => {
            if (uv == null) return [0, 0];
            const offset = uv + 8 * (start + i);
            return [b.readFloatLE(offset), 1 - b.readFloatLE(offset + 4)];
        });
        for (const face of triangulate(polygon)) {
            triangle(material, face.map(i => polygon[i]), face.map(i => uvs[i]));
        }
    }
}

for (const [name, group] of groups) {
    Object.assign(group, materialsByName.get(name) || {color: [0.48, 0.45, 0.4, 1]});
}
writeScene(target, 'Auto Workshop', groups);
