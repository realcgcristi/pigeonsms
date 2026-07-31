import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const typeMatches = (value, type) => {
  if (type === 'null') return value === null;
  if (type === 'array') return Array.isArray(value);
  if (type === 'object') return typeof value === 'object' && value !== null && !Array.isArray(value);
  if (type === 'integer') return Number.isInteger(value);
  return typeof value === type;
};

const resolvePointer = (root, pointer) => pointer
  .slice(2)
  .split('/')
  .reduce((value, part) => value?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], root);

export async function loadSchema(path, cache = new Map()) {
  const absolute = resolve(path);
  if (cache.has(absolute)) return cache.get(absolute);
  const schema = JSON.parse(await readFile(absolute, 'utf8'));
  const loaded = { schema, path: absolute, cache };
  cache.set(absolute, loaded);
  return loaded;
}

export async function validate(value, loaded, path = '$', root = loaded.schema) {
  const errors = [];
  const schema = loaded.schema;
  if (schema.$ref) {
    if (schema.$ref.startsWith('#/')) {
      const target = resolvePointer(root, schema.$ref);
      if (!target) return [`${path}: unresolved reference ${schema.$ref}`];
      return validate(value, { ...loaded, schema: target }, path, root);
    }
    const [file, pointer] = schema.$ref.split('#');
    const external = await loadSchema(resolve(dirname(loaded.path), file), loaded.cache);
    const target = pointer ? resolvePointer(external.schema, `#${pointer}`) : external.schema;
    return validate(value, { ...external, schema: target }, path, external.schema);
  }
  if (schema.const !== undefined && value !== schema.const) errors.push(`${path}: must equal ${JSON.stringify(schema.const)}`);
  if (schema.enum && !schema.enum.some((entry) => Object.is(entry, value))) errors.push(`${path}: is not an allowed value`);
  if (schema.oneOf) {
    const matches = await Promise.all(schema.oneOf.map((entry) => validate(value, { ...loaded, schema: entry }, path, root)));
    if (matches.filter((entry) => entry.length === 0).length !== 1) errors.push(`${path}: must match exactly one variant`);
    return errors;
  }
  if (schema.allOf) {
    for (const entry of schema.allOf) errors.push(...await validate(value, { ...loaded, schema: entry }, path, root));
  }
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((type) => typeMatches(value, type))) {
      errors.push(`${path}: expected ${types.join(' or ')}`);
      return errors;
    }
  }
  if (typeof value === 'string') {
    if (schema.minLength !== undefined && value.length < schema.minLength) errors.push(`${path}: shorter than ${schema.minLength}`);
    if (schema.maxLength !== undefined && value.length > schema.maxLength) errors.push(`${path}: longer than ${schema.maxLength}`);
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) errors.push(`${path}: does not match ${schema.pattern}`);
    if (schema.format === 'uri') {
      try { new URL(value); } catch { errors.push(`${path}: is not a URI`); }
    }
  }
  if (typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) errors.push(`${path}: below ${schema.minimum}`);
    if (schema.maximum !== undefined && value > schema.maximum) errors.push(`${path}: above ${schema.maximum}`);
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) errors.push(`${path}: too few items`);
    if (schema.maxItems !== undefined && value.length > schema.maxItems) errors.push(`${path}: too many items`);
    if (schema.uniqueItems && new Set(value.map(JSON.stringify)).size !== value.length) errors.push(`${path}: items must be unique`);
    if (schema.items) {
      for (let index = 0; index < value.length; index += 1) {
        errors.push(...await validate(value[index], { ...loaded, schema: schema.items }, `${path}[${index}]`, root));
      }
    }
  }
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    for (const key of schema.required ?? []) {
      if (!(key in value)) errors.push(`${path}.${key}: is required`);
    }
    for (const [key, child] of Object.entries(schema.properties ?? {})) {
      if (key in value) errors.push(...await validate(value[key], { ...loaded, schema: child }, `${path}.${key}`, root));
    }
  }
  return errors;
}

export async function validateFixture(fixturePath) {
  const fixture = JSON.parse(await readFile(fixturePath, 'utf8'));
  const reference = fixture.$schema;
  if (!reference) throw new Error(`${fixturePath}: missing $schema`);
  delete fixture.$schema;
  const schema = await loadSchema(resolve(dirname(fixturePath), reference));
  return validate(fixture, schema);
}
