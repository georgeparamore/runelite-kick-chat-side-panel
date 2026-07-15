package com.kicksidepanel.kick;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to Kick's official public REST API (<a href="https://docs.kick.com">docs.kick.com</a>)
 * for everything it actually supports - resolving a channel slug to its
 * {@code broadcaster_user_id} and sending chat messages - plus one unofficial lookup
 * ({@link #resolveChatroomId}) that the official API has no equivalent for at all.
 * <p>
 * Kick's official API has no way to read live chat (the only delivery mechanism for incoming
 * messages is a webhook to a publicly-reachable server, which a desktop plugin can't host),
 * so {@link KickChatClient} reads chat via Kick's unofficial Pusher WebSocket feed instead.
 * That feed is keyed by a numeric "chatroom id" which the official API doesn't expose -
 * {@link #resolveChatroomId} is the one place this class reaches into Kick's unofficial
 * {@code kick.com/api/v2} surface to get it, same as every other open-source Kick chat
 * client does. This is a real reliability risk flagged in this plugin's README: Kick could
 * change or block this endpoint without notice.
 */
public class KickApiClient
{
	private static final String API_BASE = "https://api.kick.com/public/v1";
	private static final String OAUTH_TOKEN_URL = "https://id.kick.com/oauth/token";
	private static final String UNOFFICIAL_CHANNEL_URL = "https://kick.com/api/v2/channels/";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private final Gson gson;

	private volatile String cachedAppToken;
	private volatile long cachedAppTokenExpiresAtMillis;

	/** {@code gson} should be RuneLite's shared, Guice-injected instance - never {@code new Gson()}. */
	public KickApiClient(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Resolves a channel slug to its {@code broadcaster_user_id} via the official API, using
	 * an app access token (Client Credentials grant - no user login required). Needed as the
	 * {@code broadcaster_user_id} argument to {@link #sendMessage}. Returns {@code null} on
	 * any failure.
	 */
	public Long resolveBroadcasterUserId(String clientId, String clientSecret, String slug)
	{
		try
		{
			String appToken = appAccessToken(clientId, clientSecret);
			if (appToken == null)
			{
				return null;
			}

			HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/channels?slug=" + urlEncode(slug)))
				.header("Authorization", "Bearer " + appToken)
				.timeout(TIMEOUT)
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
			JsonObject channel = data.get(0).getAsJsonObject();
			return channel.has("broadcaster_user_id") ? channel.get("broadcaster_user_id").getAsLong() : null;
		}
		catch (Exception e)
		{
			// Broad on purpose: callers run this on a background thread and treat null as
			// "couldn't resolve", never letting an unexpected response shape kill the thread.
			return null;
		}
	}

	/**
	 * Resolves a channel slug to the numeric chatroom id {@link KickChatClient} needs to
	 * subscribe to on Kick's Pusher feed, via Kick's unofficial (undocumented, reverse
	 * engineered) {@code kick.com/api/v2/channels/{slug}} endpoint - see the class-level note
	 * on why the official API can't do this. Anonymous, no token needed. Returns {@code null}
	 * on any failure, including Kick blocking the request outright.
	 */
	public Long resolveChatroomId(String slug)
	{
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(UNOFFICIAL_CHANNEL_URL + urlEncode(slug)))
				// A generic browser-like User-Agent, not to disguise the client as something
				// it isn't, but because this endpoint has been reported to reject requests
				// with no User-Agent at all - see the class-level reliability note.
				.header("User-Agent",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
				.header("Accept", "application/json")
				.timeout(TIMEOUT)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
			{
				return null;
			}

			JsonObject json = gson.fromJson(response.body(), JsonObject.class);
			JsonObject chatroom = json.getAsJsonObject("chatroom");
			if (chatroom == null || !chatroom.has("id"))
			{
				return null;
			}
			return chatroom.get("id").getAsLong();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Sends a chat message as the logged-in user via the official API. {@code userAccessToken}
	 * must carry the {@code chat:write} scope. Returns {@code true} on success.
	 */
	public boolean sendMessage(String userAccessToken, long broadcasterUserId, String content)
	{
		try
		{
			JsonObject body = new JsonObject();
			body.addProperty("broadcaster_user_id", broadcasterUserId);
			body.addProperty("content", content);
			body.addProperty("type", "user");

			HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/chat"))
				.header("Authorization", "Bearer " + userAccessToken)
				.header("Content-Type", "application/json")
				.timeout(TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() >= 200 && response.statusCode() < 300;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * Returns a cached app access token, refreshing it via the Client Credentials grant once
	 * it's within a minute of expiring. Thread-safety note: two overlapping refreshes in a
	 * narrow race window would just each fetch their own token and the later write wins -
	 * harmless, since both are valid.
	 */
	private String appAccessToken(String clientId, String clientSecret) throws IOException, InterruptedException
	{
		if (cachedAppToken != null && System.currentTimeMillis() < cachedAppTokenExpiresAtMillis)
		{
			return cachedAppToken;
		}

		String form = "grant_type=client_credentials"
			+ "&client_id=" + urlEncode(clientId)
			+ "&client_secret=" + urlEncode(clientSecret);

		HttpRequest request = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.timeout(TIMEOUT)
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200)
		{
			return null;
		}

		JsonObject json = gson.fromJson(response.body(), JsonObject.class);
		String token = json.has("access_token") ? json.get("access_token").getAsString() : null;
		if (token == null)
		{
			return null;
		}

		int expiresInSeconds = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
		cachedAppTokenExpiresAtMillis = System.currentTimeMillis() + Math.max(0, expiresInSeconds - 60) * 1000L;
		cachedAppToken = token;
		return token;
	}

	private static String urlEncode(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
