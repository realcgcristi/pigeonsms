import { PigeonError } from './errors.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const DEFAULT_RATES = {
  message: 4,
  reaction: 2,
  like: 2,
  forumPost: 1.5,
  typing: 4,
  read: 4,
  interaction: 4,
  default: 5,
};

const BURST = 5;
const MIN_RATE = 0.25;

export function bucketFor(method, path) {
  if (path.startsWith('/interactions/')) return 'interaction';
  if (path.includes('/forum/')) return 'forumPost';
  if (path.includes('/reactions/')) return 'reaction';
  if (path.endsWith('/like')) return 'like';
  if (path.endsWith('/typing')) return 'typing';
  if (path.endsWith('/read')) return 'read';
  if (method === 'POST' && path.includes('/messages')) return 'message';
  return 'default';
}

class Bucket {
  constructor(rate) {
    this.rate = rate;
    this.baseRate = rate;
    this.tokens = BURST;
    this.updated = Date.now();
    this.pausedUntil = 0;
    this.tail = Promise.resolve();
  }

  #refill() {
    const now = Date.now();
    this.tokens = Math.min(BURST, this.tokens + ((now - this.updated) / 1000) * this.rate);
    this.updated = now;
  }

  take() {
    const next = this.tail.then(async () => {
      for (;;) {
        const now = Date.now();
        if (now < this.pausedUntil) {
          await sleep(this.pausedUntil - now);
          continue;
        }
        this.#refill();
        if (this.tokens >= 1) {
          this.tokens -= 1;
          return;
        }
        await sleep(Math.ceil(((1 - this.tokens) / this.rate) * 1000));
      }
    });
    this.tail = next.catch(() => {});
    return next;
  }

  throttled(retryAfterMs) {
    this.pausedUntil = Date.now() + retryAfterMs;
    this.tokens = 0;
    this.rate = Math.max(MIN_RATE, this.rate / 2);
  }

  succeeded() {
    if (this.rate < this.baseRate) this.rate = Math.min(this.baseRate, this.rate * 1.25);
  }
}

export class RateLimiter {
  #buckets = new Map();

  constructor(rates = {}) {
    this.rates = { ...DEFAULT_RATES, ...rates };
  }

  #bucket(name) {
    let bucket = this.#buckets.get(name);
    if (!bucket) {
      bucket = new Bucket(this.rates[name] ?? this.rates.default);
      this.#buckets.set(name, bucket);
    }
    return bucket;
  }

  async run(name, fn) {
    const bucket = this.#bucket(name);
    await bucket.take();
    try {
      const result = await fn();
      bucket.succeeded();
      return result;
    } catch (error) {
      if (error instanceof PigeonError && error.status === 429) {
        bucket.throttled(retryAfterMs(error));
      }
      throw error;
    }
  }

  state() {
    const out = {};
    for (const [name, bucket] of this.#buckets) {
      out[name] = {
        rate: Number(bucket.rate.toFixed(2)),
        tokens: Number(bucket.tokens.toFixed(2)),
        pausedFor: Math.max(0, bucket.pausedUntil - Date.now()),
      };
    }
    return out;
  }
}

export function retryAfterMs(error) {
  const header = error?.headers?.['retry-after'] ?? error?.retryAfter;
  const seconds = Number(header);
  if (Number.isFinite(seconds) && seconds > 0) return Math.min(seconds * 1000, 60_000);
  return 2_000;
}
