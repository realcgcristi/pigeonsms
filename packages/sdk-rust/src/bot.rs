use std::{collections::HashMap, future::Future, pin::Pin, sync::Arc, time::Duration};

use serde_json::{json, Value};

use crate::{BotInteraction, PigeonClient, Result};

type HandlerFuture = Pin<Box<dyn Future<Output = Result<Value>> + Send>>;
type Handler = Arc<dyn Fn(BotInteraction) -> HandlerFuture + Send + Sync>;

pub struct PigeonBot {
    client: PigeonClient,
    handlers: HashMap<String, Handler>,
}

impl PigeonBot {
    pub fn new(client: PigeonClient) -> Self { Self { client, handlers: HashMap::new() } }

    pub fn command<F, Fut>(&mut self, name: impl Into<String>, handler: F)
    where
        F: Fn(BotInteraction) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<Value>> + Send + 'static,
    {
        self.handlers.insert(name.into(), Arc::new(move |interaction| Box::pin(handler(interaction))));
    }

    pub async fn run(&self) -> Result<()> {
        loop {
            match self.client.poll_interactions(25).await {
                Ok(interactions) => for interaction in interactions {
                    let Some(handler) = self.handlers.get(&interaction.command) else { continue };
                    let Some(callback) = interaction.callback_token.clone() else { continue };
                    let id = interaction.id.clone();
                    let response = handler(interaction).await.unwrap_or_else(|error| json!({
                        "type": "message", "content": error.to_string(), "ephemeral": true
                    }));
                    self.client.answer_interaction(&id, &callback, response).await?;
                },
                Err(_) => tokio::time::sleep(Duration::from_secs(1)).await,
            }
        }
    }
}
