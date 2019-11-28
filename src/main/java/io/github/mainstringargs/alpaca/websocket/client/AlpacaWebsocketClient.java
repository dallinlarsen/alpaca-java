package io.github.mainstringargs.alpaca.websocket.client;

import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import io.github.mainstringargs.abstracts.websocket.client.WebsocketClient;
import io.github.mainstringargs.abstracts.websocket.listener.StreamListener;
import io.github.mainstringargs.abstracts.websocket.message.StreamMessage;
import io.github.mainstringargs.abstracts.websocket.message.StreamMessageType;
import io.github.mainstringargs.alpaca.websocket.listener.AlpacaStreamListener;
import io.github.mainstringargs.alpaca.websocket.message.AlpacaStreamMessageType;
import io.github.mainstringargs.domain.alpaca.websocket.AlpacaStreamMessage;
import io.github.mainstringargs.domain.alpaca.websocket.account.AccountUpdateMessage;
import io.github.mainstringargs.domain.alpaca.websocket.authorization.AuthorizationMessage;
import io.github.mainstringargs.domain.alpaca.websocket.listening.ListeningMessage;
import io.github.mainstringargs.domain.alpaca.websocket.trade.TradeUpdateMessage;
import io.github.mainstringargs.util.gson.GsonUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.websocket.DeploymentException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The type Alpaca websocket client.
 */
public class AlpacaWebsocketClient implements WebsocketClient {

    /** The logger. */
    private static final Logger LOGGER = LogManager.getLogger(AlpacaWebsocketClient.class);

    /** The constant STREAM_KEY. */
    private static final String STREAM_KEY = "stream";

    /** The key id. */
    private String keyId;

    /** The secret. */
    private String secret;

    /** The Websocket url. */
    private String websocketURL;

    /** The observers. */
    private List<AlpacaStreamListener> listeners;

    /** The client end point. */
    private AlpacaWebsocketClientEndpoint alpacaWebsocketClientEndpoint;

    /** The Authenticated. */
    private boolean authenticated;

    /**
     * Instantiates a new Alpaca websocket client.
     *
     * @param keyId        the key id
     * @param secret       the secret
     * @param websocketURL the websocket url
     */
    public AlpacaWebsocketClient(String keyId, String secret, String websocketURL) {
        this.keyId = keyId;
        this.secret = secret;
        this.websocketURL = websocketURL;

        this.listeners = new ArrayList<>();
    }

    @Override
    public void addListener(StreamListener streamListener) {
        Preconditions.checkState(streamListener instanceof AlpacaStreamListener);

        if (listeners.isEmpty()) {
            connect();
        }

        listeners.add((AlpacaStreamListener) streamListener);

        submitStreamRequestUpdate();
    }

    @Override
    public void removeListener(StreamListener streamListener) {
        Preconditions.checkState(streamListener instanceof AlpacaStreamListener);

        listeners.remove(streamListener);

        submitStreamRequestUpdate();

        if (listeners.isEmpty()) {
            disconnect();
        }
    }

    @Override
    public void connect() {
        LOGGER.info("Connecting...");

        try {
            alpacaWebsocketClientEndpoint = new AlpacaWebsocketClientEndpoint(this, new URI(websocketURL));
            alpacaWebsocketClientEndpoint.connect();

            LOGGER.info("Connected.");

            LOGGER.info("Authenticating...");
            sendAuthenticationMessage();
        } catch (URISyntaxException | DeploymentException | IOException e) {
            LOGGER.throwing(e);
        }
    }

    @Override
    public void disconnect() {
        LOGGER.info("Disconnecting...");

        try {
            alpacaWebsocketClientEndpoint.getUserSession().close();

            LOGGER.info("Disconnected.");
        } catch (IOException e) {
            LOGGER.throwing(e);
        }
    }

    @Override
    public void sendAuthenticationMessage() {
        /* Format of message is:
         *  {
         *      "action": "authenticate",
         *      "data": {
         *          "key_id": "{YOUR_API_KEY_ID}",
         *          "secret_key": "{YOUR_API_SECRET_KEY}"
         *      }
         *  }
         */

        JsonObject authRequest = new JsonObject();
        authRequest.addProperty("action", "authenticate");

        JsonObject payload = new JsonObject();
        payload.addProperty("key_id", keyId);
        payload.addProperty("secret_key", secret);

        authRequest.add("data", payload);

        alpacaWebsocketClientEndpoint.sendMessage(authRequest.toString());
    }

    @Override
    public void handleWebsocketMessage(String message) {
        JsonElement messageJsonElement = GsonUtil.JSON_PARSER.parse(message);

        Preconditions.checkState(messageJsonElement instanceof JsonObject);

        JsonObject messageJsonObject = messageJsonElement.getAsJsonObject();
        JsonElement streamJsonElement = messageJsonObject.get(STREAM_KEY);

        if (streamJsonElement instanceof JsonPrimitive) {
            try {
                AlpacaStreamMessageType alpacaStreamMessageType = GsonUtil.GSON.fromJson(messageJsonObject,
                        AlpacaStreamMessageType.class);

                switch (alpacaStreamMessageType) {
                    case LISTENING:
                        ListeningMessage listeningMessage = GsonUtil.GSON.fromJson(messageJsonObject,
                                ListeningMessage.class);
                        sendStreamMessageToListeners(alpacaStreamMessageType, listeningMessage);

                        LOGGER.debug(listeningMessage);
                        break;
                    case AUTHORIZATION:
                        AuthorizationMessage authorizationMessage = GsonUtil.GSON.fromJson(messageJsonObject,
                                AuthorizationMessage.class);
                        sendStreamMessageToListeners(alpacaStreamMessageType, authorizationMessage);

                        authenticated = isAuthorizationMessageSuccess(authorizationMessage);

                        LOGGER.debug(authorizationMessage);
                        break;
                    case TRADE_UPDATES:
                        sendStreamMessageToListeners(alpacaStreamMessageType, GsonUtil.GSON.fromJson(messageJsonObject,
                                TradeUpdateMessage.class));
                        break;
                    case ACCOUNT_UPDATES:
                        sendStreamMessageToListeners(alpacaStreamMessageType, GsonUtil.GSON.fromJson(messageJsonObject,
                                AccountUpdateMessage.class));
                        break;
                    default:
                        LOGGER.error("Unhandled stream type: " + alpacaStreamMessageType);
                }
            } catch (JsonSyntaxException e) {
                LOGGER.throwing(e);
            }
        } else {
            LOGGER.error("Unknown stream message: " + messageJsonObject);
        }
    }

    @Override
    public void sendStreamMessageToListeners(StreamMessageType streamMessageType, StreamMessage streamMessage) {
        Preconditions.checkState(streamMessageType instanceof AlpacaStreamMessageType);
        Preconditions.checkState(streamMessage instanceof AlpacaStreamMessage);

        AlpacaStreamMessageType alpacaStreamMessageType = (AlpacaStreamMessageType) streamMessageType;
        AlpacaStreamMessage alpacaStreamMessage = (AlpacaStreamMessage) streamMessage;

        for (AlpacaStreamListener alpacaStreamListener : listeners) {
            if (alpacaStreamListener.getStreamMessageTypes() == null ||
                    alpacaStreamListener.getStreamMessageTypes().isEmpty() ||
                    alpacaStreamListener.getStreamMessageTypes().contains(alpacaStreamMessageType)) {
                alpacaStreamListener.onStreamUpdate(alpacaStreamMessageType, alpacaStreamMessage);
            }
        }
    }

    @Override
    public boolean isConnected() {
        return alpacaWebsocketClientEndpoint.getUserSession().isOpen();
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Is authorization message success boolean.
     *
     * @param authorizationMessage the authorization message
     *
     * @return the boolean
     */
    private boolean isAuthorizationMessageSuccess(AuthorizationMessage authorizationMessage) {
        return authorizationMessage.getData().getStatus().equalsIgnoreCase("authorized") &&
                authorizationMessage.getData().getAction().equalsIgnoreCase("authenticate");
    }

    /**
     * Submit stream request.
     */
    private void submitStreamRequestUpdate() {
        // Stream request example:
        // {
        //     "action": "listen",
        //     "data": {
        //         "streams": ["account_updates", "trade_updates"]
        //     }
        // }

        JsonObject streamRequestJsonObject = new JsonObject();
        streamRequestJsonObject.addProperty("action", "listen");

        JsonArray streamsJsonArray = new JsonArray();
        getRegisteredMessageTypes().forEach(alpacaStreamMessageType ->
                streamsJsonArray.add(GsonUtil.GSON.toJson(alpacaStreamMessageType)));

        JsonObject dataJsonObject = new JsonObject();
        dataJsonObject.add("streams", streamsJsonArray);

        streamRequestJsonObject.add("data", dataJsonObject);

        alpacaWebsocketClientEndpoint.sendMessage(streamRequestJsonObject.toString());

        LOGGER.info("Requested subscriptions to update to " + streamsJsonArray);
    }

    /**
     * Gets the registered message types.
     *
     * @return the registered message types
     */
    public Set<AlpacaStreamMessageType> getRegisteredMessageTypes() {
        Set<AlpacaStreamMessageType> registeredStreamMessageTypes = new HashSet<>();

        for (AlpacaStreamListener alpacaStreamListener : listeners) {
            // if its empty, assume they want everything
            if (alpacaStreamListener.getStreamMessageTypes() == null ||
                    alpacaStreamListener.getStreamMessageTypes().isEmpty()) {
                registeredStreamMessageTypes.addAll(Arrays.asList(AlpacaStreamMessageType.values()));
            } else {
                registeredStreamMessageTypes.addAll(alpacaStreamListener.getStreamMessageTypes());
            }
        }

        return registeredStreamMessageTypes;
    }
}
