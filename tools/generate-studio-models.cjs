const fs = require('node:fs');
const path = require('node:path');

const output = path.join(__dirname, '../src/main/resources/models');
const fullTurn = 2 * Math.PI;

class Geometry {
    positions = [];
    normals = [];
    indices = [];

    // Profiles are traced from the bottom outside edge toward the top.
    // A vessel can continue back down its inside wall to make a hollow bowl.
    lathe(profile, {
        center = [0, 0, 0], scale = [1, 1, 1], tilt = 0,
        segments = 64, start = 0, sweep = fullTurn, ripple = 0
    } = {}) {
        const base = this.positions.length / 3;
        const cosTilt = Math.cos(tilt), sinTilt = Math.sin(tilt);
        const columns = segments + 1;
        for (let row = 0; row < profile.length; row++) {
            const [radius, height, shift = 0] = profile[row];
            const [beforeR, beforeY, beforeX = 0] = profile[Math.max(0, row - 1)];
            const [afterR, afterY, afterX = 0] = profile[Math.min(profile.length - 1, row + 1)];
            for (let col = 0; col < columns; col++) {
                const angle = start + sweep * col / segments;
                const c = Math.cos(angle), s = Math.sin(angle);
                const modulation = 1 + ripple * (Math.cos(angle * 9) + 0.35 * Math.sin(angle * 17));
                const radial = radius * modulation;
                const derivative = radius * ripple * (-9 * Math.sin(angle * 9) + 5.95 * Math.cos(angle * 17));
                const dx = derivative * c - radial * s, dz = derivative * s + radial * c;
                const dr = (afterR - beforeR) * modulation, dy = afterY - beforeY;
                const tx = dr * c + afterX - beforeX, tz = dr * s;
                const x = (radial * c + shift) * scale[0], y = height * scale[1];
                const nz = (radius > 0 ? -dy * dx : dy * s) / scale[2];
                const nx = (radius > 0 ? dy * dz : dy * c) / scale[0];
                const ny = (radius > 0 ? tz * dx - tx * dz : -dr) / scale[1];
                const length = Math.hypot(nx, ny, nz) || 1;
                this.positions.push(center[0] + x * cosTilt - y * sinTilt,
                    center[1] + x * sinTilt + y * cosTilt, center[2] + radial * s * scale[2]);
                this.normals.push((nx * cosTilt - ny * sinTilt) / length,
                    (nx * sinTilt + ny * cosTilt) / length, nz / length);

                if (row + 1 === profile.length || col === segments) continue;
                const a = base + row * columns + col, b = a + columns;
                if (profile[row + 1][0] > 0) this.indices.push(a, b, b + 1);
                if (radius > 0) this.indices.push(a, b + 1, a + 1);
            }
        }
        return this;
    }

    oval(center, scale, tilt = 0, roundness = 1) {
        const profile = Array.from({length: 25}, (_, i) => {
            const angle = Math.PI * i / 24;
            const y = -Math.cos(angle);
            return [i === 0 || i === 24 ? 0 : Math.sin(angle) ** roundness,
                Math.sign(y) * Math.abs(y) ** roundness];
        });
        return this.lathe(profile, {center, scale, tilt, segments: 40});
    }

    wire(points, radius, sides = 8, normalAxis = null) {
        const base = this.positions.length / 3;
        const normalize = v => {
            const length = Math.hypot(...v);
            return v.map(value => value / length);
        };
        const cross = (a, b) => [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        ];
        for (let i = 0; i < points.length; i++) {
            const before = points[Math.max(0, i - 1)];
            const after = points[Math.min(points.length - 1, i + 1)];
            const tangent = normalize(after.map((value, axis) => value - before[axis]));
            const reference = normalAxis ?? (Math.abs(tangent[2]) > .9 ? [0, 1, 0] : [0, 0, 1]);
            const side = normalize(cross(tangent, reference));
            const up = cross(side, tangent);
            for (let j = 0; j < sides; j++) {
                const angle = fullTurn * j / sides;
                const normal = side.map((value, axis) => value * Math.cos(angle) + up[axis] * Math.sin(angle));
                this.positions.push(...points[i].map((value, axis) => value + radius * normal[axis]));
                this.normals.push(...normal);
                if (i + 1 < points.length) {
                    const a = base + i * sides + j;
                    const next = base + i * sides + (j + 1) % sides;
                    this.indices.push(a, a + sides, next + sides, a, next + sides, next);
                }
            }
        }
        return this;
    }

    helmetPlate(surface, rows = 40, segments = 128) {
        const base = this.positions.length / 3;
        const columns = segments + 1;
        for (let row = 0; row <= rows; row++) {
            const height = row / rows;
            for (let column = 0; column <= segments; column++) {
                const angle = -Math.PI + fullTurn * column / segments;
                const position = surface(height, angle);
                const below = surface(Math.max(0, height - .001), angle);
                const above = surface(Math.min(1, height + .001), angle);
                const before = surface(height, angle - .001);
                const after = surface(height, angle + .001);
                const [x, y, z] = above.map((value, axis) => value - below[axis]);
                const [a, b, c] = after.map((value, axis) => value - before[axis]);
                const normal = [y * c - z * b, z * a - x * c, x * b - y * a];
                const length = Math.hypot(...normal) || 1;
                this.positions.push(...position);
                this.normals.push(...normal.map(value => value / length));
                if (row < rows && column < segments) {
                    const start = base + row * columns + column, next = start + columns;
                    this.indices.push(start, next, next + 1, start, next + 1, start + 1);
                }
            }
        }
        return this;
    }

    write(name) {
        const pointCount = this.positions.length / 3;
        const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
        this.positions.forEach((value, i) => {
            min[i % 3] = Math.min(min[i % 3], Math.fround(value));
            max[i % 3] = Math.max(max[i % 3], Math.fround(value));
        });
        const positionBytes = pointCount * 12, normalBytes = positionBytes;
        const indexBytes = this.indices.length * 2;
        const align = size => (size + 3) & ~3;
        if (pointCount > 65536) throw new Error(`Too many vertices in ${name}`);
        const bin = Buffer.alloc(align(positionBytes + normalBytes + indexBytes));
        this.positions.forEach((value, i) => bin.writeFloatLE(value, 4 * i));
        this.normals.forEach((value, i) => bin.writeFloatLE(value, positionBytes + 4 * i));
        this.indices.forEach((value, i) => bin.writeUInt16LE(value, positionBytes + normalBytes + 2 * i));
        const document = {
            asset: {version: '2.0', generator: 'MaterialLightingSimulator'},
            scene: 0, scenes: [{nodes: [0]}], nodes: [{mesh: 0, name}],
            meshes: [{primitives: [{attributes: {POSITION: 0, NORMAL: 1}, indices: 2}]}],
            buffers: [{byteLength: bin.length}],
            bufferViews: [
                {buffer: 0, byteOffset: 0, byteLength: positionBytes, target: 34962},
                {buffer: 0, byteOffset: positionBytes, byteLength: normalBytes, target: 34962},
                {buffer: 0, byteOffset: positionBytes + normalBytes, byteLength: indexBytes, target: 34963}
            ],
            accessors: [
                {bufferView: 0, componentType: 5126, count: pointCount, type: 'VEC3', min, max},
                {bufferView: 1, componentType: 5126, count: pointCount, type: 'VEC3'},
                {bufferView: 2, componentType: 5123, count: this.indices.length, type: 'SCALAR'}
            ]
        };
        const encoded = Buffer.from(JSON.stringify(document));
        const json = Buffer.alloc(align(encoded.length), 0x20);
        encoded.copy(json);
        const header = Buffer.alloc(20);
        header.write('glTF');
        header.writeUInt32LE(2, 4);
        header.writeUInt32LE(header.length + json.length + 8 + bin.length, 8);
        header.writeUInt32LE(json.length, 12);
        header.write('JSON', 16);
        const binaryHeader = Buffer.alloc(8);
        binaryHeader.writeUInt32LE(bin.length);
        binaryHeader.write('BIN', 4);
        fs.writeFileSync(path.join(output, `${name}.glb`), Buffer.concat([header, json, binaryHeader, bin]));
    }
}

fs.mkdirSync(output, {recursive: true});

new Geometry().lathe([
    [0,.01],[.29,.01],[.32,.012],[.345,.025],[.35,.045],[.348,.06],
    [.335,.073],[.31,.076],[.303,.085],[.285,.092],[.25,.106],
    [.207,.122],[.166,.145],[.13,.17],[.105,.20],[.084,.233],
    [.075,.255],[.076,.28],[.086,.29],[.111,.30],[.12,.319],
    [.112,.338],[.083,.35],[.071,.37],[.069,.412],[.077,.446],
    [.095,.462],[.113,.472],[.12,.49],[.109,.506],[.133,.522],
    [.177,.543],[.225,.575],[.263,.612],[.297,.66],[.326,.711],
    [.35,.767],[.37,.831],[.389,.892],[.397,.911],[.411,.92],
    [.416,.935],[.412,.952],[.4,.96],[.385,.956],[.374,.944],
    [.371,.929],[.363,.903],[.344,.846],[.322,.788],[.297,.734],
    [.266,.688],[.232,.647],[.194,.616],[.153,.594],[.11,.579],
    [.066,.568],[.025,.564],[0,.564]
], {segments: 96}).write('chalice');

new Geometry().lathe([[0,0],[.22,0],[.4,.03],[.59,.14],[.72,.31],[.76,.5],
    [.75,.67],[.67,.80],[.53,.91],[.35,1],[.33,1.03],[.33,1.14],[.27,1.17],[0,1.17]])
    .write('bulb-glass');
// Separate socket geometry keeps the metal collar dark when the glass is lit.
const screwThread = Array.from({length: 241}, (_, index) => {
    const fraction = index / 240;
    const angle = fraction * 3.5 * fullTurn;
    return [.72 * Math.cos(angle), .064 + fraction * .45, .72 * Math.sin(angle)];
});
new Geometry().lathe([[0,0],[.63,0],[.704,.024],[.72,.055],[.72,.52],
    [.69,.55],[.61,.58],[.61,.69],[.42,.76],[0,.76]])
    .wire(screwThread, .037, 10, [0,1,0]).write('bulb-socket');

const filament = Array.from({length: 193}, (_, index) => {
    const fraction = index / 192;
    const angle = fraction * 12 * fullTurn;
    return [(fraction - .5) * .126, .009 * Math.cos(angle), .009 * Math.sin(angle)];
});
new Geometry().wire(filament, .0018).write('bulb-filament');

const bulbSupports = new Geometry();
for (const side of [-1, 1]) {
    bulbSupports.wire([[side * .014,.142,0], [side * .018,.082,0],
        [side * .060,.038,0], [side * .063,.010,0]], .0028);
}
bulbSupports.lathe([[0,.075],[.013,.076],[.021,.093],[.019,.127],[.014,.148],[0,.15]])
    .write('bulb-supports');

new Geometry().oval([0,0,0],[1,1,1]).write('steel-sphere');

const helmet = new Geometry();
const front = angle => Math.max(0, Math.cos(angle));
const brow = angle => .94 + .17 * Math.sin(Math.min(Math.abs(angle), 1.6)) - .035 * (1 - Math.cos(angle));
const ridge = (angle, width) => Math.exp(-Math.abs(Math.atan2(Math.sin(angle), Math.cos(angle))) / width);
const chin = angle => .31 - .29 * front(angle) ** 4 + .05 * Math.max(0, -Math.cos(angle));
const jaw = (t, angle) => {
    const lowerFlare = .07 * (1 - t) ** 3;
    const radius = .60 + lowerFlare + .04 * t - .045 * Math.sin(Math.PI * t);
    const width = .65 + lowerFlare - .05 * Math.sin(Math.PI * t) + .04 * t;
    const centerRidge = .105 * ridge(angle, .19) * (.7 + .3 * Math.sin(Math.PI * t));
    return [radius * Math.cos(angle) + centerRidge,
        chin(angle) + t * (brow(angle) - chin(angle)), width * Math.sin(angle)];
};
const dome = (t, angle) => {
    const start = brow(angle) + .075 * front(angle) ** .55 - .018 * (1 - front(angle));
    const arc = Math.cos(t * Math.PI * .497);
    const crest = .115 * ridge(angle, .16) * Math.sin(Math.PI * t) ** .7;
    const peak = Math.sin(t * Math.PI / 2);
    return [(.64 * arc) * Math.cos(angle) + crest,
        start + (1.91 - start) * peak + .16 * peak ** 8 * ridge(angle, .25) * (1 - t ** 18),
        (.7 * arc) * Math.sin(angle)];
};
const rim = (surface, t, radius) => helmet.wire(
    Array.from({length: 193}, (_, i) => surface(t, -Math.PI + fullTurn * i / 192)),
    radius, 8, [0,1,0]);

helmet.helmetPlate(jaw).helmetPlate(dome);
// A recessed inner plate gives the opening depth without exposing the rear room wall.
helmet.helmetPlate((t, angle) => [
    .48 * Math.cos(angle),
    .86 + .35 * t,
    .50 * Math.sin(angle)
], 5);
rim(jaw, .01, .014);
rim(jaw, .99, .011);
rim(dome, .012, .012);

for (const side of [-1, 1]) {
    const forehead = Array.from({length: 65}, (_, i) => {
        const fraction = i / 64;
        const point = dome(.47 * (1 - fraction) ** 1.7 + .17, side * fraction * 1.65);
        return [point[0] * 1.008, point[1] + .004, point[2] * 1.01];
    });
    const cheek = Array.from({length: 55}, (_, i) => {
        const t = i / 54;
        const point = jaw(t * .88 + .04, side * (.32 + .90 * t ** 1.8));
        return [point[0] * 1.012, point[1], point[2] * 1.015];
    });
    helmet.wire(forehead, .009).wire(cheek, .009);
    helmet.oval([.02,1.105,side * .695],[.076,.079,.025]);
    helmet.oval([.02,1.105,side * .719],[.037,.037,.013]);
}
helmet.write('medieval-helmet');

const doll = new Geometry();
doll.oval([0,.96,0],[.34,.49,.20]).oval([0,1.58,.01],[.30,.32,.25]);
for (const side of [-1,1]) {
    doll.oval([side*.44,.93,0],[.13,.33,.13],side*.72);
    doll.oval([side*.65,.69,0],[.12,.14,.13]);
    doll.oval([side*.19,.36,0],[.15,.31,.15],side*.15);
    doll.oval([side*.22,.12,.10],[.17,.12,.23]);
    doll.oval([side*.10,1.62,.235],[.041,.041,.029]);
}
doll.oval([0,1.53,.235],[.067,.028,.027]).write('rag-doll');

new Geometry().oval([0,.24,0],[.92,.23,.66],0,.64)
    .lathe([[.91,.225],[.945,.233],[.95,.245],[.935,.254],[.91,.255]],
        {scale:[1,1,.73],ripple:.015}).write('pillow');

const book = new Geometry();
book.oval([0,.22,0],[.72,.20,.92],0,.16)
    .oval([0,.04,0],[.78,.045,.97],0,.16)
    .oval([0,.43,0],[.78,.047,.97],0,.16)
    .oval([-.73,.24,0],[.09,.229,.96],0,.20);
for (const y of [.11,.15,.19,.23,.27,.31,.35,.39]) {
    book.oval([.721,y,0],[.01,.008,.86],0,.24);
}
book.write('cloth-bound-book');

const knight = new Geometry();
knight.lathe([[0,0],[.51,0],[.56,.023],[.569,.053],[.568,.083],[.554,.105],
    [.532,.118],[.531,.134],[.545,.148],[.543,.171],[.516,.187],[.499,.209],
    [.501,.225],[.486,.246],[.472,.270],[.461,.312],[.444,.375],[.428,.426],
    [.419,.44],[.434,.453],[.435,.475],[.422,.488],[.423,.507],[.432,.519],
    [.421,.541],[.388,.556],[0,.556]]);

// The back of the neck stays full while its front curves inward under the jaw.
knight.lathe([[.366,.516,-.070],[.384,.566,-.072],[.393,.634,-.076],
    [.381,.714,-.096],[.356,.800,-.130],[.324,.891,-.167],[.279,.980,-.216],
    [.250,1.07,-.239],[.239,1.16,-.244],[.239,1.25,-.240],[.236,1.35,-.226],
    [.237,1.46,-.196],[.229,1.57,-.157],[.210,1.67,-.113],[.168,1.75,-.087],
    [.103,1.81,-.084],[0,1.836,-.092]], {scale:[1,1,.76]});

// A continuous shifted profile forms the hanging muzzle, cheek and forehead.
knight.lathe([[0,1.02,.48],[.075,1.035,.475],[.132,1.062,.476],[.159,1.096,.468],
    [.169,1.14,.434],[.170,1.19,.395],[.187,1.25,.352],[.211,1.32,.291],
    [.226,1.40,.245],[.235,1.48,.203],[.220,1.56,.166],[.194,1.63,.132],
    [.162,1.70,.106],[.125,1.75,.083],[.069,1.79,.045],[0,1.815,.020]],
    {scale:[1,1,.79]});

for (const side of [-1, 1]) {
    knight.lathe([[.039,0],[.068,.05],[.057,.113],[.036,.183],[.018,.224],[0,.25]],
        {center:[.045,1.733,side*.082],scale:[1,1,.58],tilt:-.49,segments:32});
    knight.oval([.228,1.507,side*.173],[.058,.030,.018],-.09);
    knight.oval([.504,1.105,side*.109],[.038,.020,.018],-.39);

    const relief = (points, radius) => knight.wire(
        points.map(([x,y,z]) => [x,y,z*side]), radius, 10);
    relief([[.198,1.670,.124],[.235,1.594,.161],[.275,1.520,.177],
        [.306,1.440,.185],[.359,1.364,.170],[.417,1.269,.149]], .012);
    relief([[.492,1.264,.094],[.464,1.216,.148],[.417,1.205,.148],
        [.366,1.245,.148],[.359,1.364,.170],[.442,1.341,.130]], .010);
    relief([[.236,1.595,.160],[.122,1.600,.172],[.027,1.551,.154],
        [.017,1.462,.158],[.061,1.387,.172]], .011);
    relief([[.312,1.521,.154],[.264,1.554,.175],[.213,1.555,.176],[.167,1.534,.172]], .007);
    relief([[.238,1.471,.183],[.271,1.478,.182],[.298,1.504,.171]], .006);
    knight.oval([.413,1.247,side*.151],[.027,.027,.012]);
    knight.oval([.276,1.600,side*.148],[.025,.027,.01]);

    for (const [x,y,z,size] of [
        [-.214,1.700,.110,.098],[-.340,1.532,.151,.121],
        [-.407,1.335,.156,.125],[-.421,1.115,.154,.135],[-.405,.881,.155,.105]
    ]) {
        const curl = Array.from({length: 49}, (_, i) => {
            const t = i / 48, angle = .25 + 1.63 * Math.PI * t;
            const radius = size * (1 - .76 * t);
            return [x - radius * Math.cos(angle), y + radius * Math.sin(angle),
                z + .052 * Math.sin(Math.PI * t)];
        });
        relief(curl, .029);
    }
    relief([[.253,.563,.097],[.228,.637,.164],[.152,.718,.235],
        [.060,.789,.252],[-.057,.850,.249],[-.191,.934,.226],[-.306,1.039,.205]], .012);
    relief([[.205,.571,.148],[.157,.637,.209],[.064,.699,.259],
        [-.052,.754,.263],[-.171,.814,.263],[-.274,.900,.242],[-.363,.992,.211]], .009);
}
knight.write('knight-chess-piece');

new Geometry().lathe([[0,0],[.38,0],[.43,.05],[.44,.10],[.46,.16],[.55,.26],
    [.69,.42],[.72,.62],[.69,.79],[.57,.96],[.42,1.09],[.34,1.20],[.34,1.32],
    [.40,1.35],[.41,1.42],[.38,1.45],[.32,1.45],[.29,1.40],[.29,1.30],
    [.29,1.20],[.37,1.05],[.53,.90],[.61,.73],[.63,.57],[.58,.39],
    [.44,.23],[.27,.16],[0,.13]], {ripple:.018}).write('wooden-vase');

const log = new Geometry();
log.lathe([[0,0],[.29,0],[.43,0],[.48,.015],[.52,.07],[.54,.30],[.50,.52],
    [.53,.75],[.51,1.12],[.47,1.67],[.50,1.91],[.48,1.98],[.43,2],
    [.35,2],[.25,2],[.14,2],[0,2]], {tilt:Math.PI/2,ripple:.12});
log.lathe([[.17,0],[.18,.12],[.16,.33],[.135,.48],[.115,.50],[0,.50]],
    {center:[-.8,.33,.13],tilt:.55,ripple:.07}).write('log');

new Geometry().lathe([[0,0],[.44,0],[.48,.035],[.49,.08],[.50,.22],[.53,.48],
    [.57,.84],[.60,1.1],[.61,1.17],[.60,1.19],[.565,1.19],[.55,1.17],
    [.55,1.10],[.52,.84],[.48,.48],[.45,.24],[.42,.15],[.34,.12],[0,.12]],
    {ripple:.02}).write('glass-cup');

const bird = new Geometry();
bird.lathe([[0,0],[.48,0],[.51,.035],[.51,.075],[.46,.105],[0,.105]]);
bird.oval([-.10,.34,0],[.49,.28,.32],.08)
    .oval([.20,.57,0],[.18,.36,.16],-.25)
    .oval([.30,.91,0],[.16,.18,.15])
    .oval([.47,.93,0],[.15,.048,.082],.13)
    .oval([-.48,.52,0],[.26,.083,.20],-.54);
for (const z of [-.255,.255]) bird.oval([-.18,.39,z],[.34,.15,.094],-.17);
bird.write('glass-figurine');

new Geometry().lathe([[0,0],[.49,0],[.54,.025],[.56,.07],[.65,.14],[.76,.29],
    [.85,.48],[.88,.69],[.86,.88],[.79,1.03],[.68,1.17],[.62,1.25],
    [.62,1.32],[.65,1.36],[.66,1.40],[.63,1.42],[.59,1.42],[.57,1.39],
    [.57,1.27],[.61,1.18],[.72,1.04],[.79,.88],[.82,.69],[.79,.49],
    [.70,.31],[.57,.18],[.39,.12],[0,.10]]).write('fish-bowl');
