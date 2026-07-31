import { PigeonClient } from './client.js';
import type { BotCommand, BotInteraction } from './types.js';

export type InteractionHandler = (interaction: BotInteraction) => unknown | Promise<unknown>;

export class PigeonBot {
  readonly client: PigeonClient;
  private readonly handlers = new Map<string, InteractionHandler>();
  private active = false;

  constructor(options: { baseUrl: string; token: string; fetch?: typeof fetch }) {
    this.client = new PigeonClient({ ...options, clientName: '@pigeonsms/sdk bot' });
  }

  command(command: BotCommand, handler: InteractionHandler): this {
    this.handlers.set(command.name, handler);
    return this;
  }

  async syncCommands(commands?: BotCommand[]): Promise<BotCommand[]> {
    const definitions = commands ?? [...this.handlers.keys()].map((name) => ({ name, description: `${name} command` }));
    return this.client.replaceCommands(definitions);
  }

  async start(): Promise<void> {
    if (this.active) return;
    this.active = true;
    while (this.active) {
      try {
        for (const interaction of await this.client.pollInteractions()) {
          const handler = this.handlers.get(interaction.command);
          if (!handler || !interaction.callback_token) continue;
          try {
            const response = await handler(interaction);
            await this.client.answerInteraction(interaction.id, interaction.callback_token, response ?? { type: 'message', content: '' });
          } catch (error) {
            await this.client.answerInteraction(interaction.id, interaction.callback_token, {
              type: 'message', content: error instanceof Error ? error.message : 'command failed', ephemeral: true,
            });
          }
        }
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }
  }

  stop(): void { this.active = false; }
}
