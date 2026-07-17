package com.kicksidepanel.kick;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs into Kick using the OAuth 2.1 Authorization Code + PKCE flow - Kick's public API has
 * no device code grant (unlike Twitch's, which the sibling Twitch Chat Side Panel plugin
 * uses), so unlike that plugin this needs a real redirect: a short-lived local HTTP listener
 * on {@code http://127.0.0.1:17953/callback} catches the browser redirect after the user
 * approves the login on kick.com.
 * <p>
 * Unlike Twitch's "Public" client type, Kick's token exchange requires a {@code client_secret}
 * even for this flow (confirmed against Kick's own docs), so this plugin's Client ID/Secret
 * pair (see {@code KickSidePanelPlugin.CLIENT_ID}/{@code CLIENT_SECRET}) is baked in the same
 * way Twitch's Client ID is - it identifies "which app is asking," not any individual user.
 * Unlike a true confidential-client secret, this one can't be kept truly secret in an
 * open-source plugin's public source, which is a real (if fairly standard for this kind of
 * app) tradeoff - see the README.
 * <p>
 * Deliberately never blocks a thread waiting for the browser redirect - the user could take
 * anywhere from seconds to minutes to approve (or never approve) the login, and Plugin Hub
 * review has separately flagged blocking background-thread patterns in the sibling Twitch
 * plugin. Instead, the token exchange and everything after it runs directly inside the local
 * HTTP server's own callback handler once Kick actually redirects back - nothing waits on it
 * in the meantime.
 */
public class KickAuthService
{
	private static final String AUTHORIZE_URL = "https://id.kick.com/oauth/authorize";
	private static final String TOKEN_URL = "https://id.kick.com/oauth/token";
	private static final String USERS_URL = "https://api.kick.com/public/v1/users";
	// user:read is needed for fetchUsername (GET /public/v1/users) - chat:write alone lets
	// the token send messages but not look itself up.
	private static final String SCOPES = "chat:write user:read";
	private static final int CALLBACK_PORT = 17953;
	private static final String REDIRECT_URI = "http://127.0.0.1:" + CALLBACK_PORT + "/callback";

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private volatile HttpServer activeServer;

	public interface LoginListener
	{
		void onAuthorized(String accessToken, String refreshToken, String username);

		/** Login failed, expired, or was rejected - never called after {@link #cancel()}. */
		void onError(String message);
	}

	/** {@code gson} should be RuneLite's shared, Guice-injected instance - never {@code new Gson()}. */
	public KickAuthService(Gson gson, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.executor = executor;
	}

	/**
	 * Starts a login attempt on the client's shared executor: opens the system browser to
	 * Kick's consent page, then returns immediately - the rest of the flow continues inside
	 * {@link #handleCallback} once (and if) Kick actually redirects back. Only one attempt
	 * should be in flight at a time - call {@link #cancel()} first if a previous attempt is
	 * still waiting.
	 */
	public void startLogin(String clientId, String clientSecret, LoginListener listener)
	{
		cancelled.set(false);
		executor.execute(() -> beginLogin(clientId, clientSecret, listener));
	}

	public void cancel()
	{
		cancelled.set(true);
		HttpServer server = activeServer;
		if (server != null)
		{
			server.stop(0);
			activeServer = null;
		}
	}

	/**
	 * Generates this attempt's PKCE material, starts the local callback listener, and opens
	 * the browser - all quick, bounded work, safe to run directly on the shared executor.
	 * Nothing here blocks waiting for the user to actually approve the login.
	 */
	private void beginLogin(String clientId, String clientSecret, LoginListener listener)
	{
		try
		{
			String state = randomUrlSafeString(24);
			String codeVerifier = randomUrlSafeString(64);
			String codeChallenge = pkceChallenge(codeVerifier);

			startCallbackServer(clientId, clientSecret, state, codeVerifier, listener);

			String authorizeUrl = AUTHORIZE_URL
				+ "?response_type=code"
				+ "&client_id=" + urlEncode(clientId)
				+ "&redirect_uri=" + urlEncode(REDIRECT_URI)
				+ "&scope=" + urlEncode(SCOPES)
				+ "&state=" + urlEncode(state)
				+ "&code_challenge=" + urlEncode(codeChallenge)
				+ "&code_challenge_method=S256";
			openInBrowser(authorizeUrl);
		}
		catch (Exception e)
		{
			// Deliberately broad: this runs on the executor with no other safety net, so
			// anything narrower risks the task dying silently on an unexpected failure -
			// "nothing happens" when you click the button, with no error and nothing to
			// debug.
			if (!cancelled.get())
			{
				listener.onError("Login error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
			}
		}
	}

	/**
	 * Looks up the username for a fresh or previously-stored access token, or {@code null}
	 * if it's invalid/expired/unreachable.
	 */
	public String fetchUsername(String accessToken)
	{
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(USERS_URL))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
			{
				return null;
			}
			JsonObject json = gson.fromJson(response.body(), JsonObject.class);
			JsonArray data = json.getAsJsonArray("data");
			if (data == null || data.size() == 0)
			{
				return null;
			}
			JsonObject user = data.get(0).getAsJsonObject();
			if (user.has("name"))
			{
				return user.get("name").getAsString();
			}
			if (user.has("username"))
			{
				return user.get("username").getAsString();
			}
			return null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** Starts a one-shot local HTTP server on {@link #REDIRECT_URI}'s port. */
	private void startCallbackServer(String clientId, String clientSecret, String expectedState, String codeVerifier,
		LoginListener listener) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0);
		activeServer = server;

		server.createContext("/callback", exchange ->
			handleCallback(exchange, clientId, clientSecret, expectedState, codeVerifier, listener, server));
		server.setExecutor(null);
		server.start();
	}

	/**
	 * Handles Kick's redirect back to this plugin, running on the local HTTP server's own
	 * callback thread - not the shared {@link #executor} - since this is a one-shot,
	 * inherently event-driven piece of work (it only ever runs once per login attempt) rather
	 * than anything resembling a polling loop.
	 */
	private void handleCallback(HttpExchange exchange, String clientId, String clientSecret, String expectedState,
		String codeVerifier, LoginListener listener, HttpServer server) throws IOException
	{
		String code;
		try
		{
			String query = exchange.getRequestURI().getQuery();
			String receivedCode = queryParam(query, "code");
			String state = queryParam(query, "state");
			String error = queryParam(query, "error");

			String responseHtml;
			if (error != null)
			{
				respond(listener, "Kick login was denied (" + error + ")");
				responseHtml = "<html><body>Login cancelled - you can close this tab.</body></html>";
				code = null;
			}
			else if (receivedCode == null || !Objects.equals(state, expectedState))
			{
				respond(listener, "Login response didn't match - try again");
				responseHtml = "<html><body>Something went wrong - you can close this tab and try again.</body></html>";
				code = null;
			}
			else
			{
				responseHtml = "<html><body>Logged in - you can close this tab and return to RuneLite.</body></html>";
				code = receivedCode;
			}

			byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody())
			{
				body.write(bytes);
			}
		}
		finally
		{
			server.stop(0);
			activeServer = null;
		}

		if (code != null)
		{
			exchangeCodeForToken(clientId, clientSecret, code, codeVerifier, listener);
		}
	}

	/**
	 * Exchanges the authorization code for a token and looks up the username - the network
	 * tail end of the login flow, still running on the HTTP server's callback thread (see
	 * {@link #handleCallback}), well after the response to Kick's redirect has already been
	 * sent.
	 */
	private void exchangeCodeForToken(String clientId, String clientSecret, String code, String codeVerifier,
		LoginListener listener)
	{
		if (cancelled.get())
		{
			return;
		}

		try
		{
			HttpResponse<String> tokenResponse = post(TOKEN_URL,
				"grant_type=authorization_code"
					+ "&code=" + urlEncode(code)
					+ "&client_id=" + urlEncode(clientId)
					+ "&client_secret=" + urlEncode(clientSecret)
					+ "&redirect_uri=" + urlEncode(REDIRECT_URI)
					+ "&code_verifier=" + urlEncode(codeVerifier));

			if (tokenResponse.statusCode() != 200)
			{
				respond(listener, "Kick rejected the login (HTTP " + tokenResponse.statusCode() + ")");
				return;
			}

			JsonObject token = gson.fromJson(tokenResponse.body(), JsonObject.class);
			String accessToken = token.get("access_token").getAsString();
			String refreshToken = token.has("refresh_token") ? token.get("refresh_token").getAsString() : null;

			String username = fetchUsername(accessToken);
			if (username == null)
			{
				respond(listener, "Logged in, but couldn't look up your username - try again");
				return;
			}

			if (!cancelled.get())
			{
				listener.onAuthorized(accessToken, refreshToken, username);
			}
		}
		catch (Exception e)
		{
			// Same broad-catch reasoning as beginLogin() - this callback has no other
			// safety net, so an unexpected failure should surface a visible error rather
			// than silently stop.
			respond(listener, "Login error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
		}
	}

	private void respond(LoginListener listener, String errorMessage)
	{
		if (!cancelled.get())
		{
			listener.onError(errorMessage);
		}
	}

	private static String queryParam(String query, String key)
	{
		if (query == null)
		{
			return null;
		}
		for (String pair : query.split("&"))
		{
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(key))
			{
				return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private void openInBrowser(String uri)
	{
		try
		{
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
			{
				Desktop.getDesktop().browse(URI.create(uri));
			}
		}
		catch (Exception ignored)
		{
			// Not fatal - nothing else to show since there's no in-panel code/link fallback
			// the way Twitch's device flow has (this flow has no user-facing code to show).
		}
	}

	private HttpResponse<String> post(String url, String form) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String randomUrlSafeString(int numBytes)
	{
		byte[] bytes = new byte[numBytes];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String pkceChallenge(String codeVerifier) throws Exception
	{
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	private static String urlEncode(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
