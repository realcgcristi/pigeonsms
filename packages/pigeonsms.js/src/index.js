/**
 * pigeonsms.js — the PigeonSMS bot SDK.
 *
 * Node 20+, ESM, zero runtime dependencies. Protocol reference: BOTS.md.
 */
export { Client, verifySignature } from './client.js';
export { REST, DEFAULT_API } from './rest.js';
export { OptionBuilder, OPTION_TYPES, normalizeCommand, diffCommands } from './commands.js';
export { Interaction, normalizeInteraction } from './interaction.js';
export { Gateway } from './gateway.js';
export { PigeonError } from './errors.js';

export const version = '0.1.0';
