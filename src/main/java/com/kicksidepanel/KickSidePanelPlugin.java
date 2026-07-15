package com.kicksidepanel;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.kicksidepanel.kick.KickApiClient;
import com.kicksidepanel.kick.KickAuthService;
import com.kicksidepanel.kick.KickChannelName;
import com.kicksidepanel.kick.KickChatClient;
import com.kicksidepanel.kick.KickChatListener;
import com.kicksidepanel.kick.KickMessage;
import com.kicksidepanel.ui.KickPanelIcon;
import com.kicksidepanel.ui.KickSidePanel;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Shows Kick chat in a RuneLite side panel, Party-Hub style, sibling to the Twitch Chat Side
 * Panel plugin. Only ever connects to the single channel set in this plugin's config - there's
 * no in-panel way to switch to a different channel at runtime. Reading chat works anonymously
 * with no login; logging in with Kick (OAuth Authorization Code + PKCE) additionally unlocks
 * sending messages as yourself.
 * <p>
 * See the README for why this looks architecturally different from the sibling Twitch
 * plugin: Kick's official public API has no way to read live chat at all (its only push
 * mechanism is a webhook to a server this plugin can't host), so chat is read over Kick's
 * unofficial Pusher WebSocket feed ({@link KickChatClient}) while everything else - resolving
 * the channel for sending, logging in, and sending messages - goes through Kick's official
 * REST API ({@link KickApiClient}, {@link KickAuthService}).
 */
@PluginDescriptor(
	name = "Kick Chat Side Panel",
	description = "Shows your Kick channel's chat in a side panel",
	tags = {"kick", "chat", "stream", "panel"}
)
public class KickSidePanelPlugin extends Plugin implements KickChatListener
{
	private static final String CONFIG_GROUP = "kicksidepanel";

	// A shared Kick application (Client ID + Secret) baked into the plugin so every user just
	// logs in with their own account with zero setup - same spirit as the Twitch plugin's
	// baked-in Client ID. Unlike Twitch, Kick's token exchange requires a client_secret even
	// for this PKCE flow, so this secret can't be truly confidential in an open-source plugin's
	// public source - see the README for that tradeoff. These are placeholders: replace them
	// with your own Kick app's credentials (docs.kick.com) before shipping, and register
	// http://127.0.0.1:17953/callback as that app's redirect URI.
	private static final String CLIENT_ID = "REPLACE_WITH_KICK_CLIENT_ID";
	private static final String CLIENT_SECRET = "REPLACE_WITH_KICK_CLIENT_SECRET";

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private KickSidePanelConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	private KickSidePanel panel;
	private NavigationButton navButton;
	private KickChatClient chatClient;
	private KickApiClient apiClient;
	private KickAuthService authService;

	private volatile Long broadcasterUserId;
	private volatile String resolvedForChannel;

	@Provides
	KickSidePanelConfig getConfig(final ConfigManager configManager)
	{
		return configManager.getConfig(KickSidePanelConfig.class);
	}

	@Override
	protected void startUp()
	{
		apiClient = new KickApiClient(gson);
		chatClient = new KickChatClient(this, gson);
		authService = new KickAuthService(gson);

		panel = new KickSidePanel(new KickSidePanel.Handlers()
		{
			@Override
			public void onConnectClicked()
			{
				connectToChannel();
			}

			@Override
			public void onDisconnectClicked()
			{
				chatClient.disconnect();
				panel.setConnected(false);
				panel.setStatus("Not connected", false);
			}

			@Override
			public void onLoginClicked()
			{
				startLogin();
			}

			@Override
			public void onCancelLoginClicked()
			{
				authService.cancel();
			}

			@Override
			public void onLogoutClicked()
			{
				clearLogin();
				panel.showLoginPrompt();
			}

			@Override
			public void onSendMessage(String text)
			{
				sendMessage(text);
			}
		}, KickChannelName.normalize(config.channel()));

		BufferedImage icon = KickPanelIcon.create();

		navButton = NavigationButton.builder()
			.tooltip("Kick Chat")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		restoreLoginState();

		if (config.autoConnect() && !KickChannelName.normalize(config.channel()).isEmpty())
		{
			connectToChannel();
		}
	}

	@Override
	protected void shutDown()
	{
		authService.cancel();
		if (chatClient != null)
		{
			chatClient.disconnect();
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		panel = null;
	}

	private void connectToChannel()
	{
		String channel = KickChannelName.normalize(config.channel());
		panel.setStatus("Connecting to " + channel + "...", false);

		Thread thread = new Thread(() ->
		{
			Long chatroomId = apiClient.resolveChatroomId(channel);
			if (panel == null)
			{
				return;
			}
			if (chatroomId == null)
			{
				panel.setStatus("Couldn't find that Kick channel - it may not exist, or Kick is blocking this request", true);
				return;
			}
			chatClient.connect(channel, chatroomId);
		}, "kick-channel-resolve");
		thread.setDaemon(true);
		thread.start();
	}

	private void startLogin()
	{
		panel.showWaitingForLogin();
		authService.startLogin(CLIENT_ID, CLIENT_SECRET, new KickAuthService.LoginListener()
		{
			@Override
			public void onAuthorized(String accessToken, String refreshToken, String username)
			{
				configManager.setConfiguration(CONFIG_GROUP, "accessToken", accessToken);
				configManager.setConfiguration(CONFIG_GROUP, "refreshToken", refreshToken == null ? "" : refreshToken);
				configManager.setConfiguration(CONFIG_GROUP, "loggedInUsername", username);
				if (panel != null)
				{
					panel.showLoggedIn(username);
				}
			}

			@Override
			public void onError(String message)
			{
				if (panel != null)
				{
					panel.showLoginError(message);
				}
			}
		});
	}

	private void restoreLoginState()
	{
		String accessToken = config.accessToken();
		if (accessToken.isEmpty())
		{
			panel.showLoginPrompt();
			return;
		}

		// Validating blocks on a network call - run it off the startUp() thread so plugin
		// startup itself never stalls waiting on Kick.
		Thread thread = new Thread(() ->
		{
			String username = authService.fetchUsername(accessToken);
			if (panel == null)
			{
				return;
			}
			if (username != null)
			{
				configManager.setConfiguration(CONFIG_GROUP, "loggedInUsername", username);
				panel.showLoggedIn(username);
			}
			else
			{
				clearLogin();
				panel.showLoginPrompt();
			}
		}, "kick-token-validate");
		thread.setDaemon(true);
		thread.start();
	}

	private void clearLogin()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, "accessToken");
		configManager.unsetConfiguration(CONFIG_GROUP, "refreshToken");
		configManager.unsetConfiguration(CONFIG_GROUP, "loggedInUsername");
	}

	/**
	 * Sends a message via Kick's official REST API. Unlike the Twitch plugin, this never
	 * echoes the message locally - Kick's Pusher feed broadcasts every chat message to every
	 * subscriber of the room, including this plugin's own anonymous read connection, so a
	 * message sent this way is expected to arrive back over {@link #onMessage} the same as
	 * anyone else's, without any special-casing needed here.
	 */
	private void sendMessage(String text)
	{
		String accessToken = config.accessToken();
		String channel = KickChannelName.normalize(config.channel());
		if (accessToken.isEmpty() || channel.isEmpty() || text == null || text.trim().isEmpty())
		{
			return;
		}

		Thread thread = new Thread(() ->
		{
			Long id = resolveBroadcasterUserId(channel);
			if (id == null)
			{
				if (panel != null)
				{
					panel.appendSystemMessage("Couldn't send - failed to resolve this channel");
				}
				return;
			}

			boolean ok = apiClient.sendMessage(accessToken, id, text.trim());
			if (!ok && panel != null)
			{
				panel.appendSystemMessage("Failed to send message");
			}
		}, "kick-send-message");
		thread.setDaemon(true);
		thread.start();
	}

	/** Cached per channel - re-resolved if the configured channel has changed since last use. */
	private Long resolveBroadcasterUserId(String channel)
	{
		if (broadcasterUserId != null && channel.equals(resolvedForChannel))
		{
			return broadcasterUserId;
		}
		Long id = apiClient.resolveBroadcasterUserId(CLIENT_ID, CLIENT_SECRET, channel);
		if (id != null)
		{
			broadcasterUserId = id;
			resolvedForChannel = channel;
		}
		return id;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()) || !"channel".equals(event.getKey()))
		{
			return;
		}

		String raw = config.channel();
		String normalized = KickChannelName.normalize(raw);
		if (!normalized.equals(raw))
		{
			configManager.setConfiguration(CONFIG_GROUP, "channel", normalized);
			return;
		}

		if (panel != null)
		{
			panel.setChannel(normalized);
		}
	}

	@Override
	public void onConnected(String channel)
	{
		if (panel == null)
		{
			return;
		}
		panel.setConnected(true);
		panel.setStatus("Connected to " + channel, false);
		panel.appendSystemMessage("Connected to " + channel);
	}

	@Override
	public void onDisconnected(String reason)
	{
		if (panel == null)
		{
			return;
		}
		panel.setConnected(false);
		panel.setStatus(reason, true);
	}

	@Override
	public void onMessage(KickMessage message)
	{
		if (panel == null)
		{
			return;
		}
		panel.appendMessage(message, config.colorUsernames(), config.showTimestamps(), config.maxMessages());
	}
}
