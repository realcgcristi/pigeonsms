/**
 * Entity caches.
 *
 * Every payload the bot already receives — interaction invokers, message
 * authors, member lists — carries a full user or channel object, so the common
 * lookups should never hit the network at all. Each manager is an LRU with a
 * TTL: `get` is synchronous and free, `fetch` falls back to the API and caches
 * the result, and `fetch(id, { force: true })` bypasses the cache when the bot
 * needs certainty.
 */

const DEFAULT_TTL_MS = 5 * 60_000;
const DEFAULT_MAX = 1_000;

class Store {
  #entries = new Map();

  constructor({ ttl = DEFAULT_TTL_MS, max = DEFAULT_MAX } = {}) {
    this.ttl = ttl;
    this.max = max;
    this.hits = 0;
    this.misses = 0;
  }

  get(id) {
    const entry = this.#entries.get(id);
    if (!entry) {
      this.misses += 1;
      return null;
    }
    if (Date.now() > entry.expires) {
      this.#entries.delete(id);
      this.misses += 1;
      return null;
    }
    // Touch for LRU ordering: Map keeps insertion order, so re-inserting moves
    // this entry to the end and keeps hot ids away from the eviction edge.
    this.#entries.delete(id);
    this.#entries.set(id, entry);
    this.hits += 1;
    return entry.value;
  }

  set(id, value) {
    if (!id || value == null) return value;
    this.#entries.delete(id);
    this.#entries.set(id, { value, expires: Date.now() + this.ttl });
    while (this.#entries.size > this.max) {
      const oldest = this.#entries.keys().next().value;
      this.#entries.delete(oldest);
    }
    return value;
  }

  delete(id) {
    return this.#entries.delete(id);
  }

  clear() {
    this.#entries.clear();
  }

  get size() {
    return this.#entries.size;
  }

  values() {
    return [...this.#entries.values()].map((entry) => entry.value);
  }
}

class Manager {
  constructor(client, { ttl, max, load }) {
    this.client = client;
    this.cache = new Store({ ttl, max });
    this.load = load;
    this.inflight = new Map();
  }

  get(id) {
    return this.cache.get(id);
  }

  add(entity) {
    if (entity?.id) this.cache.set(String(entity.id), entity);
    return entity;
  }

  /** Cached read, or one shared request when several handlers miss at once. */
  async fetch(id, { force = false } = {}) {
    const key = String(id);
    if (!force) {
      const cached = this.cache.get(key);
      if (cached) return cached;
    }
    const pending = this.inflight.get(key);
    if (pending) return pending;

    const request = this.load(key)
      .then((value) => this.add(value) ?? value)
      .finally(() => this.inflight.delete(key));
    this.inflight.set(key, request);
    return request;
  }
}

export class Caches {
  constructor(client, options = {}) {
    const { ttl, max } = options;
    this.users = new Manager(client, {
      ttl,
      max,
      load: async (id) => {
        const res = await client.rest.get(`/users/${encodeURIComponent(id)}/profile`);
        return res?.profile ?? null;
      },
    });
    this.channels = new Manager(client, {
      ttl,
      max,
      load: async (id) => {
        const res = await client.rest.get(`/channels/${encodeURIComponent(id)}/messages`, {
          query: { limit: 1 },
        });
        return { id: String(id), last_seq: res?.cursor?.channel_last_seq ?? 0 };
      },
    });
    this.spaces = new Manager(client, {
      ttl,
      max,
      load: async (id) => {
        const res = await client.rest.get('/bots/me/spaces');
        for (const space of res?.spaces ?? []) this.spaces.add(space);
        return (res?.spaces ?? []).find((space) => String(space.id) === String(id)) ?? null;
      },
    });
  }

  /** Harvest every entity an inbound payload happens to carry. */
  absorb(payload) {
    if (!payload) return;
    if (payload.user?.id) this.users.add(payload.user);
    if (payload.author?.id) this.users.add(payload.author);
    if (payload.channel_id) {
      const known = this.channels.get(String(payload.channel_id));
      if (!known) this.channels.add({ id: String(payload.channel_id), space_id: payload.space_id ?? null });
    }
    for (const member of payload.members ?? []) this.users.add(member);
  }

  stats() {
    return {
      users: { size: this.users.cache.size, hits: this.users.cache.hits, misses: this.users.cache.misses },
      channels: {
        size: this.channels.cache.size,
        hits: this.channels.cache.hits,
        misses: this.channels.cache.misses,
      },
      spaces: {
        size: this.spaces.cache.size,
        hits: this.spaces.cache.hits,
        misses: this.spaces.cache.misses,
      },
    };
  }

  clear() {
    this.users.cache.clear();
    this.channels.cache.clear();
    this.spaces.cache.clear();
  }
}
