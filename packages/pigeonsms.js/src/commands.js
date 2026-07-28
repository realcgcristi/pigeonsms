import { PigeonError } from './errors.js';

export const OPTION_TYPES = ['string', 'integer', 'number', 'boolean', 'user', 'channel'];
const NAME_RE = /^[a-z0-9_-]{1,32}$/;

const BOUNDED_TYPES = new Set(['string', 'integer', 'number']);

export class OptionBuilder {
  constructor(options = []) {
    this.options = [...options];
  }

  string(name, description, opts) {
    return this.#add('string', name, description, opts);
  }

  integer(name, description, opts) {
    return this.#add('integer', name, description, opts);
  }

  number(name, description, opts) {
    return this.#add('number', name, description, opts);
  }

  boolean(name, description, opts) {
    return this.#add('boolean', name, description, opts);
  }

  user(name, description, opts) {
    return this.#add('user', name, description, opts);
  }

  channel(name, description, opts) {
    return this.#add('channel', name, description, opts);
  }

  #add(type, name, description, opts) {
    this.options.push(normalizeOption({ ...opts, name, description, type }));
    return this;
  }

  toJSON() {
    return this.options.map((option) => ({ ...option }));
  }
}

export function normalizeOption(source) {
  if (!source || typeof source !== 'object') {
    throw new PigeonError('each option must be an object', { code: 'invalid_option' });
  }
  const name = String(source.name ?? '').trim().toLowerCase();
  if (!NAME_RE.test(name)) {
    throw new PigeonError(`option name ${JSON.stringify(source.name)} must match ${NAME_RE}`, {
      code: 'invalid_option',
    });
  }
  const type = String(source.type ?? 'string');
  if (!OPTION_TYPES.includes(type)) {
    throw new PigeonError(`${name}: unknown option type ${type}`, { code: 'invalid_option' });
  }
  const description = String(source.description ?? '').trim().slice(0, 200);
  if (!description) {
    throw new PigeonError(`${name}: description is required`, { code: 'invalid_option' });
  }

  const option = { name, description, type, required: source.required === true };

  const choices = normalizeChoices(source.choices, type, name);
  if (choices.length) option.choices = choices;

  for (const bound of ['min', 'max']) {
    const value = source[bound];
    if (value === undefined || value === null) continue;
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new PigeonError(`${name}: ${bound} must be a number`, { code: 'invalid_option' });
    }
    if (!BOUNDED_TYPES.has(type)) {
      throw new PigeonError(`${name}: ${bound} needs a string or numeric type`, { code: 'invalid_option' });
    }
    option[bound] = value;
  }
  if (option.min !== undefined && option.max !== undefined && option.min > option.max) {
    throw new PigeonError(`${name}: min is greater than max`, { code: 'invalid_option' });
  }
  return option;
}

function normalizeChoices(choices, type, optionName) {
  if (choices === undefined || choices === null) return [];
  if (!Array.isArray(choices)) {
    throw new PigeonError(`${optionName}: choices must be an array`, { code: 'invalid_option' });
  }
  if (choices.length && !BOUNDED_TYPES.has(type)) {
    throw new PigeonError(`${optionName}: ${type} options cannot have choices`, { code: 'invalid_option' });
  }
  if (choices.length > 25) {
    throw new PigeonError(`${optionName}: at most 25 choices`, { code: 'invalid_option' });
  }
  return choices.map((choice) => {
    const entry = choice && typeof choice === 'object' ? choice : { name: String(choice), value: choice };
    const name = String(entry.name ?? entry.value ?? '').slice(0, 64);
    if (!name) throw new PigeonError(`${optionName}: a choice needs a name`, { code: 'invalid_option' });
    const value = entry.value ?? entry.name;
    if (type === 'string' ? typeof value !== 'string' : typeof value !== 'number') {
      throw new PigeonError(`${optionName}: choice ${name} does not match type ${type}`, {
        code: 'invalid_option',
      });
    }
    return { name, value };
  });
}

export function normalizeCommand(definition) {
  const name = String(definition?.name ?? '').trim().toLowerCase();
  if (!NAME_RE.test(name)) {
    throw new PigeonError(`command name ${JSON.stringify(definition?.name)} must match ${NAME_RE}`, {
      code: 'invalid_command_name',
    });
  }
  const description = String(definition.description ?? '').trim().slice(0, 200);
  if (!description) throw new PigeonError(`${name}: description is required`, { code: 'bad_request' });

  const spaceId = definition.spaceId ?? definition.space_id ?? null;
  const space_id = spaceId === null || spaceId === undefined || spaceId === '' ? null : String(spaceId);
  const declaredDm = definition.dmEnabled ?? definition.dm_enabled;

  return {
    name,
    description,
    options: resolveOptions(definition.options),
    space_id,
    dm_enabled: space_id ? false : declaredDm === undefined || declaredDm !== false,
  };
}

export function resolveOptions(source) {
  if (source === undefined || source === null) return [];
  if (source instanceof OptionBuilder) return source.toJSON();
  if (typeof source === 'function') {
    const builder = new OptionBuilder();
    const returned = source(builder);
    return resolveOptions(returned instanceof OptionBuilder || Array.isArray(returned) ? returned : builder);
  }
  if (Array.isArray(source)) return source.map(normalizeOption);
  throw new PigeonError('options must be an array, an OptionBuilder, or a builder function', {
    code: 'invalid_option',
  });
}

export function commandKey(command) {
  return `${command.space_id ?? command.spaceId ?? ''}:${String(command.name).toLowerCase()}`;
}

export function diffCommands(local, remote) {
  const mine = new Map(local.map((command) => [commandKey(command), command]));
  const theirs = new Map((remote ?? []).map((command) => [commandKey(command), command]));

  const added = [];
  const updated = [];
  const removed = [];

  for (const [key, command] of mine) {
    const other = theirs.get(key);
    if (!other) added.push(command);
    else if (canonical(command) !== canonical(other)) updated.push(command);
  }
  for (const [key, command] of theirs) {
    if (!mine.has(key)) removed.push(command);
  }

  return { changed: added.length + updated.length + removed.length > 0, added, updated, removed };
}

function canonical(command) {
  return JSON.stringify({
    name: String(command.name).toLowerCase(),
    description: String(command.description ?? ''),
    space_id: command.space_id ?? command.spaceId ?? null,
    dm_enabled: (command.space_id ?? command.spaceId ?? null) ? false : command.dm_enabled !== false,
    options: (command.options ?? []).map((option) => ({
      name: String(option.name).toLowerCase(),
      description: String(option.description ?? ''),
      type: String(option.type ?? 'string'),
      required: option.required === true,
      choices: (option.choices ?? []).map((choice) => ({ name: String(choice.name), value: choice.value })),
      min: option.min ?? null,
      max: option.max ?? null,
    })),
  });
}

export function readOptions(raw) {
  const options = Object.create(null);
  if (raw && typeof raw === 'object') {
    for (const [key, value] of Object.entries(raw)) options[key] = value;
  }
  return options;
}
