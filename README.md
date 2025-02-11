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
    git clone [https://github.com/your-username/Auto-Opener.git](https://www.google.com/search?q=https://github.com/your-username/Auto-Opener.git)
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

## Usage

1.  **Establish a WebSocket connection:** Use a WebSocket client to connect to the server at `ws://<host>:<port>/listen/<userId>`.
2.  **Authenticate the connection:** Include a JWT in the connection request.
3.  **Send triggers:** Once authenticated, send trigger messages as plain text strings through the WebSocket connection. The program will listen for these triggers and perform the corresponding actions defined in the `config.json` file.

## Configuration

The `config.json` file allows you to customize the behavior of Auto-Opener. See the example `config.json` in the repository for details.
