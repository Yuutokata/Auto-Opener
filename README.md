
# Auto-Opener

**Auto-Opener** is a Kotlin-based program designed to automate the process of opening webpages or running applications based on triggers received via a websocket connection. It leverages technologies like Redis for caching and Ktor for building the websocket server.

## Features

-   **Websocket Communication:** Listens for triggers on a specified websocket endpoint.
-   **Customizable Actions:** Configure the program to open specific URLs or execute applications upon receiving triggers.
-   **Redis Integration:** Utilizes Redis to store and manage configuration data.
-   **Token-Based Authentication:** Secures the websocket connection with token-based authentication.
-   **Easy Configuration:** Configure the program's behavior through a `config.json` file.

## Prerequisites

-   **Java Development Kit (JDK):** Version 17 or higher.
-   **Gradle:** Build automation tool.
-   **Redis Server:** An instance of Redis running and accessible to the program.

## Setup

1.  **Clone the repository:**
    
    Bash
    
    ```
    git clone https://github.com/yuutoamamiya/Auto-Opener.git
    
    ```
    
2.  **Configure the application:**
    
    -   Open the `config.json` file and modify the following parameters:
        -   `redis`:
            -   `host`: The hostname or IP address of your Redis server.
            -   `port`: The port number of your Redis server.
            -   `password`: The password for your Redis server (if applicable).
        -   `websocket`:
            -   `port`: The port number for the websocket server.
            -   `token`: The secret token for websocket authentication.
        -   `actions`: An array of actions to perform. Each action should have:
            -   `trigger`: The trigger message to listen for on the websocket.
            -   `type`: The type of action (`url` or `application`).
            -   `value`: The URL to open or the application to execute.
3.  **Build the application:**
    

./gradlew build

```

4. **Run the application:**

```bash
java -jar build/libs/Auto-Opener-1.0-SNAPSHOT.jar

```

## Usage

1.  **Establish a websocket connection:**
    
    Use a websocket client to connect to the server at `wss://localhost:[websocket_port]`.
    
2.  **Authenticate the connection:**
    
    Send a JSON message with the following structure for authentication:
    
    JSON
    
    ```
    {
      "token": "[your_secret_token]"
    }
    
    ```
    
3.  **Send triggers:**
    
    Once authenticated, send trigger messages as plain text strings through the websocket connection. The program will listen for these triggers and perform the corresponding actions defined in the `config.json` file.
    

## Example `config.json`

JSON

```
{
  "redis": {
    "host": "localhost",
    "port": 6379,
    "password": ""
  },
  "websocket": {
    "port": 8080,
    "token": "your-secret-token"
  },
  "actions": [
    {
      "trigger": "open_google",
      "type": "url",
      "value": "https://www.google.com"
    },
    {
      "trigger": "launch_notepad",
      "type": "application",
      "value": "notepad.exe"
    }
  ]
}

```

