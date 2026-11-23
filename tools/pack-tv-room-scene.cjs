const fs = require('node:fs');
const readline = require('node:readline');
const {writeScene} = require('./write-room-glb.cjs');

const [, , source, target] = process.argv;
if (!source || !target) throw new Error('Usage: node tools/pack-tv-room-scene.cjs <scene.obj> <scene.glb>');

const colors = {
    plaster: [0.80, 0.76, 0.70, 1],
    floor: [0.59, 0.43, 0.30, 1],
    sofa: [0.37, 0.42, 0.45, 1],
    rug: [0.54, 0.49, 0.41, 1],
    electronics: [0.13, 0.15, 0.17, 1],
    metal: [0.44, 0.46, 0.47, 1],
    copper: [0.63, 0.39, 0.24, 1],
    wood: [0.47, 0.34, 0.25, 1],
    paper: [0.70, 0.67, 0.59, 1],
    glass: [0.48, 0.61, 0.66, 0.22],
    red: [0.59, 0.20, 0.14, 1],
    dark: [0.29, 0.28, 0.26, 1],
    light: [0.67, 0.65, 0.61, 1]
};

function surface(group, material) {
    if (/^PISO$/i.test(group)) return 'floor';
    if (/^Muro|^Techo|^Guardaescobas/i.test(group)) return 'plaster';
    if (/sofa/i.test(group)) return 'sofa';
    if (/carpet/i.test(group)) return 'rug';
    if (/televisor|beoplay|black_metal/i.test(group + ' ' + material)) return 'electronics';
    if (/^glass/i.test(group)) return 'glass';
    if (/^chrome/i.test(group)) return 'metal';
    if (/^copper/i.test(group)) return 'copper';
    if (/^Box011$|cofre|madera/i.test(group)) return 'wood';
    if (/book|pages|tela_lampara/i.test(group + ' ' + material)) return 'paper';
    if (/coca/i.test(group)) return 'red';
    return /wire_(000|020|086)/.test(material) ? 'dark' : 'light';
}

// Keep the furnished interior and exclude remote geometry outside the room.
function inside(x, y, z) {
    return x >= -1.55 && x <= 3.20 && y >= -0.18 && y <= 2.82 && z >= -3.55 && z <= 3.55;
}

const groups = new Map();
const grid = 0.009;

function addTriangle(group, vertices, a, b, c) {
    const abx = vertices[b] - vertices[a], aby = vertices[b + 1] - vertices[a + 1], abz = vertices[b + 2] - vertices[a + 2];
    const acx = vertices[c] - vertices[a], acy = vertices[c + 1] - vertices[a + 1], acz = vertices[c + 2] - vertices[a + 2];
    const normal = [aby * acz - abz * acy, abz * acx - abx * acz, abx * acy - aby * acx];
    const length = Math.hypot(...normal);
    if (length < 1e-10) return;
    const axis = Math.abs(normal[0]) > Math.abs(normal[1]) ? 0 : 1;
    const dominant = Math.abs(normal[2]) > Math.abs(normal[axis]) ? 2 : axis;
    const facing = dominant * 2 + Number(normal[dominant] < 0);
    const triangle = [a, b, c].map(offset => {
        const x = vertices[offset], y = vertices[offset + 1], z = vertices[offset + 2];
        const key = `${Math.round(x / grid)},${Math.round(y / grid)},${Math.round(z / grid)},${facing}`;
        if (!group.cells.has(key)) {
            group.cells.set(key, group.p.length / 3);
            group.p.push(x, y, z);
            group.n.push(0, 0, 0);
        }
        return group.cells.get(key);
    });
    const [first, second, third] = triangle;
    if (first === second || second === third || third === first) return;
    const sorted = first < second && first < third ? triangle : second < third ? [second, third, first] : [third, first, second];
    const key = sorted.join(',');
    if (group.faces.has(key)) return;
    group.faces.add(key);
    group.indices.push(first, second, third);
    for (const vertex of triangle) {
        for (let i = 0; i < 3; i++) group.n[3 * vertex + i] += normal[i] / length;
    }
}

async function pack() {
    const lines = readline.createInterface({input: fs.createReadStream(source), crlfDelay: Infinity});
    const vertices = [];
    let object = '', material = '', name = '';
    for await (const line of lines) {
        if (line.startsWith('v ')) {
            vertices.push(...line.slice(2).trim().split(/\s+/, 3).map(Number));
        } else if (line.startsWith('g ') || line.startsWith('o ')) {
            object = line.slice(2).trim();
            name = surface(object, material);
        } else if (line.startsWith('usemtl ')) {
            material = line.slice(7).trim();
            name = surface(object, material);
        } else if (line.startsWith('f ')) {
            const polygon = line.slice(2).trim().split(/\s+/).map(word => {
                const index = parseInt(word, 10);
                return index < 0 ? vertices.length + index * 3 : (index - 1) * 3;
            });
            if (polygon.some(i => !inside(vertices[i], vertices[i + 1], vertices[i + 2]))) continue;
            if (!groups.has(name)) groups.set(name, {
                p: [], n: [], indices: [], cells: new Map(), faces: new Set(), color: colors[name]
            });
            const group = groups.get(name);
            for (let i = 1; i < polygon.length - 1; i++) {
                addTriangle(group, vertices, polygon[0], polygon[i], polygon[i + 1]);
            }
        }
    }
    for (const group of groups.values()) {
        for (let i = 0; i < group.n.length; i += 3) {
            const length = Math.hypot(group.n[i], group.n[i + 1], group.n[i + 2]);
            if (length === 0) group.n[i + 1] = 1;
            else {
                group.n[i] /= length;
                group.n[i + 1] /= length;
                group.n[i + 2] /= length;
            }
        }
    }
    writeScene(target, 'TV Room', groups);
}

pack().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
