# PigeonSMS Kotlin SDK

Official Kotlin/JVM and Android client for Open Pigeon Protocol servers.

```kotlin
val client = PigeonClient("https://api.example.com", tokenProvider = { token })
val spaces = client.spaces()

val gateway = PigeonGateway("wss://api.example.com/gateway", { token }, { savedCursors })
gateway.on("message.new") { event -> println(event.d) }
gateway.start(scope)
```

Maven coordinates: `app.pigeonsms:pigeonsms-sdk:1.0.0-beta.1`.
