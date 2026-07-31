# pigeonsms-sdk

Official async Rust SDK for Open Pigeon Protocol clients and bots.

```rust
use pigeonsms_sdk::{PigeonClient, SendMessage};

let client = PigeonClient::new("https://api.example.com")?;
client.set_token(token).await;
let spaces = client.spaces().await?;
client.send_message(&spaces[0].channels[0].id, &SendMessage::text("hello")).await?;
```

The default `gateway` feature adds resumable WebSocket events with reconnect and heartbeat handling.
