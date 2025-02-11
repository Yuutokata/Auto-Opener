
# Auto-Opener

**Auto-Opener** is a Kotlin-based program that automates opening web pages or running applications based on triggers received via a WebSocket connection. It uses Redis for caching and Ktor for building the WebSocket server.

## Features

-   **WebSocket Communication:** Listens for triggers on a specified WebSocket endpoint.
-   **Customizable Actions:** Configure the program to open specific URLs or execute applications upon receiving triggers.
-   **Redis Integration:** Utilizes Redis to store and manage configuration data.
-   **Token-Based Authentication:** Secures the WebSocket connection with token-based authentication.
-   **Easy Configuration:** Configure the program's behavior through a `config.json` file.

## Prerequisites

-   **Java Development Kit (JDK):** Version 17 or higher.
-   **Gradle:** Build automation tool.
-   **Redis Server:** An instance of Redis running and accessible to the program.

## Setup

1.  **Clone the repository:**

    ```bash
    git clone https://github.com/yuutokata/Auto-Opener.git
    ```

2.  **Configure the application:**

    -   Open the `config.json` file and modify the following parameters:
        -   `host`: The hostname or IP address where the server will run.
        -   `port`: The port number for the server.
        -   `redis`:
            -   `host`: The hostname or IP address of your Redis server.
            -   `port`: The port number of your Redis server.
        -   `tokens`:
            -   `bot`: The bot token for authentication.
            -   `user`: The user token for authentication.
        -   `jwt`:
            -   `secret`: The secret key for JWT signing.
            -   `issuer`: The issuer of the JWT.
            -   `audience`: The audience of the JWT.

3.  **Build the application:**
    ```bash
    ./gradlew build
    ```

4.  **Run the application:**

    ```bash
    java -jar build/libs/Auto-Opener-1.0-SNAPSHOT.jar
    ```

## API Endpoints

### 1. WebSocket Endpoint: `/listen/{userId}`

*   **Purpose:** Establishes a WebSocket connection for a specific user, listens for triggers, and performs actions based on those triggers.
*   **Method:** WebSocket
*   **Authentication:** Requires a valid JWT (JSON Web Token) for authentication.
*   **Usage:**
    1.  A client establishes a WebSocket connection to this route, including the `userId` in the path and a valid JWT.
    2.  The server authenticates the connection using the JWT.
    3.  Once authenticated, the client can send trigger messages as plain text strings.
    4.  The server listens for these triggers and performs the corresponding actions defined in the `config.json` file (e.g., opening a URL or executing an application).

### 2. Message Sending Endpoint: `/send_message`

*   **Purpose:** Sends a message to a specific user via a Redis topic.
*   **Method:** GET
*   **Authentication:** Requires a bot token in the `Authorization` header for authentication.
*   **Usage:**
    1.  A client sends a GET request to this route, including the `user_id` and `message` as query parameters and the bot token in the `Authorization` header.
    2.  The server authenticates the request using the bot token.
    3.  If authenticated, the server publishes the message to the corresponding Redis topic for the specified user.
    4.  The client receives a response indicating whether the message was sent successfully.

### 3. Token Generation Endpoint: `/generateToken`

*   **Purpose:** Generates a JWT for a user.
*   **Method:** GET
*   **Authentication:** Requires a user token in the `Authorization` header for authentication.
*   **Usage:**
    1.  A client sends a GET request to this route, including the user token in the `Authorization` header.
    2.  The server authenticates the request using the user token.
    3.  If authenticated, the server generates a JWT containing the user ID and sends it in the response.
    4.  The client can then use this JWT to authenticate WebSocket connections to the `/listen/{userId}` route.

## Configuration

The `config.json` file allows you to customize the behavior of Auto-Opener. See the example `config.json` in the repository for details.
