const fs = require('node:fs');

function writeScene(target, name, groups) {
    const doc = {
        asset: {version: '2.0', generator: 'MaterialLightingSimulator scenery converter'},
        scene: 0, scenes: [{nodes: [0]}], nodes: [{mesh: 0, name}],
        meshes: [{primitives: []}], materials: [], accessors: [], bufferViews: [],
        buffers: [], images: [], textures: []
    };
    const chunks = [];
    const cachedTextures = new Map();
    let offset = 0;

    function bufferView(buffer, target) {
        const index = doc.bufferViews.length;
        doc.bufferViews.push({buffer: 0, byteOffset: offset, byteLength: buffer.length, target});
        chunks.push(buffer);
        const padding = (4 - buffer.length % 4) % 4;
        if (padding) chunks.push(Buffer.alloc(padding));
        offset += buffer.length + padding;
        return index;
    }

    function accessor(values, width, bounds = false) {
        const indices = width === 1;
        const data = Buffer.alloc(values.length * 4);
        const min = Array(width).fill(Infinity);
        const max = Array(width).fill(-Infinity);
        values.forEach((value, i) => {
            if (indices) {
                data.writeUInt32LE(value, i * 4);
            } else {
                const rounded = Math.fround(value);
                data.writeFloatLE(rounded, i * 4);
                if (bounds) {
                    min[i % width] = Math.min(min[i % width], rounded);
                    max[i % width] = Math.max(max[i % width], rounded);
                }
            }
        });
        const index = doc.accessors.length;
        doc.accessors.push({
            bufferView: bufferView(data, indices ? 34963 : 34962),
            componentType: indices ? 5125 : 5126,
            count: values.length / width,
            type: indices ? 'SCALAR' : 'VEC' + width,
            ...(bounds ? {min, max} : {})
        });
        return index;
    }

    function texture(image) {
        if (cachedTextures.has(image.bytes)) return cachedTextures.get(image.bytes);
        const index = doc.images.length;
        doc.images.push({bufferView: bufferView(image.bytes), mimeType: image.mime});
        doc.textures.push({source: index});
        const reference = {index};
        cachedTextures.set(image.bytes, reference);
        return reference;
    }

    for (const [name, group] of groups) {
        if (!group.p.length || (group.indices && !group.indices.length)) continue;
        const material = doc.materials.length;
        const pbrMetallicRoughness = {baseColorFactor: group.color, metallicFactor: 0};
        if (group.texture) pbrMetallicRoughness.baseColorTexture = texture(group.texture);
        doc.materials.push({
            name, pbrMetallicRoughness,
            ...(group.normal ? {normalTexture: texture(group.normal)} : {}),
            ...(group.color[3] < 1 ? {alphaMode: 'BLEND'} : {})
        });
        const primitive = {material, attributes: {
            POSITION: accessor(group.p, 3, true),
            NORMAL: accessor(group.n, 3),
            TEXCOORD_0: accessor(group.uv ?? new Float32Array(group.p.length / 3 * 2), 2)
        }};
        if (group.indices) primitive.indices = accessor(group.indices, 1);
        doc.meshes[0].primitives.push(primitive);
    }

    if (!doc.materials.length) throw new Error('No renderable geometry found in ' + name);
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
    fs.writeFileSync(target, Buffer.concat([header, padded, bin, ...chunks]));
    console.log(`${name}: ${doc.materials.length} materials, ${doc.images.length} textures, ${(fs.statSync(target).size / 2 ** 20).toFixed(1)} MiB`);
}

module.exports = {writeScene};
