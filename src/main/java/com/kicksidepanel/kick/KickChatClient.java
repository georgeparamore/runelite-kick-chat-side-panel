package com.kicksidepanel.kick;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Reads Kick chat over Kick's unofficial Pusher WebSocket feed - Kick's official public API
 * has no way to read live chat at all (its only incoming-message mechanism is a webhook to a
 * publicly-reachable server, which a desktop plugin can't host), so every third-party Kick
 * chat client, this one included, connects directly to the same Pusher channel Kick's own
 * web client uses. This is undocumented and could change or break without notice - see the
 * plugin README for the full reliability caveat.
 * <p>
 * Anonymous and read-only by design: Kick's chat feed itself doesn't accept a login on this
 * connection the way Twitch's IRC gateway does, so unlike the Twitch plugin, there is no
 * authenticated variant of this client. Sending messages instead goes through
 * {@link KickApiClient#sendMessage}, Kick's official REST API.
 */
public class KickChatClient
{
	private static final URI PUSHER_ENDPOINT = URI.create(
		"wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679?protocol=7&client=js&version=8.4.0-rc2&flash=false");
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private final KickChatListener listener;
	private final Gson gson;
	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(CONNECT_TIMEOUT)
		.build();

	private volatile WebSocket webSocket;
	private volatile boolean stopRequested;
	private volatile String currentChannel;

	/** {@code gson} should be RuneLite's shared, Guice-injected instance - never {@code new Gson()}. */
	public KickChatClient(KickChatListener listener, Gson gson)
	{
		this.listener = listener;
		this.gson = gson;
	}

	/**
	 * Connects to Kick's Pusher WebSocket and subscribes to {@code chatroomId}'s chat channel.
	 * {@code channelSlug} is only used for the {@link KickChatListener#onConnected} callback,
	 * not the connection itself. Safe to call from the Swing EDT.
	 */
	public void connect(String channelSlug, long chatroomId)
	{
		currentChannel = channelSlug;
		stopRequested = false;

		httpClient.newWebSocketBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.buildAsync(PUSHER_ENDPOINT, new PusherListener(channelSlug, chatroomId))
			.exceptionally(error ->
			{
				if (!stopRequested)
				{
					listener.onDisconnected("Connection error: " + rootMessage(error));
				}
				return null;
			});
	}

	public void disconnect()
	{
		stopRequested = true;
		WebSocket ws = webSocket;
		webSocket = null;
		if (ws != null)
		{
			// sendClose() only starts a graceful close handshake - the connection (and
			// incoming messages with it) doesn't actually stop until Pusher acks its own
			// close frame back, which isn't guaranteed to happen promptly. abort() tears
			// down the underlying TCP connection immediately, which is what a user clicking
			// "Disconnect" actually expects.
			ws.abort();
		}
	}

	private class PusherListener implements WebSocket.Listener
	{
		private final String channelSlug;
		private final long chatroomId;
		private final StringBuilder frameBuffer = new StringBuilder();

		PusherListener(String channelSlug, long chatroomId)
		{
			this.channelSlug = channelSlug;
			this.chatroomId = chatroomId;
		}

		@Override
		public void onOpen(WebSocket ws)
		{
			webSocket = ws;
			// Nothing to send here - Pusher's handshake sends pusher:connection_established
			// first, and the actual channel subscription only goes out once that's been
			// seen (see handleEvent below), per the Pusher protocol.
			WebSocket.Listener.super.onOpen(ws);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
		{
			if (stopRequested)
			{
				// A message can already be in flight when disconnect() runs - drop it
				// rather than keep appending to a feed the user just asked to stop.
				return null;
			}

			frameBuffer.append(data);
			if (last)
			{
				String message = frameBuffer.toString();
				frameBuffer.setLength(0);
				handleFrame(ws, message);
			}
			ws.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error)
		{
			if (!stopRequested)
			{
				listener.onDisconnected("Connection error: " + rootMessage(error));
			}
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason)
		{
			if (!stopRequested)
			{
				listener.onDisconnected(reason == null || reason.isEmpty() ? "Connection closed by Kick" : reason);
			}
			return null;
		}

		private void handleFrame(WebSocket ws, String frame)
		{
			JsonObject envelope;
			try
			{
				envelope = gson.fromJson(frame, JsonObject.class);
			}
			catch (Exception e)
			{
				return;
			}
			if (envelope == null || !envelope.has("event"))
			{
				return;
			}

			String event = envelope.get("event").getAsString();
			switch (event)
			{
				case "pusher:connection_established":
					subscribe(ws, chatroomId);
					break;
				case "pusher:ping":
					ws.sendText("{\"event\":\"pusher:pong\",\"data\":{}}", true);
					break;
				case "pusher_internal:subscription_succeeded":
					listener.onConnected(channelSlug);
					break;
				case "pusher:error":
					if (!stopRequested)
					{
						listener.onDisconnected("Kick rejected the connection: " + envelope.get("data"));
					}
					break;
				case "App\\Events\\ChatMessageEvent":
					handleChatMessage(envelope);
					break;
				default:
					// Kick's Pusher feed also emits sub/gift/follow/ban events on this same
					// channel - not shown by this plugin (see README), so anything else here
					// is deliberately ignored rather than treated as an error.
					break;
			}
		}

		/**
		 * Pusher's own protocol double-encodes: the outer frame's {@code data} field is
		 * itself a JSON string, not a nested object, so it needs a second parse pass.
		 */
		private void handleChatMessage(JsonObject envelope)
		{
			if (!envelope.has("data"))
			{
				return;
			}
			JsonObject data;
			try
			{
				data = gson.fromJson(envelope.get("data").getAsString(), JsonObject.class);
			}
			catch (Exception e)
			{
				return;
			}
			if (data == null || !data.has("content") || !data.has("sender"))
			{
				return;
			}

			JsonObject sender = data.getAsJsonObject("sender");
			String username = sender.has("username") ? sender.get("username").getAsString() : null;
			if (username == null || username.isEmpty())
			{
				return;
			}

			Color color = null;
			if (sender.has("identity") && sender.getAsJsonObject("identity").has("color"))
			{
				color = parseColor(sender.getAsJsonObject("identity").get("color").getAsString());
			}

			String content = stripEmotePlaceholders(data.get("content").getAsString());
			listener.onMessage(new KickMessage(username, content, color, System.currentTimeMillis()));
		}
	}

	// Kick's chat body embeds emote references inline in the message text itself, e.g.
	// "nice one [emote:5838776:odablockShalom]" - unlike Twitch, which reports emote
	// positions in a separate IRC tag and leaves the body as the literal typed text. Since
	// this plugin deliberately never renders emote images (see README), left alone these
	// placeholders would just leak through as raw "[emote:id:name]" noise. Reducing each one
	// to its plain name keeps the message readable as actual plain text instead.
	private static final Pattern EMOTE_PLACEHOLDER = Pattern.compile("\\[emote:\\d+:([^\\]]*)\\]");

	private static String stripEmotePlaceholders(String content)
	{
		return EMOTE_PLACEHOLDER.matcher(content).replaceAll("$1");
	}

	private void subscribe(WebSocket ws, long chatroomId)
	{
		JsonObject subscribe = new JsonObject();
		subscribe.addProperty("event", "pusher:subscribe");
		JsonObject data = new JsonObject();
		data.addProperty("channel", "chatrooms." + chatroomId + ".v2");
		subscribe.add("data", data);
		ws.sendText(gson.toJson(subscribe), true);
	}

	private static Color parseColor(String hex)
	{
		if (hex == null || hex.isEmpty())
		{
			return null;
		}
		try
		{
			return Color.decode(hex);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable cause = error;
		while (cause.getCause() != null)
		{
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message != null ? message : cause.getClass().getSimpleName();
	}
}
