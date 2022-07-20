package me.axieum.mcmod.authme.api.util;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import me.axieum.mcmod.authme.impl.config.AuthMeConfig;
import net.minecraft.text.Text;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.util.Session;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.Util;

import static me.axieum.mcmod.authme.impl.AuthMe.*;

/**
 * Utility methods for authenticating via Microsoft.
 *
 * <p>For more information refer to: https://wiki.vg/Microsoft_Authentication_Scheme
 */
public final class MicrosoftUtils
{
    // A reusable Apache HTTP request config
    // NB: We use Apache's HTTP implementation as the native HTTP client does
    //     not appear to free its resources after use!
    public static final RequestConfig REQUEST_CONFIG = RequestConfig
        .custom()
        .setConnectionRequestTimeout(30_000)
        .setConnectTimeout(30_000)
        .setSocketTimeout(30_000)
        .build();

    // Base64 decoder. Used to get precise issuance millisecond for JWT tokens.
    private static final Base64.Decoder BASE_64_DECODER = Base64.getUrlDecoder();

    private MicrosoftUtils() {}

    public static CompletableFuture<MSAToken> acquireMSAToken(final Executor executor) {
        // Test if we can use an existing MSA token
        Instant msaExpiration = Instant.ofEpochMilli(getConfig().methods.microsoft.tokens.msAccessExpiration);
        if(msaExpiration.isAfter(Instant.now()) && !getConfig().methods.microsoft.tokens.msAccessToken.isBlank())
        {
            LOGGER.info("Microsoft access token is valid!");
            return CompletableFuture.supplyAsync(() -> new MicrosoftUtils.MSAToken(
                getConfig().methods.microsoft.tokens.msAccessToken,
                getConfig().methods.microsoft.tokens.msRefreshToken,
                msaExpiration));
        }
        // Check if a refresh token was provided
        else if(!getConfig().methods.microsoft.tokens.msRefreshToken.isBlank())
        {
            LOGGER.info("Microsoft access token is not valid, but refresh key is present. Refreshing...");
            return MicrosoftUtils.refreshMSAccessToken(getConfig().methods.microsoft.tokens.msRefreshToken, executor);
        }
        else {
            LOGGER.info("No saved token information was valid, starting from scratch.");
            return null;
        }
    }

    /**
     * Navigates to the Microsoft login, and listens for a successful login
     * callback.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param browserMessage function that takes true if success, and returns
     *                       a message to be shown in the browser after
     *                       logging in
     * @param executor       executor to run the login task on
     * @return completable future for the Microsoft auth token
     * @see #acquireMSAuthCode(Consumer, Function, Executor)
     */
    public static CompletableFuture<String> acquireMSAuthCode(
        final Function<Boolean, @NotNull String> browserMessage, final Executor executor
    )
    {
        return acquireMSAuthCode(url -> Util.getOperatingSystem().open(url), browserMessage, executor);
    }

    /**
     * Generates a Microsoft login link, triggers the given browser action, and
     * listens for a successful login callback.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param browserAction  consumer that opens the generated login url
     * @param browserMessage function that takes true if success, and returns
     *                       a message to be shown in the browser after
     *                       logging in
     * @param executor       executor to run the login task on
     * @return completable future for the Microsoft auth token
     */
    public static CompletableFuture<String> acquireMSAuthCode(
        final Consumer<URI> browserAction,
        final Function<Boolean, @NotNull String> browserMessage,
        final Executor executor
    )
    {
        return CompletableFuture.supplyAsync(() -> {
            LOGGER.info("Acquiring Microsoft auth code...");
            try {
                // Generate a random "state" to be included in the request that will in turn be returned with the token
                final String state = RandomStringUtils.randomAlphanumeric(8);

                // Prepare a temporary HTTP server we can listen for the OAuth2 callback on
                final HttpServer server = HttpServer.create(
                    new InetSocketAddress(getConfig().methods.microsoft.port), 0
                );
                final CountDownLatch latch = new CountDownLatch(1); // track when a request has been handled
                final AtomicReference<@Nullable String> authCode = new AtomicReference<>(null),
                    errorMsg = new AtomicReference<>(null);

                server.createContext("/callback", exchange -> {
                    // Parse the query parameters
                    final Map<String, String> query = URLEncodedUtils
                        .parse(exchange.getRequestURI(), StandardCharsets.UTF_8)
                        .stream()
                        .collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));

                    // Check the returned parameter values
                    if (!state.equals(query.get("state"))) {
                        // The "state" does not match what we sent
                        errorMsg.set(
                            String.format("State mismatch! Expected '%s' but got '%s'.", state, query.get("state"))
                        );
                    } else if (query.containsKey("code")) {
                        // Successfully matched the auth code
                        authCode.set(query.get("code"));
                    } else if (query.containsKey("error")) {
                        // Otherwise, try to find an error description
                        errorMsg.set(String.format("%s: %s", query.get("error"), query.get("error_description")));
                    }

                    // Send a response informing that the browser may now be closed
                    final byte[] message = browserMessage.apply(errorMsg.get() == null).getBytes();
                    exchange.sendResponseHeaders(200, message.length);
                    final OutputStream res = exchange.getResponseBody();
                    res.write(message);
                    res.close();

                    // Let the caller thread know that the request has been handled
                    latch.countDown();
                });

                // Build a Microsoft login url
                final URIBuilder uriBuilder = new URIBuilder(getConfig().methods.microsoft.authorizeUrl)
                    .addParameter("client_id", getConfig().methods.microsoft.clientId)
                    .addParameter("response_type", "code")
                    .addParameter(
                        "redirect_uri", String.format("http://localhost:%d/callback", server.getAddress().getPort())
                    )
                    .addParameter("scope", "XboxLive.signin offline_access")
                    .addParameter("state", state);
                if (getConfig().methods.microsoft.prompt != MicrosoftPrompt.DEFAULT) {
                    uriBuilder.addParameter("prompt", getConfig().methods.microsoft.prompt.toString());
                }
                final URI uri = uriBuilder.build();

                // Navigate to the Microsoft login in browser
                LOGGER.info("Launching Microsoft login in browser: {}", uri.toString());
                browserAction.accept(uri);

                try {
                    // Start the HTTP server
                    LOGGER.info("Begin listening on http://localhost:{}/callback for a successful Microsoft login...",
                        server.getAddress().getPort());
                    server.start();

                    // Wait for the server to stop and return the auth code, if any captured
                    latch.await();
                    return Optional.ofNullable(authCode.get())
                                   .filter(code -> !code.isBlank())
                                   // If present, log success and return
                                   .map(code -> {
                                       LOGGER.info("Acquired Microsoft auth code! ({})",
                                           StringUtils.abbreviateMiddle(code, "...", 32));
                                       return code;
                                   })
                                   // Otherwise, throw an exception with the error description if present
                                   .orElseThrow(() -> new Exception(
                                       Optional.ofNullable(errorMsg.get())
                                               .orElse("There was no auth code or error description present.")
                                   ));
                } finally {
                    // Always release the server!
                    server.stop(2);
                }
            } catch (InterruptedException e) {
                LOGGER.warn("Microsoft auth code acquisition was cancelled!");
                throw new CancellationException("Interrupted");
            } catch (Exception e) {
                LOGGER.error("Unable to acquire Microsoft auth code!", e);
                throw new CompletionException(e);
            }
        }, executor);
    }
    /**
     * Exchanges a Microsoft auth code for an access token.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param authCode Microsoft auth code
     * @param executor executor to run the login task on
     * @return completable future for the Microsoft access token
     */
    public static CompletableFuture<MSAToken> acquireMSAccessToken(final String authCode, final Executor executor)
    {
        return acquireMSAccessToken(authCode, AuthorizationCodeType.AUTHORIZATION, executor);
    }

    /**
     * Refreshes an existing MSA token using its refresh token
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param refreshToken MSA refresh token
     * @param executor executor to run the login task on
     * @return completable future for the Microsoft access token
     */
    public static CompletableFuture<MSAToken> refreshMSAccessToken(final String refreshToken, final Executor executor)
    {
        return acquireMSAccessToken(refreshToken, AuthorizationCodeType.REFRESH, executor);
    }

    /**
     * Exchanges a Microsoft auth code for an access token or refreshes an existing token.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param authCode Microsoft auth code
     * @param executor executor to run the login task on
     * @param type The type of request we are making, either a new request or refreshing an existing code.
     * @return completable future for the Microsoft access token
     */
    public static CompletableFuture<MSAToken> acquireMSAccessToken(final String authCode, final AuthorizationCodeType type, final Executor executor)
    {
        return CompletableFuture.supplyAsync(() -> {
            LOGGER.info("Exchanging Microsoft auth code for an access token...");
            try (CloseableHttpClient client = HttpClients.createMinimal()) {
                // Build a new HTTP request
                final HttpPost request = new HttpPost(URI.create(getConfig().methods.microsoft.tokenUrl));
                request.setConfig(REQUEST_CONFIG);
                request.setHeader("Content-Type", "application/x-www-form-urlencoded");
                request.setEntity(new UrlEncodedFormEntity(
                    List.of(
                        new BasicNameValuePair("client_id", getConfig().methods.microsoft.clientId),
                        new BasicNameValuePair("grant_type", type.getGrantType()),
                        new BasicNameValuePair(type.getParameterName(), authCode),
                        // We must provide the exact redirect URI that was used to obtain the auth code
                        new BasicNameValuePair(
                            "redirect_uri",
                            String.format("http://localhost:%d/callback", getConfig().methods.microsoft.port)
                        )
                    ),
                    "UTF-8"
                ));

                // Send the request on the HTTP client
                LOGGER.info("[{}] {} (timeout={}s)",
                    request.getMethod(), request.getURI().toString(), request.getConfig().getConnectTimeout() / 1000);
                final org.apache.http.HttpResponse res = client.execute(request);

                // Attempt to parse the response body as JSON and extract the access token
                final JsonObject json = JsonHelper.deserialize(EntityUtils.toString(res.getEntity()));
                return Optional.ofNullable(MSAToken.createFromJSON(json))
                               .filter(token -> !token.accessToken().isBlank())
                               // If present, log success and return
                               .map(token -> {
                                   // Save MSA tokens for faster authentication
                                   getConfig().methods.microsoft.tokens.msAccessToken = token.accessToken();
                                   getConfig().methods.microsoft.tokens.msRefreshToken = token.refreshToken();
                                   getConfig().methods.microsoft.tokens.msAccessExpiration = token.expiresAt.toEpochMilli();

                                   LOGGER.info("Acquired Microsoft access token! ({})",
                                       StringUtils.abbreviateMiddle(token.accessToken(), "...", 32));
                                   return token;
                               })
                               // Otherwise, throw an exception with the error description if present
                               .orElseThrow(() -> new Exception(
                                   json.has("error") ? String.format(
                                       "%s: %s",
                                       json.get("error").getAsString(),
                                       json.get("error_description").getAsString()
                                   ) : "There was no access token or error description present."
                               ));
            } catch (InterruptedException e) {
                LOGGER.warn("Microsoft access token acquisition was cancelled!");
                throw new CancellationException("Interrupted");
            } catch (Exception e) {
                LOGGER.error("Unable to acquire Microsoft access token!", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Exchanges a Microsoft access token for an Xbox Live access token.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param accessToken Microsoft access token
     * @param executor    executor to run the login task on
     * @return completable future for the Xbox Live access token
     */
    public static CompletableFuture<XBLToken> acquireXboxAccessToken(final MSAToken accessToken, final Executor executor)
    {
        return CompletableFuture.supplyAsync(() -> {
            AuthMeConfig.LoginMethodsSchema.MicrosoftAuthTokens tokens = getConfig().methods.microsoft.tokens;
            Instant xblExpiration = Instant.ofEpochMilli(tokens.xblExpiration);
            if(xblExpiration.isAfter(Instant.now()) && !tokens.xblToken.isBlank())
            {
                LOGGER.info("XBL token is valid");
                return new XBLToken(tokens.xblToken, xblExpiration);
            }

            LOGGER.info("Exchanging Microsoft access token for an Xbox Live access token...");
            try (CloseableHttpClient client = HttpClients.createMinimal()) {
                // Build a new HTTP request
                final HttpPost request = new HttpPost(URI.create(getConfig().methods.microsoft.xboxAuthUrl));
                request.setConfig(REQUEST_CONFIG);
                request.setHeader("Content-Type", "application/json");
                request.setEntity(new StringEntity(
                    String.format("""
                        {
                          "Properties": {
                            "AuthMethod": "RPS",
                            "SiteName": "user.auth.xboxlive.com",
                            "RpsTicket": "d=%s"
                          },
                          "RelyingParty": "http://auth.xboxlive.com",
                          "TokenType": "JWT"
                        }""", accessToken.accessToken())
                ));

                // Send the request on the HTTP client
                LOGGER.info("[{}] {} (timeout={}s)",
                    request.getMethod(), request.getURI().toString(), request.getConfig().getConnectTimeout() / 1000);
                final org.apache.http.HttpResponse res = client.execute(request);

                // Attempt to parse the response body as JSON and extract the access token
                // NB: No response body is sent if the response is not ok
                final JsonObject json = res.getStatusLine().getStatusCode() == 200
                                        ? JsonHelper.deserialize(EntityUtils.toString(res.getEntity()))
                                        : new JsonObject();
                return Optional.ofNullable(XBLToken.createFromJSON(json))
                               .filter(token -> !token.token().isBlank())
                               // If present, log success and return
                               .map(token -> {
                                   getConfig().methods.microsoft.tokens.xblToken = token.token();
                                   getConfig().methods.microsoft.tokens.xblExpiration = token.expires().toEpochMilli();

                                   LOGGER.info("Acquired Xbox Live access token! ({})",
                                       StringUtils.abbreviateMiddle(token.token(), "...", 32));
                                   return token;
                               })
                               // Otherwise, throw an exception with the error description if present
                               .orElseThrow(() -> new Exception(
                                   json.has("XErr") ? String.format(
                                       "%s: %s", json.get("XErr").getAsString(), json.get("Message").getAsString()
                                   ) : "There was no access token or error description present."
                               ));
            } catch (InterruptedException e) {
                LOGGER.warn("Xbox Live access token acquisition was cancelled!");
                throw new CancellationException("Interrupted");
            } catch (Exception e) {
                LOGGER.error("Unable to acquire Xbox Live access token!", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Exchanges an Xbox Live access token for an Xbox Live XSTS (security
     * token service) token.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param xblToken Xbox Live access token
     * @param executor    executor to run the login task on
     * @return completable future for a mapping of Xbox Live XSTS token ("Token") and user hash ("uhs")
     */
    public static CompletableFuture<XSTSToken> acquireXboxXstsToken(
        final XBLToken xblToken, final Executor executor
    )
    {
        return CompletableFuture.supplyAsync(() -> {
            AuthMeConfig.LoginMethodsSchema.MicrosoftAuthTokens tokens = getConfig().methods.microsoft.tokens;
            Instant xstsExpiration = Instant.ofEpochMilli(tokens.xblExpiration);
            if(xstsExpiration.isAfter(Instant.now())
                && !tokens.xstsToken.isBlank()
                && !tokens.xstsUhs.isBlank()
            )
            {
                LOGGER.info("XSTS token is valid");
                return new XSTSToken(tokens.xstsToken, tokens.xstsUhs, xstsExpiration);
            }
            LOGGER.info("Exchanging Xbox Live token for an Xbox Live XSTS token...");
            try (CloseableHttpClient client = HttpClients.createMinimal()) {
                // Build a new HTTP request
                final HttpPost request = new HttpPost(URI.create(getConfig().methods.microsoft.xboxXstsUrl));
                request.setConfig(REQUEST_CONFIG);
                request.setHeader("Content-Type", "application/json");
                request.setEntity(new StringEntity(
                    String.format("""
                        {
                          "Properties": {
                            "SandboxId": "RETAIL",
                            "UserTokens": ["%s"]
                          },
                          "RelyingParty": "rp://api.minecraftservices.com/",
                          "TokenType": "JWT"
                        }""", xblToken.token())
                ));

                // Send the request on the HTTP client
                LOGGER.info("[{}] {} (timeout={}s)",
                    request.getMethod(), request.getURI().toString(), request.getConfig().getConnectTimeout() / 1000);
                final org.apache.http.HttpResponse res = client.execute(request);

                // Attempt to parse the response body as JSON and extract the access token and user hash
                // NB: No response body is sent if the response is not ok
                final JsonObject json = res.getStatusLine().getStatusCode() == 200
                                        ? JsonHelper.deserialize(EntityUtils.toString(res.getEntity()))
                                        : new JsonObject();
                return Optional.ofNullable(XSTSToken.createFromJSON(json))
                               .filter(token -> !token.token().isBlank())
                               // If present, extract the user hash, log success and return
                               .map(token -> {
                                   LOGGER.info("Acquired Xbox Live XSTS token! (token={}, uhs={})",
                                       StringUtils.abbreviateMiddle(token.token(), "...", 32), token.uhs());

                                   getConfig().methods.microsoft.tokens.xstsToken = token.token();
                                   getConfig().methods.microsoft.tokens.xstsUhs = token.uhs();
                                   getConfig().methods.microsoft.tokens.xstsExpiration = token.expires().toEpochMilli();
                                   return token;
                               })
                               // Otherwise, throw an exception with the error description if present
                               .orElseThrow(() -> new Exception(
                                   json.has("XErr") ? String.format(
                                       "%s: %s", json.get("XErr").getAsString(), json.get("Message").getAsString()
                                   ) : "There was no access token or error description present."
                               ));
            } catch (InterruptedException e) {
                LOGGER.warn("Xbox Live XSTS token acquisition was cancelled!");
                throw new CancellationException("Interrupted");
            } catch (Exception e) {
                LOGGER.error("Unable to acquire Xbox Live XSTS token!", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Exchanges an Xbox Live XSTS token for a Minecraft access token.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param xstsToken Xbox Live XSTS token and user hash (uhs)
     * @param executor  executor to run the login task on
     * @return completable future for the Minecraft access token
     */
    public static CompletableFuture<MinecraftToken> acquireMCAccessToken(
        final XSTSToken xstsToken, final Executor executor
    )
    {
        return CompletableFuture.supplyAsync(() -> {
            AuthMeConfig.LoginMethodsSchema.MicrosoftAuthTokens tokens = getConfig().methods.microsoft.tokens;
            Instant mcExpiration = Instant.ofEpochMilli(tokens.mcAccessExpiration);
            if(mcExpiration.isAfter(Instant.now()) && !tokens.mcAccessToken.isBlank())
            {
                LOGGER.info("Minecraft token is valid");
                return new MinecraftToken(tokens.mcAccessToken, mcExpiration);
            }
            LOGGER.info("Exchanging Xbox Live XSTS token for a Minecraft access token...");
            try (CloseableHttpClient client = HttpClients.createMinimal()) {
                // Build a new HTTP request
                final HttpPost request = new HttpPost(URI.create(getConfig().methods.microsoft.mcAuthUrl));
                request.setConfig(REQUEST_CONFIG);
                request.setHeader("Content-Type", "application/json");
                request.setEntity(new StringEntity(
                    String.format("{\"identityToken\": \"XBL3.0 x=%s;%s\"}", xstsToken.uhs(), xstsToken.token())
                ));

                // Send the request on the HTTP client
                LOGGER.info("[{}] {} (timeout={}s)",
                    request.getMethod(), request.getURI().toString(), request.getConfig().getConnectTimeout() / 1000);
                final org.apache.http.HttpResponse res = client.execute(request);

                // Attempt to parse the response body as JSON and extract the access token
                final JsonObject json = JsonHelper.deserialize(EntityUtils.toString(res.getEntity()));
                return Optional.ofNullable(MinecraftToken.createFromJSON(json))
                               .filter(token -> !token.accessToken().isBlank())
                               // If present, log success and return
                               .map(token -> {
                                   getConfig().methods.microsoft.tokens.mcAccessToken = token.accessToken();
                                   getConfig().methods.microsoft.tokens.mcAccessExpiration = token.expiresAt.toEpochMilli();
                                   CONFIG.save();
                                   LOGGER.info("Acquired Minecraft access token! ({})",
                                       StringUtils.abbreviateMiddle(token.accessToken(), "...", 32)
                                   );
                                   return token;
                               })
                               // Otherwise, throw an exception with the error description if present
                               .orElseThrow(() -> new Exception(
                                   json.has("error") ? String.format(
                                       "%s: %s", json.get("error").getAsString(), json.get("errorMessage").getAsString()
                                   ) : "There was no access token or error description present."
                               ));
            } catch (InterruptedException e) {
                LOGGER.warn("Minecraft access token acquisition was cancelled!");
                throw new CancellationException("Interrupted");
            } catch (Exception e) {
                LOGGER.error("Unable to acquire Minecraft access token!", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Fetches the Minecraft profile for the given access token, and returns a
     * new Minecraft session.
     *
     * <p>NB: You must manually interrupt the executor thread if the
     * completable future is cancelled!
     *
     * @param mcToken  Minecraft access token
     * @param executor executor to run the login task on
     * @return completable future for the new Minecraft session
     * @see SessionUtils#setSession(Session) to apply the new session
     */
    public static CompletableFuture<Session> login(final MinecraftToken mcToken, final Executor executor)
    {
        return CompletableFuture.supplyAsync(() -> {
            LOGGER.info("Fetching Minecraft profile...");
            try (CloseableHttpClient client = HttpClients.createMinimal()) {
                // Build a new HTTP request
                final HttpGet request = new HttpGet(URI.create(getConfig().methods.microsoft.mcProfileUrl));
                request.setConfig(REQUEST_CONFIG);
                request.setHeader("Authorization", "Bearer " + mcToken.accessToken());

                // Send the request on the HTTP client
                LOGGER.info("[{}] {} (timeout={}s)",
                    request.getMethod(), request.getURI().toString(), request.getConfig().getConnectTimeout() / 1000);
                final org.apache.http.HttpResponse res = client.execute(request);

                // Attempt to parse the response body as JSON and extract the profile
                final JsonObject json = JsonHelper.deserialize(EntityUtils.toString(res.getEntity()));
                return Optional.ofNullable(json.get("id"))
                               .map(JsonElement::getAsString)
                               .filter(uuid -> !uuid.isBlank())
                               // If present, log success, build a new session and return
                               .map(uuid -> {
                                   LOGGER.info("Fetched Minecraft profile! (name={}, uuid={})",
                                       json.get("name").getAsString(), uuid);
                                   return new Session(
                                       json.get("name").getAsString(),
                                       uuid,
                                       mcToken.accessToken(),
                                       Optional.empty(),
                                       Optional.empty(),
                                       Session.AccountType.MOJANG
                                   );
                               })
                               // Otherwise, throw an exception with the error description if present
                               .orElseThrow(() -> new Exception(
                                   json.has("error") ? String.format(
                                       "%s: %s", json.get("error").getAsString(), json.get("errorMessage").getAsString()
                                   ) : "There was no profile or error description present."
                               ));
            } catch (InterruptedException e) {
                LOGGER.warn("Minecraft profile fetching was cancelled!");
                throw new CancellationException("Interrupted");
            } catch (Exception e) {
                LOGGER.error("Unable to fetch Minecraft profile!", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Derives an iat (issued at time) from a JWT
     * @param jwt token to examine
     * @return issuance time
     */
    private static Instant iatFromJWT(String jwt)
    {
        return Instant.ofEpochSecond(JsonHelper.deserialize(new String(BASE_64_DECODER.decode(jwt.split("\\.")[1]))).get("iat").getAsLong());
    }

    public record XBLToken(String token,Instant expires)
    {

        static @Nullable XBLToken createFromJSON(JsonObject json)
        {
            JsonElement accessTokenElement = json.get("Token");
            JsonElement expiresAtElement = json.get("NotAfter");

            if(accessTokenElement == null  || expiresAtElement == null)
            {
                return null;
            }

            return new XBLToken(accessTokenElement.getAsString(),
                Instant.from(DateTimeFormatter.ISO_INSTANT.parse(expiresAtElement.getAsString()))
            );
        }
    }

    public record XSTSToken(String token, String uhs, Instant expires)
    {

        static @Nullable XSTSToken createFromJSON(JsonObject json)
        {
            JsonElement accessTokenElement = json.get("Token");
            JsonElement expiresAtElement = json.get("NotAfter");

            if(accessTokenElement == null || expiresAtElement == null)
            {
                return null;
            }

            final String uhs = json.get("DisplayClaims").getAsJsonObject()
                .get("xui").getAsJsonArray()
                .get(0).getAsJsonObject()
                .get("uhs").getAsString();

            return new XSTSToken(accessTokenElement.getAsString(),
                uhs,
                Instant.from(DateTimeFormatter.ISO_INSTANT.parse(expiresAtElement.getAsString()))
            );
        }
    }

    public record MSAToken(String accessToken, String refreshToken, Instant expiresAt)
    {
        public boolean needsRefresh()
        {
            return expiresAt.isBefore(Instant.now());
        }

        static @Nullable MSAToken createFromJSON(JsonObject json)
        {
            JsonElement accessTokenElement = json.get("access_token");
            JsonElement refreshTokenElement = json.get("refresh_token");
            JsonElement expiresInElement = json.get("expires_in");

            if(accessTokenElement == null || refreshTokenElement == null || expiresInElement == null)
            {
                return null;
            }

            return new MSAToken(accessTokenElement.getAsString(), refreshTokenElement.getAsString(), Instant.now().plusSeconds(expiresInElement.getAsLong()));
        }
    }

    public record MinecraftToken(String accessToken, Instant expiresAt)
    {
        public boolean needsRefresh()
        {
            return expiresAt.isBefore(Instant.now());
        }

        static @Nullable MinecraftToken createFromJSON(JsonObject json)
        {
            JsonElement accessTokenElement = json.get("access_token");
            JsonElement expiresInElement = json.get("expires_in");

            if(accessTokenElement == null || expiresInElement == null)
            {
                return null;
            }

            String accessToken = accessTokenElement.getAsString();
            Instant issuanceTime = iatFromJWT(accessToken);

            return new MinecraftToken(accessTokenElement.getAsString(), issuanceTime.plusSeconds(expiresInElement.getAsLong()));
        }
    }

    /**
     * Defines the type of request to make when requesting an MSA token
     */
    enum AuthorizationCodeType
    {
        AUTHORIZATION("code", "authorization_code"),
        REFRESH("refresh_token", "refresh_token");

        private final String parameterName;
        private final String grantType;

        AuthorizationCodeType(String parameterName, String grantType)
        {
            this.parameterName = parameterName;
            this.grantType = grantType;
        }

        public String getParameterName() {
            return parameterName;
        }

        public String getGrantType() {
            return grantType;
        }
    }
    /**
     * Indicates the type of user interaction that is required when requesting
     * Microsoft authorization codes.
     */
    public enum MicrosoftPrompt
    {
        /**
         * Will use the default prompt, equivalent of not sending a prompt.
         */
        DEFAULT(""),

        /**
         * Will interrupt single sign-on providing account selection experience
         * listing all the accounts either in session or any remembered account
         * or an option to choose to use a different account altogether.
         */
        SELECT_ACCOUNT("select_account"),

        /**
         * Will force the user to enter their credentials on that request,
         * negating single-sign on.
         */
        LOGIN("login"),

        /**
         * Will ensure that the user isn't presented with any interactive
         * prompt whatsoever. If the request can't be completed silently via
         * single-sign on, the Microsoft identity platform will return an
         * {@code interaction_required} error.
         */
        NONE("none"),

        /**
         * Will trigger the OAuth consent dialog after the user signs in,
         * asking the user to grant permissions to the app.
         */
        CONSENT("consent");

        private final String prompt;

        /**
         * Constructs a new Microsoft Prompt enum.
         *
         * @param prompt prompt query value
         */
        MicrosoftPrompt(final String prompt)
        {
            this.prompt = prompt;
        }

        @Override
        public String toString()
        {
            return prompt;
        }
    }
}
