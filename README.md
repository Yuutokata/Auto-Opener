# Auto-Opener

Auto-Opener is a WebSocket-based message relay system that enables real-time communication between bots and users. It provides a secure and efficient way for bots to send messages to users through persistent WebSocket connections.

## Features

- **Real-time Communication**: Maintains WebSocket connections for instant message delivery
- **Bot-to-User Messaging**: Allows bots to send messages to specific users
- **Connection Management**: Robust handling of WebSocket connections with health checks and monitoring
- **Security**: JWT authentication and rate limiting to prevent abuse
- **Scalability**: Configurable dispatcher settings for optimal performance
- **Monitoring**: Comprehensive logging and metrics for system health

## Requirements

- JDK 21 or higher
- MongoDB
- Gradle

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yuutokata/Auto-Opener.git
   cd Auto-Opener
   ```

2. Configure the application by editing `config.json` (see Configuration section below)

3. Build the application:
   ```bash
   ./gradlew build
   ```

4. Run the application:
   ```bash
   ./gradlew run
   ```

## Configuration

The application is configured through the `config.json` file. Here are the key configuration options:

```json
{
  "host": "0.0.0.0",
  "port": 5064,
  "mongodb": {
    "uri": "mongodb://localhost:27017",
    "db": "AutoOpener"
  },
  "tokens": {
    "bot": [""]
  },
  "jwt": {
    "secret": "",
    "issuer": "auto-opener",
    "audience": "chrome-extension"
  },
  "rateLimits": {
    "token": {
      "limit": 20,
      "duration": 60
    },
    "websocket": {
      "limit": 40,
      "duration": 60
    },
    "service": {
      "limit": 100,
      "duration": 60
    }
  },
  "healthCheckInterval": 5,
  "pongTimeout": 25,
  "getInactivityThreshold": 60,
  "websocket": {
    "timeout": 90,
    "maxFrameSize": 65536,
    "masking": false
  },
  "dispatchers": {
    "networkParallelism": 30,
    "databaseParallelism": 10,
    "processingParallelism": 8,
    "monitoringParallelism": 4,
    "websocketParallelism": 6,
    "heartbeatParallelism": 2,
    "monitoringIntervalSeconds": 120
  }
}
```

### Configuration Options Explained

| Option | Description |
|--------|-------------|
| `host` | Host to bind the server to |
| `port` | Port to listen on |
| `mongodb.uri` | MongoDB connection URI |
| `mongodb.db` | Database name |
| `tokens.bot` | Array of bot authentication tokens |
| `jwt.secret` | JWT secret for token generation/validation |
| `jwt.issuer` | JWT issuer |
| `jwt.audience` | JWT audience (e.g., "chrome-extension") |
| `rateLimits.token.limit` | Rate limit for token endpoints |
| `rateLimits.token.duration` | Duration in seconds for token rate limiting |
| `rateLimits.websocket.limit` | Rate limit for WebSocket connections |
| `rateLimits.websocket.duration` | Duration in seconds for WebSocket rate limiting |
| `rateLimits.service.limit` | Rate limit for service endpoints |
| `rateLimits.service.duration` | Duration in seconds for service rate limiting |
| `healthCheckInterval` | Interval for health checks in seconds |
| `pongTimeout` | Timeout for ping/pong in seconds |
| `getInactivityThreshold` | Inactivity threshold in seconds |
| `websocket.timeout` | WebSocket timeout in seconds |
| `websocket.maxFrameSize` | Maximum frame size in bytes |
| `websocket.masking` | WebSocket frame masking |
| `dispatchers.networkParallelism` | Network thread pool size |
| `dispatchers.databaseParallelism` | Database thread pool size |
| `dispatchers.processingParallelism` | Processing thread pool size |
| `dispatchers.monitoringParallelism` | Monitoring thread pool size |
| `dispatchers.websocketParallelism` | WebSocket thread pool size |
| `dispatchers.heartbeatParallelism` | Heartbeat thread pool size |
| `dispatchers.monitoringIntervalSeconds` | Monitoring interval in seconds |

## Usage

### Bot Integration

Bots can connect to the WebSocket server and send messages to users. Here's a basic example:

1. Authenticate the Bot by requesting the token route:
   ```
   POST /token
   ```
   Body:
   ```json
   {
     "token": "your_bot_token_here",
     "role": "service"
   }
    ```
2. You will get this response:
    ```json
   {"token": "your_jwt_token"}
   ```

3. Connect to the WebSocket endpoint with the jwt token passed using the Sec-WebSocket-Protocol header or the Authorization header:
   ```
   GET /bot
   ```

4. Send a message to a user:
   ```json
   {
     "userId": "user_id_here",
     "message": {
       "type": "notification",
       "content": "Hello from the bot!"
     }
   }
   ```

5. Receive delivery status:
   ```json
   {
     "status": "success",
     "message": "Message delivered to 1/1 active sessions",
     "userId": "user_id_here"
   }
   ```

### User Integration

Users can connect to receive messages from bots:

1. Authenticate the User by requesting the token route:
   ```
   GET /token?userId={USER_ID}
   ```
   
2. You will get this response:
    ```json
   {"token": "your_jwt_token"}
   ```

3. Connect to the WebSocket endpoint with a user JWT passed using the Sec-WebSocket-Protocol header or the Authorization header:
   ```
   GET /listen/{USER_ID}
   ```
   Header:
   ```
   Sec-WebSocket-Protocol: your_jwt_token
   ```

4. Maintain the connection to receive messages from bots in real-time

## Security Considerations

- Always use HTTPS in production
- Keep your JWT secret secure
- Regularly rotate bot tokens
- Set appropriate rate limits to prevent abuse

## Monitoring

The application provides detailed logs with structured logging using MDC (Mapped Diagnostic Context) for better observability. It also supports metrics collection through Micrometer with Prometheus integration.

### Loki Logging Integration

Auto-Opener integrates with Grafana Loki for centralized log aggregation. The logs are sent to Loki in JSON format with proper labels for efficient querying.

#### System Variables for Loki Configuration

You can configure the Loki integration using the following environment variables:

| Variable | Description | Default Value |
|----------|-------------|---------------|
| `LOKI_URL` | URL for the Loki server | http://b856d7c7-976f-40b5-bcf7-a25f6a2daeea:5001/loki/api/v1/push |
| `APP_NAME` | Application name used in labels | - |
| `ENVIRONMENT` | Environment name (production, staging, development) | development |
| `HOSTNAME` | Host name for identifying the source | System hostname |
| `LOG_LEVEL` | Logging level | INFO |

**Note:** Logs are only sent to Loki when `ENVIRONMENT` is set to "production".

#### Example Usage

```bash
# Run with custom Loki configuration
export LOKI_URL="http://your-loki-server:3100/loki/api/v1/push"
export APP_NAME="auto-opener"
export ENVIRONMENT="production"
export HOSTNAME="app-server-01"
export LOG_LEVEL="INFO"
./gradlew run
```

## License

[MIT License](LICENSE)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
